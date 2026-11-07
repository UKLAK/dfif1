package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;


public class RedisSyncManager {

    private final JavaStudioPlugin plugin;
    private Socket commandSocket;
    private OutputStream commandOut;
    private InputStream commandIn;

    private Socket subSocket;
    private Thread subThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Consumer<String>> subscriptions = new ConcurrentHashMap<>();

    private String host;
    private int port;
    private String password;

    public RedisSyncManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized boolean connect(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;

        try {
            this.commandSocket = new Socket();
            this.commandSocket.connect(new InetSocketAddress(host, port), 5000);
            this.commandOut = commandSocket.getOutputStream();
            this.commandIn = commandSocket.getInputStream();

            if (password != null && !password.isBlank()) {
                sendRawCommand(commandOut, "AUTH", password);
                readResp(commandIn);
            }

            sendRawCommand(commandOut, "PING");
            String pingResp = readResp(commandIn);
            if (!"PONG".equalsIgnoreCase(pingResp)) {
                plugin.getLogger().warning("[RedisSync] Phản hồi PING bất thường từ Redis: " + pingResp);
            }

            running.set(true);
            plugin.getLogger().info("[RedisSync] Kết nối Redis Server thành công tại " + host + ":" + port + "!");

            startSubscriberThread();

            subscribe("javastudio:sync", this::handleSyncMessage);

            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[RedisSync] Không thể kết nối tới Redis Server (" + host + ":" + port + "): " + e.getMessage());
            close();
            return false;
        }
    }

    public synchronized void publish(String channel, String message) {
        if (!isConnected()) return;
        try {
            sendRawCommand(commandOut, "PUBLISH", channel, message);
            readResp(commandIn);
        } catch (Exception e) {
            plugin.getLogger().warning("[RedisSync] Lỗi khi publish tới " + channel + ": " + e.getMessage());
        }
    }

    public synchronized void set(String key, String value) {
        if (!isConnected()) return;
        try {
            sendRawCommand(commandOut, "SET", key, value);
            readResp(commandIn);
        } catch (Exception e) {
            plugin.getLogger().warning("[RedisSync] Lỗi SET Redis: " + e.getMessage());
        }
    }

    public synchronized String get(String key) {
        if (!isConnected()) return null;
        try {
            sendRawCommand(commandOut, "GET", key);
            return readResp(commandIn);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized List<String> keys(String pattern) {
        if (!isConnected()) return Collections.emptyList();
        try {
            sendRawCommand(commandOut, "KEYS", pattern != null && !pattern.isBlank() ? pattern : "*");
            return readRespArray(commandIn);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public synchronized boolean del(String key) {
        if (!isConnected() || key == null) return false;
        try {
            sendRawCommand(commandOut, "DEL", key);
            String resp = readResp(commandIn);
            return !"0".equals(resp);
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized String type(String key) {
        if (!isConnected() || key == null) return "none";
        try {
            sendRawCommand(commandOut, "TYPE", key);
            return readResp(commandIn);
        } catch (Exception e) {
            return "none";
        }
    }

    public synchronized long ttl(String key) {
        if (!isConnected() || key == null) return -2L;
        try {
            sendRawCommand(commandOut, "TTL", key);
            String resp = readResp(commandIn);
            return Long.parseLong(resp);
        } catch (Exception e) {
            return -2L;
        }
    }

    public void subscribe(String channel, Consumer<String> handler) {

        subscriptions.put(channel, handler);
        if (subSocket != null && subSocket.isConnected()) {
            try {
                OutputStream os = subSocket.getOutputStream();
                sendRawCommand(os, "SUBSCRIBE", channel);
            } catch (Exception ignored) {}
        }
    }

    private void handleSyncMessage(String msg) {
        plugin.getLogger().info("[RedisSync] Nhận thông điệp đồng bộ mạng: " + msg);
        String[] parts = msg.split(":::", 3);
        if (parts.length >= 2) {
            String action = parts[0];
            String target = parts[1];

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if ("RELOAD".equalsIgnoreCase(action)) {
                    StringBuilder err = new StringBuilder();
                    boolean ok = plugin.getPluginManager().reloadPlugin(target, err);
                    plugin.getLogger().info("[RedisSync] Tự động Reload dynamic plugin " + target + " qua mạng: " + (ok ? "Thành công" : "Lỗi: " + err));
                } else if ("LOAD".equalsIgnoreCase(action)) {
                    StringBuilder err = new StringBuilder();
                    boolean ok = plugin.getPluginManager().loadPlugin(target, err);
                    plugin.getLogger().info("[RedisSync] Tự động Load dynamic plugin " + target + " qua mạng: " + (ok ? "Thành công" : "Lỗi: " + err));
                }
            });
        }
    }

    private void startSubscriberThread() {
        subThread = new Thread(() -> {
            try {
                subSocket = new Socket();
                subSocket.connect(new InetSocketAddress(host, port), 5000);
                OutputStream subOut = subSocket.getOutputStream();
                InputStream subIn = subSocket.getInputStream();

                if (password != null && !password.isBlank()) {
                    sendRawCommand(subOut, "AUTH", password);
                    readResp(subIn);
                }

                for (String ch : subscriptions.keySet()) {
                    sendRawCommand(subOut, "SUBSCRIBE", ch);
                }

                while (running.get() && !subSocket.isClosed()) {
                    String type = readResp(subIn);
                    if ("message".equalsIgnoreCase(type)) {
                        String channel = readResp(subIn);
                        String body = readResp(subIn);
                        Consumer<String> handler = subscriptions.get(channel);
                        if (handler != null) {
                            handler.accept(body);
                        }
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    plugin.getLogger().warning("[RedisSync] Luồng subscriber Redis bị gián đoạn: " + e.getMessage());
                }
            }
        }, "JavaStudio-RedisSubscriber");
        subThread.setDaemon(true);
        subThread.start();
    }

    private void sendRawCommand(OutputStream os, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] b = arg.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(b.length).append("\r\n");
            sb.append(arg).append("\r\n");
        }
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private String readResp(InputStream is) throws IOException {
        int b = is.read();
        if (b == -1) throw new EOFException("Redis socket stream closed");
        char type = (char) b;

        if (type == '+' || type == '-' || type == ':') {
            return readLine(is);
        } else if (type == '$') {
            int len = Integer.parseInt(readLine(is));
            if (len == -1) return null;
            byte[] data = new byte[len];
            int read = 0;
            while (read < len) {
                int r = is.read(data, read, len - read);
                if (r == -1) break;
                read += r;
            }
            readLine(is);
            return new String(data, StandardCharsets.UTF_8);
        } else if (type == '*') {
            int count = Integer.parseInt(readLine(is));
            if (count == -1) return null;
            if (count > 0) {
                return readResp(is);
            }
            return "";
        }
        return readLine(is);
    }

    private List<String> readRespArray(InputStream is) throws IOException {
        int b = is.read();
        if (b == -1) throw new EOFException("Redis socket stream closed");
        char type = (char) b;
        if (type == '*') {
            int count = Integer.parseInt(readLine(is));
            if (count <= 0) return Collections.emptyList();
            List<String> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                list.add(readResp(is));
            }
            return list;
        }
        return Collections.emptyList();
    }


    private String readLine(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = is.read()) != -1) {
            if (b == '\r') {
                is.read();
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    public boolean isConnected() {
        return commandSocket != null && commandSocket.isConnected() && !commandSocket.isClosed();
    }

    public synchronized void close() {
        running.set(false);
        try {
            if (commandSocket != null) commandSocket.close();
            if (subSocket != null) subSocket.close();
        } catch (Exception ignored) {}
    }
}
