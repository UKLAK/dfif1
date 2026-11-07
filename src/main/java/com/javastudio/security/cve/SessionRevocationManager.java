package com.javastudio.security.cve;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionRevocationManager {

    private final PanelCveProtectionSuite suite;

    public record ActiveSession(String sessionId, String subject, long createdAt, long lastActiveAt, String clientIp) {}

    private final Map<String, ActiveSession> activeSessions = new ConcurrentHashMap<>();

    private final Set<String> revokedSessionBlacklist = ConcurrentHashMap.newKeySet();

    private long maxIdleMs = 30 * 60 * 1000L;

    public SessionRevocationManager(PanelCveProtectionSuite suite) {
        this.suite = suite;
    }

    public void registerSession(String sessionId, String subject, String clientIp) {
        if (sessionId == null || sessionId.isBlank()) return;
        long now = System.currentTimeMillis();
        activeSessions.put(sessionId, new ActiveSession(sessionId, subject, now, now, clientIp));
        revokedSessionBlacklist.remove(sessionId);
    }

    public void touchSession(String sessionId) {
        ActiveSession s = activeSessions.get(sessionId);
        if (s != null) {
            activeSessions.put(sessionId, new ActiveSession(s.sessionId(), s.subject(), s.createdAt(), System.currentTimeMillis(), s.clientIp()));
        }
    }

    public boolean isValid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        if (revokedSessionBlacklist.contains(sessionId)) return false;

        ActiveSession s = activeSessions.get(sessionId);
        if (s == null) return false;

        long now = System.currentTimeMillis();
        if (now - s.lastActiveAt() > maxIdleMs) {
            revokeSession(sessionId, "Hết thời gian nhàn rỗi (Idle Timeout)");
            return false;
        }

        return true;
    }

    public void revokeSession(String sessionId, String reason) {
        if (sessionId == null) return;
        ActiveSession s = activeSessions.remove(sessionId);
        revokedSessionBlacklist.add(sessionId);
        suite.getStats().incrementGhsaZombieSession();

        String sub = s != null ? s.subject() : "Unknown";
        suite.logAlert("GHSA-MGR9-6C2J-JXRQ",
                "Đã thu hồi lập tức phiên truy cập [" + sessionId + "] của đối tượng " + sub + ": " + reason);
    }

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
