package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.analytics.TelemetryNexus;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class JDiagCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final TelemetryNexus telemetry;

    public JDiagCommand(JavaStudioPlugin plugin, TelemetryNexus telemetry) {
        this.plugin = plugin;
        this.telemetry = telemetry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền xem chẩn đoán hiệu năng!");
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        if (!sub.equals("mitigate") && !sub.equals("auto") && !plugin.getSettingsManager().isTelemetryNexusEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Chẩn đoán Telemetry Nexus (/jdiag) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle telemetry-nexus");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendGeneralDiagnosis(sender);
            return true;
        }
        switch (sub) {
            case "cleanup", "purge", "gc" -> {
                MessageUtil.sendInfo(sender, "Đang kích hoạt dọn dẹp thực thể và thu gom rác bộ nhớ JVM...");
                telemetry.executeEmergencyCleanup();
                MessageUtil.sendSuccess(sender, "Dọn dẹp khẩn cấp hoàn tất!");
            }
            case "hud" -> {
                if (sender instanceof Player p) {
                    plugin.getCommand("jhud").execute(p, "jhud", new String[]{"toggle"});
                } else {
                    MessageUtil.sendError(sender, "Lệnh này chỉ dùng được trong game!");
                }
            }
            case "mitigate", "auto" -> {
                var engine = plugin.getAutoMitigationEngine();
                if (engine == null) {
                    MessageUtil.sendError(sender, "AutoMitigationEngine chưa được khởi tạo!");
                    return true;
                }
                if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
                    MessageUtil.send(sender, "&6=== HỆ THỐNG TỰ ĐỘNG ỨNG CỨU TẢI (AUTO-MITIGATION) ===");
                    MessageUtil.sendRaw(sender, "  &7" + engine.getStatusSummary());
                    MessageUtil.sendRaw(sender, "  &7Lệnh: &e/" + label + " mitigate <status|trigger|reset|toggle|reload|set <param> <value>>");
                    return true;
                }
                String action = args[1].toLowerCase();
                switch (action) {
                    case "trigger" -> {
                        engine.triggerMitigation("Thủ công bởi " + sender.getName());
                        MessageUtil.sendSuccess(sender, "Đã kích hoạt ứng cứu giảm tải khẩn cấp thủ công!");
                    }
                    case "reset", "restore" -> {
                        engine.restoreOriginalSettings();
                        MessageUtil.sendSuccess(sender, "Đã khôi phục cài đặt tầm nhìn và cấu hình gốc của các thế giới!");
                    }
                    case "toggle" -> {
                        engine.setEnabled(!engine.isEnabled());
                        MessageUtil.sendSuccess(sender, "AutoMitigation hiện tại: " + (engine.isEnabled() ? "&a[BẬT]" : "&c[TẮT]"));
                    }
                    case "reload" -> {
                        engine.loadConfig();
                        MessageUtil.sendSuccess(sender, "Đã nạp lại toàn bộ thông số AutoMitigation từ config.yml!");
                    }
                    case "set" -> {
                        if (args.length < 4) {
                            MessageUtil.sendError(sender, "Cách dùng: /" + label + " mitigate set <mspt|tps|lag-sec|rec-sec|purge|gc|scale-view|view-red|min-view|sim-red|min-sim> <giá_trị>");
                            return true;
                        }
                        String param = args[2].toLowerCase();
                        String val = args[3].toLowerCase();
                        try {
                            switch (param) {
                                case "mspt" -> {
                                    double d = Double.parseDouble(val);
                                    engine.setMsptThreshold(d);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh msptThreshold = " + d + "ms");
                                }
                                case "tps" -> {
                                    double d = Double.parseDouble(val);
                                    engine.setTpsThreshold(d);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh tpsThreshold = " + d);
                                }
                                case "lag-sec" -> {
                                    int i = Integer.parseInt(val);
                                    engine.setRequiredLagSeconds(i);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh requiredLagSeconds = " + i + "s");
                                }
                                case "rec-sec" -> {
                                    int i = Integer.parseInt(val);
                                    engine.setRequiredRecoverySeconds(i);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh requiredRecoverySeconds = " + i + "s");
                                }
                                case "purge" -> {
                                    boolean b = Boolean.parseBoolean(val);
                                    engine.setPurgeEntitiesEnabled(b);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh purgeEntitiesEnabled = " + b);
                                }
                                case "gc" -> {
                                    boolean b = Boolean.parseBoolean(val);
                                    engine.setTriggerGcEnabled(b);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh triggerGcEnabled = " + b);
                                }
                                case "scale-view" -> {
                                    boolean b = Boolean.parseBoolean(val);
                                    engine.setScaleViewDistanceEnabled(b);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh scaleViewDistanceEnabled = " + b);
                                }
                                case "view-red" -> {
                                    int i = Integer.parseInt(val);
                                    engine.setViewDistanceReduction(i);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh viewDistanceReduction = " + i);
                                }
                                case "min-view" -> {
                                    int i = Integer.parseInt(val);
                                    engine.setMinViewDistance(i);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh minViewDistance = " + i);
                                }
                                case "sim-red" -> {
                                    int i = Integer.parseInt(val);
                                    engine.setSimDistanceReduction(i);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh simDistanceReduction = " + i);
                                }
                                case "min-sim" -> {
                                    int i = Integer.parseInt(val);
                                    engine.setMinSimDistance(i);
                                    MessageUtil.sendSuccess(sender, "Đã chỉnh minSimDistance = " + i);
                                }
                                default -> MessageUtil.sendError(sender, "Thông số không hợp lệ: " + param);
                            }
                        } catch (NumberFormatException e) {
                            MessageUtil.sendError(sender, "Giá trị số không hợp lệ: " + val);
                        }
                    }
                    default -> MessageUtil.sendError(sender, "Cách dùng: /" + label + " mitigate <status|trigger|reset|toggle|reload|set>");
                }
            }
            case "leaks", "leak" -> handleClassLoaderLeaks(sender);
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void handleClassLoaderLeaks(CommandSender sender) {
        MessageUtil.send(sender, "&6=== KIỂM TRA RÒ RỈ BỘ NHỚ (CLASSLOADER LEAK DETECTOR) ===");
        MessageUtil.sendInfo(sender, "Đang kích hoạt System.gc() và quét các tham chiếu tĩnh / luồng ngầm...");

        var reports = com.javastudio.manager.ClassLoaderLeakDetector.scanLeaks();
        if (reports.isEmpty()) {
            MessageUtil.sendSuccess(sender, "Chưa ghi nhận ClassLoader nào bị unload từ khi server khởi động.");
            return;
        }

        int leakedCount = 0;
        for (var rep : reports) {
            if (rep.reclaimed()) {
                MessageUtil.sendRaw(sender, "  &a✔ Plugin &f" + rep.pluginName() + " &7(Đã dọn sạch sau " + rep.unloadAgeSeconds() + "s)");
            } else {
                leakedCount++;
                MessageUtil.sendRaw(sender, "  &c✖ NGUY CƠ RÒ RỈ: &e" + rep.pluginName() + " &c(Chưa được GC thu hồi sau " + rep.unloadAgeSeconds() + "s)!");
                if (!rep.retainedStaticFields().isEmpty()) {
                    MessageUtil.sendRaw(sender, "    &4Biến Static còn giữ: &c" + String.join(", ", rep.retainedStaticFields()));
                }
                if (!rep.lingeringThreads().isEmpty()) {
                    MessageUtil.sendRaw(sender, "    &4Thread chưa đóng: &c" + String.join(", ", rep.lingeringThreads()));
                }
            }
        }

        if (leakedCount > 0) {
            MessageUtil.send(sender, "&c⚠ CẢNH BÁO: Phát hiện " + leakedCount + " ClassLoader chưa được dọn dẹp! Có nguy cơ tràn Metaspace OOM.");
        } else {
            MessageUtil.sendSuccess(sender, "Tuyệt vời! 100% ClassLoader của dynamic plugins đã được giải phóng an toàn.");
        }
    }

    private void sendGeneralDiagnosis(CommandSender sender) {
        TelemetryNexus.TelemetrySnapshot snap = telemetry.captureSnapshot();
        String tpsColor = snap.tps() >= 19.5 ? "§a" : (snap.tps() >= 17.0 ? "§e" : "§c");
        String msptColor = snap.mspt() <= 30.0 ? "§a" : (snap.mspt() <= 45.0 ? "§e" : "§c");
        String ramColor = snap.usedPercent() < 70 ? "§a" : (snap.usedPercent() < 88 ? "§e" : "§c");

        sender.sendMessage("§e╔════════════════════════════════════════════════════╗");
        sender.sendMessage("§e║     §b§lTELEMETRY NEXUS §6Chẩn Đoán Hiệu Năng Hợp Nhất §e    ║");
        sender.sendMessage("§e╚════════════════════════════════════════════════════╝");
        sender.sendMessage(" §8» §7Chỉ số máy chủ:");
        sender.sendMessage("   §8• §7TPS: " + tpsColor + String.format("%.2f", snap.tps()) + " §8| §7MSPT: " + msptColor + String.format("%.2f", snap.mspt()) + "ms");
        sender.sendMessage("   §8• §7RAM: " + ramColor + snap.usedRamMb() + "MB / " + snap.maxRamMb() + "MB §8(" + snap.usedPercent() + "%)");
        sender.sendMessage("   §8• §7Thế giới: §b" + snap.totalChunks() + " §7Chunks §8| §d" + snap.totalEntities() + " §7Thực thể");
        sender.sendMessage(" §8» §7Dynamic Plugins:");
        sender.sendMessage("   §8• §7Đang nạp: §a" + snap.activePluginsCount() + " plugins");
        sender.sendMessage("   §8• §7Tiêu thụ CPU nhiều nhất: §e" + snap.slowestPluginInfo());
        sender.sendMessage(" §8» §7An Ninh & Phòng Thủ Toàn Diện:");
        sender.sendMessage("   §8• §7Panel CVEs bị chặn: §c" + snap.cveBlocked() + " §8| §7Exploits in-game bị chặn: §e" + snap.exploitsBlocked());
        sender.sendMessage(" §8» §7Lệnh nhanh: §e/jdiag gc §7(Dọn dẹp khẩn cấp) §8| §e/jhud §7(Mở HUD in-game)");
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6§l=== LỆNH CHẨN ĐOÁN HIỆU NĂNG HỢP NHẤT ===");
        sender.sendMessage(" §8» §e/" + label + " status §7- Xem tổng quan chẩn đoán hiệu năng");
        sender.sendMessage(" §8» §e/" + label + " gc §7- Kích hoạt dọn rác khẩn cấp và giải phóng RAM");
        sender.sendMessage(" §8» §e/" + label + " hud §7- Bật/tắt thanh HUD in-game");
        sender.sendMessage(" §8» §e/" + label + " mitigate [status|trigger|reset|toggle|reload|set] §7- Quản lý ứng cứu tải tự động");
        sender.sendMessage(" §8» §e/" + label + " leaks §7- Phát hiện rò rỉ bộ nhớ ClassLoader & Metaspace");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String in = args[0].toLowerCase();
            return List.of("status", "gc", "hud", "mitigate", "leaks").stream().filter(s -> s.startsWith(in)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mitigate")) {
            String in = args[1].toLowerCase();
            return List.of("status", "trigger", "reset", "toggle", "reload", "set").stream().filter(s -> s.startsWith(in)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("mitigate") && args[1].equalsIgnoreCase("set")) {
            String in = args[2].toLowerCase();
            return List.of("mspt", "tps", "lag-sec", "rec-sec", "purge", "gc", "scale-view", "view-red", "min-view", "sim-red", "min-sim")
                    .stream().filter(s -> s.startsWith(in)).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("mitigate") && args[1].equalsIgnoreCase("set")) {
            String param = args[2].toLowerCase();
            if (List.of("purge", "gc", "scale-view").contains(param)) {
                return List.of("true", "false");
            }
        }
        return Collections.emptyList();
    }
}
