package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

/**
 * NettyPacketEngine: Can thiệp tầng Netty Packet cấp thấp (Low-Level Netty Runtime).
 * Hoạt động độc lập không cần ProtocolLib trên Paper, Purpur, Spigot và Folia.
 */
public final class NettyPacketEngine implements Listener {

    private static final String HANDLER_NAME = "javastudio_packet_interceptor";

    private final JavaStudioPlugin plugin;
    private final Logger logger;

    private final Map<String, BiPredicate<Player, Object>> inboundFilters = new ConcurrentHashMap<>();
    private final Map<String, BiPredicate<Player, Object>> outboundFilters = new ConcurrentHashMap<>();
    private final Set<String> cancelledPacketNames = ConcurrentHashMap.newKeySet();
    private final Map<Player, Channel> hookedChannels = new ConcurrentHashMap<>();

    public NettyPacketEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) {
            injectPlayer(p);
        }
        logger.info("[NettyPacketEngine] Đã kích hoạt bộ can thiệp Netty ChannelDuplexHandler cấp thấp!");
    }

    public void stop() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            uninjectPlayer(p);
        }
        hookedChannels.clear();
        inboundFilters.clear();
        outboundFilters.clear();
        cancelledPacketNames.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        // Trì hoãn 1 tick để kết nối Netty được khởi tạo hoàn toàn
        Bukkit.getScheduler().runTaskLater(plugin, () -> injectPlayer(event.getPlayer()), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        uninjectPlayer(event.getPlayer());
    }

    public void injectPlayer(Player player) {
        if (player == null || !player.isOnline()) return;
        Channel channel = getChannel(player);
        if (channel == null) return;
        if (channel.pipeline().get(HANDLER_NAME) != null) return;

        ChannelDuplexHandler handler = new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object packet) throws Exception {
                String packetName = packet.getClass().getSimpleName();
                if (cancelledPacketNames.contains(packetName)) return;

                for (BiPredicate<Player, Object> filter : inboundFilters.values()) {
                    try {
                        if (!filter.test(player, packet)) return;
                    } catch (Throwable t) {
                        logger.warning("[NettyPacketEngine] Lỗi trong Inbound Filter: " + t.getMessage());
                    }
                }
                super.channelRead(ctx, packet);
            }

            @Override
            public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) throws Exception {
                String packetName = packet.getClass().getSimpleName();
                if (cancelledPacketNames.contains(packetName)) return;

                for (BiPredicate<Player, Object> filter : outboundFilters.values()) {
                    try {
                        if (!filter.test(player, packet)) return;
                    } catch (Throwable t) {
                        logger.warning("[NettyPacketEngine] Lỗi trong Outbound Filter: " + t.getMessage());
                    }
                }
                super.write(ctx, packet, promise);
            }
        };

        channel.eventLoop().submit(() -> {
            try {
                if (channel.pipeline().get(HANDLER_NAME) == null) {
                    if (channel.pipeline().get("packet_handler") != null) {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
                    } else {
                        channel.pipeline().addFirst(HANDLER_NAME, handler);
                    }
                    hookedChannels.put(player, channel);
                }
            } catch (Throwable t) {
                logger.warning("[NettyPacketEngine] Không thể ghim handler cho " + player.getName() + ": " + t.getMessage());
            }
        });
    }

    public void uninjectPlayer(Player player) {
        if (player == null) return;
        Channel channel = hookedChannels.remove(player);
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
        return java.util.Collections.unmodifiableSet(cancelledPacketNames);
    }

    public int getHookedCount() {
        return hookedChannels.size();
    }

    public Channel getChannel(Player player) {
        try {
            Method getHandle = player.getClass().getMethod("getHandle");
            Object serverPlayer = getHandle.invoke(player);

            for (Field field : serverPlayer.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object conn = field.get(serverPlayer);
                if (conn != null && conn.getClass().getSimpleName().contains("GamePacketListener")) {
                    for (Field connField : conn.getClass().getSuperclass().getDeclaredFields()) {
                        connField.setAccessible(true);
                        Object netManager = connField.get(conn);
                        if (netManager != null) {
                            for (Field chField : netManager.getClass().getDeclaredFields()) {
                                if (Channel.class.isAssignableFrom(chField.getType())) {
                                    chField.setAccessible(true);
                                    return (Channel) chField.get(netManager);
                                }
                            }
                        }
                    }
                    // Thử tìm Channel trực tiếp trong conn
                    for (Field chField : conn.getClass().getDeclaredFields()) {
                        if (Channel.class.isAssignableFrom(chField.getType())) {
                            chField.setAccessible(true);
                            return (Channel) chField.get(conn);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
