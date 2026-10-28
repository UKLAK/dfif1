package com.javastudio.discord;

import com.javastudio.JavaStudioPlugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Trình gửi thông báo và nhật ký kiểm duyệt (Audit Log) về kênh Discord qua Webhook.
 */
public class DiscordWebhook {

    private final JavaStudioPlugin plugin;

    public DiscordWebhook(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        String webhookUrl = plugin.getConfig().getString("discord.webhook-url", "");
        return webhookUrl != null && !webhookUrl.trim().isEmpty() && webhookUrl.startsWith("http");
    }

    public void sendCustomEmbed(String title, String description, int color) {
        sendEmbed(title, description, color, null);
    }

    /**
     * Gửi một embed tùy biến tới Discord Webhook (chạy async không chặn main thread)
     */
    public void sendEmbed(String title, String description, int color, String[][] fields) {
        String webhookUrl = plugin.getConfig().getString("discord.webhook-url", "");
        if (webhookUrl == null || webhookUrl.trim().isEmpty() || !webhookUrl.startsWith("http")) {
            return; // Chưa cấu hình Webhook
        }

        CompletableFuture.runAsync(() -> {
            try {
                // Làm sạch nội dung chống SSTI và Reverse Shell (CVE-2025-14700)
                String safeTitle = com.javastudio.security.cve.SstiDefenseFilter.sanitizeTemplate(title);
                String safeDescription = com.javastudio.security.cve.SstiDefenseFilter.sanitizeTemplate(description);

                StringBuilder json = new StringBuilder("{");
                json.append("\"username\":\"JavaStudio Sentinel\",");
                json.append("\"avatar_url\":\"https://raw.githubusercontent.com/PaperMC/Paper/master/src/main/resources/logo.png\",");
                json.append("\"embeds\":[{");
                json.append("\"title\":\"").append(escapeJson(safeTitle)).append("\",");
                json.append("\"description\":\"").append(escapeJson(safeDescription)).append("\",");
                json.append("\"color\":").append(color).append(",");
                json.append("\"timestamp\":\"").append(Instant.now().toString()).append("\",");
                json.append("\"footer\":{\"text\":\"JavaStudio • " + escapeJson(plugin.getServer().getName()) + "\"}");

                if (fields != null && fields.length > 0) {
                    json.append(",\"fields\":[");
                    for (int i = 0; i < fields.length; i++) {
                        String name = fields[i][0];
                        String val = fields[i][1];
                        json.append("{\"name\":\"").append(escapeJson(name)).append("\",\"value\":\"")
                            .append(escapeJson(val)).append("\",\"inline\":true}");
                        if (i < fields.length - 1) json.append(",");
                    }
                    json.append("]");
                }

                json.append("}]}");

                URL url = URI.create(webhookUrl.trim()).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "JavaStudio-Discord-Hook");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code >= 400) {
                    plugin.getLogger().warning("[Discord] Lỗi gửi webhook: HTTP " + code);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Discord] Không thể kết nối tới Webhook: " + e.getMessage());
            }
        });
    }

    /**
     * Ghi nhận lệnh /jexec được thực thi
     */
    public void logExec(String senderName, String code, boolean success, String result) {
        if (!plugin.getConfig().getBoolean("discord.log-jexec", true)) return;
        int color = success ? 0x2ecc71 : 0xe74c3c;
        String desc = "```java\n" + (code.length() > 800 ? code.substring(0, 800) + "..." : code) + "\n```";
        String[][] fields = new String[][]{
                {"Người thực thi", senderName},
                {"Trạng thái", success ? "Thành công" : "Thất bại"},
                {"Kết quả", result != null && !result.isEmpty() ? (result.length() > 200 ? result.substring(0, 200) + "..." : result) : "None"}
        };
        sendEmbed("⚡ [Audit] Lệnh Java Được Thực Thi", desc, color, fields);
    }

    /**
     * Ghi nhận sự kiện vòng đời của Dynamic Plugin (Load, Reload, Unload, Export)
     */
    public void logPluginLifecycle(String pluginName, String action, boolean success, String details) {
        if (!plugin.getConfig().getBoolean("discord.log-plugin-lifecycle", true)) return;
        int color = success ? 0x3498db : 0xe67e22;
        String desc = "Dynamic Plugin: **" + pluginName + "**\nHành động: **" + action + "**\nChi tiết: " + details;
        sendEmbed("🧩 [Plugin Event] " + action.toUpperCase() + ": " + pluginName, desc, color, null);
    }

    /**
     * Ghi nhận vi phạm Sandbox An Ninh
     */
    public void logSecurityViolation(String senderName, String code, String reason) {
        int color = 0x992d22;
        String desc = "⚠ **CẢNH BÁO AN NINH!**\nNgười chơi: **" + senderName + "** đã cố gắng chạy mã vi phạm chính sách Sandbox:\n"
                + "**Lý do:** `" + reason + "`\n```java\n" + code + "\n```";
        sendEmbed("🛡️ [Security Alert] Phát Hiện Vi Phạm Sandbox", desc, color, null);
    }

    /**
     * Ghi nhận vi phạm ExploitFixer
     */
    public void logExploit(String playerName, String exploitReason, String action) {
        int color = 0xe74c3c;
        String desc = "🚨 **PHÁT HIỆN HÀNH VI EXPLOIT!**\nNgười chơi: **" + playerName + "**\nChi tiết: `" + exploitReason + "`\nXử lý: **" + action + "**";
        String[][] fields = new String[][]{
                {"Người chơi", playerName},
                {"Hành vi", exploitReason},
                {"Hành động", action}
        };
        sendEmbed("🛡️ [ExploitFixer Alert] " + playerName, desc, color, fields);
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
