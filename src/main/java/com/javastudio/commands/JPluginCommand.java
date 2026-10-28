package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.DynamicPlugin;
import com.javastudio.compiler.CompileResult;
import com.javastudio.manager.DynamicPluginManager;
import com.javastudio.templates.PluginTemplates;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.IOException;
import java.util.*;

/**
 * Lệnh /jplugin dùng để tạo, biên dịch, nạp và quản lý các Dynamic Plugin.
 */
public class JPluginCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final DynamicPluginManager pluginManager;

    public JPluginCommand(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.pluginManager = plugin.getPluginManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền quản lý Dynamic Plugin!");
            return true;
        }

        if (!plugin.getSettingsManager().isDynamicPluginsEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Dynamic Plugins (/jplugin) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle dynamic-plugins");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "list" -> {
                List<String> projects = pluginManager.getAllProjects();
                if (projects.isEmpty()) {
                    MessageUtil.sendInfo(sender, "Chưa có plugin nào được tạo. Dùng &e/" + label + " create <tên> &7để bắt đầu!");
                    return true;
                }
                MessageUtil.send(sender, "&6=== Danh sách Dynamic Plugin (" + projects.size() + ") ===");
                for (String p : projects) {
                    boolean loaded = pluginManager.isLoaded(p);
                    String status = loaded ? "&a[✔ Đang Chạy]" : "&c[✖ Đã Tắt]";
                    MessageUtil.sendRaw(sender, "  &e• " + p + " " + status);
                }
            }
            case "create" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " create <tên_plugin> [template]");
                    return true;
                }
                String pluginName = args[1];
                String templateKey = args.length > 2 ? args[2].toLowerCase() : "standard";

                try {
                    boolean created = pluginManager.createProject(pluginName, null, templateKey);
                    if (created) {
                        MessageUtil.sendSuccess(sender, "Đã khởi tạo dự án plugin &e" + pluginName + " &athành công!");
                        MessageUtil.sendInfo(sender, "Template sử dụng: &b" + templateKey);
                        MessageUtil.sendInfo(sender, "Vị trí mã nguồn: &7plugins/JavaStudio/projects/" + pluginName + "/src/");
                        MessageUtil.sendInfo(sender, "Gõ &e/" + label + " load " + pluginName + " &7để biên dịch và chạy ngay!");
                    } else {
                        MessageUtil.sendError(sender, "Dự án plugin có tên " + pluginName + " đã tồn tại!");
                    }
                } catch (IOException e) {
                    MessageUtil.sendError(sender, "Lỗi khi tạo dự án: " + e.getMessage());
                }
            }
            case "compile" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " compile <tên_plugin>");
                    return true;
                }
                String pluginName = args[1];
                MessageUtil.sendInfo(sender, "Đang kiểm tra và biên dịch mã nguồn của &e" + pluginName + "&7...");

                CompileResult res = pluginManager.compileProject(pluginName);
                if (res == null) {
                    MessageUtil.sendError(sender, "Không tìm thấy dự án plugin " + pluginName);
                } else if (res.isSuccess()) {
                    MessageUtil.sendSuccess(sender, "Biên dịch &e" + pluginName + " &athành công! Không có lỗi cú pháp.");
                } else {
                    MessageUtil.sendError(sender, "Biên dịch thất bại:");
                    MessageUtil.sendRaw(sender, res.getFormattedErrors());
                }
            }
            case "load" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " load <tên_plugin>");
                    return true;
                }
                String pluginName = args[1];
                StringBuilder err = new StringBuilder();
                MessageUtil.sendInfo(sender, "Đang nạp và khởi chạy &e" + pluginName + "&7...");

                boolean success = pluginManager.loadPlugin(pluginName, err);
                if (success) {
                    MessageUtil.sendSuccess(sender, "Plugin &e" + pluginName + " &ađã được nạp và kích hoạt thành công!");
                } else {
                    MessageUtil.sendError(sender, "Không thể nạp plugin " + pluginName + ":");
                    MessageUtil.sendRaw(sender, err.toString());
                }
            }
            case "unload" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " unload <tên_plugin>");
                    return true;
                }
                String pluginName = args[1];
                boolean unloaded = pluginManager.unloadPlugin(pluginName);
                if (unloaded) {
                    MessageUtil.sendSuccess(sender, "Đã tắt và giải phóng tài nguyên plugin &e" + pluginName);
                } else {
                    MessageUtil.sendWarning(sender, "Plugin " + pluginName + " hiện không ở trạng thái đang chạy!");
                }
            }
            case "reload" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " reload <tên_plugin>");
                    return true;
                }
                String pluginName = args[1];
                StringBuilder err = new StringBuilder();
                MessageUtil.sendInfo(sender, "Đang nạp lại plugin &e" + pluginName + "&7...");

                boolean success = pluginManager.reloadPlugin(pluginName, err);
                if (success) {
                    MessageUtil.sendSuccess(sender, "Plugin &e" + pluginName + " &ađã được nạp lại thành công!");
                } else {
                    MessageUtil.sendError(sender, "Không thể nạp lại plugin " + pluginName + ":");
                    MessageUtil.sendRaw(sender, err.toString());
                }
            }
            case "templates" -> {
                MessageUtil.send(sender, "&6=== Các Mẫu Plugin (Templates) Sẵn Có ===");
                for (String tKey : PluginTemplates.getAvailableTemplates()) {
                    var def = PluginTemplates.getTemplate(tKey);
                    MessageUtil.sendRaw(sender, "  &b• &e" + tKey + " &7- " + def.getDescription());
                }
                MessageUtil.sendRaw(sender, "  &7Cách dùng: &f/" + label + " create <tên> <template>");
            }
            case "info" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " info <tên_plugin>");
                    return true;
                }
                String pluginName = args[1];
                DynamicPlugin p = pluginManager.getActivePlugin(pluginName);
                if (p == null) {
                    MessageUtil.sendWarning(sender, "Plugin " + pluginName + " hiện chưa được nạp (hoặc không tồn tại).");
                    return true;
                }
                MessageUtil.send(sender, "&6=== Thông tin Dynamic Plugin: &e" + p.getName() + " &6===");
                MessageUtil.sendRaw(sender, "  &7Phiên bản: &f" + p.getVersion());
                MessageUtil.sendRaw(sender, "  &7Tác giả: &f" + p.getAuthor());
                MessageUtil.sendRaw(sender, "  &7Mô tả: &f" + p.getDescription());
                MessageUtil.sendRaw(sender, "  &7Trạng thái: &aĐang chạy (Active)");
                MessageUtil.sendRaw(sender, "  &7Số Event Listeners: &e" + p.getRegisteredListeners().size());
                MessageUtil.sendRaw(sender, "  &7Các lệnh đã đăng ký: &b" + String.join(", ", p.getRegisteredCommands()));
            }
            case "export" -> {
                if (!plugin.getSettingsManager().isPluginExporterEnabled()) {
                    MessageUtil.sendError(sender, "Tính năng Xuất plugin độc lập (export) đang bị tắt trong cài đặt!");
                    MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle plugin-exporter");
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " export <tên_plugin> [--obfuscate] [--license <key>]");
                    return true;
                }
                String pluginName = args[1];
                boolean obfuscate = false;
                String licenseKey = null;

                for (int i = 2; i < args.length; i++) {
                    if (args[i].equalsIgnoreCase("--obfuscate") || args[i].equalsIgnoreCase("-o")) {
                        obfuscate = true;
                    } else if ((args[i].equalsIgnoreCase("--license") || args[i].equalsIgnoreCase("-l")) && i + 1 < args.length) {
                        licenseKey = args[i + 1];
                        i++;
                    }
                }

                StringBuilder err = new StringBuilder();
                MessageUtil.sendInfo(sender, "Đang đóng gói và xuất plugin độc lập &e" + pluginName + "&7" + (obfuscate ? " (Bảo vệ Bytecode)" : "") + (licenseKey != null ? " (Gắn khóa bản quyền)" : "") + "...");

                java.io.File exportedJar = plugin.getExporter().exportProject(pluginName, obfuscate, licenseKey, err);
                if (exportedJar != null) {
                    MessageUtil.sendSuccess(sender, "Xuất plugin thành công! File JAR độc lập: &e" + exportedJar.getName());
                    MessageUtil.sendInfo(sender, "Đường dẫn: &7" + exportedJar.getAbsolutePath());
                    if (obfuscate) MessageUtil.sendInfo(sender, "Bảo mật: &aĐã làm rối Bytecode và xóa debug symbols.");
                    if (licenseKey != null) MessageUtil.sendInfo(sender, "Bản quyền: &eKhóa kích hoạt [" + licenseKey + "]");
                    MessageUtil.sendInfo(sender, "File này có thể cài trực tiếp vào bất kỳ server Minecraft nào (Folia/Paper/Purpur/Spigot)!");
                } else {
                    MessageUtil.sendError(sender, "Không thể xuất plugin " + pluginName + ":");
                    MessageUtil.sendRaw(sender, err.toString());
                }
            }
            case "snapshots", "history" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " snapshots <tên_plugin>");
                    return true;
                }
                String pluginName = args[1];
                var rb = plugin.getRollbackManager();
                if (rb == null) {
                    MessageUtil.sendError(sender, "Rollback Manager chưa được nạp!");
                    return true;
                }
                var snaps = rb.getSnapshots(pluginName);
                if (snaps.isEmpty()) {
                    MessageUtil.sendWarning(sender, "Dự án " + pluginName + " chưa có bản snapshot lưu trữ nào!");
                    return true;
                }
                MessageUtil.send(sender, "&6=== Lịch Sử Snapshot & Sao Lưu: &e" + pluginName + " (" + snaps.size() + ") &6===");
                for (var s : snaps) {
                    MessageUtil.sendRaw(sender, "  &b• &e[" + s.id() + "] &f" + s.timestamp() + " &7- &a" + s.reason() + " &8(" + s.filesCount() + " files)");
                }
                MessageUtil.sendRaw(sender, "  &7Khôi phục nhanh: &f/" + label + " rollback " + pluginName + " <id_snapshot>");
            }
            case "rollback" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " rollback <tên_plugin> [id_snapshot|latest]");
                    return true;
                }
                String pluginName = args[1];
                String snapId = args.length > 2 ? args[2] : "latest";
                var rb = plugin.getRollbackManager();
                if (rb == null) {
                    MessageUtil.sendError(sender, "Rollback Manager chưa được nạp!");
                    return true;
                }
                StringBuilder log = new StringBuilder();
                boolean ok = rb.rollback(pluginName, snapId, log);
                if (ok) {
                    MessageUtil.sendSuccess(sender, "Khôi phục thành công! " + log);
                    MessageUtil.sendInfo(sender, "Đang nạp lại plugin...");
                    StringBuilder reloadLog = new StringBuilder();
                    if (plugin.getPluginManager().reloadPlugin(pluginName, reloadLog)) {
                        MessageUtil.sendSuccess(sender, "Plugin " + pluginName + " đã được nạp lại an toàn!");
                    } else {
                        MessageUtil.sendWarning(sender, "Plugin khôi phục xong nhưng nạp lại gặp lỗi: " + reloadLog);
                    }
                } else {
                    MessageUtil.sendError(sender, "Khôi phục thất bại: " + log);
                }
            }
            case "market" -> {
                if (!plugin.getSettingsManager().isMarketplaceEnabled()) {
                    MessageUtil.sendError(sender, "Tính năng Marketplace & Package Manager đang bị tắt trong cài đặt!");
                    MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle marketplace");
                    return true;
                }
                var packages = plugin.getMarketplaceManager().getAllPackages();
                MessageUtil.send(sender, "&6=== Kho Chợ Plugin Sẵn Có (Marketplace) (" + packages.size() + ") ===");
                for (var pkg : packages) {
                    MessageUtil.sendRaw(sender, "  &e• &b" + pkg.id() + " &a[v" + pkg.version() + "] &7- &f" + pkg.name() + " &8(" + pkg.category() + ")");
                    MessageUtil.sendRaw(sender, "    &7" + pkg.description());
                }
                MessageUtil.sendRaw(sender, "  &7Cài đặt nhanh: &f/" + label + " install <id>");
            }
            case "install" -> {
                if (!plugin.getSettingsManager().isMarketplaceEnabled()) {
                    MessageUtil.sendError(sender, "Tính năng Marketplace & Package Manager đang bị tắt trong cài đặt!");
                    MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle marketplace");
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " install <id_package>");
                    return true;
                }
                String pkgId = args[1].toLowerCase();
                StringBuilder log = new StringBuilder();
                MessageUtil.sendInfo(sender, "Đang tải & cài đặt package &e" + pkgId + "&7...");
                boolean ok = plugin.getMarketplaceManager().installPackage(pkgId, log);
                if (ok) {
                    MessageUtil.sendSuccess(sender, "Cài đặt & kích hoạt thành công: &e" + pkgId);
                } else {
                    MessageUtil.sendError(sender, "Cài đặt thất bại: " + log);
                }
            }
            case "test" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " test <tên_plugin>");
                    return true;
                }
                String pluginName = args[1];
                DynamicPlugin p = pluginManager.getActivePlugin(pluginName);
                if (p == null) {
                    MessageUtil.sendError(sender, "Plugin " + pluginName + " chưa được nạp hoặc không tồn tại!");
                    return true;
                }
                com.javastudio.profiler.DynamicTestRunner.runAndReport(sender, p);
            }
            case "profile" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " profile <tên_plugin|all> [start|stop|report|clear]");
                    return true;
                }
                String target = args[1];
                String action = args.length > 2 ? args[2].toLowerCase() : "report";
                var profiler = plugin.getProfiler();
                if (profiler == null) {
                    MessageUtil.sendError(sender, "Profiler chưa được kích hoạt!");
                    return true;
                }
                switch (action) {
                    case "start" -> {
                        if (target.equalsIgnoreCase("all") || target.equals("*")) {
                            profiler.startAll();
                            MessageUtil.sendSuccess(sender, "Đã bật Profiler đo lường cho TOÀN BỘ dynamic plugin!");
                        } else {
                            profiler.startProfiling(target);
                            MessageUtil.sendSuccess(sender, "Đã bật Profiler đo lường cho plugin: &e" + target);
                        }
                    }
                    case "stop" -> {
                        if (target.equalsIgnoreCase("all") || target.equals("*")) {
                            profiler.stopAll();
                            MessageUtil.sendInfo(sender, "Đã dừng Profiler toàn bộ plugin.");
                        } else {
                            profiler.stopProfiling(target);
                            MessageUtil.sendInfo(sender, "Đã dừng Profiler cho plugin: &e" + target);
                        }
                    }
                    case "clear" -> {
                        profiler.clear(target);
                        MessageUtil.sendInfo(sender, "Đã xóa sạch dữ liệu đo lường của plugin: &e" + target);
                    }
                    default -> profiler.printReport(sender, target);
                }
            }
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        MessageUtil.send(sender, "&6=== Hướng dẫn Lệnh /" + label + " ===");
        MessageUtil.sendRaw(sender, "  &e/" + label + " list &7- Xem danh sách dynamic plugins");
        MessageUtil.sendRaw(sender, "  &e/" + label + " market &7- Khám phá kho chợ plugins sẵn có");
        MessageUtil.sendRaw(sender, "  &e/" + label + " install <id> &7- Cài đặt 1-click plugin từ chợ");
        MessageUtil.sendRaw(sender, "  &e/" + label + " create <tên> [template] &7- Tạo plugin mới");
        MessageUtil.sendRaw(sender, "  &e/" + label + " templates &7- Xem các mẫu plugin có sẵn");
        MessageUtil.sendRaw(sender, "  &e/" + label + " compile <tên> &7- Kiểm tra lỗi biên dịch");
        MessageUtil.sendRaw(sender, "  &e/" + label + " load <tên> &7- Nạp & kích hoạt plugin");
        MessageUtil.sendRaw(sender, "  &e/" + label + " unload <tên> &7- Tắt plugin");
        MessageUtil.sendRaw(sender, "  &e/" + label + " reload <tên> &7- Nạp lại plugin");
        MessageUtil.sendRaw(sender, "  &e/" + label + " export <tên> [--obfuscate] [--license <key>] &7- Xuất file JAR bảo mật");
        MessageUtil.sendRaw(sender, "  &e/" + label + " snapshots <tên> &7- Xem lịch sử các bản sao lưu (Snapshots)");
        MessageUtil.sendRaw(sender, "  &e/" + label + " rollback <tên> [id] &7- Hoàn tác mã nguồn về bản sao lưu");
        MessageUtil.sendRaw(sender, "  &e/" + label + " info <tên> &7- Xem thông tin chi tiết");
        MessageUtil.sendRaw(sender, "  &e/" + label + " test <tên> &7- Chạy các hàm Unit Test (@Test)");
        MessageUtil.sendRaw(sender, "  &e/" + label + " profile <tên|all> [start|stop|report] &7- Đo lường lag & hiệu năng");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("list", "market", "install", "create", "templates", "compile", "load", "unload", "reload", "export", "snapshots", "rollback", "info", "test", "profile");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("compile") || sub.equals("load") || sub.equals("unload") || sub.equals("reload") || sub.equals("export") || sub.equals("snapshots") || sub.equals("rollback") || sub.equals("info") || sub.equals("test") || sub.equals("profile")) {
                List<String> list = new ArrayList<>(pluginManager.getAllProjects());
                if (sub.equals("profile")) list.add(0, "all");
                return list;
            }
            if (sub.equals("install")) {
                return plugin.getMarketplaceManager().getAllPackages().stream().map(com.javastudio.manager.MarketplaceManager.MarketPackage::id).toList();
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("profile")) {
            return Arrays.asList("start", "stop", "report", "clear");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return new ArrayList<>(PluginTemplates.getAvailableTemplates());
        }
        return Collections.emptyList();
    }
}
