package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.anticheat.AntiCheatManager;
import com.javastudio.anticheat.CheckSettings;
import com.javastudio.anticheat.CheckType;
import com.javastudio.anticheat.PlayerData;
import com.javastudio.anticheat.WebDashboardService;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public final class JAntiCheatCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    public JAntiCheatCommand(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền quản lý Anti-Cheat (cần javastudio.admin)!");
            return true;
        }

        if (!plugin.getSettingsManager().isAntiCheatEnabled()) {
            MessageUtil.sendError(sender, "Phân hệ Anti-Cheat (/jac) đang bị TẮT trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle anticheat &7hoặc qua &e/javastudio settings");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status" -> sendStatus(sender, label);
            case "alerts" -> handleAlerts(sender, args);
            case "info", "player" -> handleInfo(sender, args, label);
            case "reset", "clear" -> handleReset(sender, args, label);
            case "checks", "list" -> handleChecks(sender, args);
            case "toggle" -> handleToggle(sender, args, label);
            case "set" -> handleSet(sender, args, label);
            case "logs", "log" -> handleLogs(sender, args);
            case "exempt" -> handleExempt(sender, args, label);
            case "debug" -> handleDebug(sender, args);
            case "freeze" -> handleFreeze(sender, args, label);
            case "unfreeze" -> handleUnfreeze(sender, args, label);
            case "top" -> handleTop(sender, args);
            case "inspect" -> handleInspect(sender, args, label);
            case "replay" -> handleReplay(sender, args, label);
            case "reports", "report" -> handleReports(sender, args, label);
            case "record" -> handleRecord(sender, args, label);
            case "test" -> handleTest(sender, args, label);
            case "selftest", "verify" -> manager.runSelfTest(sender);
            case "exemptops" -> handleExemptOps(sender, args, label);
            case "bot" -> handleBot(sender, args, label);
            case "web", "dashboard" -> handleWeb(sender, args, label);
            case "wave", "banwave" -> handleWave(sender, args, label);
            case "reload" -> {
                manager.reload();
                MessageUtil.sendSuccess(sender, "Đã nạp lại toàn bộ cấu hình Anti-Cheat từ config.yml!");
            }
            default -> {
                MessageUtil.sendError(sender, "Lệnh không hợp lệ. Gõ /" + label + " help để xem hướng dẫn.");
            }
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§b╔════════════════════════════════════════════════════╗");
        sender.sendMessage("§b║   §e§lJAVA-STUDIO ANTI-CHEAT §7(Vulcan/Grim/Matrix-style) §b║");
        sender.sendMessage("§b╚════════════════════════════════════════════════════╝");
        sender.sendMessage(" §8» §e/" + label + " status §7- Tổng quan trạng thái engine và thống kê");
        sender.sendMessage(" §8» §e/" + label + " alerts [on|off] §7- Bật/tắt nhận cảnh báo vi phạm trực tiếp");
        sender.sendMessage(" §8» §e/" + label + " info <player> §7- Xem chi tiết điểm phạt (VL) của người chơi");
        sender.sendMessage(" §8» §e/" + label + " reset <player|all> §7- Xóa toàn bộ điểm phạt vi phạm");
        sender.sendMessage(" §8» §e/" + label + " checks [movement|combat|world] §7- Danh sách 42 check bảo vệ");
        sender.sendMessage(" §8» §e/" + label + " toggle <check> §7- Bật/tắt nhanh 1 check và lưu config");
        sender.sendMessage(" §8» §e/" + label + " set <check> <thuộc_tính> <giá_trị> §7- Chỉnh tham số check live");
        sender.sendMessage(" §8» §e/" + label + " logs [số_dòng] §7- Xem nhật ký vi phạm gần nhất");
        sender.sendMessage(" §8» §e/" + label + " exempt <player> [giây] §7- Miễn trừ tạm thời để kiểm thử");
        sender.sendMessage(" §8» §e/" + label + " debug [player] §7- Bật/tắt chế độ debug log thời gian thực");
        sender.sendMessage(" §8» §e/" + label + " freeze <player> §7- Đóng băng kẻ tình nghi để thẩm vấn");
        sender.sendMessage(" §8» §e/" + label + " unfreeze <player> §7- Mở khóa đóng băng cho người chơi");
        sender.sendMessage(" §8» §e/" + label + " top §7- Xem bảng xếp hạng TOP 10 người chơi vi phạm");
        sender.sendMessage(" §8» §e/" + label + " inspect <player> §7- Soi nhanh Ping, CPS, góc quay & vi phạm");
        sender.sendMessage(" §8» §e/" + label + " replay [play|pause|resume|speed|stop|list|info|delete] §7- Điều khiển chiếu lại hiện trường 3D");
        sender.sendMessage(" §8» §e/" + label + " reports §7- Quản lý danh sách các đơn tố cáo từ người chơi");
        sender.sendMessage(" §8» §e/" + label + " report <claim|resolve|dismiss> <id> §7- Xử lý đơn tố cáo");
        sender.sendMessage(" §8» §e/" + label + " record <player> §7- Lưu thủ công clip 20s của nghi phạm");
        sender.sendMessage(" §8» §e/" + label + " test [player] §7- Bật/tắt chế độ test hack cho Admin/OP (bỏ qua miễn trừ)");
        sender.sendMessage(" §8» §e/" + label + " selftest §7- Chạy tự kiểm định toán học, Neural, Backtrack & hiệu năng");
        sender.sendMessage(" §8» §e/" + label + " exemptops [on|off] §7- Bật/tắt tự động miễn trừ toàn bộ OP");
        sender.sendMessage(" §8» §e/" + label + " bot <player> [giây] §7- Triệu hồi AuraBot tàng hình bẫy KillAura nghi phạm");
        sender.sendMessage(" §8» §e/" + label + " web [start|stop|info] §7- Quản lý Web Dashboard & Live 3D Radar");
        sender.sendMessage(" §8» §e/" + label + " wave [list|execute|clear] §7- Quản lý hàng đợi đợt thanh trừng Ban Wave");
        sender.sendMessage(" §8» §e/" + label + " reload §7- Nạp lại cài đặt từ config.yml");
    }

    private void sendStatus(CommandSender sender, String label) {
        sender.sendMessage("§b§l=== TỔNG QUAN HỆ THỐNG ANTI-CHEAT ===");
        sender.sendMessage(" §8» §7Trạng thái Module: " + (manager.isActive() ? "§a§lĐANG HOẠT ĐỘNG" : "§c§lĐÃ TẮT"));
        sender.sendMessage(" §8» §7TPS Server hiện tại: " + formatTps(manager.getTps()) + " §8(Lag gate: " + (manager.isLagging() ? "§cĐang lag" : "§aỔn định") + "§8)");
        sender.sendMessage(" §8» §7Số người chơi đang theo dõi: §e" + manager.getPlayers().size());
        sender.sendMessage(" §8» §7Tổng vi phạm (Flags): §c" + String.format("%,d", manager.getTotalFlags()));
        sender.sendMessage(" §8» §7Tổng lượt xử phạt (Punishments): §4§l" + String.format("%,d", manager.getTotalPunishments()));
        sender.sendMessage(" §8» §7Netty Packet Shield đã chặn: §b" + String.format("%,d", manager.getNettyDroppedPackets()) + " gói tin rác/crash");
        sender.sendMessage(" §8» §7Quyền Bypass: §6" + manager.getBypassPermission());
        sender.sendMessage(" §8» §7Quyền nhận Alerts: §6" + manager.getAlertPermission());
        sender.sendMessage(" §8» §7Gõ §b/" + label + " checks §7để xem trạng thái chi tiết của từng check.");
    }

    private String formatTps(double tps) {
        if (tps >= 19.0) return "§a" + String.format("%.2f", tps);
        if (tps >= 16.0) return "§e" + String.format("%.2f", tps);
        return "§c" + String.format("%.2f", tps);
    }

    private void handleAlerts(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            MessageUtil.sendError(sender, "Lệnh này chỉ dùng được bởi người chơi trong game!");
            return;
        }
        PlayerData d = manager.data(p);
        if (args.length >= 2) {
            String val = args[1].toLowerCase();
            d.alerts = val.equals("on") || val.equals("true") || val.equals("1");
        } else {
            d.alerts = !d.alerts;
        }
        if (d.alerts) {
            MessageUtil.sendSuccess(sender, "§aĐã BẬT nhận cảnh báo Anti-Cheat.");
        } else {
            MessageUtil.sendWarning(sender, "§cĐã TẮT nhận cảnh báo Anti-Cheat.");
        }
    }

    private void handleInfo(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + label + " info <tên_người_chơi>");
            return;
        }
        String name = args[1];
        Player target = Bukkit.getPlayerExact(name);
        PlayerData d = target != null ? manager.data(target) : manager.peekByName(name);
        if (d == null) {
            MessageUtil.sendError(sender, "Không tìm thấy dữ liệu người chơi '" + name + "'!");
            return;
        }

        sender.sendMessage("§e§l=== HỒ SƠ VI PHẠM: §f" + d.name + " §e§l===");
        if (target != null) {
            sender.sendMessage(" §8» §7Ping mạng: §b" + manager.safePing(target) + "ms §8| §7Bypass: " + (manager.isBypassed(target, d) ? "§cCó" : "§aKhông"));
        }
        sender.sendMessage(" §8» §7Tổng điểm vi phạm (Total VL): §c" + String.format("%.1f", d.getTotalVl()) + " §8(Tổng cờ: §e" + d.totalFlags + "§8)");

        boolean hasAny = false;
        for (CheckType t : CheckType.values()) {
            double vl = d.vl[t.ordinal()];
            if (vl > 0) {
                hasAny = true;
                CheckSettings s = manager.settings(t);
                sender.sendMessage("   §8• §7" + t.getDisplayName() + " §8[" + t.getCategory().getDisplay() + "]: §c" +
                        String.format("%.1f", vl) + "§7/§e" + String.format("%.0f", s.getMaxVl()) + " VL");
            }
        }
        if (!hasAny) {
            sender.sendMessage("   §aNgười chơi hoàn toàn sạch (0 điểm vi phạm).");
        }
    }

    private void handleReset(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + label + " reset <tên_người_chơi|all>");
            return;
        }
        String arg = args[1].toLowerCase();
        if (arg.equals("all")) {
            manager.resetAll();
            MessageUtil.sendSuccess(sender, "Đã xóa toàn bộ điểm vi phạm của tất cả người chơi!");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        PlayerData d = target != null ? manager.data(target) : manager.peekByName(args[1]);
        if (d == null) {
            MessageUtil.sendError(sender, "Không tìm thấy người chơi '" + args[1] + "'!");
            return;
        }
        d.resetViolations();
        MessageUtil.sendSuccess(sender, "Đã xóa toàn bộ điểm vi phạm của " + d.name + "!");
    }

    private void handleChecks(CommandSender sender, String[] args) {
        CheckType.Category filter = null;
        if (args.length >= 2) {
            String cat = args[1].toUpperCase();
            try {
                filter = CheckType.Category.valueOf(cat);
            } catch (IllegalArgumentException ignored) {}
        }

        sender.sendMessage("§b§l=== DANH SÁCH CHECKS BẢO VỆ ===");
        for (CheckType t : CheckType.values()) {
            if (filter != null && t.getCategory() != filter) continue;
            CheckSettings s = manager.settings(t);
            String st = s.isEnabled() ? "§aBẬT" : "§cTẮT";
            String sb = s.isSetback() ? "§eSetback" : (s.isCancel() ? "§6Cancel" : "§7Alert");
            sender.sendMessage(" §8» §f" + t.getKey() + " §8(§7" + t.getDisplayName() + "§8): " + st + " §8| §7MaxVL: §c" +
                    String.format("%.0f", s.getMaxVl()) + " §8| §7Phản ứng: " + sb);
        }
    }

    private void handleToggle(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + label + " toggle <tên_check>");
            return;
        }
        CheckType t = CheckType.fromKey(args[1]);
        if (t == null) {
            MessageUtil.sendError(sender, "Check không hợp lệ! Gõ /" + label + " checks để xem danh sách.");
            return;
        }

        CheckSettings current = manager.settings(t);
        boolean newState = !current.isEnabled();
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("anticheat.checks." + t.getKey() + ".enabled", newState);
        plugin.saveConfig();
        manager.loadConfig();

        if (newState) {
            MessageUtil.sendSuccess(sender, "Đã BẬT check " + t.getDisplayName() + "!");
        } else {
            MessageUtil.sendWarning(sender, "Đã TẮT check " + t.getDisplayName() + "!");
        }
    }

    private void handleSet(CommandSender sender, String[] args, String label) {
        if (args.length < 4) {
            MessageUtil.sendError(sender, "Cách dùng: /" + label + " set <check> <thuộc_tính> <giá_trị>");
            sender.sendMessage("§7Ví dụ: §e/" + label + " set reach max-reach 3.2");
            sender.sendMessage("§7Ví dụ: §e/" + label + " set speed max-vl 12");
            sender.sendMessage("§7Ví dụ: §e/" + label + " set fly setback true");
            return;
        }

        CheckType t = CheckType.fromKey(args[1]);
        if (t == null) {
            MessageUtil.sendError(sender, "Không tìm thấy check '" + args[1] + "'!");
            return;
        }

        String prop = args[2].toLowerCase();
        String rawVal = args[3];
        Object val;
        if (rawVal.equalsIgnoreCase("true") || rawVal.equalsIgnoreCase("false")) {
            val = Boolean.parseBoolean(rawVal);
        } else {
            try {
                if (rawVal.contains(".")) {
                    val = Double.parseDouble(rawVal);
                } else {
                    val = Integer.parseInt(rawVal);
                }
            } catch (NumberFormatException e) {
                val = rawVal;
            }
        }

        FileConfiguration cfg = plugin.getConfig();
        cfg.set("anticheat.checks." + t.getKey() + "." + prop, val);
        plugin.saveConfig();
        manager.loadConfig();

        MessageUtil.sendSuccess(sender, "Đã cập nhật " + t.getKey() + "." + prop + " = " + val + " và lưu vào config.yml!");
    }

    private void handleLogs(CommandSender sender, String[] args) {
        int lines = 10;
        if (args.length >= 2) {
            try {
                lines = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {}
        }

        List<String> logs = manager.getRecentLog(lines);
        sender.sendMessage("§6§l=== NHẬT KÝ VI PHẠM GẦN NHẤT (" + logs.size() + ") ===");
        if (logs.isEmpty()) {
            sender.sendMessage("§7Không có ghi nhận vi phạm nào trong bộ đệm.");
            return;
        }
        for (String l : logs) {
            sender.sendMessage(" §8» §f" + l);
        }
    }

    private void handleExempt(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + label + " exempt <tên_người_chơi> [số_giây]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            MessageUtil.sendError(sender, "Người chơi không online!");
            return;
        }
        int seconds = 60;
        if (args.length >= 3) {
            try {
                seconds = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException ignored) {}
        }
        PlayerData d = manager.data(target);
        d.exemptUntil = System.currentTimeMillis() + (seconds * 1000L);
        MessageUtil.sendSuccess(sender, "Đã miễn trừ Anti-Cheat cho " + target.getName() + " trong " + seconds + " giây!");
    }

    private void handleDebug(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            MessageUtil.sendError(sender, "Console cần chỉ định tên người chơi: /jac debug <player>");
            return;
        }
        if (target == null) {
            MessageUtil.sendError(sender, "Người chơi không online!");
            return;
        }
        PlayerData d = manager.data(target);
        d.debug = !d.debug;
        if (d.debug) {
            MessageUtil.sendSuccess(sender, "§aĐã BẬT debug cho " + target.getName() + ".");
        } else {
            MessageUtil.sendWarning(sender, "§cĐã TẮT debug cho " + target.getName() + ".");
        }
    }

    private void handleFreeze(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + label + " freeze <tên_người_chơi>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            MessageUtil.sendError(sender, "Không tìm thấy người chơi '" + args[1] + "'!");
            return;
        }
        if (manager.freezePlayer(sender, target)) {
            MessageUtil.sendSuccess(sender, "Đã đóng băng (Freeze) người chơi " + target.getName() + " để kiểm tra.");
        } else {
            MessageUtil.sendWarning(sender, "Người chơi " + target.getName() + " hiện đã bị đóng băng trước đó!");
        }
    }

    private void handleUnfreeze(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + label + " unfreeze <tên_người_chơi>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            MessageUtil.sendError(sender, "Không tìm thấy người chơi '" + args[1] + "'!");
            return;
        }
        if (manager.unfreezePlayer(sender, target)) {
            MessageUtil.sendSuccess(sender, "Đã mở khóa đóng băng cho người chơi " + target.getName() + ".");
        } else {
            MessageUtil.sendWarning(sender, "Người chơi " + target.getName() + " không ở trong trạng thái Freeze!");
        }
    }

    private void handleTop(CommandSender sender, String[] args) {
        List<PlayerData> top = manager.getTopViolators(10);
        sender.sendMessage("§b§l=== TOP 10 NGƯỜI CHƠI CÓ VL CAO NHẤT ===");
        if (top.isEmpty()) {
            sender.sendMessage(" §7Hiện không có người chơi nào vi phạm.");
            return;
        }
        int rank = 1;
        for (PlayerData d : top) {
            double totalVl = d.getTotalVl();
            if (totalVl <= 0) continue;
            Player p = Bukkit.getPlayer(d.id);
            String status = p != null ? "§aOnline" : "§7Offline";
            sender.sendMessage(String.format(" §8#%d §e%s §7(%s§7) - §c%.1f VL §8(Tổng cờ: §f%d§8)",
                    rank++, d.name, status, totalVl, d.totalFlags));
        }
    }

    private void handleInspect(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + label + " inspect <tên_người_chơi>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            MessageUtil.sendError(sender, "Không tìm thấy người chơi '" + args[1] + "'!");
            return;
        }
        PlayerData d = manager.data(target);
        int ping = manager.safePing(target);
        int cps = d.getLiveCps();
        Location loc = target.getLocation();

        sender.sendMessage("§b╔════════════════════════════════════════════════════╗");
        sender.sendMessage("§b║   §e§lSOI DỮ LIỆU ANTI-CHEAT: §f" + target.getName());
        sender.sendMessage("§b╚════════════════════════════════════════════════════╝");
        sender.sendMessage(" §8» §7Ping mạng: " + (ping > 250 ? "§c" : "§a") + ping + "ms §8| §7CPS hiện tại: §f" + cps);
        sender.sendMessage(String.format(" §8» §7Tốc độ ngang: §e%.2f §8| §7Độ lệch góc Yaw/Pitch: §f%.1f°/%.1f°", d.lastH, d.yawDelta, d.pitchDelta));
        sender.sendMessage(String.format(" §8» §7Neural MLP: §d%.2f §8(§f%s§8) §8| §7Cluster: §b%s", d.neuralScore, d.neuralClassification, d.clusterGroup != null ? d.clusterGroup.name() : "LEGIT_CASUAL"));
        sender.sendMessage(String.format(" §8» §7Vị trí: §f%s, %.1f, %.1f, %.1f", loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ()));
        sender.sendMessage(" §8» §7Trạng thái Freeze: " + (d.isFrozen ? "§c§lBỊ KHÓA BỞI " + d.frozenBy : "§aBình thường"));
        sender.sendMessage(" §8» §7Tổng điểm vi phạm (Total VL): §c" + String.format("%.1f", d.getTotalVl()) + " §8| §7Cờ: §e" + d.totalFlags);
        sender.sendMessage(" §8» §7Gõ §e/" + label + " info " + target.getName() + " §7để xem bảng điểm chi tiết từng check.");
    }

    private void handleReplay(CommandSender sender, String[] args, String label) {
        var rm = manager.getReplayManager();
        if (rm == null) {
            MessageUtil.sendError(sender, "ReplayManager chưa sẵn sàng!");
            return;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            var incidents = rm.getIncidents();
            if (incidents.isEmpty()) {
                MessageUtil.sendInfo(sender, "Hiện chưa có clip vi phạm nào được lưu.");
                return;
            }
            MessageUtil.send(sender, "&6=== DANH SÁCH CLIP VI PHẠM ANTI-CHEAT (" + incidents.size() + ") ===");
            for (var inc : incidents) {
                MessageUtil.sendRaw(sender, "  &b• &e[" + inc.id + "] &f" + inc.playerName + " &7- &c" + inc.checkName + " &8(VL: " + inc.vl + ") &7[" + inc.timestamp + "]");
            }
            MessageUtil.sendRaw(sender, "  &7Xem lại hiện trường: &e/" + label + " replay play <id> [tốc_độ]");
            MessageUtil.sendRaw(sender, "  &7Tạm dừng / Tiếp tục: &e/" + label + " replay pause &8| &e/" + label + " replay resume");
            return;
        }
        String action = args[1].toLowerCase();
        if (action.equals("play")) {
            if (!(sender instanceof Player staff)) {
                MessageUtil.sendError(sender, "Chỉ người chơi trong game mới có thể xem phát lại hiện trường!");
                return;
            }
            if (args.length < 3) {
                MessageUtil.sendError(sender, "Cách dùng: /" + label + " replay play <id_clip> [tốc_độ: 0.5|1.0|2.0]");
                return;
            }
            String clipId = args[2];
            double speed = 1.0;
            if (args.length >= 4) {
                try {
                    speed = Double.parseDouble(args[3]);
                } catch (Throwable ignored) {}
            }
            boolean ok = rm.playReplay(staff, clipId, speed);
            if (ok) {
                MessageUtil.sendSuccess(sender, "Đang chiếu lại clip &e" + clipId + " &a(Tốc độ: &e" + speed + "x&a) bằng Ghost Mannequin!");
                MessageUtil.sendInfo(sender, "Lệnh điều khiển: &e/" + label + " replay pause &8| &e/" + label + " replay resume &8| &e/" + label + " replay stop");
            } else {
                MessageUtil.sendError(sender, "Không tìm thấy clip vi phạm có mã '" + clipId + "'!");
            }
        } else if (action.equals("pause")) {
            if (rm.pausePlayback()) {
                MessageUtil.sendSuccess(sender, "Đã tạm dừng chiếu lại clip hiện trường.");
            } else {
                MessageUtil.sendWarning(sender, "Hiện không có clip nào đang phát hoặc clip đã bị tạm dừng.");
            }
        } else if (action.equals("resume")) {
            if (rm.resumePlayback()) {
                MessageUtil.sendSuccess(sender, "Đã tiếp tục chiếu lại clip hiện trường.");
            } else {
                MessageUtil.sendWarning(sender, "Hiện không có clip nào đang bị tạm dừng.");
            }
        } else if (action.equals("speed")) {
            if (args.length < 3) {
                MessageUtil.sendError(sender, "Cách dùng: /" + label + " replay speed <tốc_độ: 0.5|1.0|2.0|4.0>");
                return;
            }
            try {
                double spd = Double.parseDouble(args[2]);
                if (rm.setSpeed(spd)) {
                    MessageUtil.sendSuccess(sender, "Đã chuyển tốc độ phát lại thành: &e" + spd + "x");
                } else {
                    MessageUtil.sendWarning(sender, "Hiện không có clip nào đang được phát lại.");
                }
            } catch (Throwable t) {
                MessageUtil.sendError(sender, "Tốc độ không hợp lệ! Vui lòng nhập số (vd: 0.5, 1.0, 2.0)");
            }
        } else if (action.equals("stop")) {
            rm.stopPlayback();
            MessageUtil.sendSuccess(sender, "Đã dừng chiếu lại hiện trường!");
        } else if (action.equals("info")) {
            if (args.length < 3) {
                MessageUtil.sendError(sender, "Cách dùng: /" + label + " replay info <id_clip>");
                return;
            }
            var inc = rm.getIncident(args[2]);
            if (inc != null) {
                MessageUtil.send(sender, "&6=== THÔNG TIN CLIP REPLAY: &e" + inc.id + " &6===");
                MessageUtil.sendRaw(sender, "  &7Người chơi bị nghi vấn: &e" + inc.playerName);
                MessageUtil.sendRaw(sender, "  &7Check kích hoạt: &c" + inc.checkName);
                MessageUtil.sendRaw(sender, "  &7Điểm vi phạm (VL): &f" + inc.vl);
                MessageUtil.sendRaw(sender, "  &7Thế giới: &a" + inc.worldName);
                MessageUtil.sendRaw(sender, "  &7Thời gian ghi: &b" + inc.timestamp);
                MessageUtil.sendRaw(sender, "  &7Tổng số frames: &6" + inc.frames.size() + " ticks");
                MessageUtil.sendRaw(sender, "  &8» &7Xem lại: &e/" + label + " replay play " + inc.id);
            } else {
                MessageUtil.sendError(sender, "Không tìm thấy clip: " + args[2]);
            }
        } else if (action.equals("delete") || action.equals("del")) {
            if (args.length < 3) {
                MessageUtil.sendError(sender, "Cách dùng: /" + label + " replay delete <id_clip>");
                return;
            }
            boolean ok = rm.deleteIncident(args[2]);
            if (ok) MessageUtil.sendSuccess(sender, "Đã xóa clip: &e" + args[2]);
            else MessageUtil.sendError(sender, "Không tìm thấy clip: " + args[2]);
        }
    }

    private void handleReports(CommandSender sender, String[] args, String label) {
        var repMgr = manager.getReportManager();
        if (repMgr == null) {
            MessageUtil.sendError(sender, "ReportManager chưa sẵn sàng!");
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("claim")) {
            if (args.length < 3) {
                MessageUtil.sendError(sender, "Cách dùng: /" + label + " report claim <id_đơn>");
                return;
            }
            boolean ok = repMgr.claimReport(args[2], sender.getName());
            if (ok) MessageUtil.sendSuccess(sender, "Bạn đã tiếp nhận điều tra đơn tố cáo &e" + args[2] + "&a!");
            else MessageUtil.sendError(sender, "Không tìm thấy đơn tố cáo có mã '" + args[2] + "'!");
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("resolve")) {
            if (args.length < 3) {
                MessageUtil.sendError(sender, "Cách dùng: /" + label + " report resolve <id_đơn> [kết_luận]");
                return;
            }
            String verdict = args.length >= 4 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "Đã xử phạt vi phạm";
            boolean ok = repMgr.resolveReport(args[2], sender.getName(), verdict);
            if (ok) MessageUtil.sendSuccess(sender, "Đã đóng đơn tố cáo &e" + args[2] + " &avới kết luận: &f" + verdict);
            else MessageUtil.sendError(sender, "Không tìm thấy đơn tố cáo: " + args[2]);
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("dismiss")) {
            if (args.length < 3) {
                MessageUtil.sendError(sender, "Cách dùng: /" + label + " report dismiss <id_đơn>");
                return;
            }
            boolean ok = repMgr.dismissReport(args[2], sender.getName());
            if (ok) MessageUtil.sendSuccess(sender, "Đã bác bỏ đơn tố cáo &e" + args[2] + " &a(Không có vi phạm).");
            else MessageUtil.sendError(sender, "Không tìm thấy đơn tố cáo: " + args[2]);
            return;
        }

        var list = repMgr.getReportsHistory();
        if (list.isEmpty()) {
            MessageUtil.sendInfo(sender, "Hiện tại không có đơn tố cáo nào.");
            return;
        }
        MessageUtil.send(sender, "&6=== DANH SÁCH ĐƠN TỐ CÁO (" + list.size() + ") ===");
        for (var r : list) {
            String stBadge = switch (r.status) {
                case PENDING -> "§c§l[CHỜ XỬ LÝ]";
                case INVESTIGATING -> "§e§l[ĐANG ĐIỀU TRA: " + r.handledBy + "]";
                case RESOLVED -> "§a§l[ĐÃ GIẢI QUYẾT]";
                case DISMISSED -> "§8§l[BÁC BỎ]";
            };
            MessageUtil.sendRaw(sender, String.format("  §b• §e[%s] §f%s §7tố cáo §c§l%s §8(§f%s§8) %s §7[%s]",
                    r.id, r.reporterName, r.targetName, r.reason, stBadge, r.timestamp));
            if (!"none".equals(r.replayId)) {
                MessageUtil.sendRaw(sender, "    §8» §7Replay: §6/" + label + " replay play " + r.replayId + " §8| §e/" + label + " report claim " + r.id);
            }
        }
    }

    private void handleRecord(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + label + " record <tên_người_chơi>");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            MessageUtil.sendError(sender, "Không tìm thấy người chơi '" + args[1] + "'!");
            return;
        }
        var rm = manager.getReplayManager();
        if (rm == null) {
            MessageUtil.sendError(sender, "ReplayManager chưa sẵn sàng!");
            return;
        }
        String clipId = rm.captureIncident(target, "MANUAL_STAFF_RECORD", 0.0);
        if (clipId != null) {
            MessageUtil.sendSuccess(sender, "Đã lưu clip ghi hình 20s gần nhất của &e" + target.getName() + " &athành mã: &e" + clipId);
            MessageUtil.sendInfo(sender, "Gõ &e/" + label + " replay play " + clipId + " &7để xem lại!");
        } else {
            MessageUtil.sendWarning(sender, "Chưa đủ dữ liệu ghi hình cho người chơi này.");
        }
    }

    private void handleTest(CommandSender sender, String[] args, String label) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                MessageUtil.sendError(sender, "Không tìm thấy người chơi '" + args[1] + "'!");
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            MessageUtil.sendError(sender, "Console cần chỉ định tên người chơi: /" + label + " test <player>");
            return;
        }

        PlayerData d = manager.data(target);
        d.forceCheck = !d.forceCheck;
        if (d.forceCheck) {
            d.bypass = false;
            d.bypassCheckedAt = Long.MAX_VALUE;
            MessageUtil.sendSuccess(sender, "§aĐã BẬT chế độ Tự Kiểm Thử (Test Mode) cho §e" + target.getName() + "§a!");
            sender.sendMessage(" §8» §7Hệ thống Anti-Cheat sẽ §cQUÉT VÀ PHẠT TRỰC TIẾP §7bạn.");
            sender.sendMessage(" §8» §7Dù bạn có OP hay quyền bypass, bạn vẫn bị phát hiện và cản trở (Scaffold, Fly, Aura...) như thường.");
        } else {
            d.bypassCheckedAt = 0;
            MessageUtil.sendWarning(sender, "§cĐã TẮT chế độ Tự Kiểm Thử cho §e" + target.getName() + "§c.");
            sender.sendMessage(" §8» §7Quyền miễn trừ OP / Bypass đã được khôi phục.");
        }
    }

    private void handleExemptOps(CommandSender sender, String[] args, String label) {
        if (args.length >= 2) {
            String val = args[1].toLowerCase();
            boolean exempt = val.equals("on") || val.equals("true") || val.equals("yes") || val.equals("1");
            manager.setExemptOps(exempt);
            plugin.getConfig().set("anticheat.exempt-ops", exempt);
            plugin.saveConfig();
            MessageUtil.sendSuccess(sender, "Đã chỉnh anticheat.exempt-ops thành: " + (exempt ? "§aBẬT (Miễn trừ OP)" : "§cTẮT (Quét cả OP)"));
        } else {
            boolean current = manager.isExemptOps();
            boolean newVal = !current;
            manager.setExemptOps(newVal);
            plugin.getConfig().set("anticheat.exempt-ops", newVal);
            plugin.saveConfig();
            MessageUtil.sendSuccess(sender, "Đã chuyển đổi anticheat.exempt-ops thành: " + (newVal ? "§aBẬT (Miễn trừ OP)" : "§cTẮT (Quét cả OP)"));
        }
    }

    private void handleBot(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cú pháp: /" + label + " bot <player> [giây]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            MessageUtil.sendError(sender, "Không tìm thấy người chơi '" + args[1] + "'!");
            return;
        }
        int sec = 6;
        if (args.length >= 3) {
            try { sec = Math.max(2, Integer.parseInt(args[2])); } catch (Throwable ignored) {}
        }
        if (manager.getAuraBotManager() == null) {
            MessageUtil.sendError(sender, "AuraBotManager chưa được kích hoạt!");
            return;
        }
        boolean spawned = manager.getAuraBotManager().spawnAuraBot(target, sec * 20);
        if (spawned) {
            MessageUtil.sendSuccess(sender, "Đã triển khai §eAuraBot tàng hình §aở điểm mù sau lưng §f" + target.getName() + " §7(trong " + sec + "s)!");
            MessageUtil.sendInfo(sender, "§7Nếu nghi phạm dùng KillAura/TriggerBot, bot sẽ bị đánh trúng và lập tức phát hiện 100%!");
        } else {
            MessageUtil.sendWarning(sender, "Không thể thả AuraBot (người chơi đã có bot đang theo dõi hoặc offline)!");
        }
    }

    private void handleWeb(CommandSender sender, String[] args, String label) {
        WebDashboardService web = manager.getWebDashboard();
        if (web == null) {
            MessageUtil.sendWarning(sender, "Phân hệ Web Dashboard đã được tách ra thành plugin mở rộng độc lập!");
            MessageUtil.sendInfo(sender, "Hãy cài thêm &eJavaStudio-Web-1.0.0.jar &7vào thư mục &fplugins/ &7cùng với JavaStudio để kích hoạt.");
            return;
        }
        Player viewer = sender instanceof Player p ? p : null;
        if (args.length < 2 || args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("status")) {
            boolean running = web.isRunning();
            boolean singlePort = web.isSinglePortActive();
            String url = web.getPublicAccessUrl(viewer);
            String token = web.getAuthToken();
            sender.sendMessage("§b§l=== WEB DASHBOARD & LIVE RADAR (EXTENSION) ===");
            sender.sendMessage(" §8» §7Trạng thái: " + (running ? "§a§lĐANG CHẠY" : "§c§lĐÃ DỪNG"));
            sender.sendMessage(" §8» §7Chế độ cổng: " + (singlePort
                    ? "§a§lPANEL 1-PORT §7(Đi chung cổng Minecraft §e:" + Bukkit.getPort() + " §8-> §7nội bộ §f127.0.0.1:" + web.getInternalPort() + "§7)"
                    : "§e§lCỔNG ĐỘC LẬP §7(:" + web.getInternalPort() + ")"));
            sender.sendMessage(" §8» §7Link đăng nhập 1-click: §b" + url);
            if (viewer != null && !url.contains("<")) {
                try {
                    net.kyori.adventure.text.Component clickMsg = net.kyori.adventure.text.Component.text(" » [NHẤN VÀO ĐÂY ĐỂ MỞ WEB DASHBOARD TRÊN TRÌNH DUYỆT]")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    net.kyori.adventure.text.Component.text("Mở " + url)));
                    viewer.sendMessage(clickMsg);
                } catch (Throwable ignored) {}
            }
            sender.sendMessage(" §8» §7Mã xác thực (Token): §e" + token);
            sender.sendMessage(" §8» §7Lệnh điều khiển: §e/" + label + " web [start|stop|restart] §7hoặc §e/jweb");
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "start" -> {
                web.start();
                MessageUtil.sendSuccess(sender, "Đã khởi động Web Dashboard tại: §b" + web.getPublicAccessUrl(viewer));
            }
            case "stop" -> {
                web.stop();
                MessageUtil.sendWarning(sender, "Đã dừng dịch vụ Web Dashboard.");
            }
            case "restart" -> {
                web.stop();
                web.start();
                MessageUtil.sendSuccess(sender, "Đã khởi động lại Web Dashboard tại: §b" + web.getPublicAccessUrl(viewer));
            }
            default -> MessageUtil.sendError(sender, "Cú pháp: /" + label + " web [info|start|stop|restart]");
        }
    }

    private void handleWave(CommandSender sender, String[] args, String label) {
        if (manager.getBanWaveManager() == null) {
            MessageUtil.sendError(sender, "BanWaveManager chưa được kích hoạt!");
            return;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            var queue = manager.getBanWaveManager().getQueue();
            sender.sendMessage("§b§l=== HÀNG ĐỢI BAN-WAVE (" + queue.size() + " đối tượng) ===");
            if (queue.isEmpty()) {
                sender.sendMessage(" §7Hiện không có người chơi nào trong danh sách chờ ban.");
            } else {
                for (var entry : queue) {
                    sender.sendMessage(" §8» §c" + entry.name() + " §7- " + entry.check().getDisplayName() + " §8(&e" + entry.reason() + "§8) §cVL " + String.format(Locale.ROOT, "%.0f", entry.vl()));
                }
            }
            sender.sendMessage(" §8» §7Thực thi ngay: §e/" + label + " wave execute");
            sender.sendMessage(" §8» §7Xóa danh sách: §e/" + label + " wave clear");
            return;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "execute", "run" -> {
                int count = manager.getBanWaveManager().executeWave(sender);
                MessageUtil.sendSuccess(sender, "Đã thực thi đợt thanh trừng Ban Wave cho " + count + " người chơi!");
            }
            case "clear", "reset" -> {
                manager.getBanWaveManager().clearQueue();
                MessageUtil.sendSuccess(sender, "Đã xóa sạch toàn bộ hàng đợi Ban Wave!");
            }
            default -> MessageUtil.sendError(sender, "Cú pháp: /" + label + " wave [list|execute|clear]");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(Arrays.asList("status", "alerts", "info", "reset", "checks", "toggle", "set", "logs", "exempt", "debug", "freeze", "unfreeze", "top", "inspect", "replay", "record", "reports", "report", "test", "selftest", "exemptops", "bot", "web", "wave", "reload", "help"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("info") || sub.equals("debug") || sub.equals("exempt") || sub.equals("freeze") || sub.equals("unfreeze") || sub.equals("inspect") || sub.equals("record") || sub.equals("test") || sub.equals("bot")) {
                return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), args[1]);
            }
            if (sub.equals("web")) {
                return filter(Arrays.asList("info", "start", "stop", "restart"), args[1]);
            }
            if (sub.equals("wave") || sub.equals("banwave")) {
                return filter(Arrays.asList("list", "execute", "clear"), args[1]);
            }
            if (sub.equals("exemptops")) {
                return filter(Arrays.asList("on", "off"), args[1]);
            }
            if (sub.equals("replay")) {
                return filter(Arrays.asList("list", "play", "pause", "resume", "speed", "info", "stop", "delete"), args[1]);
            }
            if (sub.equals("reports") || sub.equals("report")) {
                return filter(Arrays.asList("list", "claim", "resolve", "dismiss"), args[1]);
            }
            if (sub.equals("reset")) {
                List<String> list = new ArrayList<>();
                list.add("all");
                list.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                return filter(list, args[1]);
            }
            if (sub.equals("toggle") || sub.equals("set")) {
                return filter(Arrays.stream(CheckType.values()).map(CheckType::getKey).collect(Collectors.toList()), args[1]);
            }
            if (sub.equals("checks")) {
                return filter(Arrays.asList("movement", "combat", "world"), args[1]);
            }
            if (sub.equals("alerts")) {
                return filter(Arrays.asList("on", "off"), args[1]);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return filter(Arrays.asList("enabled", "max-vl", "setback", "cancel-event", "max-reach", "max-cps", "max-horizontal", "buffer-limit"), args[2]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase();
        return list.stream().filter(s -> s.toLowerCase().startsWith(p)).collect(Collectors.toList());
    }
}
