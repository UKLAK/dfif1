package com.javastudio.security.cve;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SstiDefenseFilter {

    private SstiDefenseFilter() {}

    public record SstiCheckResult(boolean safe, String detectedPattern, String description) {}

    private static final Pattern TEMPLATE_EXPRESSION_PATTERN = Pattern.compile(
            "(\\{\\{[\\s\\S]*?\\}\\})|(\\{%[\\s\\S]*?%\\})|(\\$\\{[\\s\\S]*?\\})",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DANGEROUS_SSTI_KEYWORDS = Pattern.compile(
            "(__globals__|__builtins__|__class__|__mro__|__subclasses__|__getitem__|__base__|" +
            "cycler|lipsum|joiner|namespace|url_for|request\\.application|" +
            "os\\.popen|subprocess|os\\.system|commands\\.getoutput|posix_spawn)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern REVERSE_SHELL_PATTERN = Pattern.compile(
            "(/dev/tcp/\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|" +
            "nc\\s+(-e|-c|/bin/sh|/bin/bash)|" +
            "bash\\s+-i\\s+>&|sh\\s+-i\\s+>&|" +
            "mkfifo\\s+/tmp/|" +
            "python[0-9]*\\s+-c\\s+[\"']import\\s+socket|" +
            "socat\\s+exec|perl\\s+-e\\s+[\"']use\\s+Socket)",
            Pattern.CASE_INSENSITIVE
    );

    public static boolean containsSsti(String input) {
        if (input == null || input.isBlank()) return false;
        return !inspect(input).safe();
    }

    public static SstiCheckResult inspect(String input) {
        if (input == null || input.isBlank()) {
            return new SstiCheckResult(true, null, "Hợp lệ");
        }

        Matcher shellMatcher = REVERSE_SHELL_PATTERN.matcher(input);
        if (shellMatcher.find()) {
            return new SstiCheckResult(false, shellMatcher.group(),
                    "[CVE-2025-14700] Phát hiện chuỗi tấn công Reverse Shell đặc thù (Nosiume PoC)!");
        }

        Matcher templateMatcher = TEMPLATE_EXPRESSION_PATTERN.matcher(input);
        while (templateMatcher.find()) {
            String expr = templateMatcher.group();
            Matcher keywordMatcher = DANGEROUS_SSTI_KEYWORDS.matcher(expr);
            if (keywordMatcher.find()) {
                return new SstiCheckResult(false, keywordMatcher.group(),
                        "[CVE-2025-14700] Phát hiện SSTI khai thác Jinja2 Template: " + keywordMatcher.group());
            }

            String lower = expr.toLowerCase(Locale.ROOT);
            if (lower.contains("popen") || lower.contains("system") || lower.contains("exec") || lower.contains("spawn")) {
                return new SstiCheckResult(false, expr,
                        "[CVE-2025-14700] Phát hiện lệnh thực thi hệ điều hành trong Template Expression!");
            }
        }

        Matcher keywordMatcher = DANGEROUS_SSTI_KEYWORDS.matcher(input);
        if (keywordMatcher.find()) {
            String kw = keywordMatcher.group().toLowerCase(Locale.ROOT);
            if (kw.contains("popen") || kw.contains("globals") || kw.contains("builtins") || kw.contains("subclasses")) {
                return new SstiCheckResult(false, keywordMatcher.group(),
                        "[CVE-2025-14700] Phát hiện từ khóa leo thang đặc quyền Python SSTI: " + keywordMatcher.group());
            }
        }

        return new SstiCheckResult(true, null, "An toàn");
    }

    public static String sanitizeTemplate(String input) {
        if (input == null) return "";
        String defanged = input
                .replace("{{", "\\{\\{")
                .replace("}}", "\\}\\}")
                .replace("{%", "\\{%")
                .replace("%}", "\\%}");
        defanged = REVERSE_SHELL_PATTERN.matcher(defanged).replaceAll("[REVERSE_SHELL_BLOCKED]");
        return defanged;
    }
}
