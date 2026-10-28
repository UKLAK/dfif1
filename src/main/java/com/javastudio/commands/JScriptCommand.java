package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.JavaContext;
import com.javastudio.manager.ScriptManager;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.IOException;
import java.util.*;

/**
 * Lệnh /jscript để quản lý và thực thi các file Java script độc lập.
 */
public class JScriptCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final ScriptManager scriptManager;

    public JScriptCommand(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.scriptManager = plugin.getScriptManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền quản lý script!");
            return true;
        }

        if (!plugin.getSettingsManager().isScriptManagerEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Quản lý Script (/jscript) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle script-manager");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "list" -> {
                List<String> scripts = scriptManager.getAvailableScripts();
                if (scripts.isEmpty()) {
                    MessageUtil.sendInfo(sender, "Chưa có script nào trong thư mục scripts/.");
                    return true;
                }
                MessageUtil.send(sender, "&6=== Danh sách Script Java (" + scripts.size() + ") ===");
                for (String s : scripts) {
                    MessageUtil.sendRaw(sender, "  &a• &e" + s + ".java &7(chạy: &f/" + label + " run " + s + "&7)");
                }
            }
            case "run" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " run <tên_script> [tham số...]");
                    return true;
                }
                String scriptName = args[1];
                String[] scriptArgs = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : new String[0];
                JavaContext ctx = new JavaContext(sender, plugin, scriptArgs);

                StringBuilder errorLog = new StringBuilder();
                try {
                    MessageUtil.sendInfo(sender, "Đang biên dịch và chạy script &e" + scriptName + "&7...");
                    Object result = scriptManager.runScript(scriptName, ctx, errorLog);
                    if (errorLog.length() > 0) {
                        MessageUtil.sendError(sender, "Lỗi khi chạy script " + scriptName + ":");
                        MessageUtil.sendRaw(sender, errorLog.toString());
                    } else {
                        if (result != null) {
                            MessageUtil.sendSuccess(sender, "Kết quả trả về: &f" + result);
                        } else {
                            MessageUtil.sendSuccess(sender, "Script &e" + scriptName + " &ađã chạy xong!");
                        }
                    }
                } catch (Throwable t) {
                    MessageUtil.sendError(sender, "Ngoại lệ khi chạy script: " + t.getMessage());
                    t.printStackTrace();
                }
            }
            case "new", "create" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " new <tên_script>");
                    return true;
                }
                String scriptName = args[1];
                try {
                    boolean created = scriptManager.createScript(scriptName);
                    if (created) {
                        MessageUtil.sendSuccess(sender, "Đã tạo file script &e" + scriptName + ".java &atrong thư mục scripts/!");
                    } else {
                        MessageUtil.sendError(sender, "File script " + scriptName + ".java đã tồn tại!");
                    }
                } catch (IOException e) {
                    MessageUtil.sendError(sender, "Không thể tạo file script: " + e.getMessage());
                }
            }
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        MessageUtil.send(sender, "&6=== Hướng dẫn Lệnh /" + label + " ===");
        MessageUtil.sendRaw(sender, "  &e/" + label + " list &7- Xem danh sách scripts");
        MessageUtil.sendRaw(sender, "  &e/" + label + " run <tên> [args...] &7- Chạy một script Java");
        MessageUtil.sendRaw(sender, "  &e/" + label + " new <tên> &7- Tạo file script mới từ mẫu");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("list", "run", "new");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("run")) {
            return scriptManager.getAvailableScripts();
        }
        return Collections.emptyList();
    }
}
