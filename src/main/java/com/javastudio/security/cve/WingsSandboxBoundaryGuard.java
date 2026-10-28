package com.javastudio.security.cve;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Phòng thủ chống Wings Container Sandbox Escape & Symlink Traversal (CVE-2024-27102).
 * Đảm bảo các tiến trình, Dynamic Plugin và tệp tin trong máy chủ Minecraft không thể
 * vượt ra ngoài thư mục gốc sandbox (/home/container hoặc server root) để đọc file của Host hoặc server khác.
 */
public final class WingsSandboxBoundaryGuard {

    private final PanelCveProtectionSuite suite;
    private final File serverRoot;

    public WingsSandboxBoundaryGuard(PanelCveProtectionSuite suite) {
        this.suite = suite;
        File root = new File(".");
        try {
            this.serverRoot = root.getCanonicalFile();
        } catch (IOException e) {
            throw new RuntimeException("Không thể xác định Server Root Canonical Path", e);
        }
    }

    /**
     * Xác thực xem một tệp tin hoặc đường dẫn có nằm an toàn trong ranh giới Sandbox của Server hay không.
     */
    public boolean verifySandboxBoundary(File file) {
        if (file == null) return false;

        try {
            Path filePath = file.toPath();

            // 1. Kiểm tra Symbolic Link độc hại trỏ ra ngoài Sandbox
            if (Files.isSymbolicLink(filePath)) {
                Path realTarget = filePath.toRealPath();
                File targetFile = realTarget.toFile();
                if (!PathTraversalDefense.isInsideSecure(serverRoot, targetFile)) {
                    suite.getStats().incrementCve2024_27102();
                    suite.logAlert("CVE-2024-27102",
                            "🚨 PHÁT HIỆN SYMLINK SANDBOX ESCAPE: Tệp liên kết " + file.getName() +
                            " trỏ ra ngoài server root tới: " + realTarget);
                    return false;
                }
            }

            // 2. Kiểm tra Canonical Boundary
            File canonicalFile = file.getCanonicalFile();
            if (!PathTraversalDefense.isInsideSecure(serverRoot, canonicalFile)) {
                suite.getStats().incrementCve2024_27102();
                suite.logAlert("CVE-2024-27102",
                        "🚨 PHÁT HIỆN PATH TRAVERSAL VƯỢT KHỎI SANDBOX ROOT: Đích đến: " + canonicalFile.getAbsolutePath());
                return false;
            }

            // 3. Chặn các đường dẫn nhắm vào hệ thống Linux / Windows host
            String pathStr = canonicalFile.getAbsolutePath().toLowerCase(Locale.ROOT);
            if (pathStr.startsWith("/etc/") || pathStr.startsWith("/proc/") ||
                pathStr.startsWith("/sys/") || pathStr.startsWith("/root/") ||
                pathStr.startsWith("/var/run/docker") || pathStr.contains("system32")) {
                suite.getStats().incrementCve2024_27102();
                suite.logAlert("CVE-2024-27102",
                        "🚨 PHÁT HIỆN TRUY CẬP ĐƯỜNG DẪN HỆ ĐIỀU HÀNH HOST NGUY HIỂM: " + pathStr);
                return false;
            }

            return true;
        } catch (IOException e) {
            suite.getStats().incrementCve2024_27102();
            return false;
        }
    }

    public File getServerRoot() {
        return serverRoot;
    }
}
