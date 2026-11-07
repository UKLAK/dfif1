package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.JavaContext;
import com.javastudio.pipeline.VisualFlowEngine;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;

public class JFlowCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;

    public JFlowCommand(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) {
            sender.sendMessage("§cBạn không có quyền thực hiện lệnh này!");
            return true;
        }

        if (!plugin.getSettingsManager().isVisualFlowEnabled()) {
            sender.sendMessage("§cPhân hệ Visual Logic Flow (/jflow) đang bị TẮT trong cài đặt!");
            sender.sendMessage("§7Bật lại bằng lệnh: §e/javastudio toggle visual-flow §7hoặc qua §e/javastudio settings");
            return true;
        }

        VisualFlowEngine flowEngine = plugin.getVisualFlowEngine();
        if (flowEngine == null) {
            sender.sendMessage("§cVisual Flow Engine chưa được khởi tạo!");
            return true;
        }

        if (args.length == 0 || "list".equalsIgnoreCase(args[0])) {
            sender.sendMessage("§6§l=== VISUAL LOGIC FLOWS (NO-CODE SCRIPTS) ===");
            var flows = flowEngine.getLoadedFlows();
            if (flows.isEmpty()) {
                sender.sendMessage("§7Chưa có flow nào. Hãy tạo flow mới trong thư mục flows/ hoặc qua Web Dashboard :8085");
                return true;
            }
            for (var f : flows) {
                sender.sendMessage("§e• §a" + f.name() + " §7(ID: §f" + f.id() + "§7, Trigger: §b" + f.trigger()
                        + "§7, Conds: §e" + f.conditions().size() + "§7, Actions: §d" + f.actions().size() + "§7)");
            }
            sender.sendMessage("§7Dùng §f/jflow run <id> §7để chạy thử nghiệm trực tiếp.");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "run" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cCú pháp: /jflow run <id>");
                    return true;
                }
                String id = args[1];
                JavaContext ctx = new JavaContext(sender, plugin, new String[0]);
                StringBuilder log = new StringBuilder();
                boolean ok = flowEngine.executeFlow(id, ctx, log);
                if (ok) {
                    sender.sendMessage("§a✔ Flow [" + id + "] thực thi thành công: §f" + log);
                } else {
                    sender.sendMessage("§c✖ Thực thi Flow [" + id + "] thất bại: §e" + log);
                }
            }
            case "reload" -> {
                flowEngine.reloadAllFlows();
                sender.sendMessage("§a✔ Đã nạp lại và biên dịch toàn bộ " + flowEngine.getLoadedFlows().size() + " Visual Flows!");
            }
            default -> {
                sender.sendMessage("§cCú pháp: /jflow [list|run <id>|reload]");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("list", "run", "reload");
        }
        if (args.length == 2 && "run".equalsIgnoreCase(args[0])) {
            VisualFlowEngine flowEngine = plugin.getVisualFlowEngine();
            if (flowEngine != null) {
                List<String> ids = new ArrayList<>();
                for (var f : flowEngine.getLoadedFlows()) {
                    ids.add(f.id());
                }
                return ids;
            }
        }
        return Collections.emptyList();
    }
}

