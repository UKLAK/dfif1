package com.javastudio.commands;

import com.javastudio.pipeline.FastBlockEngine;
import com.javastudio.pipeline.FastBlockEngine.ArenaSnapshot;
import com.javastudio.pipeline.FastBlockEngine.CuboidSelection;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Lệnh điều khiển FastBlockEngine và quản lý Snapshot đấu trường trong RAM (/jchunk, /jc, /jarena).
 */
public final class JChunkCommand implements CommandExecutor, TabCompleter {

    private final FastBlockEngine engine;

    public JChunkCommand(FastBlockEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("javastudio.admin")) {
            sender.sendMessage("§cBạn không có quyền thực thi lệnh này.");
            return true;
        }

        if (com.javastudio.JavaStudioPlugin.getInstance() != null && !com.javastudio.JavaStudioPlugin.getInstance().getSettingsManager().isFastBlockEnabled()) {
            sender.sendMessage("§cTính năng FastBlock & RAM Arena (/jchunk, /jc, /jarena) đang bị tắt trong cài đặt!");
            sender.sendMessage("§7Bật lại bằng lệnh: §e/javastudio toggle fast-block");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "pos1" -> handlePos(sender, true);
            case "pos2" -> handlePos(sender, false);
            case "snapshot", "save" -> handleSnapshot(sender, args);
            case "restore", "load" -> handleRestore(sender, args);
            case "fill", "set" -> handleFill(sender, args);
            case "replace" -> handleReplace(sender, args);
            case "list" -> handleList(sender);
            case "delete", "remove" -> handleDelete(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handlePos(CommandSender sender, boolean isFirst) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cChỉ người chơi trong game mới có thể đặt điểm vùng chọn.");
            return;
        }
        Location loc = player.getLocation().getBlock().getLocation();
        if (isFirst) {
            engine.setPos1(player.getUniqueId(), loc);
            player.sendMessage(String.format("§a[JChunk] §fĐã đặt §ePos1 §ftại §b(%d, %d, %d)§f.",
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        } else {
            engine.setPos2(player.getUniqueId(), loc);
            player.sendMessage(String.format("§a[JChunk] §fĐã đặt §ePos2 §ftại §b(%d, %d, %d)§f.",
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        }

        CuboidSelection sel = engine.getSelection(player.getUniqueId());
        if (sel != null && sel.isComplete()) {
            player.sendMessage("§7↳ Tổng thể tích vùng chọn: §e" + String.format("%,d", sel.getVolume()) + " khối§7.");
        }
    }

    private void handleSnapshot(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cLệnh tạo snapshot cần thực thi bởi người chơi trong game.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cCú pháp: /jchunk snapshot <tên_snapshot> [bán_kính_chunk]");
            return;
        }

        String id = args[1];
        World world = player.getWorld();
        Location p1, p2;

        // Nếu nhập bán kính Chunk (vd: /jchunk snapshot arena1 2), tự động lấy vùng quanh người chơi
        if (args.length >= 3) {
            try {
                int radius = Math.max(0, Math.min(10, Integer.parseInt(args[2])));
                int centerCx = player.getLocation().getBlockX() >> 4;
                int centerCz = player.getLocation().getBlockZ() >> 4;
                p1 = new Location(world, (centerCx - radius) << 4, world.getMinHeight(), (centerCz - radius) << 4);
                p2 = new Location(world, ((centerCx + radius) << 4) + 15, world.getMaxHeight() - 1, ((centerCz + radius) << 4) + 15);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cBán kính chunk không hợp lệ.");
                return;
            }
        } else {
            CuboidSelection sel = engine.getSelection(player.getUniqueId());
            if (sel == null || !sel.isComplete()) {
                sender.sendMessage("§cBạn chưa chọn đủ Pos1 & Pos2 (hoặc nhập thêm [bán_kính_chunk]).");
                return;
            }
            p1 = sel.pos1;
            p2 = sel.pos2;
            world = p1.getWorld();
        }

        sender.sendMessage("§e[JChunk] §fĐang nén bảng màu (Palette) vùng chọn vào RAM...");
        engine.createSnapshot(id, world, p1, p2).whenComplete((snap, err) -> {
            if (err != null) {
                sender.sendMessage("§c[JChunk] Lỗi khi chụp snapshot: " + err.getMessage());
                return;
            }
            sender.sendMessage(String.format(
                    Locale.US,
                    "§a[JChunk] §fĐã lưu Snapshot §e'%s' §fthành công!\n"
                            + " §7▪ Số khối: §f%,d khối §8(§b%d Chunks§8)\n"
                            + " §7▪ Dung lượng RAM: §a%.2f KB §8| §7Thời gian nén: §e%.2f ms",
                    snap.id, snap.totalBlocks, snap.chunks.size(), snap.getMemoryUsageKb(), snap.captureTimeMs
            ));
        });
    }

    private void handleRestore(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cCú pháp: /jchunk restore <tên_snapshot>");
            return;
        }
        String id = args[1];
        engine.restoreSnapshot(id).whenComplete((res, err) -> {
            if (err != null) {
                sender.sendMessage("§c[JChunk] Không thể khôi phục: " + err.getMessage());
                return;
            }
            sender.sendMessage(String.format(
                    Locale.US,
                    "§a[JChunk] §fĐã khôi phục đấu trường §e'%s' §ftrong §b%.2f ms§f! §7(Thay đổi: §f%,d khối §7trên §f%d Chunks§7)",
                    id, res.elapsedMs(), res.modifiedBlocks(), res.affectedChunks()
            ));
        });
    }

    private void handleFill(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cChỉ người chơi mới có thể dùng lệnh này.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cCú pháp: /jchunk fill <Material>");
            return;
        }
        CuboidSelection sel = engine.getSelection(player.getUniqueId());
        if (sel == null || !sel.isComplete()) {
            sender.sendMessage("§cVui lòng đặt §e/jchunk pos1 §cvà §e/jchunk pos2 §ctrước.");
            return;
        }
        Material mat = Material.matchMaterial(args[1]);
        if (mat == null || !mat.isBlock()) {
            sender.sendMessage("§cKhối không hợp lệ: §e" + args[1]);
            return;
        }

        engine.fillCuboid(sel.pos1.getWorld(), sel.pos1, sel.pos2, mat).whenComplete((res, err) -> {
            if (err != null) {
                sender.sendMessage("§cLỗi khi lấp đầy khối: " + err.getMessage());
                return;
            }
            sender.sendMessage(String.format(
                    Locale.US,
                    "§a[JChunk] §fĐã đặt §e%,d khối %s §ftrên §b%d Chunks §fchỉ trong §a%.2f ms§f!",
                    res.modifiedBlocks(), mat.name(), res.affectedChunks(), res.elapsedMs()
            ));
        });
    }

    private void handleReplace(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cChỉ người chơi mới có thể dùng lệnh này.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§cCú pháp: /jchunk replace <Khối_Cũ> <Khối_Mới>");
            return;
        }
        CuboidSelection sel = engine.getSelection(player.getUniqueId());
        if (sel == null || !sel.isComplete()) {
            sender.sendMessage("§cVui lòng đặt §e/jchunk pos1 §cvà §e/jchunk pos2 §ctrước.");
            return;
        }
        Material fromMat = Material.matchMaterial(args[1]);
        Material toMat = Material.matchMaterial(args[2]);
        if (fromMat == null || !fromMat.isBlock() || toMat == null || !toMat.isBlock()) {
            sender.sendMessage("§cMaterial đầu vào không hợp lệ.");
            return;
        }

        engine.replaceCuboid(sel.pos1.getWorld(), sel.pos1, sel.pos2, fromMat, toMat.createBlockData())
                .whenComplete((res, err) -> {
                    if (err != null) {
                        sender.sendMessage("§cLỗi khi thay thế khối: " + err.getMessage());
                        return;
                    }
                    sender.sendMessage(String.format(
                            Locale.US,
                            "§a[JChunk] §fĐã thay thế §e%,d khối §f(%s → %s) trong §a%.2f ms§f!",
                            res.modifiedBlocks(), fromMat.name(), toMat.name(), res.elapsedMs()
                    ));
                });
    }

    private void handleList(CommandSender sender) {
        Collection<ArenaSnapshot> all = engine.getAllSnapshots();
        if (all.isEmpty()) {
            sender.sendMessage("§7[JChunk] Hiện chưa có Snapshot đấu trường nào được lưu trong RAM.");
            return;
        }
        sender.sendMessage("§8§m------------§r §b§lDANH SÁCH SNAPSHOT ĐẤU TRƯỜNG (RAM) §8§m------------");
        double totalKb = 0;
        for (ArenaSnapshot s : all) {
            double kb = s.getMemoryUsageKb();
            totalKb += kb;
            sender.sendMessage(String.format(
                    Locale.US,
                    " §e• %s §7[World: §f%s§7] §8- §b%,d khối §7(%d Chunks) §8- §a%.2f KB",
                    s.id, s.worldName, s.totalBlocks, s.chunks.size(), kb
            ));
        }
        sender.sendMessage(String.format(Locale.US, "§7Tổng dung lượng RAM tiêu thụ: §a%.2f KB §7(%d snapshots)", totalKb, all.size()));
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cCú pháp: /jchunk delete <tên_snapshot>");
            return;
        }
        if (engine.deleteSnapshot(args[1])) {
            sender.sendMessage("§a[JChunk] §fĐã xóa và giải phóng RAM cho snapshot §e" + args[1] + "§f.");
        } else {
            sender.sendMessage("§cKhông tìm thấy snapshot: §e" + args[1]);
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§8§m----------------§r §b§lJAVASTUDIO FAST-BLOCK & CHUNK §8§m----------------");
        sender.sendMessage(" §b/jchunk pos1 §8| §bpos2 §7- Đặt 2 góc tọa độ vùng chọn 3D");
        sender.sendMessage(" §b/jchunk fill <Material> §7- Lấp đầy vùng chọn siêu tốc không tụt TPS");
        sender.sendMessage(" §b/jchunk replace <Cũ> <Mới> §7- Thay thế khối trong vùng chọn tức thì");
        sender.sendMessage(" §b/jchunk snapshot <tên> [bán_kính_chunk] §7- Nén trạng thái bản đồ vào RAM");
        sender.sendMessage(" §b/jchunk restore <tên> §7- Khôi phục bản đồ đấu trường trong vài mili-giây");
        sender.sendMessage(" §b/jchunk list §8| §bdelete <tên> §7- Xem dung lượng RAM và quản lý snapshots");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("pos1", "pos2", "snapshot", "restore", "fill", "replace", "list", "delete").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("restore") || sub.equals("delete")) {
                return engine.getAllSnapshots().stream()
                        .map(s -> s.id)
                        .filter(id -> id.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (sub.equals("fill") || sub.equals("replace")) {
                String prefix = args[1].toUpperCase();
                return Arrays.stream(Material.values())
                        .filter(Material::isBlock)
                        .map(Enum::name)
                        .filter(name -> name.startsWith(prefix))
                        .limit(25)
                        .toList();
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("replace")) {
            String prefix = args[2].toUpperCase();
            return Arrays.stream(Material.values())
                    .filter(Material::isBlock)
                    .map(Enum::name)
                    .filter(name -> name.startsWith(prefix))
                    .limit(25)
                    .toList();
        }
        return Collections.emptyList();
    }
}
