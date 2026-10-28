package com.javastudio.security;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.control.BytecodeInspector;
import com.javastudio.security.exploit.ExploitGuard;

import java.util.*;

/**
 * Trung Tâm Chỉ Huy An Ninh & Phòng Thủ Toàn Diện (Security Sentinel Nexus).
 * Hợp nhất 4 phân hệ bảo mật thành 1 thể thống nhất:
 * 1. Security Sandbox (Hộp cát chạy mã & timeout)
 * 2. Security Scanner (Quét backdoor, RCE & chuỗi Base64 độc)
 * 3. Exploit Guard (Chặn packet crash, NBT, book crash, tọa độ vô cực)
 * 4. Bytecode Inspector (Mổ xẻ & dịch ngược class bytecode)
 */
public final class SecuritySentinel {

    private final JavaStudioPlugin plugin;
    private final SecuritySandbox sandbox;
    private final SecurityScanner scanner;
    private final ExploitGuard exploitGuard;
    private final BytecodeInspector bytecodeInspector;

    public record SecurityReport(
            int securityScore,
            boolean sandboxActive,
            boolean exploitGuardActive,
            int totalExploitsBlocked,
            int highRiskFindings,
            List<String> recommendations
    ) {}

    public SecuritySentinel(JavaStudioPlugin plugin,
                            SecuritySandbox sandbox,
                            SecurityScanner scanner,
                            ExploitGuard exploitGuard,
                            BytecodeInspector bytecodeInspector) {
        this.plugin = plugin;
        this.sandbox = sandbox;
        this.scanner = scanner;
        this.exploitGuard = exploitGuard;
        this.bytecodeInspector = bytecodeInspector;
    }

    /**
     * Thực hiện kiểm toán toàn diện an ninh hệ thống (Full Security Audit).
     */
    public SecurityReport performAudit() {
        int score = 100;
        List<String> recs = new ArrayList<>();

        boolean sandboxOk = sandbox != null && plugin.getConfig().getBoolean("security.sandbox.enabled", true);
        if (!sandboxOk) {
            score -= 25;
            recs.add("Nên bật Security Sandbox trong config.yml để chống vòng lặp vô tận và RCE.");
        }

        boolean exploitOk = exploitGuard != null && exploitGuard.isEnabled();
        if (!exploitOk) {
            score -= 30;
            recs.add("Nên kích hoạt ExploitGuard để chặn sách độc, packet flood, swap macro và tọa độ vô cực.");
        }

        // Kiểm tra cấu hình Discord Webhook
        String discordUrl = plugin.getConfig().getString("discord.webhook-url", "");
        if (discordUrl == null || discordUrl.isBlank()) {
            recs.add("Khuyến nghị cấu hình Discord Webhook để nhận cảnh báo tức thời khi phát hiện Exploit hoặc dò mật khẩu.");
        }

        // Kiểm tra nguy cơ BungeeCord / Velocity UUID Spoofing Port Hijacking
        try {
            boolean isBungee = org.bukkit.Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false);
            boolean isOnlineMode = org.bukkit.Bukkit.getOnlineMode();
            String serverIp = org.bukkit.Bukkit.getIp();
            if (isBungee && !isOnlineMode) {
                if (serverIp == null || serverIp.isBlank() || serverIp.equals("0.0.0.0")) {
                    score = Math.max(0, score - 35);
                    recs.add("CẢNH BÁO NGUY HIỂM: Máy chủ bật bungeecord nhưng không bind 'server-ip' về 127.0.0.1! Hacker có thể kết nối trực tiếp vào port backend để giả mạo tài khoản OP (Bungee UUID Spoofing)!");
                }
            }
        } catch (Throwable ignored) {}

        // Kiểm tra phòng thủ Panel & Wings CVEs (Crafty Controller & Pterodactyl Panel)
        int panelBlocked = 0;
        if (plugin.getPanelCveProtection() != null) {
            var cveReport = plugin.getPanelCveProtection().performCveAudit();
            panelBlocked = cveReport.totalAttacksBlocked();
            if (cveReport.panelDefenseScore() < 100) {
                score -= (100 - cveReport.panelDefenseScore()) / 5;
            }
            if (panelBlocked > 0) {
                recs.add("Phân hệ Panel CVE Defense đã chặn đứng " + panelBlocked + " đợt tấn công nhắm vào Crafty Controller & Pterodactyl/Wings.");
            }
        }

        // Đếm tổng số vi phạm / rủi ro
        int blockedExploits = (exploitGuard != null ? (int) exploitGuard.getStats().getPacketsBlocked() : 0) + panelBlocked;
        int highRiskFindings = 0;

        // Quét nhanh toàn bộ các file JAR plugin trong server
        if (scanner != null) {
            List<SecurityScanner.ScanReport> reports = scanner.scanAllPlugins();
            for (SecurityScanner.ScanReport rep : reports) {
                if (rep != null) {
                    highRiskFindings += rep.dangerCount();
                }
            }
        }

        if (highRiskFindings > 0) {
            score = Math.max(10, score - (highRiskFindings * 15));
            recs.add("Phát hiện " + highRiskFindings + " rủi ro bảo mật nghiêm trọng trong các tệp JAR plugin máy chủ!");
        }

        if (score == 100) {
            recs.add("Hệ thống phòng thủ đang ở trạng thái tối ưu tuyệt đối!");
        }

        return new SecurityReport(Math.max(0, Math.min(100, score)), sandboxOk, exploitOk, blockedExploits, highRiskFindings, recs);
    }

    public SecuritySandbox getSandbox() { return sandbox; }
    public SecurityScanner getScanner() { return scanner; }
    public ExploitGuard getExploitGuard() { return exploitGuard; }
    public BytecodeInspector getBytecodeInspector() { return bytecodeInspector; }
    public com.javastudio.security.cve.PanelCveProtectionSuite getCveProtection() { return plugin.getPanelCveProtection(); }
    public JavaStudioPlugin getPlugin() { return plugin; }
}
