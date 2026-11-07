package com.javastudio.security.cve;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.Locale;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public final class MotdXssGuardListener implements Listener {

    private final PanelCveProtectionSuite suite;

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile(
            "<\\/?\\s*(script|iframe|img|svg|object|embed|style|body|link|form|input|meta|a|div|span|button|video|audio)[^>]*>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EVENT_HANDLER_PATTERN = Pattern.compile(
            "(on(load|error|click|mouseover|focus|blur|mouseenter|pointerover|readystatechange)\\s*=)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern JS_PROTOCOL_PATTERN = Pattern.compile(
            "(javascript\\s*:|data\\s*:\\s*text\\/html|vbscript\\s*:)",
            Pattern.CASE_INSENSITIVE
    );

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

    public static boolean containsXss(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);

        if (HTML_TAG_PATTERN.matcher(text).find()) return true;
        if (EVENT_HANDLER_PATTERN.matcher(text).find()) return true;
        if (JS_PROTOCOL_PATTERN.matcher(text).find()) return true;

        if (lower.contains("&lt;script") || lower.contains("&#x3c;script") ||
            lower.contains("<script") || lower.contains("<img") || lower.contains("<svg")) {
            return true;
        }

        return false;
    }

    public static String sanitizeMotd(String text) {
        if (text == null) return "";

        String sanitized = HTML_TAG_PATTERN.matcher(text).replaceAll("");

        sanitized = EVENT_HANDLER_PATTERN.matcher(sanitized).replaceAll("");

        sanitized = JS_PROTOCOL_PATTERN.matcher(sanitized).replaceAll("[BLOCKED_URI]");

        sanitized = GENERIC_TAG_PATTERN.matcher(sanitized).replaceAll("");

        sanitized = sanitized.replace("<", "&lt;").replace(">", "&gt;");

        return sanitized;
    }
}
