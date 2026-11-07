package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@SuppressWarnings("this-escape")
public final class ProxyBridgeManager implements PluginMessageListener {

    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private final JavaStudioPlugin plugin;
    private final Map<String, Consumer<Integer>> playerCountCallbacks = new ConcurrentHashMap<>();
    private final List<Consumer<List<String>>> serverListCallbacks = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Consumer<byte[]>> customChannelListeners = new ConcurrentHashMap<>();

    public ProxyBridgeManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        registerChannels();
    }

    private void registerChannels() {
        try {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, BUNGEE_CHANNEL, this);
            plugin.getLogger().info("[ProxyBridge] Đã đăng ký kênh Plugin Messaging 'BungeeCord' kết nối Proxy!");
        } catch (Throwable t) {
            plugin.getLogger().warning("[ProxyBridge] Không thể đăng ký kênh BungeeCord: " + t.getMessage());
        }
    }

    public boolean sendPlayerToServer(Player player, String targetServer) {
        if (player == null || !player.isOnline() || targetServer == null) return false;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(targetServer);
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean sendCrossServerMessage(Player sender, String targetPlayer, String message) {
        Player carrier = sender != null ? sender : getAnyOnlinePlayer();
        if (carrier == null) return false;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Message");
            out.writeUTF(targetPlayer);
            out.writeUTF(message);
            carrier.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void requestPlayerCount(Player sender, String serverName, Consumer<Integer> callback) {
        Player carrier = sender != null ? sender : getAnyOnlinePlayer();
        if (carrier == null) {
            callback.accept(-1);
            return;
        }
        playerCountCallbacks.put(serverName.toLowerCase(), callback);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("PlayerCount");
            out.writeUTF(serverName);
            carrier.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            callback.accept(-1);
        }
    }

    public void requestServerList(Player sender, Consumer<List<String>> callback) {
        Player carrier = sender != null ? sender : getAnyOnlinePlayer();
        if (carrier == null) {
            callback.accept(Collections.emptyList());
            return;
        }
        serverListCallbacks.add(callback);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("GetServers");
            carrier.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            callback.accept(Collections.emptyList());
        }
    }

    public boolean broadcastToAllServers(Player sender, String subChannel, byte[] data) {
        Player carrier = sender != null ? sender : getAnyOnlinePlayer();
        if (carrier == null) return false;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Forward");
            out.writeUTF("ALL");
            out.writeUTF(subChannel);
            out.writeShort(data.length);
            out.write(data);
            carrier.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void registerSubChannelListener(String subChannel, Consumer<byte[]> listener) {
        customChannelListeners.put(subChannel.toLowerCase(), listener);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!BUNGEE_CHANNEL.equals(channel)) return;

        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            String subChannel = in.readUTF();

            if ("PlayerCount".equalsIgnoreCase(subChannel)) {
                String srv = in.readUTF().toLowerCase();
                int count = in.readInt();
                Consumer<Integer> cb = playerCountCallbacks.remove(srv);
                if (cb != null) cb.accept(count);
            } else if ("GetServers".equalsIgnoreCase(subChannel)) {
                String[] srvs = in.readUTF().split(", ");
                List<String> list = Arrays.asList(srvs);
                synchronized (serverListCallbacks) {
                    for (Consumer<List<String>> cb : serverListCallbacks) {
                        cb.accept(list);
                    }
                    serverListCallbacks.clear();
                }
            } else {
                Consumer<byte[]> listener = customChannelListeners.get(subChannel.toLowerCase());
                if (listener != null) {
                    short len = in.readShort();
                    byte[] payload = new byte[len];
                    in.readFully(payload);
                    listener.accept(payload);
                }
            }
        } catch (Throwable ignored) {}
    }

    private Player getAnyOnlinePlayer() {
        Iterator<? extends Player> it = Bukkit.getOnlinePlayers().iterator();
        return it.hasNext() ? it.next() : null;
    }

    public void unregister() {
        try {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, BUNGEE_CHANNEL, this);
        } catch (Throwable ignored) {}
    }
}
