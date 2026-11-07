package com.javastudio.security.cve;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EggTemplateSanitizer {

    private EggTemplateSanitizer() {}

    private static final Pattern XSS_TAG_PATTERN = Pattern.compile(
            "<\\/?\\s*(script|iframe|img|svg|object|embed|style|link|form|input|button)[^>]*>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EVENT_HANDLER_PATTERN = Pattern.compile(
            "on[a-zA-Z]+\\s*=",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DANGEROUS_URI_PATTERN = Pattern.compile(
            "(javascript\\s*:|data\\s*:\\s*text\\/html)",
            Pattern.CASE_INSENSITIVE
    );

    public static String sanitizeFormInput(String input) {
        if (input == null) return "";
        String clean = XSS_TAG_PATTERN.matcher(input).replaceAll("");
        clean = EVENT_HANDLER_PATTERN.matcher(clean).replaceAll("");
        clean = DANGEROUS_URI_PATTERN.matcher(clean).replaceAll("[BLOCKED_URI]");
        return escapeHtml(clean.trim());
    }

    public static boolean containsDangerousEggPayload(String json) {
        if (json == null || json.isBlank()) return false;
        String lower = json.toLowerCase(Locale.ROOT);
        if (XSS_TAG_PATTERN.matcher(json).find()) return true;
        if (EVENT_HANDLER_PATTERN.matcher(json).find()) return true;
        if (DANGEROUS_URI_PATTERN.matcher(json).find()) return true;
        return lower.contains("<script") || lower.contains("javascript:") || lower.contains("onerror=");
    }

    public static String escapeHtml(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#x27;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
