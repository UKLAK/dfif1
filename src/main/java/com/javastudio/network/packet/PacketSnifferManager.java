package com.javastudio.network.packet;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class PacketSnifferManager implements Listener {

    private final JavaStudioPlugin plugin;
    private final Logger logger;

    private static final String HANDLER_NAME = "javastudio_sniffer";

    private boolean listening = false;
    private String packetFilter = "all";
    private String playerFilter = null;

    private final AtomicLong totalInbound = new AtomicLong(0);
    private final AtomicLong totalOutbound = new AtomicLong(0);
    private final AtomicLong totalCancelled = new AtomicLong(0);
    private final Map<String, AtomicLong> packetTypeCounts = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<PacketLogRecord> recentPackets = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private final Set<String> cancelledPackets = ConcurrentHashMap.newKeySet();
    private final Set<String> listenedPackets = ConcurrentHashMap.newKeySet();
    private boolean deepListen = false;

    public boolean isDeepListen() { return deepListen; }
    public void setDeepListen(boolean deepListen) { this.deepListen = deepListen; }

    private static class MovementTracker {
        final Queue<Long> timestamps = new ArrayDeque<>();
    }
    private final Map<UUID, MovementTracker> movementTrackers = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> tabCompleteCounts = new ConcurrentHashMap<>();

    public record PacketLogRecord(long timestamp, String direction, String player, String packetType) {}

    private final Map<UUID, Object> hookedChannels = new ConcurrentHashMap<>();

    public PacketSnifferManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) {
            injectPlayer(p);
        }
        logger.info("[PacketSniffer] Đã kích hoạt hệ thống giám sát Netty ChannelPipeline (Zero Dependencies)!");
    }

    public void stop() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            uninjectPlayer(p);
        }
        hookedChannels.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> injectPlayer(event.getPlayer()), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        uninjectPlayer(event.getPlayer());
    }

    public void injectPlayer(Player player) {
        try {
            Object channel = getChannel(player);
            if (channel == null) return;

            Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);
            if (pipeline == null) return;

            try {
                Method getMethod = pipeline.getClass().getMethod("get", String.class);
                if (getMethod.invoke(pipeline, HANDLER_NAME) != null) {
                    pipeline.getClass().getMethod("remove", String.class).invoke(pipeline, HANDLER_NAME);
                }
            } catch (Throwable ignored) {}

            ClassLoader loader = channel.getClass().getClassLoader();
            Class<?> inboundClass = Class.forName("io.netty.channel.ChannelInboundHandler", false, loader);
            Class<?> outboundClass = Class.forName("io.netty.channel.ChannelOutboundHandler", false, loader);
            Class<?> channelHandlerClass = Class.forName("io.netty.channel.ChannelHandler", false, loader);

            Object handlerProxy = Proxy.newProxyInstance(loader, new Class<?>[]{inboundClass, outboundClass}, (proxy, method, args) -> {
                String mName = method.getName();

                if ("channelRead".equals(mName) && args != null && args.length >= 2) {
                    Object ctx = args[0];
                    Object msg = args[1];

                    if (isMaliciousPreMainThread(player, msg) || isPacketCancelled(msg)) {
                        totalCancelled.incrementAndGet();
                        return null;
                    }

                    handlePacketIn(player, msg);
                    checkPacketTimer(player, msg);

                    Method fireRead = ctx.getClass().getMethod("fireChannelRead", Object.class);
                    return fireRead.invoke(ctx, msg);
                }

                if ("write".equals(mName) && args != null && args.length >= 3) {
                    Object ctx = args[0];
                    Object msg = args[1];
                    Object promise = args[2];

                    if (isPacketCancelled(msg)) {
                        totalCancelled.incrementAndGet();
                        return null;
                    }

                    handlePacketOut(player, msg);
                    for (Method m : ctx.getClass().getMethods()) {
                        if (m.getName().equals("write") && m.getParameterCount() == 2) {
                            return m.invoke(ctx, msg, promise);
                        }
                    }
                    return null;
                }

                if ("exceptionCaught".equals(mName) && args != null && args.length >= 2) {
                    Object ctx = args[0];
                    Throwable cause = (Throwable) args[1];
                    Method fireException = ctx.getClass().getMethod("fireExceptionCaught", Throwable.class);
                    return fireException.invoke(ctx, cause);
                }

                if (args != null && args.length > 0) {
                    Object ctx = args[0];
                    try {
                        for (Method m : ctx.getClass().getMethods()) {
                            if (m.getName().equals("fire" + Character.toUpperCase(mName.charAt(0)) + mName.substring(1))) {
                                if (m.getParameterCount() == args.length - 1) {
                                    Object[] subArgs = Arrays.copyOfRange(args, 1, args.length);
                                    return m.invoke(ctx, subArgs);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                return null;
            });

            boolean added = false;
            try {
                Method getMethod = pipeline.getClass().getMethod("get", String.class);
                if (getMethod.invoke(pipeline, "packet_handler") != null) {
                    Method addBefore = pipeline.getClass().getMethod("addBefore", String.class, String.class, channelHandlerClass);
                    addBefore.invoke(pipeline, "packet_handler", HANDLER_NAME, handlerProxy);
                    added = true;
                }
            } catch (Throwable ignored) {}

            if (!added) {
                Method addLast = pipeline.getClass().getMethod("addLast", String.class, channelHandlerClass);
                addLast.invoke(pipeline, HANDLER_NAME, handlerProxy);
            }

            hookedChannels.put(player.getUniqueId(), channel);
        } catch (Throwable ignored) {
        }
    }

    public void uninjectPlayer(Player player) {
        try {
            Object channel = hookedChannels.remove(player.getUniqueId());
            if (channel == null) channel = getChannel(player);
            if (channel != null) {
                Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);
                if (pipeline != null) {
                    Method getMethod = pipeline.getClass().getMethod("get", String.class);
                    if (getMethod.invoke(pipeline, HANDLER_NAME) != null) {
                        pipeline.getClass().getMethod("remove", String.class).invoke(pipeline, HANDLER_NAME);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void handlePacketIn(Player player, Object packet) {
        if (packet == null) return;
        totalInbound.incrementAndGet();
        String pName = packet.getClass().getSimpleName();
        packetTypeCounts.computeIfAbsent("IN:" + pName, k -> new AtomicLong(0)).incrementAndGet();

        recordPacket("IN", player.getName(), pName);

        if (listenedPackets.contains("all") || listenedPackets.contains(pName.toLowerCase())) {
            dumpPacketFields(player, "IN", packet);
        } else if (listening) {
            if (playerFilter != null && !player.getName().equalsIgnoreCase(playerFilter)) return;
            if (!packetFilter.equalsIgnoreCase("all") && !pName.toLowerCase().contains(packetFilter.toLowerCase())) return;

            plugin.getLogger().info(String.format("[PacketSniffer] [IN] [%s] %s", player.getName(), pName));
        }
    }

    private void handlePacketOut(Player player, Object packet) {
        if (packet == null) return;
        totalOutbound.incrementAndGet();
        String pName = packet.getClass().getSimpleName();
        packetTypeCounts.computeIfAbsent("OUT:" + pName, k -> new AtomicLong(0)).incrementAndGet();

        recordPacket("OUT", player.getName(), pName);

        if (listenedPackets.contains("all") || listenedPackets.contains(pName.toLowerCase())) {
            dumpPacketFields(player, "OUT", packet);
        } else if (listening) {
            if (playerFilter != null && !player.getName().equalsIgnoreCase(playerFilter)) return;
            if (!packetFilter.equalsIgnoreCase("all") && !pName.toLowerCase().contains(packetFilter.toLowerCase())) return;

            plugin.getLogger().info(String.format("[PacketSniffer] [OUT] [%s] %s", player.getName(), pName));
        }
    }

    private void recordPacket(String dir, String player, String type) {
        recentPackets.addLast(new PacketLogRecord(System.currentTimeMillis(), dir, player, type));
        while (recentPackets.size() > 150) {
            recentPackets.pollFirst();
        }
    }

    public void sendFakeBlock(Player player, Location loc, Material material) {
        player.sendBlockChange(loc, material.createBlockData());
    }

    private Object getChannel(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            for (Field f : handle.getClass().getFields()) {
                if (f.getName().equals("connection") || f.getName().equals("playerConnection")) {
                    f.setAccessible(true);
                    return extractChannelFromConnection(f.get(handle));
                }
            }
            for (Field f : handle.getClass().getDeclaredFields()) {
                if (f.getName().equals("connection") || f.getName().equals("playerConnection") || f.getType().getSimpleName().contains("Connection")) {
                    f.setAccessible(true);
                    return extractChannelFromConnection(f.get(handle));
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Object extractChannelFromConnection(Object connection) {
        if (connection == null) return null;
        try {
            for (Field f : connection.getClass().getDeclaredFields()) {
                if (f.getType().getSimpleName().equals("Connection") || f.getType().getSimpleName().equals("NetworkManager")) {
                    f.setAccessible(true);
                    Object netMgr = f.get(connection);
                    if (netMgr != null) {
                        for (Field nf : netMgr.getClass().getDeclaredFields()) {
                            if (nf.getType().getSimpleName().contains("Channel")) {
                                nf.setAccessible(true);
                                return nf.get(netMgr);
                            }
                        }
                    }
                }
                if (f.getType().getSimpleName().contains("Channel")) {
                    f.setAccessible(true);
                    return f.get(connection);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public boolean isListening() {
        return listening;
    }

    public void setListening(boolean listening) {
        this.listening = listening;
    }

    public String getPacketFilter() {
        return packetFilter;
    }

    public void setPacketFilter(String packetFilter) {
        this.packetFilter = packetFilter;
    }

    public String getPlayerFilter() {
        return playerFilter;
    }

    public void setPlayerFilter(String playerFilter) {
        this.playerFilter = playerFilter;
    }

    public long getTotalInbound() {
        return totalInbound.get();
    }

    public long getTotalOutbound() {
        return totalOutbound.get();
    }

    public int getHookedCount() {
        return hookedChannels.size();
    }

    public Map<String, AtomicLong> getPacketTypeCounts() {
        return Collections.unmodifiableMap(packetTypeCounts);
    }

    public List<PacketLogRecord> getRecentPackets() {
        return new ArrayList<>(recentPackets);
    }

    public String getRecentPacketsJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"inbound\":").append(totalInbound.get()).append(",");
        sb.append("\"outbound\":").append(totalOutbound.get()).append(",");
        sb.append("\"hooked\":").append(hookedChannels.size()).append(",");
        sb.append("\"listening\":").append(listening).append(",");
        sb.append("\"recent\":[");
        List<PacketLogRecord> list = new ArrayList<>(recentPackets);
        for (int i = 0; i < list.size(); i++) {
            PacketLogRecord r = list.get(i);
            sb.append("{\"time\":").append(r.timestamp())
              .append(",\"dir\":\"").append(r.direction())
              .append("\",\"player\":\"").append(r.player())
              .append("\",\"type\":\"").append(r.packetType())
              .append("\"}");
            if (i < list.size() - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    public void dumpPacketFields(Player player, String dir, Object packet) {
        if (packet == null) return;
        String pName = packet.getClass().getSimpleName();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("§6[Netty Field Dump] §b[%s] §e[%s] §f%s: {\n", dir, player.getName(), pName));
        try {
            Class<?> c = packet.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(packet);
                    sb.append("   §7").append(f.getName()).append(" (").append(f.getType().getSimpleName()).append("): §f")
                      .append(formatFieldValue(val)).append("\n");
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            sb.append("   §c(Lỗi trích xuất fields: ").append(t.getMessage()).append(")\n");
        }
        sb.append("§6}");
        plugin.getLogger().info(sb.toString().replaceAll("§[0-9a-fk-or]", ""));
    }

    private String formatFieldValue(Object val) {
        if (val == null) return "null";
        if (val instanceof byte[] b) return "byte[" + b.length + "]";
        if (val instanceof Object[] arr) return arr.getClass().getSimpleName() + "[" + arr.length + "]";
        String s = String.valueOf(val);
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    private boolean isMaliciousPreMainThread(Player player, Object packet) {
        if (packet == null) return false;
        String name = packet.getClass().getSimpleName().toLowerCase();

        if (name.contains("book") || name.contains("editbook")) {
            try {
                for (Field f : packet.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object val = f.get(packet);
                    if (val instanceof Collection<?> col && col.size() > 50) {
                        plugin.getLogger().warning("[Pre-Main ExploitGuard] Chặn Crasher Book từ " + player.getName() + " (Pages: " + col.size() + ") trên Netty IO!");
                        return true;
                    }
                    if (val instanceof String str && str.length() > 2048) {
                        plugin.getLogger().warning("[Pre-Main ExploitGuard] Chặn Crasher Book từ " + player.getName() + " (Text length: " + str.length() + ") trên Netty IO!");
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (name.contains("commandsuggestion") || name.contains("tabcomplete")) {
            AtomicLong count = tabCompleteCounts.computeIfAbsent(player.getUniqueId(), k -> new AtomicLong(0));
            if (count.incrementAndGet() > 25) {
                return true;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> count.set(0), 20L);
        }

        if (name.contains("move") || name.contains("position")) {
            try {
                for (Field f : packet.getClass().getDeclaredFields()) {
                    if (f.getType() == double.class) {
                        f.setAccessible(true);
                        double d = f.getDouble(packet);
                        if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > 30_000_000.0) {
                            plugin.getLogger().warning("[Pre-Main ExploitGuard] Chặn gói tin tọa độ bất thường (" + f.getName() + "=" + d + ") từ " + player.getName() + " trên Netty IO!");
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        return false;
    }

    private void checkPacketTimer(Player player, Object packet) {
        if (packet == null) return;
        String name = packet.getClass().getSimpleName().toLowerCase();
        if (!name.contains("move") && !name.contains("position")) return;

        MovementTracker tracker = movementTrackers.computeIfAbsent(player.getUniqueId(), k -> new MovementTracker());
        long now = System.currentTimeMillis();

        synchronized (tracker.timestamps) {
            tracker.timestamps.add(now);
            while (tracker.timestamps.size() > 20) {
                tracker.timestamps.poll();
            }

            if (tracker.timestamps.size() >= 20) {
                long first = tracker.timestamps.peek();
                long elapsed = now - first;
                if (elapsed < 500L && elapsed > 0L) {
                    double rate = 20.0 / (elapsed / 1000.0);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        var ac = plugin.getAntiCheatManager();
                        if (ac != null) {
                            var data = ac.data(player);
                            ac.flag(player, data, com.javastudio.anticheat.CheckType.TIMER,
                                    String.format("Tốc độ gửi gói Move: %.1f pkts/s (20 pkts trong %dms)", rate, elapsed), 1.0);
                        }

                    });
                }
            }
        }
    }

    public boolean isPacketCancelled(Object packet) {
        if (packet == null || cancelledPackets.isEmpty()) return false;
        String name = packet.getClass().getSimpleName().toLowerCase();
        for (String filter : cancelledPackets) {
            if (name.contains(filter.toLowerCase())) return true;
        }
        return false;
    }

    public void cancelPacket(String name) {
        if (name != null) cancelledPackets.add(name.toLowerCase());
    }

    public boolean uncancelPacket(String name) {
        if (name == null) return false;
        return cancelledPackets.remove(name.toLowerCase());
    }

    public void clearCancelled() {
        cancelledPackets.clear();
    }

    public Set<String> getCancelledPackets() {
        return Collections.unmodifiableSet(cancelledPackets);
    }

    public long getTotalCancelled() {
        return totalCancelled.get();
    }

    public void addListenedPacket(String name) {
        if (name != null) listenedPackets.add(name.toLowerCase());
    }

    public boolean removeListenedPacket(String name) {
        if (name == null) return false;
        return listenedPackets.remove(name.toLowerCase());
    }

    public Set<String> getListenedPackets() {
        return Collections.unmodifiableSet(listenedPackets);
    }
}
