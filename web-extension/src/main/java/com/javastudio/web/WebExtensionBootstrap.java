package com.javastudio.web;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.anticheat.AntiCheatManager;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class WebExtensionBootstrap implements WebExtensionController {

    private final JavaPlugin webPlugin;
    private final JavaStudioPlugin corePlugin;
    private AcWebDashboard dashboard;

    public WebExtensionBootstrap(JavaPlugin webPlugin, Plugin core) {
        this.webPlugin = webPlugin;
        this.corePlugin = (JavaStudioPlugin) core;
    }

    @Override
    public void enable() {
        AntiCheatManager manager = corePlugin.getAntiCheatManager();
        if (manager == null) {
            throw new IllegalStateException("AntiCheatManager chua san sang trong JavaStudio");
        }
        this.dashboard = new AcWebDashboard(webPlugin, corePlugin, manager);
        manager.setWebDashboard(this.dashboard);
        if (corePlugin.getSettingsManager() == null || corePlugin.getSettingsManager().isWebIdeEnabled()) {
            this.dashboard.start();
        }
    }

    @Override
    public void disable() {
        AcWebDashboard current = this.dashboard;
        this.dashboard = null;
        if (current != null) {
            try {
                current.stop();
            } catch (Throwable ignored) {}
        }
        try {
            AntiCheatManager manager = corePlugin.getAntiCheatManager();
            if (manager != null && manager.getWebDashboard() == current) {
                manager.setWebDashboard(null);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean handleCommand(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền quản lý Web Dashboard (cần javastudio.admin)!");
            return true;
        }
        if (dashboard == null) {
            MessageUtil.sendError(sender, "Web Dashboard hiện chưa sẵn sàng!");
            return true;
        }
        Player viewer = sender instanceof Player p ? p : null;
        if (args.length == 0 || args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("status")) {
            boolean running = dashboard.isRunning();
            boolean singlePort = dashboard.isSinglePortActive();
            String url = dashboard.getPublicAccessUrl(viewer);
            String token = dashboard.getAuthToken();
            var mux = corePlugin.getSinglePortMuxEngine();
            sender.sendMessage("§b§l=== JAVASTUDIO WEB EXTENSION ===");
            sender.sendMessage(" §8» §7Trạng thái: " + (running ? "§a§lĐANG CHẠY" : "§c§lĐÃ DỪNG"));
            sender.sendMessage(" §8» §7Môi trường: §f" + com.javastudio.network.SinglePortMuxEngine.detectEnvironmentLabel());
            sender.sendMessage(" §8» §7Chế độ cổng: " + (singlePort
                    ? "§a§lPANEL 1-PORT §7(Đi chung cổng Minecraft §e:" + Bukkit.getPort() + " §8-> §7nội bộ §f127.0.0.1:" + dashboard.getInternalPort() + "§7)"
                    : "§e§lCỔNG ĐỘC LẬP §7(:" + dashboard.getInternalPort() + ")"));
            if (mux != null && singlePort) {
                sender.sendMessage(" §8» §7Lưu lượng HTTP qua cổng chính: §a" + mux.getTotalHttpRequests() + " request");
            }
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
            sender.sendMessage(" §8» §7Điều khiển: §e/" + label + " [info|start|stop|restart|reload]");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                dashboard.start();
                MessageUtil.sendSuccess(sender, "Đã khởi động Web Dashboard tại: §b" + dashboard.getPublicAccessUrl(viewer));
            }
            case "stop" -> {
                dashboard.stop();
                MessageUtil.sendWarning(sender, "Đã dừng dịch vụ Web Dashboard.");
            }
            case "restart" -> {
                dashboard.stop();
                dashboard.start();
                MessageUtil.sendSuccess(sender, "Đã khởi động lại Web Dashboard tại: §b" + dashboard.getPublicAccessUrl(viewer));
            }
            case "reload" -> {
                webPlugin.reloadConfig();
                dashboard.loadConfig();
                if (dashboard.isRunning()) {
                    dashboard.stop();
                    dashboard.start();
                }
                MessageUtil.sendSuccess(sender, "Đã nạp lại cấu hình của JavaStudio-Web!");
            }
            default -> MessageUtil.sendError(sender, "Cú pháp: /" + label + " [info|start|stop|restart|reload]");
        }
        return true;
    }

    @Override
    public List<String> handleTabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return Arrays.asList("info", "start", "stop", "restart", "reload")
                    .stream()
                    .filter(s -> s.startsWith(p))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
