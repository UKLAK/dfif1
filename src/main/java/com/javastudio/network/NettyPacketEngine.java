package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.PlatformUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

public final class NettyPacketEngine implements Listener {

    private static final String HANDLER_NAME = "javastudio_packet_interceptor";

    private final JavaStudioPlugin plugin;
    private final Logger logger;

    private final Map<String, BiPredicate<Player, Object>> inboundFilters = new ConcurrentHashMap<>();
    private final Map<String, BiPredicate<Player, Object>> outboundFilters = new ConcurrentHashMap<>();
    private final Set<String> cancelledPacketNames = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Channel> hookedChannels = new ConcurrentHashMap<>();

    private volatile boolean started;
    private volatile boolean listenerRegistered;

    private volatile Method cachedGetHandle;
    private volatile Field cachedConnectionField;
    private volatile Field cachedNetworkManagerField;
    private volatile Field cachedChannelField;

    public NettyPacketEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            injectPlayer(p);
        }
        logger.info("[NettyPacketEngine] Đã kích hoạt bộ can thiệp Netty ChannelDuplexHandler cấp thấp!");
    }

    public synchronized void stop() {
        started = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            uninjectPlayer(p);
        }
        hookedChannels.clear();
        inboundFilters.clear();
        outboundFilters.clear();
        cancelledPacketNames.clear();
    }

    public boolean isStarted() {
        return started;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!started) return;
        Player player = event.getPlayer();
        PlatformUtil.runAtEntityLater(plugin, player, () -> {
            if (started && player.isOnline()) {
                injectPlayer(player);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        uninjectPlayer(event.getPlayer());
    }

    public void injectPlayer(Player player) {
        if (!started || player == null || !player.isOnline()) return;
        Channel channel = getChannel(player);
        if (channel == null || !channel.isOpen()) return;
        if (channel.pipeline().get(HANDLER_NAME) != null) {
            hookedChannels.put(player.getUniqueId(), channel);
            return;
        }

        final UUID playerId = player.getUniqueId();
        ChannelDuplexHandler handler = new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object packet) throws Exception {
                if (packet != null) {
                    String packetName = packet.getClass().getSimpleName();
                    if (cancelledPacketNames.contains(packetName)) return;

                    if (!inboundFilters.isEmpty()) {
                        for (BiPredicate<Player, Object> filter : inboundFilters.values()) {
                            try {
                                if (!filter.test(player, packet)) return;
                            } catch (Throwable t) {
                                logger.warning("[NettyPacketEngine] Lỗi trong Inbound Filter: " + t.getMessage());
                            }
                        }
                    }
                }
                super.channelRead(ctx, packet);
            }

            @Override
            public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) throws Exception {
                if (packet != null) {
                    String packetName = packet.getClass().getSimpleName();
                    if (cancelledPacketNames.contains(packetName)) return;

                    if (!outboundFilters.isEmpty()) {
                        for (BiPredicate<Player, Object> filter : outboundFilters.values()) {
                            try {
                                if (!filter.test(player, packet)) return;
                            } catch (Throwable t) {
                                logger.warning("[NettyPacketEngine] Lỗi trong Outbound Filter: " + t.getMessage());
                            }
                        }
                    }
                }
                super.write(ctx, packet, promise);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                hookedChannels.remove(playerId);
                super.channelInactive(ctx);
            }
        };

        channel.eventLoop().submit(() -> {
            try {
                if (channel.isOpen() && channel.pipeline().get(HANDLER_NAME) == null) {
                    if (channel.pipeline().get("packet_handler") != null) {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
                    } else {
                        channel.pipeline().addFirst(HANDLER_NAME, handler);
                    }
                    hookedChannels.put(playerId, channel);
                }
            } catch (Throwable t) {
                logger.warning("[NettyPacketEngine] Không thể ghim handler cho " + player.getName() + ": " + t.getMessage());
            }
        });
    }

    public void uninjectPlayer(Player player) {
        if (player == null) return;
        Channel channel = hookedChannels.remove(player.getUniqueId());
        if (channel == null) {
            channel = getChannel(player);
        }
        if (channel != null) {
            final Channel ch = channel;
            ch.eventLoop().submit(() -> {
                try {
                    if (ch.pipeline().get(HANDLER_NAME) != null) {
                        ch.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Throwable ignored) {}
            });
        }
    }

    public void registerInboundFilter(String id, BiPredicate<Player, Object> filter) {
        inboundFilters.put(id, filter);
    }

    public void unregisterInboundFilter(String id) {
        inboundFilters.remove(id);
    }

    public void registerOutboundFilter(String id, BiPredicate<Player, Object> filter) {
        outboundFilters.put(id, filter);
    }

    public void unregisterOutboundFilter(String id) {
        outboundFilters.remove(id);
    }

    public void setPacketCancelled(String simplePacketName, boolean cancel) {
        if (cancel) cancelledPacketNames.add(simplePacketName);
        else cancelledPacketNames.remove(simplePacketName);
    }

    public Set<String> getCancelledPackets() {
        return Collections.unmodifiableSet(cancelledPacketNames);
    }

    public int getHookedCount() {
        return hookedChannels.size();
    }

    public void sendPacket(Player player, Object packet) {
        if (player == null || packet == null) return;
        Channel ch = hookedChannels.get(player.getUniqueId());
        if (ch == null) ch = getChannel(player);
        if (ch != null && ch.isOpen()) {
            final Channel targetChannel = ch;
            targetChannel.eventLoop().execute(() -> targetChannel.writeAndFlush(packet));
        }
    }

    public void broadcastPacket(Object packet) {
        if (packet == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendPacket(p, packet);
        }
    }

    public Channel getChannel(Player player) {
        try {
            if (cachedGetHandle != null && cachedConnectionField != null && cachedChannelField != null) {
                Object handle = cachedGetHandle.invoke(player);
                if (handle != null) {
                    Object conn = cachedConnectionField.get(handle);
                    if (conn != null) {
                        Object holder = cachedNetworkManagerField != null ? cachedNetworkManagerField.get(conn) : conn;
                        if (holder != null) {
                            Object ch = cachedChannelField.get(holder);
                            if (ch instanceof Channel channel) {
                                return channel;
                            }
                        }
                    }
                }
            }

            Method getHandle = player.getClass().getMethod("getHandle");
            Object serverPlayer = getHandle.invoke(player);
            if (serverPlayer == null) return null;

            for (Class<?> spClass = serverPlayer.getClass(); spClass != null && spClass != Object.class; spClass = spClass.getSuperclass()) {
                for (Field connField : spClass.getDeclaredFields()) {
                    connField.setAccessible(true);
                    Object conn = connField.get(serverPlayer);
                    if (conn == null) continue;
                    String connType = conn.getClass().getSimpleName();
                    if (!connType.contains("PacketListener") && !connType.contains("PlayerConnection")) continue;

                    Channel direct = findChannelField(conn, connField, null, getHandle);
                    if (direct != null) return direct;

                    for (Class<?> cCls = conn.getClass(); cCls != null && cCls != Object.class; cCls = cCls.getSuperclass()) {
                        for (Field netField : cCls.getDeclaredFields()) {
                            netField.setAccessible(true);
                            Object netObj = netField.get(conn);
                            if (netObj == null || netObj instanceof String || netObj instanceof Number || netObj instanceof Boolean) {
                                continue;
                            }
                            Channel nested = findChannelField(netObj, connField, netField, getHandle);
                            if (nested != null) return nested;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Channel findChannelField(Object holder, Field connField, Field netField, Method getHandle) throws IllegalAccessException {
        for (Class<?> hCls = holder.getClass(); hCls != null && hCls != Object.class; hCls = hCls.getSuperclass()) {
            for (Field chField : hCls.getDeclaredFields()) {
                if (Channel.class.isAssignableFrom(chField.getType())) {
                    chField.setAccessible(true);
                    Object val = chField.get(holder);
                    if (val instanceof Channel channel) {
                        this.cachedGetHandle = getHandle;
                        this.cachedConnectionField = connField;
                        this.cachedNetworkManagerField = netField;
                        this.cachedChannelField = chField;
                        return channel;
                    }
                }
            }
        }
        return null;
    }
}
