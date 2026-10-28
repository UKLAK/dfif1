package com.javastudio.security.cve;

import java.util.regex.Pattern;

/**
 * Phòng thủ chống lộ mật khẩu và mã bảo mật trong Access Logs (CVE-2024-49762).
 * Tự động làm sạch, che giấu các tham số nhạy cảm (password, secret, 2FA code, token)
 * xuất hiện trong URL query hoặc nhật ký máy chủ web, ngăn chặn việc rò rỉ vào access.log.
 */
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

    /**
     * Làm mờ các thông tin nhạy cảm trong chuỗi log / URL.
     */
    public static String redact(String input) {
        if (input == null || input.isBlank()) return "";
        String redacted = SENSITIVE_QUERY_PATTERN.matcher(input).replaceAll("$1***REDACTED***");
        redacted = BEARER_HEADER_PATTERN.matcher(redacted).replaceAll("$1***REDACTED***");
        return redacted;
    }

    /**
     * Kiểm tra xem chuỗi URL Query có chứa các tham số xác thực nhạy cảm không được phép truyền qua GET hay không.
     */
    public static boolean containsSensitiveQueryParam(String query) {
        if (query == null || query.isBlank()) return false;
        return SENSITIVE_QUERY_PATTERN.matcher(query).find();
    }
}
