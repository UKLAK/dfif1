package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.control.PluginController;
import com.javastudio.control.ServerController;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Lệnh quản trị cấp cao /jcontrol (alias: /jpm, /plugman, /jserver)
 * cho phép kiểm soát 100% mọi plugin trên server (bật/tắt/nạp/gỡ/reload)
 * và điều khiển hiệu năng, thế giới, tài nguyên của toàn bộ phần mềm máy chủ.
 */
public class JControlCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final PluginController pluginCtrl;
    private final ServerController serverCtrl;

    public JControlCommand(JavaStudioPlugin plugin, PluginController pluginCtrl, ServerController serverCtrl) {
        this.plugin = plugin;
        this.pluginCtrl = pluginCtrl;
        this.serverCtrl = serverCtrl;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền quản trị JavaStudio Server Controller!");
            return true;
        }

        String cmdLabel = label.toLowerCase();
        if (args.length == 0) {
            if (cmdLabel.contains("server")) {
                if (!plugin.getSettingsManager().isServerMonitorEnabled()) {
                    MessageUtil.sendError(sender, "Tính năng Server Monitor (/jserver) đang bị tắt trong cài đặt!");
                    MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle server-monitor");
                    return true;
                }
                showServerStatus(sender);
            } else {
                showHelp(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        // Kiểm tra phân quyền module
        boolean isServerOp = sub.equals("status") || sub.equals("gc") || sub.equals("purge") || sub.equals("save") || sub.equals("cmd");
        if (isServerOp && !plugin.getSettingsManager().isServerMonitorEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Server Monitor (/jserver) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle server-monitor");
            return true;
        }

        boolean isPluginOp = sub.equals("list") || sub.equals("info") || sub.equals("enable") || sub.equals("disable") || sub.equals("load") || sub.equals("unload") || sub.equals("reload") || sub.equals("usage");
        if (isPluginOp && !plugin.getSettingsManager().isPluginControllerEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Plugin Controller (/jpm) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle plugin-controller");
            return true;
        }

        switch (sub) {
            case "list" -> handleList(sender);
            case "info" -> handleInfo(sender, args);
            case "enable" -> handleEnable(sender, args);
            case "disable" -> handleDisable(sender, args);
            case "load" -> handleLoad(sender, args);
            case "unload" -> handleUnload(sender, args);
            case "reload" -> handleReload(sender, args);
            case "usage" -> handleUsage(sender, args);
            case "scan" -> handleScan(sender, args);
            case "inspect" -> handleInspect(sender, args);
            case "patch" -> handlePatch(sender, args);
            case "status" -> showServerStatus(sender);
            case "gc" -> handleGc(sender);
            case "purge" -> handlePurge(sender, args);
            case "save" -> handleSave(sender);
            case "cmd" -> handleConsoleCmd(sender, args);
            default -> showHelp(sender);
        }

        return true;
    }

    private void handleList(CommandSender sender) {
        List<Plugin> all = pluginCtrl.getAllPlugins();
        int enabledCount = 0;
        int disabledCount = 0;
        int dynamicCount = plugin.getPluginManager().getActivePlugins().size();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            Plugin p = all.get(i);
            boolean isDynamic = plugin.getPluginManager().isLoaded(p.getName());
            if (p.isEnabled()) {
                enabledCount++;
                sb.append(isDynamic ? "§b" : "§a").append(p.getName());
            } else {
                disabledCount++;
                sb.append("§c").append(p.getName());
            }
            if (i < all.size() - 1) sb.append("§7, ");
        }

        MessageUtil.send(sender, "&6=== Danh Sách Plugins Trên Server (" + all.size() + ") ===");
        MessageUtil.sendRaw(sender, "  &a■ Đang bật: " + enabledCount + " &7| &c■ Đang tắt: " + disabledCount + " &7| &b■ Dynamic: " + dynamicCount);
        MessageUtil.sendRaw(sender, "  " + sb);
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + "jpm info <tên_plugin>");
            return;
        }

        Map<String, Object> details = pluginCtrl.getPluginDetails(args[1]);
        if (details == null) {
            MessageUtil.sendError(sender, "Không tìm thấy plugin '" + args[1] + "'!");
            return;
        }

        MessageUtil.send(sender, "&6=== Thông Tin Plugin: " + details.get("name") + " ===");
        MessageUtil.sendRaw(sender, "  &7Phiên bản: &e" + details.get("version"));
        MessageUtil.sendRaw(sender, "  &7Trạng thái: " + ((boolean) details.get("enabled") ? "&aĐang hoạt động (Enabled)" : "&cĐã tắt (Disabled)"));
        MessageUtil.sendRaw(sender, "  &7Tác giả: &f" + details.get("authors"));
        MessageUtil.sendRaw(sender, "  &7Mô tả: &f" + details.get("description"));
        MessageUtil.sendRaw(sender, "  &7Class chính: &8" + details.get("main"));
        MessageUtil.sendRaw(sender, "  &7Phụ thuộc (Depend): &e" + details.get("depend"));
        MessageUtil.sendRaw(sender, "  &7Phụ thuộc mềm (SoftDepend): &e" + details.get("softDepend"));
        MessageUtil.sendRaw(sender, "  &7Các plugin khác đang dùng nó: &d" + details.get("dependents"));
        if (details.containsKey("file")) {
            long kb = ((long) details.get("fileSize")) / 1024;
            MessageUtil.sendRaw(sender, "  &7File JAR: &b" + details.get("file") + " &7(" + kb + " KB)");
        }
    }

    private void handleEnable(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + "jpm enable <tên_plugin>");
            return;
        }
        StringBuilder log = new StringBuilder();
        if (pluginCtrl.enablePlugin(args[1], log)) {
            MessageUtil.sendSuccess(sender, log.toString());
        } else {
            MessageUtil.sendError(sender, log.toString());
        }
    }

    private void handleDisable(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + "jpm disable <tên_plugin>");
            return;
        }
        StringBuilder log = new StringBuilder();
        if (pluginCtrl.disablePlugin(args[1], log)) {
            MessageUtil.sendSuccess(sender, log.toString());
        } else {
            MessageUtil.sendError(sender, log.toString());
        }
    }

    private void handleLoad(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + "jpm load <file_name.jar>");
            return;
        }
        StringBuilder log = new StringBuilder();
        if (pluginCtrl.loadPlugin(args[1], log)) {
            MessageUtil.sendSuccess(sender, log.toString());
        } else {
            MessageUtil.sendError(sender, log.toString());
        }
    }

    private void handleUnload(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + "jpm unload <tên_plugin>");
            return;
        }
        if (args[1].equalsIgnoreCase(plugin.getName())) {
            MessageUtil.sendWarning(sender, "Không thể unload chính JavaStudio qua lệnh này! Hãy dùng lệnh tắt server.");
            return;
        }

        List<String> dependents = pluginCtrl.getDependentPlugins(args[1]);
        if (!dependents.isEmpty()) {
            MessageUtil.sendWarning(sender, "Cảnh báo: Các plugin sau đang phụ thuộc vào " + args[1] + ": " + dependents);
        }

        StringBuilder log = new StringBuilder();
        if (pluginCtrl.unloadPlugin(args[1], log)) {
            MessageUtil.sendSuccess(sender, log.toString());
        } else {
            MessageUtil.sendError(sender, log.toString());
        }
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + "jpm reload <tên_plugin>");
            return;
        }
        if (args[1].equalsIgnoreCase(plugin.getName())) {
            plugin.reloadConfig();
            MessageUtil.sendSuccess(sender, "Đã reload cấu hình JavaStudio!");
            return;
        }

        StringBuilder log = new StringBuilder();
        if (pluginCtrl.reloadPlugin(args[1], log)) {
            MessageUtil.sendSuccess(sender, log.toString());
        } else {
            MessageUtil.sendError(sender, log.toString());
        }
    }

    private void handleUsage(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + "jpm usage <tên_plugin>");
            return;
        }
        List<String> deps = pluginCtrl.getDependentPlugins(args[1]);
        if (deps.isEmpty()) {
            MessageUtil.sendInfo(sender, "Không có plugin nào khác phụ thuộc vào '" + args[1] + "'.");
        } else {
            MessageUtil.send(sender, "&6Các plugin đang phụ thuộc vào &e" + args[1] + "&6: &d" + String.join(", ", deps));
        }
    }

    private void showServerStatus(CommandSender sender) {
        double[] tps = serverCtrl.getTPS();
        double mspt = serverCtrl.getMSPT();
        ServerController.MemoryInfo mem = serverCtrl.getMemoryInfo();
        Map<String, Object> sys = serverCtrl.getSystemInfo();
        List<ServerController.WorldInfo> worlds = serverCtrl.getWorldsInfo();

        String tps1 = formatTps(tps[0]);
        String tps5 = formatTps(tps[1]);
        String tps15 = formatTps(tps[2]);

        MessageUtil.send(sender, "&6=== Bảng Điều Khiển Toàn Bộ Server (Server Dashboard) ===");
        MessageUtil.sendRaw(sender, "  &7Hiệu năng TPS: " + tps1 + " &7(1m), " + tps5 + " &7(5m), " + tps15 + " &7(15m)" + (mspt > 0 ? " &8[MSPT: " + String.format("%.2f", mspt) + "ms]" : ""));
        MessageUtil.sendRaw(sender, "  &7Bộ nhớ RAM: &e" + mem.usedMb() + " MB &7/ &b" + mem.maxMb() + " MB &7(" + mem.usedPercent() + "% sử dụng)");
        MessageUtil.sendRaw(sender, "  &7Phần cứng: &f" + sys.get("cores") + " Cores CPU &7| Luồng JVM: &e" + sys.get("threads") + " &7| Uptime: &a" + sys.get("uptime"));
        MessageUtil.sendRaw(sender, "  &7Hệ điều hành: &8" + sys.get("os") + " &7| Java: &8" + sys.get("java"));

        StringBuilder wsb = new StringBuilder();
        int totalEntities = 0;
        int totalChunks = 0;
        for (ServerController.WorldInfo w : worlds) {
            wsb.append("&a").append(w.name()).append(" &7(").append(w.loadedChunks()).append(" chunks, ").append(w.entitiesCount()).append(" entities) ");
            totalEntities += w.entitiesCount();
            totalChunks += w.loadedChunks();
        }
        MessageUtil.sendRaw(sender, "  &7Thế giới (" + worlds.size() + " worlds, " + totalChunks + " chunks, " + totalEntities + " entities):");
        MessageUtil.sendRaw(sender, "    " + wsb);
    }

    private void handleGc(CommandSender sender) {
        MessageUtil.sendInfo(sender, "Đang kích hoạt bộ thu gom rác bộ nhớ (Garbage Collector)...");
        long freedMb = serverCtrl.triggerGC();
        ServerController.MemoryInfo mem = serverCtrl.getMemoryInfo();
        MessageUtil.sendSuccess(sender, "Đã thu gom rác thành công! Giải phóng: &e" + freedMb + " MB &aRAM. Hiện tại: &b" + mem.usedMb() + " MB &7/ &e" + mem.maxMb() + " MB.");
    }

    private void handlePurge(CommandSender sender, String[] args) {
        boolean items = true;
        boolean mobs = false;
        boolean animals = false;
        World targetWorld = null;

        if (args.length > 1) {
            World w = Bukkit.getWorld(args[1]);
            if (w != null) {
                targetWorld = w;
                if (args.length > 2) {
                    mobs = args[2].equalsIgnoreCase("mobs") || args[2].equalsIgnoreCase("all");
                    animals = args[2].equalsIgnoreCase("all");
                }
            } else {
                mobs = args[1].equalsIgnoreCase("mobs") || args[1].equalsIgnoreCase("all");
                animals = args[1].equalsIgnoreCase("all");
            }
        }

        int count = serverCtrl.purgeEntities(targetWorld, items, mobs, animals);
        MessageUtil.sendSuccess(sender, "Đã dọn sạch &e" + count + " &athực thể " + (targetWorld != null ? "trên thế giới " + targetWorld.getName() : "trên toàn bộ máy chủ") + "!");
    }

    private void handleSave(CommandSender sender) {
        MessageUtil.sendInfo(sender, "Đang ép lưu dữ liệu tất cả Worlds và người chơi xuống đĩa cứng...");
        serverCtrl.saveAllWorlds();
        MessageUtil.sendSuccess(sender, "Đã lưu toàn bộ thế giới thành công!");
    }

    private void handleConsoleCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + "jcontrol cmd <lệnh_console...>");
            return;
        }
        String cmd = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        boolean success = serverCtrl.executeConsoleCommand(cmd);
        if (success) {
            MessageUtil.sendSuccess(sender, "Đã gửi lệnh console: /" + cmd);
        } else {
            MessageUtil.sendError(sender, "Không thể thực thi lệnh console!");
        }
    }

    private String formatTps(double tps) {
        double clamped = Math.min(20.0, tps);
        String formatted = String.format("%.2f", clamped);
        if (clamped >= 19.0) return "§a" + formatted;
        if (clamped >= 16.0) return "§e" + formatted;
        return "§c" + formatted;
    }

    private void handleScan(CommandSender sender, String[] args) {
        if (!plugin.getSettingsManager().isSecurityScannerEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Security Scanner đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle security-scanner");
            return;
        }

        if (args.length > 1) {
            String name = args[1];
            File jar = findJar(name);
            if (jar == null || !jar.exists()) {
                MessageUtil.sendError(sender, "Không tìm thấy file JAR cho plugin '" + name + "'!");
                return;
            }
            MessageUtil.sendInfo(sender, "Đang quét mã độc và an ninh file &e" + jar.getName() + "&7...");
            var report = plugin.getSecurityScanner().scanPluginJar(jar);
            printScanReport(sender, report);
        } else {
            MessageUtil.sendInfo(sender, "Đang quét an ninh toàn bộ thư mục plugins/...");
            var reports = plugin.getSecurityScanner().scanAllPlugins();
            int totalDanger = 0;
            int totalWarn = 0;
            for (var r : reports) {
                totalDanger += r.dangerCount();
                totalWarn += r.warningCount();
                if (r.dangerCount() > 0 || r.warningCount() > 0) {
                    printScanReport(sender, r);
                }
            }
            MessageUtil.send(sender, "&6=== Kết Quả Quét An Ninh Toàn Bộ Máy Chủ ===");
            MessageUtil.sendRaw(sender, "  &7Đã quét &e" + reports.size() + " &7plugins. Tổng phát hiện: " +
                    (totalDanger > 0 ? "&c" + totalDanger + " NGUY HIỂM" : "&a0 Nguy hiểm") + " &7| " +
                    (totalWarn > 0 ? "&e" + totalWarn + " Cảnh báo" : "&a0 Cảnh báo"));
            if (totalDanger == 0 && totalWarn == 0) {
                MessageUtil.sendSuccess(sender, "Tất cả các plugin đều an toàn! Không tìm thấy mã độc.");
            }
        }
    }

    private void printScanReport(CommandSender sender, com.javastudio.security.SecurityScanner.ScanReport report) {
        String statusColor = report.dangerCount() > 0 ? "&c[NGUY HIỂM]" : (report.warningCount() > 0 ? "&e[CẢNH BÁO]" : "&a[AN TOÀN]");
        MessageUtil.send(sender, "&6• Plugin: &f" + report.pluginName() + " " + statusColor);
        for (var f : report.findings()) {
            String tag = f.severity().equals("DANGER") ? "&c[DANGER]" : "&e[WARN]";
            MessageUtil.sendRaw(sender, "   " + tag + " &8" + f.className() + " &7- &f" + f.description());
        }
    }

    private void handleInspect(CommandSender sender, String[] args) {
        if (!plugin.getSettingsManager().isBytecodeInspectorEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Bytecode Inspector đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle bytecode-inspector");
            return;
        }

        if (args.length < 2) {
            MessageUtil.sendError(sender, "Cách dùng: /" + "jpm inspect <tên_plugin> [tên_class]");
            return;
        }
        String pluginName = args[1];
        File jar = findJar(pluginName);
        if (jar == null || !jar.exists()) {
            MessageUtil.sendError(sender, "Không tìm thấy file JAR cho plugin '" + pluginName + "'!");
            return;
        }

        if (args.length == 2) {
            MessageUtil.sendInfo(sender, "Đang trích xuất danh sách class từ &e" + jar.getName() + "&7...");
            List<String> classes = plugin.getBytecodeInspector().listClassesInJar(jar);
            MessageUtil.send(sender, "&6=== Các Class Trong Plugin: " + jar.getName() + " (" + classes.size() + ") ===");
            int limit = Math.min(classes.size(), 20);
            for (int i = 0; i < limit; i++) {
                MessageUtil.sendRaw(sender, "  &b• &f" + classes.get(i));
            }
            if (classes.size() > 20) {
                MessageUtil.sendRaw(sender, "  &7...và còn " + (classes.size() - 20) + " class khác. Xem đầy đủ trên Web IDE!");
            }
            MessageUtil.sendRaw(sender, "  &7Dịch ngược class: &e/jpm inspect " + pluginName + " <tên_class>");
        } else {
            String className = args[2];
            MessageUtil.sendInfo(sender, "Đang dịch ngược class &e" + className + "&7...");
            String decompiled = plugin.getBytecodeInspector().decompileClass(jar, className);
            MessageUtil.send(sender, "&6=== Bytecode Decompiled: " + className + " ===");
            String[] lines = decompiled.split("\n");
            int limit = Math.min(lines.length, 30);
            for (int i = 0; i < limit; i++) {
                MessageUtil.sendRaw(sender, lines[i]);
            }
            if (lines.length > 30) {
                MessageUtil.sendRaw(sender, "  &7... (còn " + (lines.length - 30) + " dòng, hãy mở Web IDE để xem toàn bộ)");
            }
        }
    }

    private void handlePatch(CommandSender sender, String[] args) {
        var pm = plugin.getBytecodePatchManager();
        if (pm == null) {
            MessageUtil.sendError(sender, "BytecodePatchManager chưa được khởi tạo!");
            return;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            var patches = pm.getActivePatches();
            if (patches.isEmpty()) {
                MessageUtil.sendInfo(sender, "Hiện chưa có bản vá nóng Bytecode nào đang hoạt động.");
                return;
            }
            MessageUtil.send(sender, "&6=== DANH SÁCH BẢN VÁ NÓNG BYTECODE (" + patches.size() + ") ===");
            for (var p : patches) {
                MessageUtil.sendRaw(sender, "  &b• &e[" + p.id() + "] &f" + p.targetPlugin() + " &7- &e" + p.targetClass() + "#" + p.targetMethod() + " &8(" + p.patchType() + ")");
                MessageUtil.sendRaw(sender, "    &7Ghi chú: " + p.description());
            }
            return;
        }
        String pSub = args[1].toLowerCase();
        if (pSub.equals("apply")) {
            if (args.length < 5) {
                MessageUtil.sendError(sender, "Cách dùng: /jpm patch apply <plugin> <class> <method> [mô tả]");
                return;
            }
            String targetPlugin = args[2];
            String targetClass = args[3];
            String targetMethod = args[4];
            String desc = args.length > 5 ? args[5] : "Custom live patch";
            StringBuilder log = new StringBuilder();
            boolean ok = pm.applyPatch(targetPlugin, targetClass, targetMethod, "LOG_INTERCEPT", desc, log);
            if (ok) MessageUtil.sendSuccess(sender, log.toString());
            else MessageUtil.sendError(sender, log.toString());
        } else if (pSub.equals("revert")) {
            if (args.length < 3) {
                MessageUtil.sendError(sender, "Cách dùng: /jpm patch revert <patch_id>");
                return;
            }
            StringBuilder log = new StringBuilder();
            boolean ok = pm.revertPatch(args[2], log);
            if (ok) MessageUtil.sendSuccess(sender, log.toString());
            else MessageUtil.sendError(sender, log.toString());
        } else {
            MessageUtil.sendError(sender, "Cách dùng: /jpm patch <list|apply|revert>");
        }
    }

    private File findJar(String name) {
        Plugin p = pluginCtrl.getPlugin(name);
        if (p != null) {
            File f = pluginCtrl.getPluginFile(p);
            if (f != null && f.exists()) return f;
        }
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File f = new File(pluginsDir, name.endsWith(".jar") ? name : name + ".jar");
        if (f.exists()) return f;
        for (File jar : pluginCtrl.getAvailableJarFiles()) {
            if (jar.getName().equalsIgnoreCase(name) || jar.getName().equalsIgnoreCase(name + ".jar")) {
                return jar;
            }
        }
        return null;
    }

    private void showHelp(CommandSender sender) {
        MessageUtil.send(sender, "&6=== JavaStudio Server & PlugMan Controller ===");
        MessageUtil.sendRaw(sender, "  &e/jpm list &7- Xem danh sách toàn bộ plugin server");
        MessageUtil.sendRaw(sender, "  &e/jpm info <plugin> &7- Xem chi tiết plugin, tác giả, phụ thuộc");
        MessageUtil.sendRaw(sender, "  &e/jpm enable <plugin> &7- Bật một plugin đang tắt");
        MessageUtil.sendRaw(sender, "  &e/jpm disable <plugin> &7- Vô hiệu hóa một plugin");
        MessageUtil.sendRaw(sender, "  &e/jpm load <file.jar> &7- Nạp file JAR mới vào server mà không cần restart");
        MessageUtil.sendRaw(sender, "  &e/jpm unload <plugin> &7- Gỡ sạch plugin và giải phóng file lock");
        MessageUtil.sendRaw(sender, "  &e/jpm reload <plugin> &7- Hot-reload bất kỳ plugin nào");
        MessageUtil.sendRaw(sender, "  &e/jpm usage <plugin> &7- Xem plugin nào đang phụ thuộc vào nó");
        MessageUtil.sendRaw(sender, "  &e/jpm scan [plugin] &7- Quét phát hiện mã độc, backdoor, force OP");
        MessageUtil.sendRaw(sender, "  &e/jpm inspect <plugin> [class] &7- Mổ xẻ & dịch ngược bytecode class");
        MessageUtil.sendRaw(sender, "  &e/jpm patch <list|apply|revert> &7- Vá nóng Live Bytecode plugin bên thứ 3");
        MessageUtil.sendRaw(sender, "  &e/jserver status &7- Bảng điều khiển hiệu năng TPS, MSPT, RAM, CPU");
        MessageUtil.sendRaw(sender, "  &e/jserver gc &7- Ép dọn rác RAM (Garbage Collector)");
        MessageUtil.sendRaw(sender, "  &e/jserver purge [world] [items|mobs|all] &7- Dọn thực thể gây lag");
        MessageUtil.sendRaw(sender, "  &e/jserver save &7- Ép lưu toàn bộ Worlds xuống đĩa");
        MessageUtil.sendRaw(sender, "  &e/jserver cmd <lệnh> &7- Chạy lệnh với quyền Server Console");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("list", "info", "enable", "disable", "load", "unload", "reload", "usage", "scan", "inspect", "patch", "status", "gc", "purge", "save", "cmd")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("info") || sub.equals("enable") || sub.equals("disable") || sub.equals("unload") || sub.equals("reload") || sub.equals("usage") || sub.equals("scan") || sub.equals("inspect")) {
                return pluginCtrl.getAllPlugins().stream().map(Plugin::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            }
            if (sub.equals("load")) {
                return pluginCtrl.getAvailableJarFiles().stream().map(File::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            }
            if (sub.equals("purge")) {
                List<String> list = new ArrayList<>();
                list.add("items");
                list.add("mobs");
                list.add("all");
                Bukkit.getWorlds().forEach(w -> list.add(w.getName()));
                return list.stream().filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
