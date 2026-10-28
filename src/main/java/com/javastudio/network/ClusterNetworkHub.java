package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * Trung Tâm Điều Phối Mạng Đa Máy Chủ Hợp Nhất (Cluster Network Hub).
 * Hợp nhất 3 phương thức truyền tin phân tán thành một API duy nhất:
 * 1. Redis Pub/Sub (Mạng tốc độ cao qua RAM Redis)
 * 2. Proxy Plugin Messaging (Chuẩn BungeeCord / Velocity)
 * 3. Raw Socket Network Sync (Đồng bộ trực tiếp qua TCP Socket)
 *
 * Tự động chọn tầng truyền tải tối ưu nhất dựa trên cấu hình hoạt động hiện tại!
 */
public final class ClusterNetworkHub {

    private final JavaStudioPlugin plugin;
    private final RedisSyncManager redis;
    private final ProxyBridgeManager proxy;
    private final NetworkSyncManager socketSync;

    public ClusterNetworkHub(JavaStudioPlugin plugin,
                             RedisSyncManager redis,
                             ProxyBridgeManager proxy,
                             NetworkSyncManager socketSync) {
        this.plugin = plugin;
        this.redis = redis;
        this.proxy = proxy;
        this.socketSync = socketSync;
    }

    public JavaStudioPlugin getPlugin() {
        return plugin;
    }

    /**
     * Phát thông điệp tới toàn bộ cụm máy chủ qua kênh (Channel).
     * Tự động ưu tiên: Redis -> Proxy Plugin Messaging -> Raw Socket Sync.
     */
    public boolean broadcast(String channel, String message) {
        if (channel == null || message == null) return false;

        // 1. Ưu tiên Redis Pub/Sub nếu đang kết nối
        if (redis != null && redis.isConnected()) {
            redis.publish(channel, message);
            return true;
        }

        // 2. Chuyển sang kênh Plugin Messaging BungeeCord
        if (proxy != null) {
            proxy.broadcastToAllServers(null, channel, message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        }

        // 3. Fallback qua Socket Sync
        if (socketSync != null) {
            socketSync.broadcastPayload("BROADCAST", channel, message);
            return true;
        }

        return false;
    }

    /**
     * Chuyển người chơi sang máy chủ chỉ định trong cụm (Proxy Switch).
     */
    public void switchServer(Player player, String targetServer) {
        if (proxy != null && player != null && targetServer != null) {
            proxy.sendPlayerToServer(player, targetServer);
        }
    }

    /**
     * Gửi tin nhắn riêng tới một người chơi ở bất kỳ server nào trong cụm.
     */
    public void sendPrivateMessage(String targetPlayer, String message) {
        if (proxy != null) {
            proxy.sendCrossServerMessage(null, targetPlayer, message);
        } else if (redis != null && redis.isConnected()) {
            redis.publish("msg:" + targetPlayer, message);
        }
    }

    /**
     * Lấy danh sách toàn bộ server trong cụm.
     */
    public void requestServerList(Consumer<List<String>> callback) {
        if (proxy != null) {
            proxy.requestServerList(null, callback);
        }
    }

    /**
     * Lấy số lượng người chơi trên một server trong cụm.
     */
    public void requestPlayerCount(String serverName, Consumer<Integer> callback) {
        if (proxy != null) {
            proxy.requestPlayerCount(null, serverName, callback);
        }
    }

    /**
     * Lấy bản tóm tắt trạng thái của 3 tầng mạng.
     */
    public String getStatusSummary() {
        boolean rOk = redis != null && redis.isConnected();
        boolean pOk = proxy != null;
        boolean sOk = socketSync != null;

        return String.format("§7Redis: %s §8| §7Proxy (Bungee/Velocity): %s §8| §7SocketSync: %s",
                rOk ? "§a§lĐÃ KẾT NỐI" : "§cTẮT",
                pOk ? "§a§lSẴN SÀNG" : "§cTẮT",
                sOk ? "§aBẬT" : "§cTẮT");
    }

    public RedisSyncManager getRedis() { return redis; }
    public ProxyBridgeManager getProxy() { return proxy; }
    public NetworkSyncManager getSocketSync() { return socketSync; }
}
