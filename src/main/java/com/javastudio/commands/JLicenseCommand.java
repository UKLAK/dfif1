package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.export.LicenseManager;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class JLicenseCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final LicenseManager licenseManager;

    public JLicenseCommand(JavaStudioPlugin plugin, LicenseManager licenseManager) {
        this.plugin = plugin;
        this.licenseManager = licenseManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền quản lý bản quyền!");
            return true;
        }

        if (!plugin.getSettingsManager().isLicenseManagerEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Quản lý Bản Quyền (/jlicense) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle license-manager");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            showHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "generate", "gen" -> {
                if (args.length < 3) {
                    MessageUtil.sendError(sender, "Cách dùng: /jlicense generate <plugin> <tên_người_mua> [số_ngày] [ip] [port]");
                    return true;
                }
                String pluginName = args[1];
                String buyer = args[2];
                long days = 0;
                if (args.length >= 4) {
                    try { days = Long.parseLong(args[3]); } catch (Exception ignored) {}
                }
                String boundIp = (args.length >= 5) ? args[4] : "*";
                int boundPort = 0;
                if (args.length >= 6) {
                    try { boundPort = Integer.parseInt(args[5]); } catch (Exception ignored) {}
                }

                String key = licenseManager.generateLicense(pluginName, buyer, days, boundIp, boundPort);
                MessageUtil.sendHeader(sender, "TẠO GIẤY PHÉP BẢN QUYỀN THÀNH CÔNG");
                MessageUtil.sendRaw(sender, "§fPlugin: §b" + pluginName);
                MessageUtil.sendRaw(sender, "§fNgười mua: §e" + buyer);
                MessageUtil.sendRaw(sender, "§fThời hạn: §a" + (days > 0 ? days + " ngày" : "Vĩnh viễn (Lifetime)"));
                MessageUtil.sendRaw(sender, "§fKhóa IP: §7" + boundIp + (boundPort > 0 ? ":" + boundPort : ""));
                MessageUtil.sendRaw(sender, "§6MÃ KHÓA BẢN QUYỀN (LICENSE KEY):");
                MessageUtil.sendRaw(sender, "§d" + key);
                MessageUtil.sendRaw(sender, "§7Dùng: §e/jplugin export " + pluginName + " --license " + key);
            }
            case "verify" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /jlicense verify <license_key> [plugin_name]");
                    return true;
                }
                String key = args[1];
                String expectedPlugin = (args.length >= 3) ? args[2] : null;

                var result = licenseManager.verifyLicense(key, expectedPlugin, null, 0);
                if (result.valid()) {
                    MessageUtil.sendSuccess(sender, "Giấy phép HỢP LỆ: " + result.message());
                    var p = result.payload();
                    if (p != null) {
                        MessageUtil.sendRaw(sender, "§f- Plugin: §b" + p.pluginName());
                        MessageUtil.sendRaw(sender, "§f- Người Mua: §e" + p.buyer());
                        MessageUtil.sendRaw(sender, "§f- Hết Hạn: §a" + p.getExpiryFormatted());
                        MessageUtil.sendRaw(sender, "§f- Ràng Buộc IP: §7" + p.boundIp());
                    }
                } else {
                    MessageUtil.sendError(sender, "Giấy phép KHÔNG HỢP LỆ: " + result.message());
                }
            }
            case "info" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /jlicense info <license_key>");
                    return true;
                }
                var p = licenseManager.parseLicense(args[1]);
                if (p == null) {
                    MessageUtil.sendError(sender, "Không thể giải mã License Key này!");
                    return true;
                }
                MessageUtil.sendHeader(sender, "THÔNG TIN GIẤY PHÉP (DECODED PAYLOAD)");
                MessageUtil.sendRaw(sender, "§f- Plugin Được Phép: §b" + p.pluginName());
                MessageUtil.sendRaw(sender, "§f- Khách Hàng: §e" + p.buyer());
                MessageUtil.sendRaw(sender, "§f- Hạn Sử Dụng: §a" + p.getExpiryFormatted() + (p.isExpired() ? " §c(ĐÃ HẾT HẠN)" : ""));
                MessageUtil.sendRaw(sender, "§f- Giới Hạn IP: §7" + p.boundIp());
                MessageUtil.sendRaw(sender, "§f- Giới Hạn Cổng: §7" + (p.boundPort() > 0 ? p.boundPort() : "Mọi cổng"));
            }
            case "trace", "watermark" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /jlicense trace <tên_file.jar>");
                    return true;
                }
                File exports = new File(plugin.getDataFolder(), "exports");
                File jar = new File(exports, args[1]);
                if (!jar.exists()) {
                    jar = new File(plugin.getDataFolder().getParentFile(), args[1]);
                }
                if (!jar.exists()) {
                    jar = new File(args[1]);
                }

                String report = licenseManager.traceLeakFromJar(jar);
                MessageUtil.sendHeader(sender, "BÁO CÁO TRUY VẾT THỦY VÂN (ANTI-LEAK)");
                for (String line : report.split("\n")) {
                    MessageUtil.sendRaw(sender, "§f" + line);
                }
            }
            default -> showHelp(sender);
        }
        return true;
    }

    private void showHelp(CommandSender sender) {
        MessageUtil.sendHeader(sender, "HỆ THỐNG BẢN QUYỀN THƯƠNG MẠI (/jlicense)");
        MessageUtil.sendRaw(sender, "  §e/jlicense generate <plugin> <buyer> [days] [ip] [port] §7- Tạo key HMAC-SHA256");
        MessageUtil.sendRaw(sender, "  §e/jlicense verify <key> [plugin] §7- Kiểm tra tính hợp lệ & chữ ký");
        MessageUtil.sendRaw(sender, "  §e/jlicense info <key> §7- Đọc thông tin chi tiết người mua");
        MessageUtil.sendRaw(sender, "  §e/jlicense trace <file.jar> §7- Quét tìm thủy vân chống rò rỉ mã nguồn");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filter(Arrays.asList("generate", "verify", "info", "trace", "help"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("generate")) {
            return filter(new ArrayList<>(plugin.getPluginManager().getActivePlugins().keySet()), args[1]);
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("trace") || args[0].equalsIgnoreCase("watermark"))) {
            File exports = new File(plugin.getDataFolder(), "exports");
            if (exports.exists()) {
                File[] jars = exports.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    List<String> list = new ArrayList<>();
                    for (File f : jars) list.add(f.getName());
                    return filter(list, args[1]);
                }
            }
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> input, String prefix) {
        if (prefix == null || prefix.isBlank()) return input;
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String s : input) {
            if (s.toLowerCase().startsWith(lower)) {
                result.add(s);
            }
        }
        return result;
    }
}
