package com.javastudio.commands;

import com.javastudio.pipeline.CircuitBreakerEngine;
import com.javastudio.pipeline.CircuitBreakerEngine.BreakerNode;
import com.javastudio.pipeline.CircuitBreakerEngine.BreakerState;
import com.javastudio.pipeline.CircuitBreakerEngine.CrashSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.stream.Collectors;

public final class JTraceCommand implements CommandExecutor, TabCompleter {

    private final CircuitBreakerEngine engine;

    public JTraceCommand(CircuitBreakerEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("javastudio.admin")) {
            sender.sendMessage("§cBạn không có quyền thực thi lệnh này.");
            return true;
        }

        if (com.javastudio.JavaStudioPlugin.getInstance() != null && !com.javastudio.JavaStudioPlugin.getInstance().getSettingsManager().isCircuitBreakerEnabled()) {
            sender.sendMessage("§cTính năng Cầu Chì Circuit Breaker (/jtrace, /jt, /jbreaker) đang bị tắt trong cài đặt!");
            sender.sendMessage("§7Bật lại bằng lệnh: §e/javastudio toggle circuit-breaker");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "hook" -> handleHook(sender, args);
            case "watch" -> handleWatch(sender, args);
            case "unwatch", "stop" -> handleUnwatch(sender);
            case "dump" -> handleDump(sender, args);
            case "breakers", "status", "list" -> handleBreakers(sender, args);
            case "reset" -> handleReset(sender, args);
            case "config" -> handleConfig(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleHook(CommandSender sender, String[] args) {
        Plugin target = null;
        if (args.length >= 2 && !args[1].equalsIgnoreCase("all")) {
            target = Bukkit.getPluginManager().getPlugin(args[1]);
            if (target == null) {
                sender.sendMessage("§cKhông tìm thấy plugin: §e" + args[1]);
                return;
            }
        }
        int count = engine.instrumentPlugin(target);
        sender.sendMessage("§a[JTrace] §fĐã gắn cầu chì bảo vệ và bộ đo nano-giây cho §e" + count + " §fListeners.");
    }

    private void handleWatch(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cLệnh watch trực tiếp chỉ dành cho người chơi trong game.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cCú pháp: /jtrace watch <TênPlugin|TênSựKiện|all> [min-ms]");
            return;
        }

        String keyword = args[1];
        double minMs = 0.0;
        if (args.length >= 3) {
            try {
                minMs = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cNgưỡng mili-giây không hợp lệ.");
                return;
            }
        }

        engine.instrumentPlugin(null);
        engine.startWatching(player.getUniqueId(), keyword, minMs);

        sender.sendMessage("§a[JTrace] §fĐang theo dõi thời gian thực với bộ lọc §e'" + keyword
                + "' §f(Chỉ hiện khi thời gian chạy từ §b" + minMs + "ms §ftrở lên).");
        sender.sendMessage("§7Gõ §f/jtrace stop §7để dừng theo dõi.");
    }

    private void handleUnwatch(CommandSender sender) {
        if (sender instanceof Player player && engine.stopWatching(player.getUniqueId())) {
            sender.sendMessage("§e[JTrace] §fĐã tắt chế độ theo dõi thời gian thực.");
        } else {
            sender.sendMessage("§7Bạn hiện không bật phiên theo dõi nào.");
        }
    }

    private void handleDump(CommandSender sender, String[] args) {
        String targetId = args.length >= 2 ? args[1] : "latest";

        if (targetId.equalsIgnoreCase("list")) {
            List<CrashSnapshot> recent = engine.getRecentCrashes(10);
            if (recent.isEmpty()) {
                sender.sendMessage("§a[JTrace] §fHiện chưa có bản ghi lỗi nào trong Hộp đen.");
                return;
            }
            sender.sendMessage("§8§m----------------§r §b§lHỘP ĐEN NGOẠI LỆ GẦN NHẤT §8§m----------------");
            for (CrashSnapshot s : recent) {
                sender.sendMessage(String.format(" §e%s §7[%s] §f%s §8(§7%s§8) §c%s",
                        s.id, s.timestamp, s.nodeId, s.eventName, s.exceptionType));
            }
            sender.sendMessage("§7Gõ §b/jtrace dump <Mã_Lỗi> §7để soi giá trị biến trong RAM lúc xảy ra lỗi.");
            return;
        }

        CrashSnapshot snap = engine.getCrashSnapshot(targetId);
        if (snap == null) {
            sender.sendMessage("§cKhông tìm thấy dữ liệu Hộp đen cho mã: §e" + targetId);
            return;
        }

        sender.sendMessage("§8§m================§r §c§lHỘP ĐEN: " + snap.id + " §8§m================");
        sender.sendMessage("§7▪ Thời điểm: §f" + snap.timestamp + " §8| §7Node: §e" + snap.nodeId);
        sender.sendMessage("§7▪ Sự kiện: §b" + snap.eventName);
        sender.sendMessage("§7▪ Ngoại lệ: §c" + snap.exceptionType + ": §f" + snap.exceptionMessage);
        sender.sendMessage("§7▪ Trạng thái Biến của Listener trong RAM lúc lỗi:");
        snap.listenerFields.forEach((k, v) -> sender.sendMessage("   §e• " + k + " §7= §f" + v));
        sender.sendMessage("§7▪ Trạng thái Biến của Event lúc lỗi:");
        snap.eventFields.forEach((k, v) -> sender.sendMessage("   §b• " + k + " §7= §f" + v));
        sender.sendMessage("§7▪ Ngăn xếp lệnh (Stacktrace rút gọn):\n" + snap.shortStackTrace);
    }

    private void handleBreakers(CommandSender sender, String[] args) {
        Collection<BreakerNode> all = engine.getAllBreakers();
        if (all.isEmpty()) {
            engine.instrumentPlugin(null);
            all = engine.getAllBreakers();
        }

        List<BreakerNode> sorted = all.stream()
                .sorted(Comparator.comparing((BreakerNode n) -> n.state != BreakerState.OPEN)
                        .thenComparing(Comparator.comparingDouble(BreakerNode::getMaxMs).reversed()))
                .limit(15)
                .toList();

        sender.sendMessage("§8§m------------§r §6§lTRẠNG THÁI CẦU CHÌ (CIRCUIT BREAKERS) §8§m------------");
        for (BreakerNode n : sorted) {
            String stateBadge = switch (n.state) {
                case CLOSED -> "§a[ĐÓNG/OK]";
                case OPEN -> "§c§l[NGẮT MẠCH]";
                case HALF_OPEN -> "§e[THỬ PHỤC HỒI]";
            };
            sender.sendMessage(String.format(Locale.US, "%s §e%s §7(Calls: §f%d §7| Avg: §b%.3fms §7| Max: §6%.2fms §7| Err: §c%d§7)",
                    stateBadge, n.id, n.totalCalls.get(), n.getAverageMs(), n.getMaxMs(), n.totalErrors.get()));
            if (n.state == BreakerState.OPEN) {
                sender.sendMessage("   §c↳ Lý do ngắt: §f" + n.tripReason + " §8(Đã chặn " + n.skippedCalls.get() + " lần gọi)");
            }
        }
    }

    private void handleReset(CommandSender sender, String[] args) {
        String target = args.length >= 2 ? args[1] : "all";
        int count = engine.resetBreaker(target);
        sender.sendMessage("§a[JTrace] §fĐã đóng lại cầu chì và khôi phục hoạt động cho §e" + count + " §fNodes.");
    }

    private void handleConfig(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§eCấu hình hiện tại: §fMax-Errors/s = §c" + engine.getMaxErrorsPerSecond()
                    + " §8| §fMax-MSPT = §c" + engine.getMaxExecutionMs() + "ms");
            sender.sendMessage("§7Đổi cấu hình: §f/jtrace config <max-errors|max-ms> <giá_trị>");
            return;
        }
        try {
            if (args[1].equalsIgnoreCase("max-errors")) {
                engine.setMaxErrorsPerSecond(Integer.parseInt(args[2]));
                sender.sendMessage("§aĐã đặt ngưỡng ngắt mạch lỗi tối đa: §e" + engine.getMaxErrorsPerSecond() + " lỗi/giây.");
            } else if (args[1].equalsIgnoreCase("max-ms")) {
                engine.setMaxExecutionMs(Double.parseDouble(args[2]));
                sender.sendMessage("§aĐã đặt ngưỡng ngắt mạch thời gian chạy: §e" + engine.getMaxExecutionMs() + " ms.");
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cGiá trị số không hợp lệ.");
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§8§m----------------§r §b§lJAVASTUDIO TRACE & BREAKER §8§m----------------");
        sender.sendMessage(" §b/jtrace hook [plugin|all] §7- Gắn cầu chì bảo vệ vào các Listeners");
        sender.sendMessage(" §b/jtrace watch <từ_khóa|all> [min-ms] §7- Theo dõi hàm/sự kiện thời gian thực");
        sender.sendMessage(" §b/jtrace stop §7- Tắt chế độ theo dõi trực tiếp");
        sender.sendMessage(" §b/jtrace breakers §7- Xem bảng xếp hạng MSPT và các cầu chì bị ngắt");
        sender.sendMessage(" §b/jtrace dump [latest|list|mã_lỗi] §7- Mở Hộp đen xem biến RAM lúc xảy ra lỗi");
        sender.sendMessage(" §b/jtrace reset <tên_plugin|node_id|all> §7- Đóng lại cầu chì sau khi sửa lỗi");
        sender.sendMessage(" §b/jtrace config <max-errors|max-ms> <số> §7- Tinh chỉnh ngưỡng tự ngắt mạch");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("hook", "watch", "stop", "breakers", "dump", "reset", "config").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "hook", "reset", "watch" -> {
                    List<String> names = new ArrayList<>();
                    names.add("all");
                    for (Plugin p : Bukkit.getPluginManager().getPlugins()) names.add(p.getName());
                    yield names.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
                }
                case "dump" -> List.of("latest", "list");
                case "config" -> List.of("max-errors", "max-ms");
                default -> Collections.emptyList();
            };
        }
        return Collections.emptyList();
    }
}
