package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.security.SecuritySentinel;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;

/**
 * Lệnh Chỉ Huy An Ninh & Phòng Thủ CVE Toàn Diện (/jsecurity hoặc /jprotect).
 * Hợp nhất toàn bộ các phân hệ bảo mật và CVE defenses vào 1 cổng điều khiển duy nhất.
 */
public final class JSecurityCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final SecuritySentinel sentinel;

    public JSecurityCommand(JavaStudioPlugin plugin, SecuritySentinel sentinel) {
        this.plugin = plugin;
        this.sentinel = sentinel;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền truy cập trung tâm an ninh!");
            return true;
        }

        boolean isDirect2fa = label.equalsIgnoreCase("j2fa") || label.equalsIgnoreCase("totp");
        if (isDirect2fa) {
            if (!plugin.getSettingsManager().isTwoFactorAuthEnabled()) {
                MessageUtil.sendError(sender, "Tính năng Xác thực 2 bước 2FA (/j2fa) đang bị tắt trong cài đặt!");
                MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle two-factor-auth");
                return true;
            }
            handleTotp(sender, label, args, true);
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendGeneralStatus(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "audit", "check" -> runAudit(sender);
            case "exploit" -> {
                String[] forwardArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[]{"status"};
                plugin.getCommand("jexploit").execute(sender, "jexploit", forwardArgs);
            }
            case "scan" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " scan <tên_plugin>");
                    return true;
                }
                plugin.getCommand("jpm").execute(sender, "jpm", new String[]{"scan", args[1]});
            }
            case "inspect" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " inspect <lớp_class>");
                    return true;
                }
                plugin.getCommand("jpm").execute(sender, "jpm", new String[]{"inspect", args[1]});
            }
            case "totp", "2fa" -> {
                if (!plugin.getSettingsManager().isTwoFactorAuthEnabled()) {
                    MessageUtil.sendError(sender, "Tính năng Xác thực 2 bước 2FA (/j2fa) đang bị tắt trong cài đặt!");
                    MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle two-factor-auth");
                    return true;
                }
                handleTotp(sender, label, args, false);
            }
            case "cve", "panel" -> {
                if (!plugin.getSettingsManager().isPanelCveDefenseEnabled()) {
                    MessageUtil.sendError(sender, "Tính năng Phòng thủ Panel CVEs (/jsecurity cve) đang bị tắt trong cài đặt!");
                    MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle panel-cve-defense");
                    return true;
                }
                handleCveCommand(sender, label, args);
            }
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void handleTotp(CommandSender sender, String label, String[] args, boolean isDirect) {
        if (!(sender instanceof org.bukkit.entity.Player p)) {
            MessageUtil.sendError(sender, "Lệnh cài đặt 2FA yêu cầu thực thi bởi người chơi trong game!");
            return;
        }
        var tm = plugin.getTotpManager();
        int actionIndex = isDirect ? 0 : 1;
        int paramIndex = isDirect ? 1 : 2;

        if (args.length <= actionIndex || args[actionIndex].equalsIgnoreCase("status")) {
            boolean enabled = tm.isTotpEnabled(p.getUniqueId());
            boolean auth = tm.isAuthenticated(p.getUniqueId());
            MessageUtil.sendHeader(p, "TRẠNG THÁI BẢO MẬT 2FA (TOTP)");
            MessageUtil.sendRaw(p, "  &7Google Authenticator: " + (enabled ? "&a[ĐÃ BẬT] ✔" : "&c[CHƯA BẬT] ✖"));
            MessageUtil.sendRaw(p, "  &7Phiên đăng nhập hiện tại: " + (auth ? "&a[ĐÃ XÁC THỰC] ✔" : "&e[CẦN NHẬP MÃ] ⚠"));
            String prefix = isDirect ? "/" + label : "/jsecurity totp";
            MessageUtil.sendRaw(p, "  &7Cú pháp: &e" + prefix + " setup &7| &a" + prefix + " verify <6_số>");
            return;
        }

        String totpAction = args[actionIndex].toLowerCase();
        switch (totpAction) {
            case "setup" -> {
                String secret = tm.generateSecret();
                tm.saveSecret(p.getUniqueId(), secret);
                tm.giveQrSetupMap(p, secret);
            }
            case "verify" -> {
                if (args.length <= paramIndex) {
                    String prefix = isDirect ? "/" + label : "/jsecurity totp";
                    MessageUtil.sendError(p, "Cú pháp: " + prefix + " verify <6_số>");
                    return;
                }
                try {
                    int code = Integer.parseInt(args[paramIndex].trim());
                    boolean ok = tm.verifyCode(p.getUniqueId(), code);
                    if (ok) {
                        MessageUtil.sendSuccess(p, "Xác thực 2FA thành công! Quyền truy cập các lệnh /jexec, /jserver đã mở khóa.");
                    } else {
                        MessageUtil.sendError(p, "Mã xác thực 2FA không chính xác hoặc đã hết hạn! Vui lòng thử lại.");
                    }
                } catch (NumberFormatException e) {
                    MessageUtil.sendError(p, "Mã 2FA phải là số gồm 6 chữ số!");
                }
            }
            case "disable" -> {
                tm.removeSecret(p.getUniqueId());
                MessageUtil.sendWarning(p, "Đã hủy bỏ xác thực 2FA cho tài khoản của bạn.");
            }
            default -> {
                String prefix = isDirect ? "/" + label : "/jsecurity totp";
                MessageUtil.sendError(p, "Cú pháp: " + prefix + " <setup|verify|disable|status>");
            }
        }
    }

    private void sendGeneralStatus(CommandSender sender) {
        SecuritySentinel.SecurityReport rep = sentinel.performAudit();
        String scoreColor = rep.securityScore() >= 85 ? "§a§l" : (rep.securityScore() >= 60 ? "§e§l" : "§c§l");

        sender.sendMessage("§e╔════════════════════════════════════════════════════╗");
        sender.sendMessage("§e║      §c§lSECURITY SENTINEL §6Trung Tâm An Ninh Hợp Nhất §e   ║");
        sender.sendMessage("§e╚════════════════════════════════════════════════════╝");
        sender.sendMessage(" §8» §7Điểm đánh giá an ninh: " + scoreColor + rep.securityScore() + "% / 100%");
        sender.sendMessage(" §8» §7Các lớp phòng thủ tích hợp:");
        sender.sendMessage("   §8• §7Security Sandbox (Hộp cát chạy mã): " + (rep.sandboxActive() ? "§a[BẬT]" : "§c[TẮT]"));
        sender.sendMessage("   §8• §7ExploitGuard (Chặn packet & NBT): " + (rep.exploitGuardActive() ? "§a[BẬT]" : "§c[TẮT]"));
        sender.sendMessage("   §8• §7Tổng số hành vi gian lận đã chặn: §e" + rep.totalExploitsBlocked());
        sender.sendMessage("   §8• §7Số rủi ro nghiêm trọng phát hiện: " + (rep.highRiskFindings() > 0 ? "§c" + rep.highRiskFindings() : "§a0"));
        sender.sendMessage(" §8» §7Khuyến nghị bảo mật:");
        for (String rec : rep.recommendations()) {
            sender.sendMessage("   §8- §f" + rec);
        }
    }

    private void runAudit(CommandSender sender) {
        MessageUtil.sendInfo(sender, "Đang tiến hành kiểm toán an ninh toàn diện...");
        sendGeneralStatus(sender);
    }

    private void handleCveCommand(CommandSender sender, String label, String[] args) {
        var suite = plugin.getPanelCveProtection();
        if (suite == null) {
            MessageUtil.sendError(sender, "Phân hệ Panel CVE Defense hiện chưa được khởi tạo!");
            return;
        }

        String sub = args.length > 1 ? args[1].toLowerCase() : "status";
        switch (sub) {
            case "status" -> sendCveStatus(sender);
            case "audit", "check" -> {
                MessageUtil.sendInfo(sender, "Đang tiến hành kiểm toán bảo vệ Panel & Wings CVEs...");
                var rep = suite.performCveAudit();
                String scoreColor = rep.panelDefenseScore() >= 85 ? "§a§l" : (rep.panelDefenseScore() >= 60 ? "§e§l" : "§c§l");
                sender.sendMessage("§6§l[CVE AUDIT] §7Điểm phòng thủ Hosting: " + scoreColor + rep.panelDefenseScore() + "% / 100%");
                sender.sendMessage(" §8» §7Tổng số cuộc tấn công đã chặn: §e" + rep.totalAttacksBlocked());
                sender.sendMessage(" §8» §7Khuyến nghị bảo mật:");
                for (String rec : rep.recommendations()) {
                    sender.sendMessage("   §8• §f" + rec);
                }
            }
            case "honeypot" -> {
                var trapped = suite.getPterodactylHoneypot().getTrappedIps();
                sender.sendMessage("§c§l=== BẪY AN NINH HONEYPOT (CVE-2025-49132) ===");
                sender.sendMessage(" §8» §7Tổng số IP tấn công đã bị sập bẫy: §c" + trapped.size());
                if (trapped.isEmpty()) {
                    sender.sendMessage("   §a✔ Chưa có địa chỉ IP độc hại nào quét thăm dò.");
                } else {
                    for (var entry : trapped.entrySet()) {
                        long agoSec = (System.currentTimeMillis() - entry.getValue()) / 1000;
                        sender.sendMessage("   §8• §c" + entry.getKey() + " §7(Bị khóa cách đây " + agoSec + "s)");
                    }
                }
            }
            case "sessions" -> {
                var sessions = suite.getSessionRevocationManager().getActiveSessions();
                sender.sendMessage("§a§l=== PHIÊN TRUY CẬP ĐANG HOẠT ĐỘNG (GHSA-MGR9-6C2J-JXRQ) ===");
                sender.sendMessage(" §8» §7Số phiên Web IDE / Token còn hiệu lực: §e" + sessions.size());
                for (var s : sessions.values()) {
                    long activeAgo = (System.currentTimeMillis() - s.lastActiveAt()) / 1000;
                    sender.sendMessage("   §8• §f" + s.subject() + " §7(IP: §e" + s.clientIp() + "§7, hoạt động " + activeAgo + "s trước)");
                }
            }
            case "revoke" -> {
                if (args.length < 3) {
                    MessageUtil.sendError(sender, "Cú pháp: /" + label + " cve revoke <token|all>");
                    return;
                }
                String target = args[2];
                if (target.equalsIgnoreCase("all")) {
                    int count = suite.getSessionRevocationManager().purgeIdleZombieSessions();
                    MessageUtil.sendSuccess(sender, "Đã thu hồi tất cả phiên Zombie nhàn rỗi! (Đã xóa " + count + " phiên)");
                } else {
                    suite.getSessionRevocationManager().revokeSession(target, "Thu hồi thủ công bởi " + sender.getName());
                    MessageUtil.sendSuccess(sender, "Đã thu hồi phiên [" + target + "] thành công!");
                }
            }
            default -> {
                sender.sendMessage("§6§l=== LỆNH PHÒNG VỆ PANEL & WINGS CVE ===");
                sender.sendMessage(" §8» §e/" + label + " cve status §7- Xem ma trận trạng thái phòng thủ 10+ CVEs");
                sender.sendMessage(" §8» §e/" + label + " cve audit §7- Chạy kiểm toán chuyên sâu bảo mật Panel");
                sender.sendMessage(" §8» §e/" + label + " cve honeypot §7- Xem danh sách IP sập bẫy CVE-2025-49132");
                sender.sendMessage(" §8» §e/" + label + " cve sessions §7- Xem các phiên hoạt động (Anti-Zombie)");
                sender.sendMessage(" §8» §e/" + label + " cve revoke <token|all> §7- Thu hồi phiên tức thời");
            }
        }
    }

    private void sendCveStatus(CommandSender sender) {
        var suite = plugin.getPanelCveProtection();
        var stats = suite.getStats();
        var rep = suite.performCveAudit();
        String scoreColor = rep.panelDefenseScore() >= 85 ? "§a§l" : (rep.panelDefenseScore() >= 60 ? "§e§l" : "§c§l");

        sender.sendMessage("§6╔════════════════════════════════════════════════════════╗");
        sender.sendMessage("§6║    §c§lPANEL & WINGS CVE DEFENSE SUITE §eBảo Vệ Toàn Diện  §6║");
        sender.sendMessage("§6╚════════════════════════════════════════════════════════╝");
        sender.sendMessage(" §8» §7Điểm phòng thủ: " + scoreColor + rep.panelDefenseScore() + "% / 100% §7| Tổng chặn: §e" + rep.totalAttacksBlocked());
        sender.sendMessage(" §e§n1. Crafty Controller Protection:§r");
        sender.sendMessage("   §8• §7CVE-2025-14700 §c[9.9] §7Webhook SSTI/Reverse Shell: " + (suite.isSstiProtectionEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Chặn: §e" + stats.getCve2025_14700() + "§8)");
        sender.sendMessage("   §8• §7CVE-2026-0963  §c[9.9] §7File API Path Traversal: " + (suite.isPathTraversalProtectionEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Chặn: §e" + stats.getCve2026_0963() + "§8)");
        sender.sendMessage("   §8• §7CVE-2026-13716 §c[9.1] §7Nested .../...// Traversal: " + (suite.isPathTraversalProtectionEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Chặn: §e" + stats.getCve2026_13716() + "§8)");
        sender.sendMessage("   §8• §7CVE-2026-5652  §c[9.0] §7Control Plane Anomaly Guard: " + (suite.isSessionRevocationEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Chặn: §e" + stats.getCve2026_5652() + "§8)");
        sender.sendMessage("   §8• §7CVE-2025-14701 §e[7.1] §7Server MOTD Stored XSS: " + (suite.isMotdXssProtectionEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Chặn: §e" + stats.getCve2025_14701() + "§8)");
        sender.sendMessage("   §8• §7CVE-2025-5990  §e[6.7] §7Form & Server Name XSS: " + (suite.isEggSanitizerEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Chặn: §e" + stats.getCve2025_5990() + "§8)");
        sender.sendMessage("   §8• §7CVE-2024-1064  §e[7.5] §7Host Header Injection: " + (suite.isHostHeaderProtectionEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Chặn: §e" + stats.getCve2024_1064() + "§8)");
        sender.sendMessage(" §e§n2. Pterodactyl Panel + Wings Protection:§r");
        sender.sendMessage("   §8• §7CVE-2025-49132 §4[10.0] §7Unauth LFI/pearcmd Honeypot: " + (suite.isPterodactylLfiHoneypotEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Bẫy: §e" + stats.getCve2025_49132() + "§8)");
        sender.sendMessage("   §8• §7CVE-2024-27102 §4[10.0] §7Wings Sandbox Escape & Symlink: " + (suite.isWingsSandboxProtectionEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Chặn: §e" + stats.getCve2024_27102() + "§8)");
        sender.sendMessage("   §8• §7CVE-2024-34067 §e[6.1] §7Egg Import XSS Sanitizer: " + (suite.isEggSanitizerEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Chặn: §e" + stats.getCve2024_34067() + "§8)");
        sender.sendMessage("   §8• §7CVE-2024-49762 §a[4.6] §7Plaintext Password Log Redactor: " + (suite.isSensitiveLogRedactionEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Chặn: §e" + stats.getCve2024_49762() + "§8)");
        sender.sendMessage("   §8• §7GHSA-MGR9-6C2J §a[Low] §7Zombie Session Reaper: " + (suite.isSessionRevocationEnabled() ? "§a[BẬT] ✔" : "§c[TẮT] ✖") + " §8(Thu hồi: §e" + stats.getGhsaZombieSession() + "§8)");
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6§l=== LỆNH TRUNG TÂM AN NINH SECURITY SENTINEL ===");
        sender.sendMessage(" §8» §e/" + label + " status §7- Xem tổng quan điểm an ninh & trạng thái phòng thủ");
        sender.sendMessage(" §8» §e/" + label + " audit §7- Chạy kiểm toán an ninh toàn diện");
        sender.sendMessage(" §8» §e/" + label + " cve [status|audit|honeypot|sessions|revoke] §7- Bảo vệ Panel & Wings CVEs");
        sender.sendMessage(" §8» §e/" + label + " scan <plugin> §7- Quét mã độc trong Dynamic Plugin");
        sender.sendMessage(" §8» §e/" + label + " inspect <class> §7- Mổ xẻ và dịch ngược cấu trúc Bytecode");
        sender.sendMessage(" §8» §e/" + label + " exploit [args] §7- Điều khiển hệ thống chống Exploit");
        sender.sendMessage(" §8» §e/" + label + " totp <setup|verify|disable> §7- Xác thực 2 lớp Google Authenticator");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String in = args[0].toLowerCase();
            return List.of("status", "audit", "cve", "panel", "scan", "inspect", "exploit", "totp", "2fa").stream()
                    .filter(s -> s.startsWith(in))
                    .toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("cve") || sub.equals("panel")) {
                String in = args[1].toLowerCase();
                return List.of("status", "audit", "honeypot", "sessions", "revoke").stream()
                        .filter(s -> s.startsWith(in))
                        .toList();
            }
            if (sub.equals("totp") || sub.equals("2fa")) {
                String in = args[1].toLowerCase();
                return List.of("setup", "verify", "disable", "status").stream()
                        .filter(s -> s.startsWith(in))
                        .toList();
            }
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("cve") || args[0].equalsIgnoreCase("panel")) && args[1].equalsIgnoreCase("revoke")) {
            return List.of("all");
        }
        return Collections.emptyList();
    }
}
