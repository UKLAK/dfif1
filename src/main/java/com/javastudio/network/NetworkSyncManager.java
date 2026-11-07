package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkSyncManager {

    private static final int MAX_AUTH_LINE_BYTES = 4096;
    private static final int AUTH_TIMEOUT_MS = 3000;

    private final JavaStudioPlugin plugin;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private final Set<Socket> connectedClients = ConcurrentHashMap.newKeySet();
    private volatile boolean running = false;

    public NetworkSyncManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized boolean startMaster(int port, String authToken) {
        if (authToken == null || authToken.trim().length() < 8) {
            plugin.getLogger().warning("[NetworkSync] Từ chối khởi chạy Master: authToken phải dài tối thiểu 8 ký tự!");
            return false;
        }
        if (running) stop();
        try {
            String bindAddr = plugin.getConfig().getString("network.sync-bind", "127.0.0.1");
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            InetSocketAddress addr = (bindAddr == null || bindAddr.isBlank() || bindAddr.equals("*"))
                    ? new InetSocketAddress(port)
                    : new InetSocketAddress(bindAddr, port);
            serverSocket.bind(addr, 32);
            running = true;
            serverThread = new Thread(() -> masterListenLoop(authToken.trim()), "JavaStudio-NetworkSyncMaster");
            serverThread.setDaemon(true);
            serverThread.start();
            plugin.getLogger().info("[NetworkSync] Đã khởi chạy Network Master tại " + addr + "!");
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[NetworkSync] Không thể khởi động Network Master: " + e.getMessage());
            return false;
        }
    }

    public int broadcastPayload(String type, String name, String payload) {
        if (!running || connectedClients.isEmpty()) return 0;
        int sent = 0;
        String packet = type + ":::" + name + ":::" + payload.replace("\n", "\\n") + "\n";
        byte[] bytes = packet.getBytes(StandardCharsets.UTF_8);

        Iterator<Socket> it = connectedClients.iterator();
        while (it.hasNext()) {
            Socket socket = it.next();
            try {
                if (socket.isClosed()) {
                    it.remove();
                    continue;
                }
                OutputStream os = socket.getOutputStream();
                os.write(bytes);
                os.flush();
                sent++;
            } catch (IOException e) {
                it.remove();
            }
        }
        return sent;
    }

    private void masterListenLoop(String authToken) {
        byte[] expectedAuth = ("AUTH " + authToken).getBytes(StandardCharsets.UTF_8);
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().start(() -> authenticateClient(client, expectedAuth));
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void authenticateClient(Socket client, byte[] expectedAuth) {
        try {
            client.setSoTimeout(AUTH_TIMEOUT_MS);
            String authLine = readBoundedLine(new BufferedInputStream(client.getInputStream()));
            if (authLine != null && MessageDigest.isEqual(authLine.getBytes(StandardCharsets.UTF_8), expectedAuth)) {
                client.setSoTimeout(0);
                connectedClients.add(client);
                plugin.getLogger().info("[NetworkSync] Máy chủ con đã xác thực thành công: " + client.getRemoteSocketAddress());
            } else {
                client.close();
            }
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private static String readBoundedLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b == '\r') continue;
            bos.write(b);
            if (bos.size() > MAX_AUTH_LINE_BYTES) {
                throw new IOException("Auth line quá dài");
            }
        }
        if (b == -1 && bos.size() == 0) return null;
        return bos.toString(StandardCharsets.UTF_8);
    }

    public synchronized void stop() {
        running = false;
        for (Socket s : connectedClients) {
            try { s.close(); } catch (Exception ignored) {}
        }
        connectedClients.clear();
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getConnectedCount() {
        return connectedClients.size();
    }
}
