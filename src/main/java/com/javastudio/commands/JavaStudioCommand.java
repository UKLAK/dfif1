package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Lệnh chính /javastudio hiển thị thông tin hệ sinh thái, cấu hình và trạng thái.
 */
public class JavaStudioCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;

    public JavaStudioCommand(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền sử dụng lệnh này!");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            MessageUtil.send(sender, "&6=== Hệ Thống JavaStudio Plugin Engine ===");
            MessageUtil.sendRaw(sender, "  &7Phiên bản plugin: &e" + plugin.getDescription().getVersion());
            MessageUtil.sendRaw(sender, "  &7Máy chủ Server: &b" + Bukkit.getName() + " " + Bukkit.getBukkitVersion());
            MessageUtil.sendRaw(sender, "  &7Nền tảng phần mềm: &d" + com.javastudio.utils.PlatformUtil.getPlatformName() + " (MC " + com.javastudio.utils.PlatformUtil.getMinecraftVersion() + ")");
            MessageUtil.sendRaw(sender, "  &7Hỗ trợ Kyori Adventure: " + (com.javastudio.utils.PlatformUtil.isAdventureSupported() ? "&a✔ Khả dụng" : "&e⚠ Chế độ tương thích Spigot"));
            MessageUtil.sendRaw(sender, "  &7Phiên bản Java: &aJava " + com.javastudio.utils.PlatformUtil.getJavaVersion() + " (&f" + System.getProperty("java.version") + " &7- &e" + System.getProperty("java.vendor") + "&a)");
            MessageUtil.sendRaw(sender, "  &7Virtual Threads (Project Loom): " + (com.javastudio.utils.PlatformUtil.isVirtualThreadsSupported() ? "&a✔ Đã kích hoạt" : "&7Không khả dụng"));
            MessageUtil.sendRaw(sender, "  &7Trạng thái JDK Compiler: " + (plugin.getCompiler().isAvailable() ? "&a✔ Sẵn sàng (JDK)" : "&c✖ Không khả dụng (Đang chạy JRE)"));
            MessageUtil.sendRaw(sender, "  &7Dynamic Plugins đang chạy: &e" + plugin.getPluginManager().getActivePlugins().size());
            MessageUtil.sendRaw(sender, "  &7Scripts hiện có: &e" + plugin.getScriptManager().getAvailableScripts().size());
            MessageUtil.sendRaw(sender, "  &7Tương thích đa nền tảng: &a✔ Java 21+ & Paper/Purpur/Spigot/Folia 1.20.5 -> 26.3+");
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            plugin.reloadConfig();
            plugin.getCompiler().invalidateClasspathCache();
            MessageUtil.sendSuccess(sender, "Đã nạp lại file config.yml và làm mới cache Classpath của JavaStudio!");
            return true;
        }

        if (sub.equals("settings") || sub.equals("config") || sub.equals("modules")) {
            showSettingsDashboard(sender);
            return true;
        }

        if (sub.equals("toggle")) {
            if (args.length < 2) {
                MessageUtil.sendError(sender, "Cách dùng: /" + label + " toggle <tên_module>");
                MessageUtil.sendInfo(sender, "Dùng &e/" + label + " settings &7để xem danh sách toàn bộ các module!");
                return true;
            }
            String modKey = args[1];
            var module = com.javastudio.manager.SettingsManager.ModuleKey.fromKey(modKey);
            if (module == null) {
                MessageUtil.sendError(sender, "Không tìm thấy module có tên: " + modKey);
                return true;
            }
            boolean newState = plugin.getSettingsManager().toggleModule(module);
            String status = newState ? "&a[BẬT] ✔" : "&c[TẮT] ✖";
            MessageUtil.sendSuccess(sender, "Đã chuyển trạng thái module &e" + module.getDisplayName() + " &athành: " + status);
            if (!newState) {
                MessageUtil.sendInfo(sender, "&7Lợi ích giảm tải: &b" + plugin.getSettingsManager().getResourceSavings(module));
            }
            return true;
        }

        if (sub.equals("set")) {
            if (args.length < 3) {
                MessageUtil.sendError(sender, "Cách dùng: /" + label + " set <tên_module> <on|off|true|false>");
                return true;
            }
            String modKey = args[1];
            var module = com.javastudio.manager.SettingsManager.ModuleKey.fromKey(modKey);
            if (module == null) {
                MessageUtil.sendError(sender, "Không tìm thấy module có tên: " + modKey);
                return true;
            }
            String valStr = args[2].toLowerCase();
            boolean enable = valStr.equals("on") || valStr.equals("true") || valStr.equals("1") || valStr.equals("enable");
            plugin.getSettingsManager().setModuleEnabled(module, enable);
            String status = enable ? "&a[BẬT] ✔" : "&c[TẮT] ✖";
            MessageUtil.sendSuccess(sender, "Đã đặt module &e" + module.getDisplayName() + " &asáng: " + status);
            if (!enable) {
                MessageUtil.sendInfo(sender, "&7Lợi ích giảm tải: &b" + plugin.getSettingsManager().getResourceSavings(module));
            }
            return true;
        }

        if (sub.equals("watcher") || sub.equals("watch")) {
            if (args.length > 1) {
                String action = args[1].toLowerCase();
                if (action.equals("on") || action.equals("start")) {
                    plugin.getSettingsManager().setModuleEnabled(com.javastudio.manager.SettingsManager.ModuleKey.FILE_WATCHER, true);
                    MessageUtil.sendSuccess(sender, "Đã khởi động Hot-Reload File Watcher!");
                    return true;
                } else if (action.equals("off") || action.equals("stop")) {
                    plugin.getSettingsManager().setModuleEnabled(com.javastudio.manager.SettingsManager.ModuleKey.FILE_WATCHER, false);
                    MessageUtil.sendWarning(sender, "Đã dừng Hot-Reload File Watcher (Giảm 100% I/O ổ đĩa)!");
                    return true;
                }
            }
            boolean isRunning = plugin.getFileWatcher().isRunning();
            MessageUtil.send(sender, "&6=== Hot-Reload File Watcher ===");
            MessageUtil.sendRaw(sender, "  &7Trạng thái: " + (isRunning ? "&aĐang chạy (Tự động nạp khi lưu file)" : "&cĐang dừng (Tiết kiệm CPU/Disk)"));
            MessageUtil.sendRaw(sender, "  &7Lệnh bật/tắt: &e/" + label + " toggle file-watcher");
            return true;
        }

        MessageUtil.send(sender, "&6=== Các Lệnh Quản Trị JavaStudio ===");
        MessageUtil.sendRaw(sender, "  &e/" + label + " settings &7- Bảng điều khiển bật/tắt toàn bộ module & tính năng");
        MessageUtil.sendRaw(sender, "  &e/" + label + " toggle <module> &7- Bật/tắt tức thì 1 module để tối ưu RAM/CPU");
        MessageUtil.sendRaw(sender, "  &e/" + label + " set <module> <on|off> &7- Đặt trạng thái cụ thể cho module");
        MessageUtil.sendRaw(sender, "  &e/" + label + " watcher [on|off] &7- Bật/tắt tự động reload khi lưu file");
        MessageUtil.sendRaw(sender, "  &e/" + label + " reload &7- Nạp lại toàn bộ file cấu hình config.yml");
        MessageUtil.sendRaw(sender, "  &e/jexec <code...> &7- Chạy mã Java tức thì (hoặc &e/java&7)");
        MessageUtil.sendRaw(sender, "  &e/jplugin &7- Quản lý các dynamic plugin");
        MessageUtil.sendRaw(sender, "  &e/jscript &7- Quản lý và thực thi các script độc lập");
        MessageUtil.sendRaw(sender, "  &e/jpm &7- Quản lý & can thiệp toàn bộ plugin bên ngoài");
        MessageUtil.sendRaw(sender, "  &e/jserver &7- Giám sát TPS, RAM & dọn dẹp thực thể");
        return true;
    }

    private void showSettingsDashboard(CommandSender sender) {
        MessageUtil.send(sender, "&6=== Cài Đặt Hệ Thống JavaStudio (Settings) ===");
        MessageUtil.sendRaw(sender, "  &7Tắt những tính năng không dùng để giảm tải CPU, RAM & I/O mạng:");

        var sm = plugin.getSettingsManager();
        for (var mod : com.javastudio.manager.SettingsManager.ModuleKey.values()) {
            boolean enabled = sm.isEnabled(mod);
            String statusIcon = enabled ? "&a✔ [BẬT]" : "&c✖ [TẮT]";
            String nameColor = enabled ? "&f" : "&8";
            MessageUtil.sendRaw(sender, "  " + statusIcon + " " + nameColor + mod.getDisplayName() + " &7(&e" + mod.getKey() + "&7)");
            if (!enabled) {
                MessageUtil.sendRaw(sender, "      &b↳ &3Đang tiết kiệm: &f" + sm.getResourceSavings(mod));
            }
        }
        MessageUtil.sendRaw(sender, " ");
        MessageUtil.sendInfo(sender, "💡 Để bật/tắt: &e/javastudio toggle <key> &7(ví dụ: &f/javastudio toggle auto-mitigation&7)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("info", "settings", "toggle", "set", "watcher", "reload", "help");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("set"))) {
            List<String> keys = new java.util.ArrayList<>();
            for (var m : com.javastudio.manager.SettingsManager.ModuleKey.values()) {
                keys.add(m.getKey());
            }
            return keys;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return Arrays.asList("on", "off", "true", "false");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("watcher")) {
            return Arrays.asList("on", "off", "status");
        }
        return Collections.emptyList();
    }
}
