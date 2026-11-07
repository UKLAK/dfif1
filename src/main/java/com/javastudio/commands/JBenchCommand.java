package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.analytics.ServerBenchmarkEngine;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class JBenchCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final ServerBenchmarkEngine engine;

    public JBenchCommand(JavaStudioPlugin plugin, ServerBenchmarkEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            sender.sendMessage("§cBạn không có quyền thực hiện lệnh này!");
            return true;
        }

        if (args.length == 0 || "run".equalsIgnoreCase(args[0])) {
            sender.sendMessage("§e[JavaStudio] Đang chuẩn bị môi trường kiểm thử Benchmark...");
            engine.runBenchmarkAsync(sender, false);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "quick" -> {
                sender.sendMessage("§e[JavaStudio] Đang chạy kiểm thử Benchmark chế độ Siêu Tốc (Quick Mode)...");
                engine.runBenchmarkAsync(sender, true);
            }
            case "report" -> {
                var res = engine.getLatestResult();
                if (res == null) {
                    sender.sendMessage("§7Chưa có bài kiểm tra Benchmark nào được thực hiện kể từ khi khởi động server.");
                    sender.sendMessage("§eGợi ý: Dùng §f/jbench run §eđể bắt đầu bài đo kiểm đầu tiên!");
                    return true;
                }
                engine.printBenchmarkReport(sender, res);
            }
            case "info", "system" -> {
                Runtime rt = Runtime.getRuntime();
                long maxRam = rt.maxMemory() / (1024 * 1024);
                long totalRam = rt.totalMemory() / (1024 * 1024);
                long usedRam = totalRam - (rt.freeMemory() / (1024 * 1024));
                sender.sendMessage("§6§l=== THÔNG TIN PHẦN CỨNG & JVM MÁY CHỦ ===");
                sender.sendMessage("§7• Hệ điều hành: §f" + System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")");
                sender.sendMessage("§7• Số lượng vCPU Cores: §e" + rt.availableProcessors() + " Cores");
                sender.sendMessage("§7• Bộ nhớ Heap: §f" + usedRam + " MB §7/ §b" + maxRam + " MB (Tối đa)");
                sender.sendMessage("§7• Phiên bản Java: §a" + System.getProperty("java.version") + " §7(" + System.getProperty("java.vendor") + ")");
                sender.sendMessage("§7• Server Software: §d" + com.javastudio.utils.PlatformUtil.getPlatformName() + " " + com.javastudio.utils.PlatformUtil.getMinecraftVersion());
                sender.sendMessage("§7• Project Loom Virtual Threads: " + (com.javastudio.utils.PlatformUtil.isVirtualThreadsSupported() ? "§aCó (Hỗ trợ)" : "§cKhông"));
                sender.sendMessage("§eNhập §f/jbench run §eđể đo lường và chấm điểm hiệu năng thực tế!");
            }
            default -> {
                sender.sendMessage("§cCú pháp: /jbench [run|quick|report|info]");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return List.of("run", "quick", "report", "info").stream().filter(s -> s.startsWith(p)).toList();
        }
        return Collections.emptyList();
    }
}

