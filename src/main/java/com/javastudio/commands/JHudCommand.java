package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.analytics.DebugHudManager;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Lệnh bật/tắt thanh Debug HUD thời gian thực (/jhud).
 */
public final class JHudCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final DebugHudManager hudManager;

    public JHudCommand(JavaStudioPlugin plugin, DebugHudManager hudManager) {
        this.plugin = plugin;
        this.hudManager = hudManager;
    }

    public JavaStudioPlugin getPlugin() {
        return plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền xem Debug HUD!");
            return true;
        }

        if (com.javastudio.JavaStudioPlugin.getInstance() != null && !com.javastudio.JavaStudioPlugin.getInstance().getSettingsManager().isDebugHudEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Debug HUD (/jhud) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle debug-hud");
            return true;
        }

        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "Lệnh này chỉ có thể sử dụng bởi người chơi trong game (In-Game)!");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("toggle")) {
            DebugHudManager.HudMode next = hudManager.toggleMode(player);
            MessageUtil.sendSuccess(player, "Debug HUD đã chuyển sang chế độ: §e§l" + next.name());
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "actionbar", "bar" -> {
                hudManager.setMode(player, DebugHudManager.HudMode.ACTIONBAR);
                MessageUtil.sendSuccess(player, "Đã bật Debug HUD trên thanh §e§lAction Bar§a!");
            }
            case "bossbar", "boss" -> {
                hudManager.setMode(player, DebugHudManager.HudMode.BOSSBAR);
                MessageUtil.sendSuccess(player, "Đã bật Debug HUD trên thanh §e§lBoss Bar§a!");
            }
            case "off", "stop", "hide" -> {
                hudManager.setMode(player, DebugHudManager.HudMode.OFF);
                MessageUtil.sendInfo(player, "Đã tắt Debug HUD.");
            }
            default -> {
                player.sendMessage("§6§l=== LỆNH DEBUG HUD THỜI GIAN THỰC ===");
                player.sendMessage(" §8» §e/" + label + " toggle §7- Chuyển đổi qua lại giữa Action Bar / Boss Bar / Tắt");
                player.sendMessage(" §8» §e/" + label + " actionbar §7- Hiển thị trên thanh Action Bar (Hotbar)");
                player.sendMessage(" §8» §e/" + label + " bossbar §7- Hiển thị trên thanh Boss Bar đầu màn hình");
                player.sendMessage(" §8» §e/" + label + " off §7- Tắt Debug HUD");
            }
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return List.of("toggle", "actionbar", "bossbar", "off").stream()
                    .filter(s -> s.startsWith(input))
                    .toList();
        }
        return Collections.emptyList();
    }
}
