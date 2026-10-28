package com.javastudio.security.cve;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trình Quản Lý & Thu Hồi Phiên Truy Cập Tức Thì (GHSA-MGR9-6C2J-JXRQ & CVE-2026-5652).
 * Giải quyết dứt điểm vấn đề Zombie Sessions / Không thu hồi quyền SFTP & API khi bị hạ cấp hoặc xóa,
 * đồng thời kiểm soát vòng đời phiên đăng nhập Web IDE và Remote Bridge.
 */
public final class SessionRevocationManager {

    private final PanelCveProtectionSuite suite;

    public record ActiveSession(String sessionId, String subject, long createdAt, long lastActiveAt, String clientIp) {}

    // Danh sách các phiên đang hoạt động: sessionId -> ActiveSession
    private final Map<String, ActiveSession> activeSessions = new ConcurrentHashMap<>();

    // Danh sách đen các token/session đã bị thu hồi cưỡng bức
    private final Set<String> revokedSessionBlacklist = ConcurrentHashMap.newKeySet();

    // Thời gian nhàn rỗi tối đa trước khi tự động kết liễu phiên (30 phút)
    private long maxIdleMs = 30 * 60 * 1000L;

    public SessionRevocationManager(PanelCveProtectionSuite suite) {
        this.suite = suite;
    }

    /**
     * Đăng ký một phiên hoạt động mới.
     */
    public void registerSession(String sessionId, String subject, String clientIp) {
        if (sessionId == null || sessionId.isBlank()) return;
        long now = System.currentTimeMillis();
        activeSessions.put(sessionId, new ActiveSession(sessionId, subject, now, now, clientIp));
        revokedSessionBlacklist.remove(sessionId);
    }

    /**
     * Cập nhật thời gian hoạt động của phiên.
     */
    public void touchSession(String sessionId) {
        ActiveSession s = activeSessions.get(sessionId);
        if (s != null) {
            activeSessions.put(sessionId, new ActiveSession(s.sessionId(), s.subject(), s.createdAt(), System.currentTimeMillis(), s.clientIp()));
        }
    }

    /**
     * Kiểm tra xem phiên có còn hiệu lực hay đã bị thu hồi / hết hạn (Zombie Session).
     */
    public boolean isValid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        if (revokedSessionBlacklist.contains(sessionId)) return false;

        ActiveSession s = activeSessions.get(sessionId);
        if (s == null) return false;

        long now = System.currentTimeMillis();
        if (now - s.lastActiveAt() > maxIdleMs) {
            // Đã nhàn rỗi quá thời gian cho phép -> tự động thu hồi
            revokeSession(sessionId, "Hết thời gian nhàn rỗi (Idle Timeout)");
            return false;
        }

        return true;
    }

    /**
     * Thu hồi lập tức một phiên cụ thể (Zombie Session Reaper).
     */
    public void revokeSession(String sessionId, String reason) {
        if (sessionId == null) return;
        ActiveSession s = activeSessions.remove(sessionId);
        revokedSessionBlacklist.add(sessionId);
        suite.getStats().incrementGhsaZombieSession();

        String sub = s != null ? s.subject() : "Unknown";
        suite.logAlert("GHSA-MGR9-6C2J-JXRQ",
                "Đã thu hồi lập tức phiên truy cập [" + sessionId + "] của đối tượng " + sub + ": " + reason);
    }

    /**
     * Thu hồi toàn bộ các phiên của một người dùng (khi bị hạ quyền, đổi mật khẩu, hoặc reset 2FA).
     */
    public int revokeAllForSubject(String subject, String reason) {
        if (subject == null) return 0;
        int count = 0;
        List<String> toRemove = new ArrayList<>();
        for (var entry : activeSessions.entrySet()) {
            if (subject.equalsIgnoreCase(entry.getValue().subject())) {
                toRemove.add(entry.getKey());
            }
        }
        for (String sid : toRemove) {
            revokeSession(sid, reason);
            count++;
        }
        return count;
    }

    /**
     * Quét và dọn dẹp các phiên Zombie nhàn rỗi.
     */
    public int purgeIdleZombieSessions() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (var entry : activeSessions.entrySet()) {
            if (now - entry.getValue().lastActiveAt() > maxIdleMs) {
                expired.add(entry.getKey());
            }
        }
        for (String sid : expired) {
            revokeSession(sid, "Zombie Session Reaper");
        }
        return expired.size();
    }

    public Map<String, ActiveSession> getActiveSessions() {
        return Collections.unmodifiableMap(activeSessions);
    }

    public Set<String> getRevokedSessionBlacklist() {
        return Collections.unmodifiableSet(revokedSessionBlacklist);
    }
}
