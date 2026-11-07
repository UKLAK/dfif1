package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.pipeline.AsyncChunkPrefetcher;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class JPrefetchCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final AsyncChunkPrefetcher prefetcher;

    public JPrefetchCommand(JavaStudioPlugin plugin, AsyncChunkPrefetcher prefetcher) {
        this.plugin = plugin;
        this.prefetcher = prefetcher;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) {
            MessageUtil.send(sender, "&cBạn không có quyền thực hiện lệnh này!");
            return true;
        }

        if (!plugin.getSettingsManager().isChunkPrefetcherEnabled()) {
            MessageUtil.send(sender, "&cPhân hệ Chunk Prefetcher (/jprefetch) đang bị TẮT trong cài đặt!");
            MessageUtil.send(sender, "&7Bật lại bằng lệnh: &e/javastudio toggle chunk-prefetcher &7hoặc qua &e/javastudio settings");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            showStatus(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "toggle" -> {
                boolean nextState = !prefetcher.isEnabled();
                prefetcher.setEnabled(nextState);
                MessageUtil.send(sender, "&e[ChunkPrefetcher] &fĐã " + (nextState ? "&aKÍCH HOẠT" : "&cTẮT") + " &fhệ thống Async Chunk Prefetcher.");
            }
            case "stats" -> {
                showStatus(sender);
            }
            case "reset" -> {
                prefetcher.resetStats();
                MessageUtil.send(sender, "&a[ChunkPrefetcher] Đã đặt lại toàn bộ chỉ số thống kê tải trước chunk.");
            }
            default -> {
                MessageUtil.send(sender, "&6=== Hướng Dẫn /jprefetch ===");
                MessageUtil.send(sender, "&e/jprefetch status &7- Xem trạng thái và thông số prefetch");
                MessageUtil.send(sender, "&e/jprefetch toggle &7- Bật/tắt hệ thống dự đoán chunk");
                MessageUtil.send(sender, "&e/jprefetch reset  &7- Đặt lại chỉ số thống kê");
            }
        }
        return true;
    }

    private void showStatus(CommandSender sender) {
        MessageUtil.send(sender, "&6=== Hệ Thống Asynchronous Chunk Prefetcher ===");
        MessageUtil.send(sender, "&7Trạng thái: " + (prefetcher.isEnabled() ? "&aĐANG HOẠT ĐỘNG" : "&cĐÃ TẮT"));
        MessageUtil.send(sender, "&7Người chơi theo dõi: &e" + prefetcher.getTrackedPlayerCount());
        MessageUtil.send(sender, "&7Tổng chunk đã nạp trước: &a" + prefetcher.getTotalPrefetched());
        MessageUtil.send(sender, "&7Lần trúng cache (đã nạp sẵn): &b" + prefetcher.getCacheHits());
        MessageUtil.send(sender, "&7Bỏ qua do TPS thấp (&c< 18.0&7): &e" + prefetcher.getSkippedDueToTps());
        MessageUtil.send(sender, "&7Tác vụ async đang xử lý: &d" + prefetcher.getCurrentConcurrentLoads());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> sub = List.of("status", "toggle", "stats", "reset");
            List<String> res = new ArrayList<>();
            for (String s : sub) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    res.add(s);
                }
            }
            return res;
        }
        return List.of();
    }
}

