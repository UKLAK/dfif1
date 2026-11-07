package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

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

    public boolean broadcast(String channel, String message) {
        if (channel == null || message == null) return false;

        if (redis != null && redis.isConnected()) {
            redis.publish(channel, message);
            return true;
        }

        if (proxy != null) {
            proxy.broadcastToAllServers(null, channel, message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        }

        if (socketSync != null) {
            socketSync.broadcastPayload("BROADCAST", channel, message);
            return true;
        }

        return false;
    }

    public void switchServer(Player player, String targetServer) {
        if (proxy != null && player != null && targetServer != null) {
            proxy.sendPlayerToServer(player, targetServer);
        }
    }

    public void sendPrivateMessage(String targetPlayer, String message) {
        if (proxy != null) {
            proxy.sendCrossServerMessage(null, targetPlayer, message);
        } else if (redis != null && redis.isConnected()) {
            redis.publish("msg:" + targetPlayer, message);
        }
    }

    public void requestServerList(Consumer<List<String>> callback) {
        if (proxy != null) {
            proxy.requestServerList(null, callback);
        }
    }

    public void requestPlayerCount(String serverName, Consumer<Integer> callback) {
        if (proxy != null) {
            proxy.requestPlayerCount(null, serverName, callback);
        }
    }

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
