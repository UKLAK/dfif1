package com.javastudio.security.cve;

import java.util.regex.Pattern;

public final class SensitiveLogRedactor {

    private SensitiveLogRedactor() {}

    private static final Pattern SENSITIVE_QUERY_PATTERN = Pattern.compile(
            "((?:password|passwd|secret|token|totp|code|auth|api_key|app_key|bearer)\\s*=\\s*)([^&\\s\\r\\n]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BEARER_HEADER_PATTERN = Pattern.compile(
            "(Bearer\\s+)[a-zA-Z0-9_\\-\\.]+",
            Pattern.CASE_INSENSITIVE
    );

    public static String redact(String input) {
        if (input == null || input.isBlank()) return "";
        String redacted = SENSITIVE_QUERY_PATTERN.matcher(input).replaceAll("$1***REDACTED***");
        redacted = BEARER_HEADER_PATTERN.matcher(redacted).replaceAll("$1***REDACTED***");
        return redacted;
    }

    public static boolean containsSensitiveQueryParam(String query) {
        if (query == null || query.isBlank()) return false;
        return SENSITIVE_QUERY_PATTERN.matcher(query).find();
    }
}
