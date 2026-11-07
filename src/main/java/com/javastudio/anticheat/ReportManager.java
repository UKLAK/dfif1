package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReportManager {

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;
    private final File reportsFile;

    public enum ReportStatus {
        PENDING,
        INVESTIGATING,
        RESOLVED,
        DISMISSED
    }

    public static class ReportEntry {
        public final String id;
        public final String reporterName;
        public final UUID reporterUuid;
        public final String targetName;
        public final UUID targetUuid;
        public final String reason;
        public final String timestamp;
        public final String replayId;
        public ReportStatus status;
        public String handledBy;
        public String resolution;

        public ReportEntry(String id, String reporterName, UUID reporterUuid,
                           String targetName, UUID targetUuid, String reason,
                           String timestamp, String replayId, ReportStatus status,
                           String handledBy, String resolution) {
            this.id = id;
            this.reporterName = reporterName;
            this.reporterUuid = reporterUuid;
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.reason = reason;
            this.timestamp = timestamp;
            this.replayId = replayId != null ? replayId : "none";
            this.status = status != null ? status : ReportStatus.PENDING;
            this.handledBy = handledBy != null ? handledBy : "none";
            this.resolution = resolution != null ? resolution : "";
        }
    }

    private final Map<UUID, Long> reportCooldowns = new ConcurrentHashMap<>();
    private final List<ReportEntry> reportsHistory = Collections.synchronizedList(new ArrayList<>());

    public ReportManager(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.reportsFile = new File(plugin.getDataFolder(), "reports.json");
        loadReports();
    }

    private void loadReports() {
        if (!reportsFile.exists()) return;
        try {
            String json = java.nio.file.Files.readString(reportsFile.toPath(), StandardCharsets.UTF_8);
            Pattern pattern = Pattern.compile(
                    "\\{\\s*\"id\":\\s*\"([^\"]+)\",\\s*\"reporterName\":\\s*\"([^\"]*)\",\\s*\"reporterUuid\":\\s*\"([^\"]*)\",\\s*\"targetName\":\\s*\"([^\"]*)\",\\s*\"targetUuid\":\\s*\"([^\"]*)\",\\s*\"reason\":\\s*\"([^\"]*)\",\\s*\"timestamp\":\\s*\"([^\"]*)\",\\s*\"replayId\":\\s*\"([^\"]*)\",\\s*\"status\":\\s*\"([^\"]*)\",\\s*\"handledBy\":\\s*\"([^\"]*)\",\\s*\"resolution\":\\s*\"([^\"]*)\"\\s*\\}"
            );
            Matcher m = pattern.matcher(json);
            while (m.find()) {
                String id = m.group(1);
                String repName = m.group(2);
                UUID repUuid = parseUuidSafe(m.group(3));
                String tarName = m.group(4);
                UUID tarUuid = parseUuidSafe(m.group(5));
                String reason = m.group(6);
                String time = m.group(7);
                String repId = m.group(8);
                ReportStatus st = ReportStatus.PENDING;
                try { st = ReportStatus.valueOf(m.group(9)); } catch (Throwable ignored) {}
                String handled = m.group(10);
                String res = m.group(11);

                reportsHistory.add(new ReportEntry(id, repName, repUuid, tarName, tarUuid, reason, time, repId, st, handled, res));
            }
        } catch (Throwable ignored) {}
    }

    private UUID parseUuidSafe(String s) {
        try {
            return UUID.fromString(s);
        } catch (Throwable t) {
            return UUID.randomUUID();
        }
    }

    private synchronized void saveReports() {
        PlatformUtil.runTaskAsync(plugin, () -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("[\n");
                synchronized (reportsHistory) {
                    for (int i = 0; i < reportsHistory.size(); i++) {
                        ReportEntry r = reportsHistory.get(i);
                        sb.append(String.format(Locale.ROOT,
                                "  {\"id\":\"%s\",\"reporterName\":\"%s\",\"reporterUuid\":\"%s\",\"targetName\":\"%s\",\"targetUuid\":\"%s\",\"reason\":\"%s\",\"timestamp\":\"%s\",\"replayId\":\"%s\",\"status\":\"%s\",\"handledBy\":\"%s\",\"resolution\":\"%s\"}%s\n",
                                escapeJson(r.id), escapeJson(r.reporterName), r.reporterUuid,
                                escapeJson(r.targetName), r.targetUuid, escapeJson(r.reason),
                                escapeJson(r.timestamp), escapeJson(r.replayId),
                                r.status.name(), escapeJson(r.handledBy), escapeJson(r.resolution),
                                (i < reportsHistory.size() - 1 ? "," : "")
                        ));
                    }
                }
                sb.append("]\n");
                java.nio.file.Files.writeString(reportsFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
            } catch (Throwable ignored) {}
        });
    }

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

        d.investigationBoostUntil = now + 90_000L;
        d.reportCount++;
        d.lastReportedTime = now;

        if (manager.getAuraBotManager() != null) {
            manager.getAuraBotManager().spawnAuraBot(target, 160);
        }

        String replayId = "none";
        if (manager.getReplayManager() != null) {
            String clip = manager.getReplayManager().captureIncident(target, "REPORT_" + reason.toUpperCase(Locale.ROOT), d.getTotalVl());
            if (clip != null) replayId = clip;
        }

        String reportId = "rep_" + (now % 100_000);
        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss dd/MM"));
        ReportEntry entry = new ReportEntry(reportId, reporter.getName(), reporter.getUniqueId(),
                target.getName(), target.getUniqueId(), reason, timeStr, replayId,
                ReportStatus.PENDING, "none", "");

        reportsHistory.add(0, entry);
        while (reportsHistory.size() > 100) reportsHistory.remove(reportsHistory.size() - 1);
        saveReports();

        MessageUtil.sendSuccess(reporter, "Đã gửi báo cáo về người chơi &e" + target.getName() + "&a!");
        MessageUtil.sendInfo(reporter, "Hệ thống Anti-Cheat và Đội ngũ Staff đang tự động điều tra đối tượng.");

        String alertMsg1 = "&8[&c&lREPORT&8] &e" + reporter.getName() + " &7vừa tố cáo &c&l" + target.getName() + " &7(Lý do: &f" + reason + "&7)";
        String alertMsg2 = "  &b» &7Hành động: &e/tp " + target.getName() + " &8| &a/jac inspect " + target.getName()
                + (!"none".equals(replayId) ? " &8| &6/jac replay play " + replayId : "")
                + " &8| &e/jac report claim " + reportId;

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("javastudio.ac.alerts") || staff.isOp()) {
                MessageUtil.sendRaw(staff, MessageUtil.colorize(alertMsg1));
                MessageUtil.sendRaw(staff, MessageUtil.colorize(alertMsg2));
                try {
                    staff.playSound(staff.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f);
                } catch (Throwable ignored) {}
            }
        }
        plugin.getLogger().info("[Report] " + reporter.getName() + " tố cáo " + target.getName() + " (Lý do: " + reason + ")");

        sendDiscordReport(entry);

        return true;
    }

    public boolean claimReport(String reportId, String staffName) {
        synchronized (reportsHistory) {
            for (ReportEntry r : reportsHistory) {
                if (r.id.equalsIgnoreCase(reportId)) {
                    r.status = ReportStatus.INVESTIGATING;
                    r.handledBy = staffName;
                    saveReports();
                    return true;
                }
            }
        }
        return false;
    }

    public boolean resolveReport(String reportId, String staffName, String verdict) {
        synchronized (reportsHistory) {
            for (ReportEntry r : reportsHistory) {
                if (r.id.equalsIgnoreCase(reportId)) {
                    r.status = ReportStatus.RESOLVED;
                    r.handledBy = staffName;
                    r.resolution = verdict != null ? verdict : "Đã xử lý";
                    saveReports();
                    return true;
                }
            }
        }
        return false;
    }

    public boolean dismissReport(String reportId, String staffName) {
        synchronized (reportsHistory) {
            for (ReportEntry r : reportsHistory) {
                if (r.id.equalsIgnoreCase(reportId)) {
                    r.status = ReportStatus.DISMISSED;
                    r.handledBy = staffName;
                    r.resolution = "Không phát hiện vi phạm";
                    saveReports();
                    return true;
                }
            }
        }
        return false;
    }

    public List<ReportEntry> getReportsHistory() {
        return new ArrayList<>(reportsHistory);
    }

    public int getPendingCount() {
        int count = 0;
        synchronized (reportsHistory) {
            for (ReportEntry r : reportsHistory) {
                if (r.status == ReportStatus.PENDING || r.status == ReportStatus.INVESTIGATING) count++;
            }
        }
        return count;
    }

    private void sendDiscordReport(ReportEntry entry) {
        String webhook = plugin.getConfig().getString("anticheat.ban-wave.discord-webhook", "");
        if (webhook == null || webhook.isBlank()) {
            webhook = plugin.getConfig().getString("discord.webhook-url", "");
        }
        if (webhook == null || webhook.isBlank()) return;

        final String finalWebhook = webhook;
        PlatformUtil.runTaskAsync(plugin, () -> {
            try {
                String json = String.format(Locale.ROOT, """
                {
                  "username": "JAntiCheat Investigation",
                  "avatar_url": "https://mc-heads.net/avatar/%s/100",
                  "embeds": [{
                    "title": "🚨 Tố Cáo Người Chơi Mới (%s)",
                    "color": 16738657,
                    "fields": [
                      {"name": "Người tố cáo", "value": "%s", "inline": true},
                      {"name": "Đối tượng bị tố cáo", "value": "%s", "inline": true},
                      {"name": "Lý do", "value": "%s", "inline": false},
                      {"name": "Mã Replay Clip", "value": "`%s`", "inline": true},
                      {"name": "Thời gian", "value": "%s", "inline": true}
                    ],
                    "footer": {"text": "Tự động kích hoạt điều tra 90s, tăng độ nhạy & thả AuraBot"}
                  }]
                }
                """, entry.targetName, entry.id, entry.reporterName, entry.targetName,
                        escapeJson(entry.reason), entry.replayId, entry.timestamp);

                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(finalWebhook))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                        .build();
                client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            } catch (Throwable ignored) {}
        });
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
