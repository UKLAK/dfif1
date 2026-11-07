package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.ai.AiAssistant;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

                MessageUtil.sendInfo(sender, "Đang gửi yêu cầu tới AI (" + ai.getProvider() + " / " + ai.getModel() + ") để sinh plugin &e" + pluginName + "&7...");

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
            case "script", "makescript" -> {
                if (args.length < 3) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " script <TênScript> <Mô tả tính năng...>");
                    return true;
                }
                String scriptName = args[1];
                StringBuilder prompt = new StringBuilder();
                for (int i = 2; i < args.length; i++) prompt.append(args[i]).append(" ");

                MessageUtil.sendInfo(sender, "Đang tạo file script &e" + scriptName + ".java &7qua AI...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    AiAssistant.AiResult res = ai.generateScript(scriptName, prompt.toString().trim());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (res.success()) {
                            MessageUtil.sendSuccess(sender, res.message());
                        } else {
                            MessageUtil.sendError(sender, "Lỗi tạo script: " + res.message());
                        }
                    });
                });
            }
            case "optimize", "opt" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " optimize <TênPlugin>");
                    return true;
                }
                String pName = args[1];
                MessageUtil.sendInfo(sender, "AI đang phân tích và tối ưu hóa mã nguồn cho plugin &e" + pName + "&7...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    AiAssistant.AiResult res = ai.optimizePlugin(pName);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (res.success()) {
                            MessageUtil.sendSuccess(sender, res.message());
                        } else {
                            MessageUtil.sendError(sender, res.message());
                        }
                    });
                });
            }
            case "fix", "heal", "autofix" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " autofix <TênPlugin>");
                    return true;
                }
                String pName = args[1];
                MessageUtil.sendInfo(sender, "Đang kích hoạt AI Self-Healing Loop để phân tích và tự sửa lỗi cho &e" + pName + "&7...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    AiAssistant.AiResult res = ai.fixPlugin(pName);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (res.success()) {
                            MessageUtil.sendSuccess(sender, res.message());
                        } else {
                            MessageUtil.sendError(sender, res.message());
                        }
                    });
                });
            }
            case "provider" -> {
                if (args.length < 2) {
                    MessageUtil.sendInfo(sender, "Provider hiện tại: &e" + ai.getProvider() + " &7| Endpoint: &f" + ai.getEndpoint());
                    MessageUtil.sendRaw(sender, "  &7Chuyển đổi: &e/" + label + " provider <openai|gemini|ollama|claude>");
                    return true;
                }
                String newP = args[1].toLowerCase();
                ai.setProvider(newP);
                MessageUtil.sendSuccess(sender, "Đã chuyển AI Provider sang: &e" + newP.toUpperCase() + " &7(Model: &f" + ai.getModel() + "&7, Endpoint: &f" + ai.getEndpoint() + "&7)");
            }
            case "model" -> {
                if (args.length < 2) {
                    MessageUtil.sendInfo(sender, "Model hiện tại: &e" + ai.getModel());
                    MessageUtil.sendRaw(sender, "  &7Đổi model: &e/" + label + " model <tên_model>");
                    return true;
                }
                ai.setModel(args[1]);
                MessageUtil.sendSuccess(sender, "Đã đổi AI Model thành: &e" + args[1]);
            }
            case "ask", "chat" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " ask <Câu hỏi thắc mắc...>");
                    return true;
                }
                StringBuilder q = new StringBuilder();
                for (int i = 1; i < args.length; i++) q.append(args[i]).append(" ");

                MessageUtil.sendInfo(sender, "Đang tham vấn AI Copilot (" + ai.getModel() + ")...");
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
            case "watchdog" -> {
                var wd = plugin.getAutonomousWatchdog();
                if (wd == null) {
                    MessageUtil.sendError(sender, "Autonomous AI Watchdog chưa được khởi tạo!");
                    return true;
                }
                if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
                    MessageUtil.send(sender, "&6=== Autonomous AI Watchdog Agent ===");
                    MessageUtil.sendRaw(sender, "  &eTrạng thái: " + (wd.isEnabled() ? "&aĐang hoạt động" : "&cĐã tắt"));
                    MessageUtil.sendRaw(sender, "  &eTự động ứng cứu (Auto-Mitigate): " + (wd.isAutoMitigate() ? "&aBật" : "&cTắt"));
                    MessageUtil.sendRaw(sender, "  &eSự cố đã ghi nhận: &f" + wd.getIncidentHistory().size());
                    MessageUtil.sendRaw(sender, "  &7Lệnh: &f/jai watchdog [diagnose|mitigate on/off|incidents]");
                    return true;
                }
                String subAction = args[1].toLowerCase();
                if (subAction.equals("diagnose") || subAction.equals("dump")) {
                    MessageUtil.sendInfo(sender, "Đang kích hoạt Autonomous AI Watchdog để kiểm tra Thread Dump ngay...");
                    wd.triggerIncident(20.0);
                    MessageUtil.sendSuccess(sender, "Đã khởi động quy trình chuẩn đoán RCA ngầm!");
                } else if (subAction.equals("mitigate")) {
                    if (args.length >= 3) {
                        boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
                        wd.setAutoMitigate(on);
                        MessageUtil.sendSuccess(sender, "Đã đặt Auto-Mitigate thành: &e" + on);
                    } else {
                        MessageUtil.sendInfo(sender, "Auto-Mitigate hiện tại: &e" + wd.isAutoMitigate());
                    }
                } else if (subAction.equals("incidents")) {
                    var list = wd.getIncidentHistory();
                    MessageUtil.send(sender, "&6=== Lịch Sử Sự Cố Watchdog (" + list.size() + ") ===");
                    if (list.isEmpty()) {
                        MessageUtil.sendRaw(sender, "  &7Chưa ghi nhận sự cố giật lag nghiêm trọng nào.");
                    } else {
                        for (var inc : list) {
                            MessageUtil.sendRaw(sender, String.format("  &e%s &7[TPS: %.2f] - &f%s &8(Ứng cứu: %s)",
                                    inc.id(), inc.tps(), inc.culpritLocation(), inc.autoMitigated() ? "&aCó" : "&7Không"));
                        }
                    }
                }
            }
            case "status", "info" -> {
                MessageUtil.send(sender, "&6=== Trạng Thái AI Copilot ===");
                MessageUtil.sendRaw(sender, "  &eProvider: &f" + ai.getProvider());
                MessageUtil.sendRaw(sender, "  &eModel: &f" + ai.getModel());
                MessageUtil.sendRaw(sender, "  &eEndpoint: &f" + ai.getEndpoint());
                MessageUtil.sendRaw(sender, "  &eĐã cấu hình: " + (ai.isConfigured() ? "&a✔ Hoạt động" : "&c✖ Chưa có API Key"));
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
        MessageUtil.sendRaw(sender, "  &e/" + label + " script <tên> <mô tả> &7- Tự động sinh file script Java (.java)");
        MessageUtil.sendRaw(sender, "  &e/" + label + " fix <plugin> &7- Tự động sửa lỗi cú pháp (Self-Healing)");
        MessageUtil.sendRaw(sender, "  &e/" + label + " optimize <plugin> &7- AI tối ưu hóa hiệu năng & Paper/Folia API");
        MessageUtil.sendRaw(sender, "  &e/" + label + " provider <tên> &7- Chuyển provider (openai, gemini, ollama, claude)");
        MessageUtil.sendRaw(sender, "  &e/" + label + " model <tên> &7- Đổi model (gpt-4o, gemini-1.5-flash, llama3, ...)");
        MessageUtil.sendRaw(sender, "  &e/" + label + " ask <câu hỏi> &7- Tham vấn AI về Bukkit API hoặc giải thích code");
        MessageUtil.sendRaw(sender, "  &e/" + label + " watchdog &7- Quản trị Đặc vụ Tự trị AI Giám sát & Ứng cứu TPS");
        MessageUtil.sendRaw(sender, "  &e/" + label + " status &7- Kiểm tra trạng thái kết nối AI");
        MessageUtil.sendRaw(sender, "  &e/" + label + " reload &7- Nạp lại cấu hình config.yml");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("generate", "script", "fix", "autofix", "optimize", "provider", "model", "ask", "watchdog", "status", "reload");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("watchdog")) {
                return Arrays.asList("status", "diagnose", "mitigate", "incidents");
            }
            if (sub.equals("provider")) {
                return Arrays.asList("openai", "gemini", "ollama", "claude", "deepseek");
            }
            if (sub.equals("fix") || sub.equals("heal") || sub.equals("autofix") || sub.equals("optimize")) {
                return new ArrayList<>(plugin.getPluginManager().getActivePlugins().keySet());
            }
        }
        return Collections.emptyList();
    }
}
