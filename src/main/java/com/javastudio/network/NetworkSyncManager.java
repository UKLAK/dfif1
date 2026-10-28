package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý đồng bộ mạng giữa các máy chủ Minecraft (Multi-Server & Network Sync).
 * Cho phép các cụm server (Lobby, Survival, Skyblock...) đồng bộ mã nguồn,
 * cập nhật plugin theo thời gian thực và thực thi lệnh xuyên máy chủ.
 */
public class NetworkSyncManager {

    private final JavaStudioPlugin plugin;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private final Set<Socket> connectedClients = ConcurrentHashMap.newKeySet();
    private volatile boolean running = false;

    public NetworkSyncManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Bật chế độ Master Hub lắng nghe các server con kết nối tới.
     */
    public synchronized boolean startMaster(int port, String authToken) {
        if (running) stop();
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            serverThread = new Thread(() -> masterListenLoop(authToken), "JavaStudio-NetworkSyncMaster");
            serverThread.setDaemon(true);
            serverThread.start();
            plugin.getLogger().info("[NetworkSync] Đã khởi chạy Network Master trên cổng " + port + "!");
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[NetworkSync] Không thể khởi động Network Master: " + e.getMessage());
            return false;
        }
    }

    /**
     * Đồng bộ và phát sóng mã Java hoặc file script tới toàn bộ các server con.
     */
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
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String authLine = reader.readLine();

                if (authLine != null && authLine.equals("AUTH " + authToken)) {
                    connectedClients.add(client);
                    plugin.getLogger().info("[NetworkSync] Máy chủ con đã kết nối thành công: " + client.getRemoteSocketAddress());
                } else {
                    client.close();
                }
            } catch (IOException e) {
                if (!running) break;
            }
        }
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
