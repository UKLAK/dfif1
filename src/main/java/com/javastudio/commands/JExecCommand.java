package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.DynamicScript;
import com.javastudio.api.JavaContext;
import com.javastudio.compiler.CompileResult;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Lệnh /jexec và /java cho phép thực thi trực tiếp các câu lệnh Java từ chat Minecraft hoặc Console.
 */
public class JExecCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final File logFile;

    public JExecCommand(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "logs" + File.separator + "exec.log");
        if (!logFile.getParentFile().exists()) {
            logFile.getParentFile().mkdirs();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền thực thi lệnh Java!");
            return true;
        }

        if (!plugin.getSettingsManager().isDirectExecEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Thực thi Java Trực tiếp (/jexec, /java) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle direct-exec");
            return true;
        }

        if (sender instanceof Player p && plugin.getTotpManager() != null) {
            if (plugin.getTotpManager().isTotpEnabled(p.getUniqueId()) && !plugin.getTotpManager().isAuthenticated(p.getUniqueId())) {
                MessageUtil.sendError(p, "YÊU CẦU 2FA: Tài khoản Admin của bạn đã bật xác thực 2 lớp Google Authenticator.");
                MessageUtil.sendWarning(p, "Hãy nhập mã 6 số bằng lệnh: &a/jsecurity totp verify <6_chữ_số> &etrước khi chạy /jexec!");
                return true;
            }
        }

        if (args.length == 0) {
            MessageUtil.sendInfo(sender, "Cách dùng: &e/" + label + " [-a] [-t] <mã Java...>");
            MessageUtil.sendRaw(sender, "  &7Ví dụ 1: &f/" + label + " heal(); give(\"diamond\", 64);");
            MessageUtil.sendRaw(sender, "  &7Ví dụ 2: &f/" + label + " day(); lightning();");
            MessageUtil.sendRaw(sender, "  &7Chế độ REPL tương tác: &f/" + label + " repl");
            MessageUtil.sendRaw(sender, "  &7Hủy tác vụ đang chạy: &f/" + label + " kill <id>");
            MessageUtil.sendRaw(sender, "  &7Xem các tác vụ đang chạy: &f/" + label + " list");
            return true;
        }

        if (args[0].equalsIgnoreCase("kill") || args[0].equalsIgnoreCase("--kill")) {
            if (args.length < 2) {
                MessageUtil.sendError(sender, "Vui lòng nhập ID thực thi! Ví dụ: /" + label + " kill exec-1");
                return true;
            }
            String targetId = args[1];
            boolean killed = com.javastudio.pipeline.ExecutionWatchdog.killExecution(targetId);
            if (killed) {
                MessageUtil.sendSuccess(sender, "Đã ngắt thành công phiên thực thi: &e" + targetId);
            } else {
                MessageUtil.sendError(sender, "Không tìm thấy hoặc phiên thực thi &e" + targetId + " &cđã kết thúc!");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("--list")) {
            var list = com.javastudio.pipeline.ExecutionWatchdog.getActiveExecutions();
            MessageUtil.send(sender, "&6=== DANH SÁCH THỰC THI ĐANG CHẠY (" + list.size() + ") ===");
            if (list.isEmpty()) {
                MessageUtil.sendRaw(sender, "  &7Không có tác vụ /jexec nào đang chạy.");
            } else {
                for (var exec : list) {
                    long ms = (System.nanoTime() - exec.startNano()) / 1_000_000L;
                    MessageUtil.sendRaw(sender, String.format("  &e%s &7[%s] &f%dms &8(%s) &7- &f%s",
                            exec.id(), exec.mainThread() ? "&cMainThread" : "&aAsync", ms, exec.caller(), exec.codeSnippet()));
                }
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("repl")) {
            if (!(sender instanceof Player p)) {
                MessageUtil.sendError(sender, "Chế độ tương tác REPL qua chat chỉ dành cho người chơi trong game!");
                return true;
            }
            if (!plugin.getSettingsManager().isInteractiveReplEnabled()) {
                MessageUtil.sendError(sender, "Chế độ Interactive REPL đang bị tắt trong cài đặt (modules.interactive-repl: false)!");
                return true;
            }
            if (plugin.getReplSession() == null) {
                MessageUtil.sendError(sender, "Hệ thống REPL chưa sẵn sàng!");
                return true;
            }
            if (plugin.getReplSession().isInRepl(p)) {
                plugin.getReplSession().stopRepl(p);
            } else {
                plugin.getReplSession().startRepl(p);
            }
            return true;
        }

        boolean async = false;
        boolean measureTime = false;
        String fileName = null;
        int startIndex = 0;

        for (int i = 0; i < Math.min(3, args.length); i++) {
            if (args[i].equalsIgnoreCase("-a") || args[i].equalsIgnoreCase("--async")) {
                async = true;
                startIndex = i + 1;
            } else if (args[i].equalsIgnoreCase("-t") || args[i].equalsIgnoreCase("--time")) {
                measureTime = true;
                startIndex = i + 1;
            } else if (args[i].equalsIgnoreCase("-f") || args[i].equalsIgnoreCase("--file")) {
                if (i + 1 < args.length) {
                    fileName = args[i + 1];
                    startIndex = i + 2;
                    break;
                }
            }
        }

        // Tự động nhận diện nếu tham số bắt đầu bằng @file hoặc là tên 1 file có trong thư mục scripts/
        if (fileName == null && startIndex < args.length) {
            String candidate = args[startIndex];
            if (candidate.startsWith("@")) {
                fileName = candidate.substring(1);
                startIndex++;
            } else {
                File scriptsDir = plugin.getScriptManager().getScriptsFolder();
                File check = new File(scriptsDir, candidate.endsWith(".java") ? candidate : candidate + ".java");
                if (check.exists()) {
                    fileName = candidate;
                    startIndex++;
                }
            }
        }

        String rawCode;
        String[] scriptArgs = new String[0];

        if (fileName != null) {
            File scriptsDir = plugin.getScriptManager().getScriptsFolder();
            if (!fileName.endsWith(".java")) fileName += ".java";
            File targetFile = new File(scriptsDir, fileName);
            if (!targetFile.exists()) {
                MessageUtil.sendError(sender, "Không tìm thấy file script '" + fileName + "' trong thư mục scripts/!");
                return true;
            }
            try {
                rawCode = java.nio.file.Files.readString(targetFile.toPath(), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (startIndex < args.length) {
                    scriptArgs = Arrays.copyOfRange(args, startIndex, args.length);
                }
            } catch (Exception e) {
                MessageUtil.sendError(sender, "Lỗi đọc file script: " + e.getMessage());
                return true;
            }
        } else {
            if (startIndex >= args.length) {
                MessageUtil.sendError(sender, "Vui lòng nhập đoạn mã Java hoặc dùng -f <tên_file>!");
                return true;
            }
            rawCode = String.join(" ", Arrays.copyOfRange(args, startIndex, args.length)).trim();
        }

        logExecution(sender.getName(), rawCode);

        final boolean isAsync = async;
        final boolean isMeasure = measureTime;
        final String[] fArgs = scriptArgs;

        Runnable task = () -> executeJava(sender, rawCode, isMeasure, fArgs);

        if (isAsync) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }

        return true;
    }

    private void executeJava(CommandSender sender, String rawCode, boolean measureTime, String[] scriptArgs) {
        long startNano = System.nanoTime();

        // 1. Kiểm tra qua Security Sandbox
        String violation = plugin.getSecuritySandbox().validateCode(rawCode);
        if (violation != null) {
            MessageUtil.sendError(sender, violation);
            plugin.getDiscordWebhook().logSecurityViolation(sender.getName(), rawCode, violation);
            return;
        }

        String cleanCode = rawCode.trim();
        String className = "Exec_" + UUID.randomUUID().toString().replace("-", "");
        String fullClassPath = "com.javastudio.runtime." + className;

        if (cleanCode.contains("class ") && cleanCode.contains("{")) {
            String pkg = "com.javastudio.runtime.";
            java.util.regex.Matcher mPkg = java.util.regex.Pattern.compile("package\\s+([a-zA-Z0-9_.]+);").matcher(cleanCode);
            if (mPkg.find()) {
                pkg = mPkg.group(1).trim() + ".";
            }
            java.util.regex.Matcher mCls = java.util.regex.Pattern.compile("(?:public\\s+)?class\\s+([a-zA-Z0-9_]+)").matcher(cleanCode);
            if (mCls.find()) {
                className = mCls.group(1);
            }
            fullClassPath = pkg + className;
        }

        String sourceCode = buildClassSource(className, rawCode);

        CompileResult result = plugin.getCompiler().compileSingleSource(fullClassPath, sourceCode);
        // Tự động nhận diện và sửa lỗi nếu biểu thức là hàm void (không trả về giá trị)
        if (!result.isSuccess() && !cleanCode.contains("class ") && result.getFormattedErrors().contains("'void' type not allowed")) {
            sourceCode = buildClassSource(className, rawCode + ";\n        return null;");
            result = plugin.getCompiler().compileSingleSource(fullClassPath, sourceCode);
        }

        if (!result.isSuccess()) {
            MessageUtil.sendError(sender, "Lỗi cú pháp khi biên dịch mã Java:");
            MessageUtil.sendRaw(sender, result.getFormattedErrors());
            plugin.getDiscordWebhook().logExec(sender.getName(), rawCode, false, "Compile Error");
            return;
        }

        var handle = com.javastudio.pipeline.ExecutionWatchdog.start(sender.getName(), rawCode);
        try {
            Class<?> clazz = result.loadClass(fullClassPath);
            if (!DynamicScript.class.isAssignableFrom(clazz)) {
                MessageUtil.sendError(sender, "Class phải implements DynamicScript hoặc kế thừa QuickScript!");
                return;
            }
            DynamicScript scriptInstance = (DynamicScript) clazz.getDeclaredConstructor().newInstance();

            JavaContext ctx = new JavaContext(sender, plugin, scriptArgs != null ? scriptArgs : new String[0]);
            long timeout = plugin.getConfig().getLong("security.sandbox.max-execution-ms", 5000);
            Object returnedValue = plugin.getSecuritySandbox().executeWithTimeout(() -> scriptInstance.execute(ctx), timeout);

            long elapsedNanos = System.nanoTime() - startNano;
            double elapsedMs = elapsedNanos / 1_000_000.0;

            if (returnedValue != null) {
                MessageUtil.send(sender, "&a➡ Kết quả: &f" + returnedValue);
            } else if (ctx.getOutputs().isEmpty()) {
                MessageUtil.sendSuccess(sender, "Thực thi mã Java thành công!" + (measureTime ? " &7(" + String.format("%.2f", elapsedMs) + "ms)" : ""));
            }

            if (measureTime && !ctx.getOutputs().isEmpty()) {
                MessageUtil.sendInfo(sender, "Thời gian thực thi: " + String.format("%.2f", elapsedMs) + "ms");
            }

            plugin.getDiscordWebhook().logExec(sender.getName(), rawCode, true, returnedValue != null ? String.valueOf(returnedValue) : "Thành công");
        } catch (Throwable t) {
            MessageUtil.sendError(sender, "Lỗi Runtime khi chạy mã: " + t.getMessage());
            plugin.getDiscordWebhook().logExec(sender.getName(), rawCode, false, t.getMessage());
            t.printStackTrace();
        } finally {
            com.javastudio.pipeline.ExecutionWatchdog.stop(handle);
        }
    }

    private String buildClassSource(String className, String userCode) {
        String cleanCode = userCode.trim();

        // Nếu mã nguồn người dùng đã tự định nghĩa một class Java hoàn chỉnh
        if (cleanCode.contains("class ") && cleanCode.contains("{")) {
            if (!cleanCode.contains("package ")) {
                return "package com.javastudio.runtime;\n\n" +
                       "import org.bukkit.*;\nimport org.bukkit.entity.*;\nimport org.bukkit.inventory.*;\nimport org.bukkit.block.*;\nimport org.bukkit.potion.*;\nimport com.javastudio.api.*;\n" +
                       cleanCode;
            }
            return cleanCode;
        }

        boolean isExpression = !cleanCode.contains(";") && !cleanCode.startsWith("return") && !cleanCode.contains("{");

        String executionBody;
        if (isExpression) {
            executionBody = "return String.valueOf(" + cleanCode + ");";
        } else {
            if (!cleanCode.endsWith(";")) {
                cleanCode += ";";
            }
            executionBody = cleanCode;
            if (!cleanCode.contains("return")) {
                executionBody += "\n        return null;";
            }
        }

        return """
                package com.javastudio.runtime;

                import org.bukkit.*;
                import org.bukkit.entity.*;
                import org.bukkit.inventory.*;
                import org.bukkit.event.*;
                import org.bukkit.block.*;
                import org.bukkit.potion.*;
                import org.bukkit.util.*;
                import java.util.*;
                import java.util.stream.*;
                import java.io.*;
                import java.time.*;
                import com.javastudio.api.*;

                public class %CLASS_NAME% extends QuickScript {

                    @Override
                    public Object run() throws Throwable {
                        %BODY%
                    }
                }
                """.replace("%CLASS_NAME%", className).replace("%BODY%", executionBody);
    }

    private void logExecution(String senderName, String code) {
        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw)) {
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            pw.println("[" + time + "] [" + senderName + "] " + code);
        } catch (Exception ignored) {}
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("-a", "-t", "-f", "heal()", "give(\"diamond\", 64)", "day()", "tp(0, 100, 0)", "lightning()", "spawn(\"zombie\", 3)", "nearby(10)", "top()", "god()", "fly()");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("-f") || args[0].equalsIgnoreCase("--file"))) {
            return plugin.getScriptManager().getAvailableScripts();
        }
        return Collections.emptyList();
    }
}
