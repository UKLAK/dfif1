package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.network.ClusterMeshEngine;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;

public class JMeshCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;

    public JMeshCommand(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            sender.sendMessage("§cBạn không có quyền thực hiện lệnh này!");
            return true;
        }

        if (!plugin.getSettingsManager().isClusterMeshEnabled()) {
            sender.sendMessage("§cPhân hệ Cluster P2P Mesh (/jmesh) đang bị TẮT trong cài đặt!");
            sender.sendMessage("§7Bật lại bằng lệnh: §e/javastudio toggle cluster-mesh §7hoặc qua §e/javastudio settings");
            return true;
        }

        ClusterMeshEngine mesh = plugin.getClusterMeshEngine();
        if (mesh == null) {
            sender.sendMessage("§cCluster Mesh Engine chưa được khởi tạo!");
            return true;
        }

        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sender.sendMessage("§6§l=== CLUSTER P2P MESH NETWORK (RAFT + HMAC-SHA256) ===");
            sender.sendMessage("§eNode ID: §f" + mesh.getNodeId());
            sender.sendMessage("§eVai trò (Raft Role): §a" + mesh.getCurrentRole() + " §7(Term: §f" + mesh.getCurrentTerm() + "§7)");
            sender.sendMessage("§eLeader Node: §b" + mesh.getLeaderId());
            sender.sendMessage("§eSố máy chủ liên kết (Peers): §f" + mesh.getActivePeers().size());
            sender.sendMessage("§eDistributed KV Keys: §d" + mesh.getAllClusterState().size() + " §8| §eActive Locks: §6" + mesh.getActiveLocks().size());
            sender.sendMessage("§eTrạng thái Mesh: " + (mesh.isRunning() ? "§aĐang hoạt động" : "§cĐã tắt"));
            sender.sendMessage("§7Lệnh: §f/jmesh [status|nodes|broadcast|kv|lock|sync|restore]");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "nodes" -> {
                sender.sendMessage("§6§l=== DANH SÁCH MÁY CHỦ TRONG CỤM (MESH NODES) ===");
                var peers = mesh.getActivePeers();
                if (peers.isEmpty()) {
                    sender.sendMessage("§7Chưa phát hiện node nào khác trong mạng LAN/WAN.");
                    return true;
                }
                for (var n : peers) {
                    long ago = (System.currentTimeMillis() - n.lastSeen()) / 1000;
                    String roleBadge = n.isLeader() ? "§6[LEADER] " : "§7[NODE] ";
                    sender.sendMessage(roleBadge + "§e" + n.id() + " §7(" + n.host() + ":" + n.port() + ") - "
                            + "§a" + n.onlinePlayers() + " players§7, §b" + n.tps() + " TPS§7, ping: §f" + ago + "s ago");
                }
            }
            case "broadcast" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cCú pháp: /jmesh broadcast <tin nhắn>");
                    return true;
                }
                String msg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                mesh.broadcastClusterMessage(msg);
                sender.sendMessage("§a✔ Đã phát sóng thông điệp tới toàn bộ máy chủ trong cụm!");
            }
            case "kv" -> handleKv(sender, mesh, args);
            case "lock" -> handleLock(sender, mesh, args);
            case "sync" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cCú pháp: /jmesh sync <tên người chơi>");
                    return true;
                }
                var target = org.bukkit.Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§cKhông tìm thấy người chơi: " + args[1]);
                    return true;
                }
                if (plugin.getClusterInventorySync() != null) {
                    plugin.getClusterInventorySync().syncPlayerToCluster(target, true, sender);
                } else {
                    sender.sendMessage("§cPhân hệ Inventory Sync chưa được kích hoạt!");
                }
            }
            case "restore" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cCú pháp: /jmesh restore <tên người chơi>");
                    return true;
                }
                var target = org.bukkit.Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§cKhông tìm thấy người chơi: " + args[1]);
                    return true;
                }
                if (plugin.getClusterInventorySync() != null) {
                    plugin.getClusterInventorySync().restorePlayerFromCluster(target, true, sender);
                } else {
                    sender.sendMessage("§cPhân hệ Inventory Sync chưa được kích hoạt!");
                }
            }
            default -> sender.sendMessage("§cCú pháp: /jmesh [status|nodes|broadcast|kv|lock|sync|restore]");
        }
        return true;
    }

    private void handleKv(CommandSender sender, ClusterMeshEngine mesh, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cCú pháp: /jmesh kv <list|get|set|del> [key] [value...]");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                var all = mesh.getAllClusterState();
                sender.sendMessage("§6=== DISTRIBUTED CLUSTER KV STORE (" + all.size() + " keys) ===");
                if (all.isEmpty()) {
                    sender.sendMessage("§7(Trống)");
                } else {
                    all.forEach((k, v) -> sender.sendMessage(" §e• " + k + " §7= §f" + v.value() + " §8(v" + v.version() + ")"));
                }
            }
            case "get" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cCú pháp: /jmesh kv get <key>");
                    return;
                }
                String val = mesh.getClusterState(args[2]);
                sender.sendMessage(val != null ? "§a[KV] §e" + args[2] + " §7= §f" + val : "§7[KV] Không tồn tại khóa: §e" + args[2]);
            }
            case "set" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cCú pháp: /jmesh kv set <key> <value...>");
                    return;
                }
                String val = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                mesh.putClusterState(args[2], val);
                sender.sendMessage("§a✔ Đã đồng bộ KV trên toàn cụm: §e" + args[2] + " §7= §f" + val);
            }
            case "del", "delete", "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cCú pháp: /jmesh kv del <key>");
                    return;
                }
                mesh.removeClusterState(args[2]);
                sender.sendMessage("§a✔ Đã xóa khóa KV trên toàn cụm: §e" + args[2]);
            }
            default -> sender.sendMessage("§cCú pháp: /jmesh kv <list|get|set|del>");
        }
    }

    private void handleLock(CommandSender sender, ClusterMeshEngine mesh, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cCú pháp: /jmesh lock <list|acquire|release> [lock_key] [ttl_ms]");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                var locks = mesh.getActiveLocks();
                sender.sendMessage("§6=== DISTRIBUTED CLUSTER LOCKS (" + locks.size() + ") ===");
                if (locks.isEmpty()) {
                    sender.sendMessage("§7(Không có khóa phân tán nào đang giữ)");
                } else {
                    long now = System.currentTimeMillis();
                    locks.forEach((k, l) -> sender.sendMessage(" §6• " + k + " §7giữ bởi §e" + l.owner()
                            + " §8(fencing=#" + l.fencingToken() + ", còn " + Math.max(0, l.expiresAt() - now) + "ms)"));
                }
            }
            case "acquire", "lock" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cCú pháp: /jmesh lock acquire <lock_key> [ttl_ms]");
                    return;
                }
                long ttl = 10_000L;
                if (args.length >= 4) {
                    try { ttl = Long.parseLong(args[3]); } catch (NumberFormatException ignored) {}
                }
                boolean ok = mesh.tryAcquireLock(args[2], mesh.getNodeId(), ttl);
                if (ok) {
                    sender.sendMessage("§a✔ Đã chiếm quyền Distributed Lock §e'" + args[2] + "' §ftrong §b" + ttl + "ms§f!");
                } else {
                    sender.sendMessage("§c✖ Khóa §e'" + args[2] + "' §cđang bị một máy chủ khác nắm giữ!");
                }
            }
            case "release", "unlock" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cCú pháp: /jmesh lock release <lock_key>");
                    return;
                }
                boolean ok = mesh.releaseLock(args[2], mesh.getNodeId());
                sender.sendMessage(ok
                        ? "§a✔ Đã giải phóng Distributed Lock §e'" + args[2] + "'§a."
                        : "§c✖ Không thể giải phóng (không tồn tại hoặc thuộc node khác).");
            }
            default -> sender.sendMessage("§cCú pháp: /jmesh lock <list|acquire|release>");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return List.of("status", "nodes", "broadcast", "kv", "lock", "sync", "restore").stream().filter(s -> s.startsWith(p)).toList();
        }
        if (args.length == 2) {
            String p = args[1].toLowerCase(Locale.ROOT);
            if (args[0].equalsIgnoreCase("kv")) {
                return List.of("list", "get", "set", "del").stream().filter(s -> s.startsWith(p)).toList();
            }
            if (args[0].equalsIgnoreCase("lock")) {
                return List.of("list", "acquire", "release").stream().filter(s -> s.startsWith(p)).toList();
            }
            if (args[0].equalsIgnoreCase("sync") || args[0].equalsIgnoreCase("restore")) {
                return org.bukkit.Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList();
            }
        }
        return Collections.emptyList();
    }
}
