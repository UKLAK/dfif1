package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.ai.AiAssistant;
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
 * Lệnh tương tác với Trợ lý Lập trình AI (/jai).
 */
public class JAiCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final AiAssistant ai;

    public JAiCommand(JavaStudioPlugin plugin, AiAssistant ai) {
        this.plugin = plugin;
        this.ai = ai;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền sử dụng trợ lý AI!");
            return true;
        }

        if (!plugin.getSettingsManager().isAiAssistantEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Trợ lý Lập trình AI (/jai) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle ai-assistant");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "generate", "create", "make" -> {
                if (args.length < 3) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " generate <TênPlugin> <Mô tả tính năng...>");
                    return true;
                }
                String pluginName = args[1];
                StringBuilder prompt = new StringBuilder();
                for (int i = 2; i < args.length; i++) prompt.append(args[i]).append(" ");

                MessageUtil.sendInfo(sender, "Đang gửi yêu cầu tới AI Assistant để sinh mã cho &e" + pluginName + "&7...");
                MessageUtil.sendInfo(sender, "Yêu cầu: &f" + prompt.toString().trim());

                // Chạy bất đồng bộ để không chặn luồng server
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    AiAssistant.AiResult res = ai.generatePlugin(pluginName, prompt.toString().trim());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (res.success()) {
                            MessageUtil.sendSuccess(sender, res.message());
                        } else {
                            MessageUtil.sendError(sender, "AI không thể hoàn tất plugin: " + res.message());
                        }
                    });
                });
            }
            case "ask", "chat" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " ask <Câu hỏi thắc mắc...>");
                    return true;
                }
                StringBuilder q = new StringBuilder();
                for (int i = 1; i < args.length; i++) q.append(args[i]).append(" ");

                MessageUtil.sendInfo(sender, "Đang tham vấn AI Copilot...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    String answer = ai.askQuestion(q.toString().trim());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        MessageUtil.send(sender, "&6=== Phản Hồi Từ AI Copilot ===");
                        for (String line : answer.split("\n")) {
                            MessageUtil.sendRaw(sender, "  &f" + line);
                        }
                    });
                });
            }
            case "reload" -> {
                ai.reload();
                MessageUtil.sendSuccess(sender, "Đã nạp lại cấu hình AI Assistant từ config.yml!");
            }
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        MessageUtil.send(sender, "&6=== JavaStudio AI Code Assistant (/" + label + ") ===");
        MessageUtil.sendRaw(sender, "  &e/" + label + " generate <tên> <mô tả> &7- Tự động sinh mã & nạp dynamic plugin");
        MessageUtil.sendRaw(sender, "  &e/" + label + " ask <câu hỏi> &7- Tham vấn AI về Bukkit API hoặc giải thích code");
        MessageUtil.sendRaw(sender, "  &e/" + label + " reload &7- Nạp lại cài đặt API Key & Model");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("generate", "ask", "reload");
        }
        return Collections.emptyList();
    }
}
