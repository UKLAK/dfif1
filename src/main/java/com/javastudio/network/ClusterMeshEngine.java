package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public final class ClusterMeshEngine {

    public enum NodeRole {
        FOLLOWER,
        CANDIDATE,
        LEADER
    }

    public record ClusterNode(
            String id,
            String host,
            int port,
            long lastSeen,
            int onlinePlayers,
            double tps,
            boolean isLeader
    ) {}

    public record StateEntry(String value, long version, long updatedAt) {}
    public record LockEntry(String key, String owner, long expiresAt, long fencingToken) {}

    private static final int MAX_LINE_LENGTH = 2_097_152;
    private static final int SOCKET_TIMEOUT_MS = 3000;
    private static final long REPLAY_WINDOW_MS = 30_000L;
    private static final long PEER_EXPIRY_MS = 10_000L;
    private static final int MAX_BROADCAST_LENGTH = 256;

    private final JavaStudioPlugin plugin;
    private final String nodeId;
    private final int meshPort;
    private final String bindAddress;
    private final boolean discoveryEnabled;
    private final List<String> seedNodes;
    private final byte[] secret;
    private final Map<String, ClusterNode> peers = new ConcurrentHashMap<>();
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    private final Map<String, StateEntry> clusterState = new ConcurrentHashMap<>();
    private final Map<String, LockEntry> clusterLocks = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong stateVersionSeq = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong fencingSeq = new java.util.concurrent.atomic.AtomicLong(0);
    private final SecureRandom random = new SecureRandom();

    private volatile NodeRole currentRole = NodeRole.FOLLOWER;
    private volatile long currentTerm = 0;
    private volatile String votedFor = null;
    private volatile String leaderId = null;
    private volatile long lastLeaderHeartbeat = System.currentTimeMillis();
    private volatile long electionTimeoutMs;

    private ServerSocket serverSocket;
    private DatagramSocket discoverySocket;
    private volatile boolean running = false;
    private PlatformUtil.TaskHandle heartbeatTask;

    public ClusterMeshEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.nodeId = "node-" + Long.toHexString(random.nextLong() & 0xFFFFFFFFFFL);
        var cfg = plugin.getConfig();
        this.meshPort = cfg.getInt("network.mesh-port", 25590);
        this.bindAddress = cfg.getString("network.mesh-bind", "0.0.0.0");
        this.discoveryEnabled = cfg.getBoolean("network.mesh-discovery", true);
        this.seedNodes = cfg.getStringList("network.mesh-seeds");
        this.secret = loadOrCreateSecret();
        this.electionTimeoutMs = randomElectionTimeout();
    }

    private byte[] loadOrCreateSecret() {
        var cfg = plugin.getConfig();
        String value = cfg.getString("network.mesh-secret", "");
        if (value == null || value.trim().length() < 16) {
            byte[] raw = new byte[32];
            random.nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b & 0xFF));
            value = sb.toString();
            cfg.set("network.mesh-secret", value);
            try {
                plugin.saveConfig();
            } catch (Throwable ignored) {}
            plugin.getLogger().warning("[ClusterMesh] Đã sinh mesh-secret mới. Sao chép network.mesh-secret giống nhau cho mọi server trong cụm.");
        }
        return value.trim().getBytes(StandardCharsets.UTF_8);
    }

    private long randomElectionTimeout() {
        return 4000L + random.nextInt(4000);
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        try {
            boolean panelIsolated = SinglePortMuxEngine.isPanelEnvironment()
                    && seedNodes.isEmpty()
                    && !plugin.getConfig().getBoolean("network.mesh-force-on-panel", false);

            if (panelIsolated) {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", 0), 16);
                Thread.ofVirtual().name("ClusterMesh-Listener").start(this::listenTcpConnections);
            } else {
                InetAddress bind = (bindAddress == null || bindAddress.isBlank() || bindAddress.equals("*"))
                        ? null : InetAddress.getByName(bindAddress);

                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                try {
                    serverSocket.bind(bind == null ? new InetSocketAddress(meshPort) : new InetSocketAddress(bind, meshPort), 64);
                } catch (BindException be) {
                    if (seedNodes.isEmpty()) {
                        serverSocket.close();
                        serverSocket = new ServerSocket();
                        serverSocket.setReuseAddress(true);
                        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0), 16);
                        panelIsolated = true;
                    } else {
                        throw be;
                    }
                }
                Thread.ofVirtual().name("ClusterMesh-Listener").start(this::listenTcpConnections);

                if (discoveryEnabled && !panelIsolated) {
                    try {
                        discoverySocket = new DatagramSocket(null);
                        discoverySocket.setReuseAddress(true);
                        discoverySocket.setBroadcast(true);
                        discoverySocket.bind(new InetSocketAddress(meshPort));
                        Thread.ofVirtual().name("ClusterMesh-Discovery").start(this::listenUdpDiscovery);
                    } catch (BindException ignored) {
                        if (discoverySocket != null) {
                            try { discoverySocket.close(); } catch (Throwable ignoredClose) {}
                            discoverySocket = null;
                        }
                    }
                }
            }

            for (String seed : seedNodes) {
                int colon = seed.lastIndexOf(':');
                if (colon <= 0) continue;
                try {
                    String host = seed.substring(0, colon).trim();
                    int port = Integer.parseInt(seed.substring(colon + 1).trim());
                    String seedKey = "seed:" + host + ":" + port;
                    peers.putIfAbsent(seedKey, new ClusterNode(seedKey, host, port, System.currentTimeMillis(), 0, 20.0, false));
                } catch (NumberFormatException ignored) {}
            }

            lastLeaderHeartbeat = System.currentTimeMillis();
            heartbeatTask = PlatformUtil.runTaskTimer(plugin, () -> Thread.ofVirtual().start(this::tick), 40L, 40L);
            if (panelIsolated) {
                plugin.getLogger().info("[ClusterMesh] Chế độ Panel 1-Port: cô lập nội bộ 127.0.0.1:" + serverSocket.getLocalPort() + " (tắt cổng phụ 25590 để tránh xung đột).");
            } else {
                plugin.getLogger().info("[ClusterMesh] Đã kích hoạt P2P Mesh Network trên cổng " + meshPort + " (Node ID: " + nodeId + ", xác thực HMAC-SHA256)");
            }
        } catch (Throwable t) {
            running = false;
            plugin.getLogger().warning("[ClusterMesh] Không thể khởi động Mesh Network: " + t.getMessage());
            closeSockets();
        }
    }

    public synchronized void stop() {
        running = false;
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        closeSockets();
        peers.clear();
        seenNonces.clear();
        currentRole = NodeRole.FOLLOWER;
        leaderId = null;
    }

    private void closeSockets() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Throwable ignored) {}
        try {
            if (discoverySocket != null) discoverySocket.close();
        } catch (Throwable ignored) {}
        serverSocket = null;
        discoverySocket = null;
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] out = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC không khả dụng", e);
        }
    }

    private String seal(String body) {
        String nonce = Long.toHexString(random.nextLong());
        String framed = body + "|" + System.currentTimeMillis() + "|" + nonce;
        return framed + "|" + sign(framed);
    }

    private String unseal(String line) {
        if (line == null) return null;
        int macIdx = line.lastIndexOf('|');
        if (macIdx <= 0) return null;
        String framed = line.substring(0, macIdx);
        String mac = line.substring(macIdx + 1);
        if (!MessageDigest.isEqual(sign(framed).getBytes(StandardCharsets.UTF_8), mac.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        int nonceIdx = framed.lastIndexOf('|');
        if (nonceIdx <= 0) return null;
        String nonce = framed.substring(nonceIdx + 1);
        String withTs = framed.substring(0, nonceIdx);
        int tsIdx = withTs.lastIndexOf('|');
        if (tsIdx <= 0) return null;
        long ts;
        try {
            ts = Long.parseLong(withTs.substring(tsIdx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > REPLAY_WINDOW_MS) return null;
        if (seenNonces.putIfAbsent(nonce, now) != null) return null;
        return withTs.substring(0, tsIdx);
    }

    private static String readBoundedLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b == '\r') continue;
            buf.write(b);
            if (buf.size() > MAX_LINE_LENGTH) throw new IOException("Dòng vượt quá giới hạn");
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }

    private void listenTcpConnections() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Thread.ofVirtual().start(() -> handleTcpClient(socket));
            } catch (Throwable ignored) {}
        }
    }

    private void handleTcpClient(Socket socket) {
        try (socket) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String body = unseal(readBoundedLine(in));
            if (body == null) return;

            String[] parts = body.split("\\|", 6);
            if (parts.length < 5) return;

            String type = parts[0];
            String senderId = parts[1];
            if (senderId.equals(nodeId)) return;
            int senderPort = Integer.parseInt(parts[2]);
            int online = Integer.parseInt(parts[3]);
            double tps = Double.parseDouble(parts[4]);
            String payload = parts.length > 5 ? parts[5] : "";

            String senderHost = socket.getInetAddress().getHostAddress();
            peers.keySet().removeIf(k -> k.equals("seed:" + senderHost + ":" + senderPort));

            switch (type) {
                case "HEARTBEAT" -> {
                    boolean claimsLeader = false;
                    long senderTerm = 0;
                    String[] meta = payload.split(",", 2);
                    try {
                        senderTerm = Long.parseLong(meta[0]);
                        claimsLeader = meta.length > 1 && "LEADER".equals(meta[1]);
                    } catch (NumberFormatException ignored) {}
                    handleHeartbeat(senderId, senderTerm, claimsLeader);
                    peers.put(senderId, new ClusterNode(senderId, senderHost, senderPort, System.currentTimeMillis(), online, tps, senderId.equals(leaderId)));
                    writer.write(seal("ACK|" + nodeId + "|" + meshPort + "|" + Bukkit.getOnlinePlayers().size() + "|20.0|" + currentTerm) + "\n");
                    writer.flush();
                }
                case "BROADCAST" -> {
                    peers.computeIfPresent(senderId, (k, v) -> new ClusterNode(v.id(), v.host(), v.port(), System.currentTimeMillis(), online, tps, v.isLeader()));
                    String text = payload.length() > MAX_BROADCAST_LENGTH ? payload.substring(0, MAX_BROADCAST_LENGTH) : payload;
                    PlatformUtil.runTask(plugin, () -> MessageUtil.broadcast("&b[Mesh Network] &f" + text));
                    writer.write(seal("OK|" + nodeId + "|" + meshPort + "|0|20.0|") + "\n");
                    writer.flush();
                }
                case "VOTE_REQ" -> {
                    long term;
                    try {
                        term = Long.parseLong(payload);
                    } catch (NumberFormatException e) {
                        return;
                    }
                    boolean granted = handleVoteRequest(senderId, term);
                    writer.write(seal((granted ? "VOTE_GRANTED" : "VOTE_DENIED") + "|" + nodeId + "|" + meshPort + "|0|20.0|" + currentTerm) + "\n");
                    writer.flush();
                }
                case "KV_REPL" -> {
                    applyReplicatedKv(payload, false);
                    writer.write(seal("OK|" + nodeId + "|" + meshPort + "|0|20.0|") + "\n");
                    writer.flush();
                }
                case "KV_FWD" -> {
                    applyReplicatedKv(payload, true);
                    writer.write(seal("OK|" + nodeId + "|" + meshPort + "|0|20.0|") + "\n");
                    writer.flush();
                }
                case "LOCK_ACQ" -> {
                    long token = handleLeaderLockAcquire(payload);
                    writer.write(seal("LOCK_RES|" + nodeId + "|" + meshPort + "|0|20.0|" + token) + "\n");
                    writer.flush();
                }
                case "LOCK_REL" -> {
                    boolean released = handleLeaderLockRelease(payload);
                    writer.write(seal("LOCK_RES|" + nodeId + "|" + meshPort + "|0|20.0|" + (released ? "1" : "0")) + "\n");
                    writer.flush();
                }
                case "LOCK_REPL" -> {
                    applyLockReplica(payload);
                    writer.write(seal("OK|" + nodeId + "|" + meshPort + "|0|20.0|") + "\n");
                    writer.flush();
                }
                default -> {
                }
            }
        } catch (Throwable ignored) {}
    }

    private synchronized void handleHeartbeat(String senderId, long senderTerm, boolean claimsLeader) {
        if (!claimsLeader) return;
        if (senderTerm < currentTerm) return;
        if (currentRole == NodeRole.LEADER && senderTerm == currentTerm && nodeId.compareTo(senderId) < 0) {
            return;
        }
        if (senderTerm > currentTerm) {
            currentTerm = senderTerm;
            votedFor = null;
        }
        if (currentRole == NodeRole.LEADER && !senderId.equals(nodeId)) {
            plugin.getLogger().info("[ClusterMesh] Nhường vai trò LEADER cho " + senderId + " (Term: " + currentTerm + ")");
        }
        currentRole = NodeRole.FOLLOWER;
        leaderId = senderId;
        lastLeaderHeartbeat = System.currentTimeMillis();
    }

    private synchronized boolean handleVoteRequest(String candidateId, long term) {
        if (term < currentTerm) return false;
        if (term > currentTerm) {
            currentTerm = term;
            votedFor = null;
            if (currentRole == NodeRole.LEADER) {
                currentRole = NodeRole.FOLLOWER;
                leaderId = null;
            }
        }
        if (votedFor == null || votedFor.equals(candidateId)) {
            votedFor = candidateId;
            lastLeaderHeartbeat = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    private void listenUdpDiscovery() {
        byte[] buf = new byte[1024];
        while (running && discoverySocket != null && !discoverySocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                discoverySocket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                String body = unseal(msg);
                if (body == null || !body.startsWith("MESH_DISCOVERY|")) continue;
                String[] parts = body.split("\\|");
                if (parts.length >= 3 && !parts[1].equals(nodeId)) {
                    String peerId = parts[1];
                    int peerPort = Integer.parseInt(parts[2]);
                    String host = packet.getAddress().getHostAddress();
                    peers.putIfAbsent(peerId, new ClusterNode(peerId, host, peerPort, System.currentTimeMillis(), 0, 20.0, false));
                }
            } catch (Throwable ignored) {}
        }
    }

    private void tick() {
        if (!running) return;

        long now = System.currentTimeMillis();
        seenNonces.entrySet().removeIf(e -> now - e.getValue() > REPLAY_WINDOW_MS * 2);
        peers.entrySet().removeIf(e -> !e.getKey().startsWith("seed:") && (now - e.getValue().lastSeen()) > PEER_EXPIRY_MS);
        clusterLocks.entrySet().removeIf(e -> now > e.getValue().expiresAt());

        if (discoveryEnabled) {
            try {
                byte[] bytes = seal("MESH_DISCOVERY|" + nodeId + "|" + meshPort).getBytes(StandardCharsets.UTF_8);
                DatagramPacket p = new DatagramPacket(bytes, bytes.length, InetAddress.getByName("255.255.255.255"), meshPort);
                DatagramSocket ds = discoverySocket;
                if (ds != null && !ds.isClosed()) ds.send(p);
            } catch (Throwable ignored) {}
        }

        sendHeartbeats();

        boolean hasRealPeers = peers.keySet().stream().anyMatch(k -> !k.startsWith("seed:"));
        if (!hasRealPeers) {
            becomeStandaloneLeader();
            return;
        }

        if (currentRole == NodeRole.LEADER) return;

        if (now - lastLeaderHeartbeat > electionTimeoutMs) {
            triggerLeaderElection();
        }
    }

    private synchronized void becomeStandaloneLeader() {
        if (currentRole == NodeRole.LEADER) return;
        if (currentTerm == 0) currentTerm = 1;
        currentRole = NodeRole.LEADER;
        leaderId = nodeId;
        votedFor = nodeId;
        plugin.getLogger().info("[ClusterMesh] Không có peer nào, node hoạt động độc lập với vai trò LEADER (Term: " + currentTerm + ")");
    }

    private void sendHeartbeats() {
        double tps = 20.0;
        try {
            double[] t = plugin.getServerController().getTPS();
            if (t != null && t.length > 0) tps = Math.min(20.0, t[0]);
        } catch (Throwable ignored) {}

        int online = Bukkit.getOnlinePlayers().size();
        String body = "HEARTBEAT|" + nodeId + "|" + meshPort + "|" + online + "|" + String.format(Locale.ROOT, "%.2f", tps)
                + "|" + currentTerm + "," + currentRole.name();

        for (ClusterNode node : peers.values()) {
            Thread.ofVirtual().start(() -> sendOneWay(node, seal(body)));
        }
    }

    private void sendOneWay(ClusterNode node, String sealed) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(node.host(), node.port()), 1500);
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            bw.write(sealed + "\n");
            bw.flush();
            readBoundedLine(new BufferedInputStream(s.getInputStream()));
        } catch (Throwable ignored) {}
    }

    private void triggerLeaderElection() {
        long electionTerm;
        synchronized (this) {
            if (currentRole == NodeRole.LEADER) return;
            currentTerm++;
            electionTerm = currentTerm;
            currentRole = NodeRole.CANDIDATE;
            votedFor = nodeId;
            leaderId = null;
            electionTimeoutMs = randomElectionTimeout();
            lastLeaderHeartbeat = System.currentTimeMillis();
        }

        List<ClusterNode> targets = new ArrayList<>(peers.values());
        int votes = 1;
        String request = "VOTE_REQ|" + nodeId + "|" + meshPort + "|0|20.0|" + electionTerm;

        for (ClusterNode node : targets) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(node.host(), node.port()), 1000);
                s.setSoTimeout(SOCKET_TIMEOUT_MS);
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                bw.write(seal(request) + "\n");
                bw.flush();
                String resp = unseal(readBoundedLine(new BufferedInputStream(s.getInputStream())));
                if (resp == null) continue;
                String[] parts = resp.split("\\|", 6);
                if (parts.length >= 6) {
                    try {
                        long peerTerm = Long.parseLong(parts[5]);
                        synchronized (this) {
                            if (peerTerm > currentTerm) {
                                currentTerm = peerTerm;
                                currentRole = NodeRole.FOLLOWER;
                                votedFor = null;
                                return;
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
                if ("VOTE_GRANTED".equals(parts[0])) votes++;
            } catch (Throwable ignored) {}
        }

        synchronized (this) {
            if (currentRole != NodeRole.CANDIDATE || currentTerm != electionTerm) return;
            int clusterSize = targets.size() + 1;
            if (votes > clusterSize / 2) {
                currentRole = NodeRole.LEADER;
                leaderId = nodeId;
                plugin.getLogger().info("[ClusterMesh] Node này đã được bầu làm LEADER cụm máy chủ (Term: " + currentTerm + ", phiếu: " + votes + "/" + clusterSize + ")");
            } else {
                currentRole = NodeRole.FOLLOWER;
            }
        }
        sendHeartbeats();
    }

    public void broadcastClusterMessage(String text) {
        if (text == null) return;
        String clean = text.replace("\n", " ").replace("\r", " ");
        if (clean.length() > MAX_BROADCAST_LENGTH) clean = clean.substring(0, MAX_BROADCAST_LENGTH);
        String body = "BROADCAST|" + nodeId + "|" + meshPort + "|" + Bukkit.getOnlinePlayers().size() + "|20.0|" + clean;
        for (ClusterNode node : peers.values()) {
            Thread.ofVirtual().start(() -> sendOneWay(node, seal(body)));
        }
    }

    private static String b64Encode(String s) {
        if (s == null) return "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64Decode(String s) {
        if (s == null || s.isEmpty()) return "";
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    public void putClusterState(String key, String value) {
        if (key == null || key.isBlank()) return;
        ClusterNode leaderNode = findLeaderPeer();
        if (currentRole != NodeRole.LEADER && leaderNode != null) {
            String fwdPayload = "SET," + b64Encode(key) + "," + b64Encode(value != null ? value : "") + ",0";
            String msg = "KV_FWD|" + nodeId + "|" + meshPort + "|0|20.0|" + fwdPayload;
            Thread.ofVirtual().start(() -> sendOneWay(leaderNode, seal(msg)));
            clusterState.put(key, new StateEntry(value != null ? value : "", stateVersionSeq.incrementAndGet(), System.currentTimeMillis()));
            return;
        }
        long ver = stateVersionSeq.incrementAndGet();
        String cleanVal = value != null ? value : "";
        clusterState.put(key, new StateEntry(cleanVal, ver, System.currentTimeMillis()));
        replicateKv("SET", key, cleanVal, ver);
    }

    public void removeClusterState(String key) {
        if (key == null || key.isBlank()) return;
        ClusterNode leaderNode = findLeaderPeer();
        if (currentRole != NodeRole.LEADER && leaderNode != null) {
            String fwdPayload = "DEL," + b64Encode(key) + ",,0";
            String msg = "KV_FWD|" + nodeId + "|" + meshPort + "|0|20.0|" + fwdPayload;
            Thread.ofVirtual().start(() -> sendOneWay(leaderNode, seal(msg)));
            clusterState.remove(key);
            return;
        }
        long ver = stateVersionSeq.incrementAndGet();
        clusterState.remove(key);
        replicateKv("DEL", key, "", ver);
    }

    public String getClusterState(String key) {
        StateEntry e = clusterState.get(key);
        return e != null ? e.value() : null;
    }

    public Map<String, StateEntry> getAllClusterState() {
        return Collections.unmodifiableMap(clusterState);
    }

    private void replicateKv(String op, String key, String val, long ver) {
        String payload = op + "," + b64Encode(key) + "," + b64Encode(val) + "," + ver;
        String body = "KV_REPL|" + nodeId + "|" + meshPort + "|0|20.0|" + payload;
        for (ClusterNode node : peers.values()) {
            Thread.ofVirtual().start(() -> sendOneWay(node, seal(body)));
        }
    }

    private void applyReplicatedKv(String payload, boolean rebroadcastAsLeader) {
        String[] p = payload.split(",", 4);
        if (p.length < 4) return;
        String op = p[0];
        String key = b64Decode(p[1]);
        String val = b64Decode(p[2]);
        long ver;
        try {
            ver = Long.parseLong(p[3]);
        } catch (NumberFormatException e) {
            ver = 0L;
        }
        if (rebroadcastAsLeader) {
            ver = stateVersionSeq.incrementAndGet();
        } else {
            final long incomingVer = ver;
            stateVersionSeq.updateAndGet(cur -> Math.max(cur, incomingVer));
        }
        if ("DEL".equals(op)) {
            clusterState.remove(key);
            if (rebroadcastAsLeader) replicateKv("DEL", key, "", ver);
        } else if ("SET".equals(op)) {
            final long applyVer = ver;
            clusterState.compute(key, (k, existing) -> {
                if (existing != null && existing.version() > applyVer && !rebroadcastAsLeader) {
                    return existing;
                }
                return new StateEntry(val, applyVer, System.currentTimeMillis());
            });
            if (rebroadcastAsLeader) replicateKv("SET", key, val, ver);
        }
    }

    public boolean tryAcquireLock(String lockKey, String owner, long ttlMs) {
        if (lockKey == null || lockKey.isBlank()) return false;
        String safeOwner = (owner == null || owner.isBlank()) ? nodeId : owner;
        long clampedTtl = Math.max(500L, Math.min(300_000L, ttlMs));
        ClusterNode leaderNode = findLeaderPeer();
        if (currentRole != NodeRole.LEADER && leaderNode != null) {
            String payload = b64Encode(lockKey) + "," + b64Encode(safeOwner) + "," + clampedTtl;
            String req = "LOCK_ACQ|" + nodeId + "|" + meshPort + "|0|20.0|" + payload;
            String resp = sendRequest(leaderNode, seal(req));
            if (resp != null && resp.startsWith("LOCK_RES|")) {
                String[] parts = resp.split("\\|", 6);
                if (parts.length >= 6) {
                    try {
                        return Long.parseLong(parts[5]) > 0L;
                    } catch (NumberFormatException ignored) {}
                }
            }
            return false;
        }
        return acquireLocalLeaderLock(lockKey, safeOwner, clampedTtl) > 0L;
    }

    public boolean releaseLock(String lockKey, String owner) {
        if (lockKey == null || lockKey.isBlank()) return false;
        String safeOwner = (owner == null || owner.isBlank()) ? nodeId : owner;
        ClusterNode leaderNode = findLeaderPeer();
        if (currentRole != NodeRole.LEADER && leaderNode != null) {
            String payload = b64Encode(lockKey) + "," + b64Encode(safeOwner);
            String req = "LOCK_REL|" + nodeId + "|" + meshPort + "|0|20.0|" + payload;
            String resp = sendRequest(leaderNode, seal(req));
            return resp != null && resp.endsWith("|1");
        }
        return releaseLocalLeaderLock(lockKey, safeOwner);
    }

    public Map<String, LockEntry> getActiveLocks() {
        long now = System.currentTimeMillis();
        clusterLocks.entrySet().removeIf(e -> now > e.getValue().expiresAt());
        return Collections.unmodifiableMap(clusterLocks);
    }

    private synchronized long acquireLocalLeaderLock(String key, String owner, long ttlMs) {
        long now = System.currentTimeMillis();
        LockEntry cur = clusterLocks.get(key);
        if (cur != null && cur.expiresAt() > now && !cur.owner().equals(owner)) {
            return 0L;
        }
        long token = fencingSeq.incrementAndGet();
        long exp = now + ttlMs;
        LockEntry next = new LockEntry(key, owner, exp, token);
        clusterLocks.put(key, next);
        String repl = "ACQ," + b64Encode(key) + "," + b64Encode(owner) + "," + exp + "," + token;
        String body = "LOCK_REPL|" + nodeId + "|" + meshPort + "|0|20.0|" + repl;
        for (ClusterNode node : peers.values()) {
            Thread.ofVirtual().start(() -> sendOneWay(node, seal(body)));
        }
        return token;
    }

    private synchronized boolean releaseLocalLeaderLock(String key, String owner) {
        LockEntry cur = clusterLocks.get(key);
        if (cur == null) return false;
        if (!cur.owner().equals(owner) && System.currentTimeMillis() <= cur.expiresAt()) {
            return false;
        }
        clusterLocks.remove(key);
        String repl = "REL," + b64Encode(key) + ",,0,0";
        String body = "LOCK_REPL|" + nodeId + "|" + meshPort + "|0|20.0|" + repl;
        for (ClusterNode node : peers.values()) {
            Thread.ofVirtual().start(() -> sendOneWay(node, seal(body)));
        }
        return true;
    }

    private long handleLeaderLockAcquire(String payload) {
        String[] p = payload.split(",", 3);
        if (p.length < 3) return 0L;
        try {
            return acquireLocalLeaderLock(b64Decode(p[0]), b64Decode(p[1]), Long.parseLong(p[2]));
        } catch (Exception e) {
            return 0L;
        }
    }

    private boolean handleLeaderLockRelease(String payload) {
        String[] p = payload.split(",", 2);
        if (p.length < 2) return false;
        return releaseLocalLeaderLock(b64Decode(p[0]), b64Decode(p[1]));
    }

    private void applyLockReplica(String payload) {
        String[] p = payload.split(",", 5);
        if (p.length < 5) return;
        String key = b64Decode(p[1]);
        if ("REL".equals(p[0])) {
            clusterLocks.remove(key);
        } else if ("ACQ".equals(p[0])) {
            try {
                String owner = b64Decode(p[2]);
                long exp = Long.parseLong(p[3]);
                long tok = Long.parseLong(p[4]);
                clusterLocks.put(key, new LockEntry(key, owner, exp, tok));
            } catch (NumberFormatException ignored) {}
        }
    }

    private ClusterNode findLeaderPeer() {
        String lid = this.leaderId;
        if (lid == null || lid.equals(nodeId)) return null;
        return peers.get(lid);
    }

    private String sendRequest(ClusterNode node, String sealed) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(node.host(), node.port()), 1500);
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            bw.write(sealed + "\n");
            bw.flush();
            return unseal(readBoundedLine(new BufferedInputStream(s.getInputStream())));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public NodeRole getCurrentRole() {
        return currentRole;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public String getLeaderId() {
        return leaderId != null ? leaderId : "Election pending...";
    }

    public Collection<ClusterNode> getActivePeers() {
        List<ClusterNode> list = new ArrayList<>();
        for (Map.Entry<String, ClusterNode> e : peers.entrySet()) {
            if (!e.getKey().startsWith("seed:")) list.add(e.getValue());
        }
        return Collections.unmodifiableList(list);
    }

    public boolean isRunning() {
        return running;
    }
}
