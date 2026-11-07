package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.anticheat.AntiCheatManager;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class JReportCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    public JReportCommand(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public JavaStudioPlugin getPlugin() {
        return plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "Chỉ người chơi trong game mới có thể gửi tố cáo!");
            return true;
        }

        if (!plugin.getSettingsManager().isAntiCheatEnabled() || !manager.isActive() || manager.getReportManager() == null) {
            MessageUtil.sendError(player, "Hệ thống tố cáo hack (/report) đang tạm tắt!");
            return true;
        }

        if (args.length < 2) {
            MessageUtil.sendError(player, "Cách dùng: /" + label + " <người_chơi> <lý do>");
            MessageUtil.sendInfo(player, "Ví dụ: &e/" + label + " Steve Killaura / Fly");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            target = Bukkit.getPlayer(args[0]);
        }
        if (target == null || !target.isOnline()) {
            MessageUtil.sendError(player, "Không tìm thấy người chơi '" + args[0] + "' hoặc người này đang offline!");
            return true;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        manager.getReportManager().submitReport(player, target, reason);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!plugin.getSettingsManager().isAntiCheatEnabled() || !manager.isActive()) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> !name.equalsIgnoreCase(sender.getName()))
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            List<String> reasons = List.of("Killaura", "Fly", "Speed", "Reach", "AutoClicker", "XRay", "Scaffold", "AimAssist");
            String prefix = args[1].toLowerCase();
            return reasons.stream().filter(r -> r.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
