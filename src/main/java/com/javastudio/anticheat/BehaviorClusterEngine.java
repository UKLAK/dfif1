package com.javastudio.anticheat;

import org.bukkit.entity.Player;

import java.util.ArrayDeque;

/**
 * Hướng 3: Bộ phân tích hành vi đa biến (Multi-Variate Heuristic Clustering).
 * Đánh giá 6 chỉ số chuyên sâu để phân loại phong cách chơi thành:
 * LEGIT_CASUAL, LEGIT_PRO, SUSPICIOUS, DEFINITE_CHEAT.
 */
public final class BehaviorClusterEngine {

    private final AntiCheatManager manager;

    public BehaviorClusterEngine(AntiCheatManager manager) {
        this.manager = manager;
    }

    /**
     * Cập nhật và phân cụm hồ sơ hành vi người chơi dựa trên 6 chiều dữ liệu.
     */
    public void evaluatePlayer(Player p, PlayerData d) {
        // 1. Độ lệch chuẩn khoảng thời gian Click (CPS StdDev)
        double cpsStdDev = calculateStdDev(d.clickDelays);
        if (d.clickDelays.size() >= 8) {
            d.clusterCpsStdDev = cpsStdDev;
        }

        // 2. Độ nhọn phân phối gia tốc góc quay (Yaw Kurtosis)
        double kurtosis = calculateKurtosis(d.yawDeltas);
        if (d.yawDeltas.size() >= 15) {
            d.clusterKurtosis = kurtosis;
        }

        // 3. Tỷ lệ khóa tâm viền Hitbox (Hitbox Proximity: 0.0 tâm, 1.0 viền ngoài)
        if (d.lastVictimLoc != null && p.getLocation() != null) {
            double dist = p.getLocation().distance(d.lastVictimLoc);
            d.clusterHitboxProximity = Math.min(1.0, Math.max(0.0, (dist - 1.5) / 3.0));
        }

        // 4. Độ trễ phản xạ tâm ngắm (Reaction Latency: người thật 140-280ms, cheat < 50ms)
        if (d.lastTargetSwitchTime > 0) {
            long switchDelta = System.currentTimeMillis() - d.lastTargetSwitchTime;
            if (switchDelta < 1000) {
                d.clusterReactionLatency = Math.max(20.0, (double) switchDelta);
            }
        }

        // 5. Độ ổn định Ping (Ping Jitter)
        int currentPing = manager.safePing(p);
        d.pingHistory.addLast(currentPing);
        while (d.pingHistory.size() > 20) d.pingHistory.pollFirst();
        d.clusterPingJitter = calculateIntStdDev(d.pingHistory);

        // 6. Độ mượt đường ngắm (Tracking Smoothness)
        d.clusterSmoothness = Math.max(0.0, Math.min(1.0, 1.0 - (d.combatAnomalyScore * 0.8)));

        // ============================================================
        //  THUẬT TOÁN PHÂN CỤM (HEURISTIC CLUSTERING)
        // ============================================================
        int cps = d.clicks.size();

        if (d.clusterCpsStdDev < 5.0 && cps >= 13 && d.clickDelays.size() >= 12) {
            // Gian lận Autoclicker / Macro hoàn hảo
            d.clusterGroup = PlayerData.ClusterGroup.DEFINITE_CHEAT;
        } else if (d.combatAnomalyScore >= 0.75 && d.clusterReactionLatency < 50.0) {
            // Gian lận Killaura / Aimbot khóa tâm tức thì
            d.clusterGroup = PlayerData.ClusterGroup.DEFINITE_CHEAT;
        } else if (d.combatAnomalyScore >= 0.50 || (d.clusterCpsStdDev < 10.0 && cps >= 11)) {
            // Nghi vấn sử dụng hỗ trợ ngắm hoặc clicker có jitter nhẹ
            d.clusterGroup = PlayerData.ClusterGroup.SUSPICIOUS;
        } else if (cps >= 10 && d.clusterCpsStdDev >= 16.0 && d.clusterReactionLatency >= 130.0) {
            // Game thủ Pro (tốc độ tay cao nhưng sai số sinh học hoàn toàn tự nhiên)
            d.clusterGroup = PlayerData.ClusterGroup.LEGIT_PRO;
        } else {
            // Người chơi thông thường
            d.clusterGroup = PlayerData.ClusterGroup.LEGIT_CASUAL;
        }
    }

    private double calculateStdDev(ArrayDeque<Long> samples) {
        if (samples.size() < 3) return 25.0; // Mặc định tự nhiên
        double sum = 0.0;
        for (long s : samples) sum += s;
        double mean = sum / samples.size();
        double variance = 0.0;
        for (long s : samples) {
            variance += Math.pow(s - mean, 2);
        }
        return Math.sqrt(variance / samples.size());
    }

    private double calculateIntStdDev(ArrayDeque<Integer> samples) {
        if (samples.size() < 3) return 5.0;
        double sum = 0.0;
        for (int s : samples) sum += s;
        double mean = sum / samples.size();
        double variance = 0.0;
        for (int s : samples) {
            variance += Math.pow(s - mean, 2);
        }
        return Math.sqrt(variance / samples.size());
    }

    private double calculateKurtosis(ArrayDeque<Float> samples) {
        if (samples.size() < 6) return 3.0;
        double sum = 0.0;
        for (float s : samples) sum += s;
        double mean = sum / samples.size();

        double m2 = 0.0;
        double m4 = 0.0;
        for (float s : samples) {
            double diff = s - mean;
            m2 += Math.pow(diff, 2);
            m4 += Math.pow(diff, 4);
        }
        int n = samples.size();
        double variance = m2 / n;
        if (variance < 0.0001) return 3.0;
        return (m4 / n) / Math.pow(variance, 2);
    }
}
