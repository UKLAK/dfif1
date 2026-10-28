package com.javastudio.security.cve;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Phòng thủ chống Stored XSS qua Server MOTD (CVE-2025-14701).
 * Khi Crafty Controller, Pterodactyl hoặc bất kỳ Web Panel nào quét/ping server để lấy danh sách máy chủ,
 * MOTD chứa mã độc JavaScript (<script>, <img onerror>, <svg onload>) sẽ được làm sạch triệt để,
 * bảo vệ tuyệt đối trình duyệt của Quản trị viên Web Panel khỏi bị tấn công đánh cắp phiên (Session Hijacking).
 */
@SuppressWarnings("deprecation")
public final class MotdXssGuardListener implements Listener {

    private final PanelCveProtectionSuite suite;

    // Pattern nhận diện các thẻ HTML và cú pháp nhúng JavaScript
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile(
            "<\\/?\\s*(script|iframe|img|svg|object|embed|style|body|link|form|input|meta|a|div|span|button|video|audio)[^>]*>",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern nhận diện các Event Handlers độc hại
    private static final Pattern EVENT_HANDLER_PATTERN = Pattern.compile(
            "(on(load|error|click|mouseover|focus|blur|mouseenter|pointerover|readystatechange)\\s*=)",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern nhận diện JavaScript Pseudo-protocol và Data URI
    private static final Pattern JS_PROTOCOL_PATTERN = Pattern.compile(
            "(javascript\\s*:|data\\s*:\\s*text\\/html|vbscript\\s*:)",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern tổng quát bóc tách các thẻ HTML <...>
    private static final Pattern GENERIC_TAG_PATTERN = Pattern.compile("<[^>]+>");

    public MotdXssGuardListener(PanelCveProtectionSuite suite) {
        this.suite = suite;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onServerListPing(ServerListPingEvent event) {
        if (!suite.isMotdXssProtectionEnabled()) return;

        String rawMotd = event.getMotd();
        if (rawMotd == null || rawMotd.isEmpty()) return;

        if (containsXss(rawMotd)) {
            suite.getStats().incrementCve2025_14701();
            String clientIp = event.getAddress() != null ? event.getAddress().getHostAddress() : "Unknown";
            suite.logAlert("CVE-2025-14701", "Phát hiện Stored XSS trong Server MOTD! Đã vô hiệu hóa trước khi gửi tới Panel/Client Ping (" + clientIp + ")");
        }

        String cleanMotd = sanitizeMotd(rawMotd);
        event.setMotd(cleanMotd);
    }

    /**
     * Kiểm tra xem chuỗi MOTD có chứa các vector tấn công Stored XSS không.
     */
    public static boolean containsXss(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);

        if (HTML_TAG_PATTERN.matcher(text).find()) return true;
        if (EVENT_HANDLER_PATTERN.matcher(text).find()) return true;
        if (JS_PROTOCOL_PATTERN.matcher(text).find()) return true;

        // Kiểm tra các biến thể HTML entities hoặc thẻ unclosed
        if (lower.contains("&lt;script") || lower.contains("&#x3c;script") ||
            lower.contains("<script") || lower.contains("<img") || lower.contains("<svg")) {
            return true;
        }

        return false;
    }

    /**
     * Làm sạch hoàn toàn MOTD: Loại bỏ các thẻ HTML và JavaScript nhưng vẫn giữ nguyên mã màu Minecraft (§ và &).
     */
    public static String sanitizeMotd(String text) {
        if (text == null) return "";

        // 1. Loại bỏ các thẻ HTML nguy hiểm cụ thể
        String sanitized = HTML_TAG_PATTERN.matcher(text).replaceAll("");

        // 2. Loại bỏ Event Handlers độc hại
        sanitized = EVENT_HANDLER_PATTERN.matcher(sanitized).replaceAll("");

        // 3. Loại bỏ Javascript protocol
        sanitized = JS_PROTOCOL_PATTERN.matcher(sanitized).replaceAll("[BLOCKED_URI]");

        // 4. Loại bỏ các thẻ đóng mở HTML còn sót lại mà không ảnh hưởng tới ký tự màu Minecraft
        sanitized = GENERIC_TAG_PATTERN.matcher(sanitized).replaceAll("");

        // 5. Thay thế các ký tự ngoặc nhọn độc hại còn lẻ loi
        sanitized = sanitized.replace("<", "&lt;").replace(">", "&gt;");

        return sanitized;
    }
}
