package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public final class BehaviorClusterEngine {

    private final AntiCheatManager manager;

    public BehaviorClusterEngine(AntiCheatManager manager) {
        this.manager = manager;
    }

    public void evaluatePlayer(Player p, PlayerData d) {
        long now = System.currentTimeMillis();
        if (now - d.lastClusterEval < 500L) return;
        d.lastClusterEval = now;

        double cpsStdDev = calculateStdDev(d.clickDelays);
        if (d.clickDelays.size() >= 8) {
            d.clusterCpsStdDev = cpsStdDev;
        }

        double kurtosis = calculateKurtosis(d.yawDeltas);
        if (d.yawDeltas.size() >= 12) {
            d.clusterKurtosis = kurtosis;
        }

        Location pLoc = p.getLocation();
        if (d.lastVictimLoc != null && pLoc != null && pLoc.getWorld() != null
                && d.lastVictimLoc.getWorld() == pLoc.getWorld()) {
            double dist = pLoc.distance(d.lastVictimLoc);
            d.clusterHitboxProximity = Math.min(1.0, Math.max(0.0, (dist - 1.5) / 3.0));
        }

        if (d.lastTargetSwitchTime > 0) {
            long switchDelta = now - d.lastTargetSwitchTime;
            if (switchDelta < 1000) {
                d.clusterReactionLatency = Math.max(20.0, (double) switchDelta);
            }
        }

        int currentPing = manager.safePing(p);
        d.pingHistory.addLast(currentPing);
        while (d.pingHistory.size() > 20) d.pingHistory.pollFirst();
        d.clusterPingJitter = calculateIntStdDev(d.pingHistory);

        double entropy = calculateEntropy(d.yawDeltas);
        double smoothnessFromAnomaly = Math.max(0.0, Math.min(1.0, 1.0 - (d.combatAnomalyScore * 0.75)));
        if (entropy < 1.8 && d.yawDeltas.size() >= 10) {
            smoothnessFromAnomaly = Math.max(0.1, smoothnessFromAnomaly * 0.6);
        }
        d.clusterSmoothness = smoothnessFromAnomaly;

        int cps = d.clicks.size();

        if (d.clusterCpsStdDev < 4.2 && cps >= 14 && d.clickDelays.size() >= 14) {
            d.clusterGroup = PlayerData.ClusterGroup.DEFINITE_CHEAT;
        } else if (d.combatAnomalyScore >= 0.78 && d.clusterReactionLatency < 50.0) {
            d.clusterGroup = PlayerData.ClusterGroup.DEFINITE_CHEAT;
        } else if (d.combatAnomalyScore >= 0.52 || (d.clusterCpsStdDev < 8.0 && cps >= 12)) {
            d.clusterGroup = PlayerData.ClusterGroup.SUSPICIOUS;
        } else if (cps >= 10 && d.clusterCpsStdDev >= 14.0 && d.clusterReactionLatency >= 110.0) {
            d.clusterGroup = PlayerData.ClusterGroup.LEGIT_PRO;
        } else {
            d.clusterGroup = PlayerData.ClusterGroup.LEGIT_CASUAL;
        }
    }

    public double calculateEntropy(ArrayDeque<Float> samples) {
        if (samples.size() < 5) return 3.0;
        Map<Integer, Integer> freq = new HashMap<>();
        for (float s : samples) {
            int bin = (int) Math.floor(Math.abs(s) * 2.0);
            freq.put(bin, freq.getOrDefault(bin, 0) + 1);
        }
        double entropy = 0.0;
        int n = samples.size();
        for (int count : freq.values()) {
            double p = (double) count / n;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    private double calculateStdDev(ArrayDeque<Long> samples) {
        if (samples.size() < 3) return 25.0;
        double sum = 0.0;
        for (long s : samples) sum += s;
        double mean = sum / samples.size();
        double variance = 0.0;
        for (long s : samples) {
            double diff = s - mean;
            variance += diff * diff;
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
            double diff = s - mean;
            variance += diff * diff;
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
            double d2 = diff * diff;
            m2 += d2;
            m4 += d2 * d2;
        }
        int n = samples.size();
        double variance = m2 / n;
        if (variance < 0.0001) return 3.0;
        return (m4 / n) / (variance * variance);
    }
}
