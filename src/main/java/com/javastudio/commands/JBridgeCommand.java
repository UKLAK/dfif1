package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.bridge.RemoteBridgeManager;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Lệnh cầu nối điều hành từ xa & Webhook Discord (/jbridge).
 */
public class JBridgeCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final RemoteBridgeManager bridge;

    public JBridgeCommand(JavaStudioPlugin plugin, RemoteBridgeManager bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền quản lý cầu nối Remote Bridge!");
            return true;
        }

        if (!plugin.getSettingsManager().isRemoteBridgeEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Cầu Nối Từ Xa (/jbridge) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle remote-bridge");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            boolean hasDiscord = plugin.getDiscordWebhook() != null && plugin.getDiscordWebhook().isEnabled();
            MessageUtil.sendHeader(sender, "CẦU NỐI ĐIỀU HÀNH TỪ XA (/jbridge)");
            MessageUtil.sendRaw(sender, "  &7Trạng thái Discord Webhook: " + (hasDiscord ? "&a[KẾT NỐI] ✔" : "&c[CHƯA CẤU HÌNH] ✖"));
            MessageUtil.sendRaw(sender, "  &7Kênh cảnh báo tự động: &eAnti-Cheat, Security Sentinel, Lag Spikes");
            MessageUtil.sendRaw(sender, "  &7Lệnh cứu hộ khẩn cấp: &a/jbridge rescue <gc|lock>");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "test" -> {
                if (plugin.getDiscordWebhook() == null || !plugin.getDiscordWebhook().isEnabled()) {
                    MessageUtil.sendWarning(sender, "Discord Webhook đang tắt hoặc chưa cấu hình URL trong config.yml!");
                    return true;
                }
                plugin.getDiscordWebhook().sendCustomEmbed("🔔 [JavaStudio Test]", "Kiểm tra kết nối cầu nối từ xa thành công bởi: `" + sender.getName() + "`", 0x00BFFF);
                MessageUtil.sendSuccess(sender, "Đã gửi thông báo kiểm tra tới kênh Discord Staff!");
            }
            case "alert" -> {
                if (args.length < 3) {
                    MessageUtil.sendError(sender, "Cú pháp: /" + label + " alert <ac|security|lag> <nội_dung>");
                    return true;
                }
                String type = args[1].toLowerCase();
                String content = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                switch (type) {
                    case "ac" -> {
                        Player p = (sender instanceof Player pl) ? pl : null;
                        if (p != null) bridge.sendAntiCheatAlert(p, "Manual Alert: " + content, 10, 25, 20.0);
                        else MessageUtil.sendError(sender, "Cần là người chơi để thử alert ac!");
                    }
                    case "security" -> bridge.sendSecurityAlert("Manual Bridge", content, "HIGH");
                    case "lag" -> bridge.sendLagSpikeAlert(16.5, 45.2, 850);
                    default -> MessageUtil.sendError(sender, "Loại alert không hợp lệ (hỗ trợ: ac, security, lag)");
                }
                MessageUtil.sendSuccess(sender, "Đã gửi cảnh báo loại &e" + type.toUpperCase() + " &avề Discord!");
            }
            case "rescue" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cú pháp: /" + label + " rescue <gc|lock>");
                    return true;
                }
                boolean ok = bridge.executeRescue(args[1], sender);
                if (!ok) {
                    MessageUtil.sendError(sender, "Hành động cứu hộ không hợp lệ (hỗ trợ: gc, lock)");
                }
            }
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        MessageUtil.sendHeader(sender, "HƯỚNG DẪN CẦU NỐI ĐIỀU HÀNH (/" + label + ")");
        MessageUtil.sendRaw(sender, "  &e/" + label + " status &7- Kiểm tra kết nối Discord & trạng thái");
        MessageUtil.sendRaw(sender, "  &e/" + label + " test &7- Gửi tin nhắn test tới Discord Webhook");
        MessageUtil.sendRaw(sender, "  &e/" + label + " alert <ac|security|lag> <msg> &7- Đẩy cảnh báo thủ công");
        MessageUtil.sendRaw(sender, "  &e/" + label + " rescue <gc|lock> &7- Thực thi tác vụ cứu hộ máy chủ khẩn cấp");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "test", "alert", "rescue");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("alert")) return Arrays.asList("ac", "security", "lag");
            if (sub.equals("rescue")) return Arrays.asList("gc", "lock");
        }
        return Collections.emptyList();
    }
}
