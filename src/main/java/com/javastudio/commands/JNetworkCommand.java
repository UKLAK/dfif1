package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.network.NetworkSyncManager;
import com.javastudio.network.RedisSyncManager;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.File;
import java.util.*;

public class JNetworkCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;

    public JNetworkCommand(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền quản lý mạng máy chủ!");
            return true;
        }

        if (!plugin.getSettingsManager().isNetworkSyncEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Mạng Đồng Bộ Đa Máy Chủ (/jnetwork) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle network-sync &7hoặc qua &e/javastudio settings");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        NetworkSyncManager netSync = plugin.getNetworkSyncManager();
        RedisSyncManager redisSync = plugin.getRedisSyncManager();

        switch (sub) {
            case "status" -> {
                MessageUtil.send(sender, "&6=== TRẠNG THÁI MẠNG ĐA SERVER (NETWORK SYNC) ===");
                boolean netRunning = netSync != null && netSync.isRunning();
                MessageUtil.sendRaw(sender, "  &7TCP Socket Master: " + (netRunning ? "&a[Hoạt động]" : "&c[Đang tắt]"));
                if (netSync != null) {
                    MessageUtil.sendRaw(sender, "  &7Máy chủ con kết nối: &b" + netSync.getConnectedCount() + " servers");
                }

                boolean redisConnected = redisSync != null && redisSync.isConnected();
                MessageUtil.sendRaw(sender, "  &7Redis Cluster Sync: " + (redisConnected ? "&a[Đã kết nối]" : "&7[Chưa bật / Offline]"));
                if (redisConnected) {
                    MessageUtil.sendRaw(sender, "  &7Kênh Pub/Sub: &fjavastudio:sync");
                }
            }
            case "broadcast" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " broadcast <tên_plugin>");
                    return true;
                }
                String pluginName = args[1];
                File projectDir = new File(plugin.getPluginManager().getProjectsFolder(), pluginName);
                if (!projectDir.exists()) {
                    MessageUtil.sendError(sender, "Không tìm thấy dynamic plugin: " + pluginName);
                    return true;
                }

                MessageUtil.sendInfo(sender, "Đang phát sóng đồng bộ plugin &e" + pluginName + " &7tới toàn bộ cụm server...");

                int sentSockets = 0;
                if (netSync != null) {
                    sentSockets = netSync.broadcastPayload("SYNC_PLUGIN", pluginName, "RELOAD");
                }
                if (redisSync != null && redisSync.isConnected()) {
                    redisSync.publish("javastudio:sync", "RELOAD:::" + pluginName + ":::MANUAL_BROADCAST");
                }

                MessageUtil.sendSuccess(sender, "Đã gửi gói tin đồng bộ tới các máy chủ trong cụm! (Socket clients: " + sentSockets + ")");
            }
            case "exec" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " exec <mã Java...>");
                    return true;
                }
                StringBuilder code = new StringBuilder();
                for (int i = 1; i < args.length; i++) code.append(args[i]).append(" ");
                String codeStr = code.toString().trim();

                MessageUtil.sendInfo(sender, "Đang phát sóng thực thi mã Java xuyên cụm server...");
                if (netSync != null) {
                    netSync.broadcastPayload("CROSS_EXEC", "cluster", codeStr);
                }
                if (redisSync != null && redisSync.isConnected()) {
                    redisSync.publish("javastudio:sync", "EXEC:::cluster:::" + codeStr);
                }
                MessageUtil.sendSuccess(sender, "Đã gửi lệnh thực thi xuyên server!");
            }
            case "redis" -> {
                if (redisSync == null || !redisSync.isConnected()) {
                    MessageUtil.sendWarning(sender, "Redis chưa được kết nối. Kiểm tra cấu hình 'redis' trong config.yml!");
                    return true;
                }
                redisSync.publish("javastudio:sync", "PING:::test:::hello");
                MessageUtil.sendSuccess(sender, "Kết nối Redis đang hoạt động bình thường! (Đã gửi ping tới javastudio:sync)");
            }
            case "proxy" -> {
                var proxy = plugin.getProxyBridge();
                if (proxy == null) {
                    MessageUtil.sendError(sender, "ProxyBridge chưa sẵn sàng!");
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&6=== LỆNH PROXY (VELOCITY / BUNGEECORD) ===");
                    MessageUtil.sendRaw(sender, "  &e/" + label + " proxy list &7- Lấy danh sách server trên proxy");
                    MessageUtil.sendRaw(sender, "  &e/" + label + " proxy count <tên|ALL> &7- Đếm số người chơi");
                    MessageUtil.sendRaw(sender, "  &e/" + label + " proxy send <player> <server> &7- Chuyển server người chơi");
                    MessageUtil.sendRaw(sender, "  &e/" + label + " proxy msg <player> <nội_dung> &7- Nhắn tin xuyên server");
                    return true;
                }

                String pSub = args[1].toLowerCase();
                org.bukkit.entity.Player carrier = sender instanceof org.bukkit.entity.Player p ? p : null;

                switch (pSub) {
                    case "list" -> {
                        MessageUtil.sendInfo(sender, "Đang gửi yêu cầu danh sách server tới Proxy...");
                        proxy.requestServerList(carrier, list -> {
                            MessageUtil.sendSuccess(sender, "Danh sách server trên Proxy: &e" + String.join(", ", list));
                        });
                    }
                    case "count" -> {
                        String target = args.length >= 3 ? args[2] : "ALL";
                        MessageUtil.sendInfo(sender, "Đang kiểm tra số người chơi server: " + target + "...");
                        proxy.requestPlayerCount(carrier, target, count -> {
                            MessageUtil.sendSuccess(sender, "Số người chơi hiện tại (" + target + "): &e" + count + " online");
                        });
                    }
                    case "send" -> {
                        if (args.length < 4) {
                            MessageUtil.sendError(sender, "Cách dùng: /" + label + " proxy send <tên_player> <server_đích>");
                            return true;
                        }
                        org.bukkit.entity.Player targetPlayer = org.bukkit.Bukkit.getPlayer(args[2]);
                        if (targetPlayer == null) {
                            MessageUtil.sendError(sender, "Không tìm thấy người chơi: " + args[2]);
                            return true;
                        }
                        boolean sent = proxy.sendPlayerToServer(targetPlayer, args[3]);
                        if (sent) {
                            MessageUtil.sendSuccess(sender, "Đã gửi yêu cầu chuyển " + targetPlayer.getName() + " sang " + args[3] + "!");
                        } else {
                            MessageUtil.sendError(sender, "Không thể gửi yêu cầu chuyển server!");
                        }
                    }
                    case "msg" -> {
                        if (args.length < 4) {
                            MessageUtil.sendError(sender, "Cách dùng: /" + label + " proxy msg <player> <tin_nhắn...>");
                            return true;
                        }
                        StringBuilder msg = new StringBuilder();
                        for (int i = 3; i < args.length; i++) msg.append(args[i]).append(" ");
                        boolean sent = proxy.sendCrossServerMessage(carrier, args[2], msg.toString().trim());
                        if (sent) {
                            MessageUtil.sendSuccess(sender, "Đã gửi tin nhắn riêng xuyên server tới " + args[2] + "!");
                        } else {
                            MessageUtil.sendError(sender, "Không thể gửi tin nhắn qua proxy!");
                        }
                    }
                    default -> MessageUtil.sendError(sender, "Lệnh proxy không hợp lệ! Dùng /" + label + " proxy để xem trợ giúp.");
                }
            }
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        MessageUtil.send(sender, "&6=== Hướng dẫn Lệnh /" + label + " ===");
        MessageUtil.sendRaw(sender, "  &e/" + label + " status &7- Xem trạng thái kết nối mạng & Redis");
        MessageUtil.sendRaw(sender, "  &e/" + label + " broadcast <tên> &7- Đồng bộ plugin tức thì sang các server khác");
        MessageUtil.sendRaw(sender, "  &e/" + label + " exec <code...> &7- Chạy mã Java đồng loạt trên toàn bộ server");
        MessageUtil.sendRaw(sender, "  &e/" + label + " redis &7- Kiểm tra kết nối Redis");
        MessageUtil.sendRaw(sender, "  &e/" + label + " proxy &7- Điều khiển mạng Proxy (Velocity/BungeeCord)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "broadcast", "exec", "redis", "proxy");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("proxy")) {
            return Arrays.asList("list", "count", "send", "msg");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("broadcast")) {
            return plugin.getPluginManager().getAllProjects();
        }
        return Collections.emptyList();
    }
}
