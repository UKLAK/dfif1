package com.javastudio.anticheat;

import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class CombatAnomalyEngine {

    private final AntiCheatManager manager;

    public CombatAnomalyEngine(AntiCheatManager manager) {
        this.manager = manager;
    }

    public void analyzeCombatMotion(Player p, PlayerData d, float yawDelta, float pitchDelta) {
        if (!manager.isActive() || !manager.isEnabled(CheckType.AIM)) return;
        if (manager.isBypassed(p, d) || AcUtil.isBedrockPlayer(p)) return;

        long now = System.currentTimeMillis();
        if (now - d.lastAttack > 850L) return;

        if (yawDelta < 0.12f && pitchDelta < 0.12f) return;

        d.microYawDeltas.addLast(yawDelta);
        d.microPitchDeltas.addLast(pitchDelta);

        while (d.microYawDeltas.size() > 25) d.microYawDeltas.pollFirst();
        while (d.microPitchDeltas.size() > 25) d.microPitchDeltas.pollFirst();

        if (d.microPitchDeltas.size() < 22) return;

        if (now - d.lastAnomalyCheckTime < 350L) return;
        d.lastAnomalyCheckTime = now;

        double pitchEntropy = calculateShannonEntropy(d.microPitchDeltas);
        double yawEntropy = calculateShannonEntropy(d.microYawDeltas);
        double avgEntropy = (pitchEntropy + yawEntropy) / 2.0;

        int tremorCount = countTremorOscillations(d.microPitchDeltas);

        double score = 0.0;

        if (avgEntropy < 1.35) {
            score += 0.45 * ((1.35 - avgEntropy) / 1.35);
        } else if (avgEntropy < 1.75) {
            score += 0.18;
        }

        if (tremorCount == 0) {
            score += 0.35;
        } else if (tremorCount <= 2) {
            score += 0.15;
        }

        if (yawDelta > 4.0f && pitchDelta < 0.002f) {
            score += 0.25;
        }

        d.combatAnomalyScore = score;

        if (score >= 0.78) {
            d.highAnomalyStreak++;
            if (d.highAnomalyStreak >= 4) {
                String detail = String.format(Locale.ROOT, "Robotic Aim Anomaly (Score=%.2f, H_P=%.2f, H_Y=%.2f, Tremor=%d/22)",
                        score, pitchEntropy, yawEntropy, tremorCount);
                manager.flag(p, d, CheckType.AIM, detail, 1.0);
                d.highAnomalyStreak = 1;
            }
        } else {
            d.highAnomalyStreak = Math.max(0, d.highAnomalyStreak - 1);
        }
    }

    private double calculateShannonEntropy(ArrayDeque<Float> deltas) {
        if (deltas.isEmpty()) return 0.0;
        Map<Integer, Integer> binCounts = new HashMap<>();
        int total = deltas.size();

        for (float f : deltas) {
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

    private int countTremorOscillations(ArrayDeque<Float> deltas) {
        if (deltas.size() < 3) return 0;
        Float[] arr = deltas.toArray(new Float[0]);
        int oscillations = 0;

        for (int i = 1; i < arr.length - 1; i++) {
            float prevDiff = arr[i] - arr[i - 1];
            float nextDiff = arr[i + 1] - arr[i];
            if ((prevDiff > 0.01f && nextDiff < -0.01f) || (prevDiff < -0.01f && nextDiff > 0.01f)) {
                oscillations++;
            }
        }
        return oscillations;
    }
}
