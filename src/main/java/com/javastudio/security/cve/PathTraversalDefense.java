package com.javastudio.security.cve;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class PathTraversalDefense {

    private PathTraversalDefense() {}

    private static final Pattern NESTED_TRAVERSAL_PATTERN = Pattern.compile(
            "(\\.\\.\\.+[\\\\/])|([\\\\/]\\.\\.[\\\\/]{2,})|(\\.\\.[\\\\/]{2,})|(\\.\\.;[\\\\/])|" +
            "(%2e%2e|%2f|%5c)|(\\.{3,}[\\\\/])|(\\\\\\\\\\.\\\\)|(\\\\\\\\\\?\\\\)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> SENSITIVE_FILENAMES = Set.of(
            ".env",
            ".env.production",
            ".env.local",
            "pearcmd.php",
            "server.properties",
            "crafty.sqlite",
            "crafty.json",
            "wings.yaml",
            "config.json",
            "id_rsa",
            "id_ed25519",
            "authorized_keys",
            "passwd",
            "shadow",
            "docker.sock"
    );

    public static boolean containsNestedTraversal(String path) {
        if (path == null || path.isBlank()) return false;

        if (path.indexOf('\0') != -1 || path.contains("%00")) {
            return true;
        }

        if (path.contains(".../") || path.contains("...\\") ||
            path.contains("..//") || path.contains("..\\\\") ||
            path.contains(".../...//") || path.contains("....//")) {
            return true;
        }

        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e%2e") || lower.contains("%2f") || lower.contains("%5c") ||
            lower.contains("..%2f") || lower.contains("..%5c")) {
            return true;
        }

        return NESTED_TRAVERSAL_PATTERN.matcher(path).find();
    }

    public static File resolveSecureChild(File baseDir, String relativePath) throws SecurityException {
        if (baseDir == null) {
            throw new SecurityException("[CVE-2026-0963] Thư mục gốc (baseDir) không được null!");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new SecurityException("[CVE-2026-0963] Đường dẫn tệp tin không được để trống!");
        }

        if (relativePath.indexOf('\0') != -1 || relativePath.contains("%00")) {
            throw new SecurityException("[CVE-2026-0963] Phát hiện Null Byte Injection trong đường dẫn!");
        }

        if (containsNestedTraversal(relativePath)) {
            throw new SecurityException("[CVE-2026-13716] Phát hiện ký tự Nested Path Traversal lồng nhau (.../...//)!");
        }

        String normalized = relativePath.replace('\\', '/').trim();

        if (normalized.startsWith("/") || (normalized.length() > 2 && normalized.charAt(1) == ':')) {
            throw new SecurityException("[CVE-2026-0963] Không được phép sử dụng đường dẫn tuyệt đối!");
        }

        String[] segments = normalized.split("/+");
        for (String segment : segments) {
            String segTrim = segment.trim();
            if (segTrim.equals("..") || segTrim.equals("...") ||
                segTrim.startsWith("..") || segTrim.endsWith("..")) {
                throw new SecurityException("[CVE-2026-0963] Phát hiện phân đoạn đường dẫn lùi thư mục: " + segment);
            }
        }

        File child = new File(baseDir, String.join(File.separator, segments));

        try {
            String baseCanonical = baseDir.getCanonicalPath();
            String childCanonical = child.getCanonicalPath();

            boolean safe = childCanonical.equals(baseCanonical) ||
                           childCanonical.startsWith(baseCanonical + File.separator);

            if (!safe) {
                throw new SecurityException("[CVE-2026-0963] Canonical Path vi phạm ranh giới thư mục gốc! Đích: " + childCanonical);
            }
        } catch (IOException e) {
            throw new SecurityException("[CVE-2026-0963] Lỗi I/O khi xác thực Canonical Path: " + e.getMessage());
        }

        if (isSensitiveSystemFile(child)) {
            throw new SecurityException("[CVE-2025-49132] Truy cập tệp tin nhạy cảm bị từ chối: " + child.getName());
        }

        return child;
    }

    public static boolean isInsideSecure(File baseDir, File child) {
        if (baseDir == null || child == null) return false;
        try {
            String baseCanonical = baseDir.getCanonicalPath();
            String childCanonical = child.getCanonicalPath();
            return childCanonical.equals(baseCanonical) ||
                   childCanonical.startsWith(baseCanonical + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean validateZipEntry(File targetDir, String entryName) {
        if (targetDir == null || entryName == null || entryName.isBlank()) return false;
        if (containsNestedTraversal(entryName)) return false;

        try {
            File targetFile = resolveSecureChild(targetDir, entryName);
            return isInsideSecure(targetDir, targetFile);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isSensitiveSystemFile(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (SENSITIVE_FILENAMES.contains(name)) {
            return true;
        }
        return name.endsWith(".env") || name.startsWith(".env.") ||
               name.equals("pearcmd.php") || name.equals("server.properties");
    }
}
