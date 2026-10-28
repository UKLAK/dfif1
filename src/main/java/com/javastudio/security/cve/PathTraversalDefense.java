package com.javastudio.security.cve;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phòng thủ chuyên sâu chống Path Traversal và Zip Slip (CVE-2026-0963, CVE-2026-13716, CVE-2024-27102).
 * Ngăn chặn tuyệt đối các biến thể vượt thư mục lồng nhau (.../...//, ..//..//),
 * mã hóa URL (%2e%2e%2f), Null Byte Injection (%00), và bảo vệ các tệp tin hệ thống nhạy cảm (.env, server.properties).
 */
public final class PathTraversalDefense {

    private PathTraversalDefense() {}

    // Pattern nhận diện các biến thể Path Traversal nguy hiểm:
    // 1. Nested dots/slashes: .../, ...//, ....//, ..//, ..\\
    // 2. Encoded dots/slashes: %2e%2e, %2f, %5c, ..%2f, ..%5c
    // 3. Semicolon/dot tricks: ..;/
    // 4. Windows UNC/Device paths: \\.\, \\?\
    private static final Pattern NESTED_TRAVERSAL_PATTERN = Pattern.compile(
            "(\\.\\.\\.+[\\\\/])|([\\\\/]\\.\\.[\\\\/]{2,})|(\\.\\.[\\\\/]{2,})|(\\.\\.;[\\\\/])|" +
            "(%2e%2e|%2f|%5c)|(\\.{3,}[\\\\/])|(\\\\\\\\\\.\\\\)|(\\\\\\\\\\?\\\\)",
            Pattern.CASE_INSENSITIVE
    );

    // Danh sách các tệp tin hệ thống nhạy cảm cấm truy cập
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

    /**
     * Kiểm tra xem đường dẫn có chứa các biến thể Path Traversal độc hại hay không (CVE-2026-13716).
     */
    public static boolean containsNestedTraversal(String path) {
        if (path == null || path.isBlank()) return false;

        // Kiểm tra Null Byte
        if (path.indexOf('\0') != -1 || path.contains("%00")) {
            return true;
        }

        // Kiểm tra chuỗi .../...// hoặc ..//..//
        if (path.contains(".../") || path.contains("...\\") ||
            path.contains("..//") || path.contains("..\\\\") ||
            path.contains(".../...//") || path.contains("....//")) {
            return true;
        }

        // Kiểm tra URL-encoded traversals
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e%2e") || lower.contains("%2f") || lower.contains("%5c") ||
            lower.contains("..%2f") || lower.contains("..%5c")) {
            return true;
        }

        return NESTED_TRAVERSAL_PATTERN.matcher(path).find();
    }

    /**
     * Xác thực và phân giải đường dẫn con an toàn tuyệt đối bên trong thư mục gốc.
     * Áp dụng Canonical Path Verification với File.separator để tránh lỗi Prefix-Bypass.
     *
     * @param baseDir Thư mục gốc được phép
     * @param relativePath Đường dẫn tương đối từ người dùng / API
     * @return File đích an toàn
     * @throws SecurityException Khi phát hiện hành vi vượt rào Path Traversal
     */
    public static File resolveSecureChild(File baseDir, String relativePath) throws SecurityException {
        if (baseDir == null) {
            throw new SecurityException("[CVE-2026-0963] Thư mục gốc (baseDir) không được null!");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new SecurityException("[CVE-2026-0963] Đường dẫn tệp tin không được để trống!");
        }

        // 1. Chặn Null Byte
        if (relativePath.indexOf('\0') != -1 || relativePath.contains("%00")) {
            throw new SecurityException("[CVE-2026-0963] Phát hiện Null Byte Injection trong đường dẫn!");
        }

        // 2. Chặn biến thể Nested Path Traversal (.../...//) (CVE-2026-13716)
        if (containsNestedTraversal(relativePath)) {
            throw new SecurityException("[CVE-2026-13716] Phát hiện ký tự Nested Path Traversal lồng nhau (.../...//)!");
        }

        // 3. Chuẩn hóa dấu phân cách
        String normalized = relativePath.replace('\\', '/').trim();

        // 4. Chặn đường dẫn tuyệt đối (bắt đầu bằng / hoặc C:)
        if (normalized.startsWith("/") || (normalized.length() > 2 && normalized.charAt(1) == ':')) {
            throw new SecurityException("[CVE-2026-0963] Không được phép sử dụng đường dẫn tuyệt đối!");
        }

        // 5. Kiểm tra từng phân đoạn đường dẫn
        String[] segments = normalized.split("/+");
        for (String segment : segments) {
            String segTrim = segment.trim();
            if (segTrim.equals("..") || segTrim.equals("...") ||
                segTrim.startsWith("..") || segTrim.endsWith("..")) {
                throw new SecurityException("[CVE-2026-0963] Phát hiện phân đoạn đường dẫn lùi thư mục: " + segment);
            }
        }

        // 6. Xây dựng File đối tượng
        File child = new File(baseDir, String.join(File.separator, segments));

        // 7. Kiểm tra ranh giới Canonical Path với File.separator
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

        // 8. Chặn truy cập tệp tin nhạy cảm
        if (isSensitiveSystemFile(child)) {
            throw new SecurityException("[CVE-2025-49132] Truy cập tệp tin nhạy cảm bị từ chối: " + child.getName());
        }

        return child;
    }

    /**
     * Kiểm tra nhanh một tệp tin đã resolve có nằm hoàn toàn bên trong thư mục gốc hay không.
     */
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

    /**
     * Bảo vệ chống Zip Slip khi giải nén tệp tin nén (Archive / Template / Egg Import).
     */
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

    /**
     * Kiểm tra xem tệp tin có thuộc danh sách tệp tin cấu hình nhạy cảm bị khóa không (.env, pearcmd.php, server.properties).
     */
    public static boolean isSensitiveSystemFile(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (SENSITIVE_FILENAMES.contains(name)) {
            return true;
        }
        // Kiểm tra đuôi file nguy hiểm hoặc tệp tin môi trường
        return name.endsWith(".env") || name.startsWith(".env.") ||
               name.equals("pearcmd.php") || name.equals("server.properties");
    }
}
