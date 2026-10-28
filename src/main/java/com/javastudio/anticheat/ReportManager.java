package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hướng 4: Hệ thống Báo cáo Thông minh (/report) & Trí tuệ Điều tra Tự động.
 * Khi nhận report, hệ thống tự động:
 * 1. Đẩy độ nhạy (Sensitivity Boost) cho đối tượng bị tố cáo trong 90s.
 * 2. Triệu hồi AuraBot ngầm quanh đối tượng.
 * 3. Ghi lại clip Replay hiện trường.
 * 4. Báo động Staff trong game & Discord Webhook.
 */
public final class ReportManager {

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    public record ReportEntry(
            String id,
            String reporterName,
            UUID reporterUuid,
            String targetName,
            UUID targetUuid,
            String reason,
            String timestamp,
            String replayId
    ) {}

    private final Map<UUID, Long> reportCooldowns = new ConcurrentHashMap<>();
    private final List<ReportEntry> reportsHistory = Collections.synchronizedList(new ArrayList<>());

    public ReportManager(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /**
     * Xử lý gửi báo cáo người chơi.
     */
    public boolean submitReport(Player reporter, Player target, String reason) {
        if (reporter.equals(target)) {
            MessageUtil.sendError(reporter, "Bạn không thể tự tố cáo chính mình!");
            return false;
        }

        long now = System.currentTimeMillis();
        long lastReport = reportCooldowns.getOrDefault(reporter.getUniqueId(), 0L);
        if (now - lastReport < 60_000L && !reporter.isOp()) {
            long waitSec = (60_000L - (now - lastReport)) / 1000L;
            MessageUtil.sendWarning(reporter, "Vui lòng chờ " + waitSec + " giây trước khi gửi báo cáo tiếp theo!");
            return false;
        }
        reportCooldowns.put(reporter.getUniqueId(), now);

        PlayerData d = manager.data(target);

        // 1. Kích hoạt Investigation Boost (tăng độ nhạy trong 90s)
        d.investigationBoostUntil = now + 90_000L;
        d.reportCount++;
        d.lastReportedTime = now;

        // 2. Tự động spawn AuraBot kiểm tra Killaura
        manager.getAuraBotManager().spawnAuraBot(target, 160);

        // 3. Tự động lưu Replay Clip hiện trường
        String replayId = manager.getReplayManager().captureIncident(target, "REPORT_" + reason.toUpperCase(Locale.ROOT), d.getTotalVl());

        String reportId = "rep_" + (now % 100_000);
        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss dd/MM"));
        ReportEntry entry = new ReportEntry(reportId, reporter.getName(), reporter.getUniqueId(),
                target.getName(), target.getUniqueId(), reason, timeStr, replayId != null ? replayId : "none");
        reportsHistory.add(entry);
        while (reportsHistory.size() > 50) reportsHistory.remove(0);

        // 4. Phản hồi cho người báo cáo
        MessageUtil.sendSuccess(reporter, "Đã gửi báo cáo về người chơi &e" + target.getName() + "&a!");
        MessageUtil.sendInfo(reporter, "Hệ thống Anti-Cheat và Đội ngũ Staff đang tự động điều tra đối tượng.");

        // 5. Cảnh báo Staff online
        String alertMsg1 = "&8[&c&lREPORT&8] &e" + reporter.getName() + " &7vừa tố cáo &c&l" + target.getName() + " &7với lý do: &f" + reason;
        String alertMsg2 = "  &b» &7Hành động nhanh: &e/tp " + target.getName() + " &8| &a/jac inspect " + target.getName()
                + (replayId != null ? " &8| &6/jac replay play " + replayId : "");

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("javastudio.ac.alerts") || staff.isOp()) {
                MessageUtil.sendRaw(staff, MessageUtil.colorize(alertMsg1));
                MessageUtil.sendRaw(staff, MessageUtil.colorize(alertMsg2));
            }
        }
        plugin.getLogger().info("[Report] " + reporter.getName() + " tố cáo " + target.getName() + " (Lý do: " + reason + ")");

        // 6. Gửi Discord Webhook nếu có cấu hình
        sendDiscordReport(entry);

        return true;
    }

    private void sendDiscordReport(ReportEntry entry) {
        String webhook = plugin.getConfig().getString("anticheat.ban-wave.discord-webhook", "");
        if (webhook == null || webhook.isBlank()) return;

        PlatformUtil.runTaskAsync(plugin, () -> {
            try {
                String json = String.format(Locale.ROOT, """
                {
                  "username": "JAntiCheat Investigation",
                  "avatar_url": "https://mc-heads.net/avatar/%s/100",
                  "embeds": [{
                    "title": "🚨 Báo Cáo Người Chơi Mới (%s)",
                    "color": 16738657,
                    "fields": [
                      {"name": "Người tố cáo", "value": "%s", "inline": true},
                      {"name": "Đối tượng bị tố cáo", "value": "%s", "inline": true},
                      {"name": "Lý do", "value": "%s", "inline": false},
                      {"name": "Mã Replay Clip", "value": "`%s`", "inline": true},
                      {"name": "Thời gian", "value": "%s", "inline": true}
                    ],
                    "footer": {"text": "Hệ thống tự động kích hoạt điều tra 90 giây & AuraBot"}
                  }]
                }
                """, entry.targetName(), entry.id(), entry.reporterName(), entry.targetName(),
                        escapeJson(entry.reason()), entry.replayId(), entry.timestamp());

                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(webhook))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                        .build();
                client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            } catch (Throwable ignored) {}
        });
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    public List<ReportEntry> getReportsHistory() {
        return new ArrayList<>(reportsHistory);
    }
}
