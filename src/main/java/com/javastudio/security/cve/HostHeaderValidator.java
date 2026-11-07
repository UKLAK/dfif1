package com.javastudio.security.cve;

import java.util.regex.Pattern;

public final class HostHeaderValidator {

    private HostHeaderValidator() {}

    private static final Pattern VALID_HOST_PATTERN = Pattern.compile(
            "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)*[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(:[0-9]{1,5})?$"
    );

    private static final Pattern VALID_IPV6_HOST_PATTERN = Pattern.compile(
            "^\\[[0-9a-fA-F:]+\\](:[0-9]{1,5})?$"
    );

    public record HostValidationResult(boolean valid, String reason) {}

    public static HostValidationResult validate(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return new HostValidationResult(false, "[CVE-2024-1064] Header 'Host' bị thiếu hoặc rỗng");
        }

        if (hostHeader.length() > 255) {
            return new HostValidationResult(false, "[CVE-2024-1064] Độ dài Host header vượt quá giới hạn 255 ký tự");
        }

        if (hostHeader.contains("\r") || hostHeader.contains("\n") ||
            hostHeader.contains("%0d") || hostHeader.contains("%0a") ||
            hostHeader.contains("%0D") || hostHeader.contains("%0A")) {
            return new HostValidationResult(false, "[CVE-2024-1064] Phát hiện CRLF Injection trong Host Header!");
        }

        for (int i = 0; i < hostHeader.length(); i++) {
            char c = hostHeader.charAt(i);
            if (c <= 32 || c >= 127) {
                return new HostValidationResult(false, "[CVE-2024-1064] Chứa ký tự không in được hoặc khoảng trắng");
            }
        }

        int colonIdx = hostHeader.lastIndexOf(':');
        if (hostHeader.startsWith("[") && hostHeader.contains("]")) {
            int closeBracket = hostHeader.indexOf(']');
            if (colonIdx > closeBracket) {
                String portStr = hostHeader.substring(colonIdx + 1);
                if (!isValidPort(portStr)) {
                    return new HostValidationResult(false, "[CVE-2024-1064] Cổng mạng không hợp lệ: " + portStr);
                }
            }
        } else if (colonIdx != -1) {
            String portStr = hostHeader.substring(colonIdx + 1);
            if (!isValidPort(portStr)) {
                return new HostValidationResult(false, "[CVE-2024-1064] Cổng mạng không hợp lệ: " + portStr);
            }
        }

        if (hostHeader.startsWith("[")) {
            if (!VALID_IPV6_HOST_PATTERN.matcher(hostHeader).matches()) {
                return new HostValidationResult(false, "[CVE-2024-1064] Cấu trúc IPv6 Host header không đúng định dạng RFC");
            }
        } else {
            if (!VALID_HOST_PATTERN.matcher(hostHeader).matches()) {
                return new HostValidationResult(false, "[CVE-2024-1064] Cấu trúc Hostname/IPv4 không đúng định dạng RFC: " + hostHeader);
            }
        }

        return new HostValidationResult(true, "Hợp lệ");
    }

    private static boolean isValidPort(String portStr) {
        try {
            int port = Integer.parseInt(portStr);
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
