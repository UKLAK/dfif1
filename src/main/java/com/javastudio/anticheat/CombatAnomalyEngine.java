package com.javastudio.anticheat;

import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hướng 1: Bộ Soi "Legit Cheat" Bằng Thống Kê Sinh Học & Entropy (Combat AI/Math Engine).
 * Phát hiện các bản ghost cheat tinh vi (Vape V4, Drip Lite, Rise, Slinky) sử dụng Humanizer/Smooth Aim
 * để vượt qua các bộ lọc GCD truyền thống.
 *
 * Áp dụng toán học cao cấp:
 * 1. Shannon Entropy: Đo độ hỗn loạn ngẫu nhiên của chuỗi góc quay (Người: H >= 3.0, Bot nội suy: H < 1.8).
 * 2. Physiological Tremor: Đo tần số rung lắc sinh học vi mô của ngón tay/cơ bắp (8 - 12Hz).
 * 3. Anomaly Score (0.00 - 1.00): Tổng hợp xác suất gian lận tích lũy.
 */
public final class CombatAnomalyEngine {

    private final AntiCheatManager manager;

    public CombatAnomalyEngine(AntiCheatManager manager) {
        this.manager = manager;
    }

    /**
     * Phân tích chuỗi góc quay vi mô của người chơi trong giao tranh.
     */
    public void analyzeCombatMotion(Player p, PlayerData d, float yawDelta, float pitchDelta) {
        if (!manager.isActive() || !manager.isEnabled(CheckType.AIM)) return;
        if (manager.isBypassed(p, d)) return;

        long now = System.currentTimeMillis();
        // Chỉ phân tích khi người chơi đang giao tranh (vừa đánh hoặc vung tay trong 1.5s)
        if (now - d.lastAttack > 1500 && now - d.lastSwing > 1200) return;

        // Bỏ qua khi người chơi đứng yên không cử động chuột
        if (yawDelta < 0.05f && pitchDelta < 0.05f) return;

        d.microYawDeltas.addLast(yawDelta);
        d.microPitchDeltas.addLast(pitchDelta);

        while (d.microYawDeltas.size() > 25) d.microYawDeltas.pollFirst();
        while (d.microPitchDeltas.size() > 25) d.microPitchDeltas.pollFirst();

        // Cần ít nhất 20 mẫu chuyển động liên tục để tính xác suất
        if (d.microPitchDeltas.size() < 20) return;

        // Giới hạn tần suất kiểm tra (mỗi 300ms 1 lần)
        if (now - d.lastAnomalyCheckTime < 300) return;
        d.lastAnomalyCheckTime = now;

        // 1. Tính Shannon Entropy cho Pitch & Yaw Delta
        double pitchEntropy = calculateShannonEntropy(d.microPitchDeltas);
        double yawEntropy = calculateShannonEntropy(d.microYawDeltas);
        double avgEntropy = (pitchEntropy + yawEntropy) / 2.0;

        // 2. Tính Tần số Rung Động Sinh Học (Physiological Tremor)
        int tremorCount = countTremorOscillations(d.microPitchDeltas);

        // 3. Tính Anomaly Score (0.0 - 1.0)
        double score = 0.0;

        // Điểm trừ Entropy thấp: Humanizer của cheat nội suy toán học nên entropy góc quay rất thấp
        if (avgEntropy < 1.65) {
            score += 0.40 * ((1.65 - avgEntropy) / 1.65);
        } else if (avgEntropy < 2.10) {
            score += 0.20;
        }

        // Điểm trừ thiếu rung vi mô sinh học (Tremor): Người thật luôn rung tay 8-12Hz (ít nhất 6 lần đổi hướng trong 20 tick)
        if (tremorCount <= 1) {
            score += 0.35; // Hoàn toàn không có rung sinh học
        } else if (tremorCount <= 3) {
            score += 0.15;
        }

        // Điểm trừ quỹ đạo quá hoàn hảo (Yaw vs Pitch correlation)
        if (yawDelta > 1.5f && pitchDelta < 0.005f) {
            score += 0.25; // Khóa tuyệt đối trục Y
        }

        d.combatAnomalyScore = score;

        // 4. Phát hiện bất thường
        if (score >= 0.70) {
            d.highAnomalyStreak++;
            if (d.highAnomalyStreak >= 3) {
                String detail = String.format(Locale.ROOT, "Legit Aim Anomaly (Score=%.2f, H_P=%.2f, H_Y=%.2f, Tremor=%d/20)",
                        score, pitchEntropy, yawEntropy, tremorCount);
                manager.flag(p, d, CheckType.AIM, detail, 1.0);
                d.highAnomalyStreak = 0;
            }
        } else {
            d.highAnomalyStreak = Math.max(0, d.highAnomalyStreak - 1);
        }
    }

    /**
     * Tính toán Shannon Entropy: H = - sum( P(x) * log2(P(x)) )
     * Chia dữ liệu thành các thùng (bins) độ rộng 0.1 độ.
     */
    private double calculateShannonEntropy(ArrayDeque<Float> deltas) {
        if (deltas.isEmpty()) return 0.0;
        Map<Integer, Integer> binCounts = new HashMap<>();
        int total = deltas.size();

        for (float f : deltas) {
            // Bin width = 0.1 độ
            int bin = Math.round(f * 10.0f);
            binCounts.put(bin, binCounts.getOrDefault(bin, 0) + 1);
        }

        double entropy = 0.0;
        for (int count : binCounts.values()) {
            double p = (double) count / total;
            if (p > 0.0) {
                entropy -= p * (Math.log(p) / Math.log(2.0));
            }
        }
        return entropy;
    }

    /**
     * Đếm số lần đảo chiều vi mô của góc quay (Physiological Tremor).
     * Bàn tay người luôn rung lắc nhẹ tạo ra các điểm đổi dấu vi sai (sign changes).
     */
    private int countTremorOscillations(ArrayDeque<Float> deltas) {
        if (deltas.size() < 3) return 0;
        Float[] arr = deltas.toArray(new Float[0]);
        int oscillations = 0;

        for (int i = 1; i < arr.length - 1; i++) {
            float prevDiff = arr[i] - arr[i - 1];
            float nextDiff = arr[i + 1] - arr[i];
            // Đổi chiều vi sai (đỉnh hoặc đáy sóng rung động)
            if ((prevDiff > 0.01f && nextDiff < -0.01f) || (prevDiff < -0.01f && nextDiff > 0.01f)) {
                oscillations++;
            }
        }
        return oscillations;
    }
}
