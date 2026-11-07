package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class NeuralHeuristicEngine {

    public enum NeuralClassification {
        LEGIT_HUMAN,
        PVPER_HIGH_SKILL,
        SUSPICIOUS_HEURISTIC,
        ARTIFICIAL_KILL_AURA,
        BLATANT_AIMBOT
    }

    public record NeuralEvaluation(
            double anomalyScore,
            NeuralClassification classification,
            String primaryFeature,
            double primaryFeatureWeight,
            long inferenceTimeNanos
    ) {
        public boolean isFlagWorthy() {
            return anomalyScore >= 0.76;
        }

        public boolean isSuspicious() {
            return anomalyScore >= 0.48;
        }
    }

    private static final int INPUT_SIZE = 12;
    private static final int HIDDEN_1_SIZE = 16;
    private static final int HIDDEN_2_SIZE = 8;

    private static final String[] FEATURE_NAMES = {
            "DeltaYaw",
            "DeltaPitch",
            "YawAcceleration",
            "ClickRegularity",
            "HitboxOffset",
            "RayDistance",
            "AimRoboticness",
            "FovAngle",
            "PingJitter",
            "AttackFrequency",
            "SnapBackVelocity",
            "ObstructionFactor"
    };

    private static final double[][] W1 = new double[INPUT_SIZE][HIDDEN_1_SIZE];
    private static final double[] B1 = new double[HIDDEN_1_SIZE];
    private static final double[][] W2 = new double[HIDDEN_1_SIZE][HIDDEN_2_SIZE];
    private static final double[] B2 = new double[HIDDEN_2_SIZE];
    private static final double[] W3 = new double[HIDDEN_2_SIZE];
    private static final double B3 = -1.85;

    static {
        for (int i = 0; i < INPUT_SIZE; i++) {
            for (int j = 0; j < HIDDEN_1_SIZE; j++) {
                W1[i][j] = Math.max(0.0, Math.sin((i + 1) * 1.618 + j) * 0.30);
            }
        }
        for (int j = 0; j < HIDDEN_1_SIZE; j++) {
            B1[j] = -0.10;
            for (int k = 0; k < HIDDEN_2_SIZE; k++) {
                W2[j][k] = Math.max(0.0, Math.sin((j + 1) * 2.718 + k) * 0.32);
            }
        }
        for (int k = 0; k < HIDDEN_2_SIZE; k++) {
            B2[k] = -0.05;
            W3[k] = 0.50 + Math.cos(k * 1.414) * 0.20;
        }

        W1[0][0] = 0.95;
        W1[2][1] = 1.15;
        W1[3][2] = 1.25;
        W1[4][3] = 1.10;
        W1[5][4] = 1.35;
        W1[10][5] = 1.55;
        W1[11][6] = 1.30;
    }

    private final AntiCheatManager manager;

    public NeuralHeuristicEngine(AntiCheatManager manager) {
        this.manager = manager;
    }

    public NeuralEvaluation evaluateAttack(Player attacker, PlayerData d, Entity victim, double reachDist, boolean rayHit) {
        long t0 = System.nanoTime();

        double deltaYaw = d.yawDelta;
        double deltaPitch = d.pitchDelta;
        double prevDeltaYaw = d.lastDeltaYaw;
        double yawAccel = Math.abs(deltaYaw - prevDeltaYaw);
        d.lastDeltaYaw = deltaYaw;

        double clickRegularity = 0.0;
        if (d.clickDelays.size() >= 10 && d.clicks.size() >= 12) {
            double stdDev = AcUtil.standardDeviation(d.clickDelays);
            clickRegularity = Math.max(0.0, Math.min(1.0, (12.0 - stdDev) / 10.0));
        }

        Location eye = attacker.getEyeLocation();
        BoundingBox box = victim.getBoundingBox();
        Vector eyeToTarget = box.getCenter().subtract(eye.toVector());
        double len = eyeToTarget.length();
        double angleDeg = 0.0;
        if (len > 0.25) {
            eyeToTarget.multiply(1.0 / len);
            Vector lookDir = eye.getDirection().normalize();
            double dot = Math.max(-1.0, Math.min(1.0, lookDir.dot(eyeToTarget)));
            angleDeg = Math.toDegrees(Math.acos(dot));
        }

        double hitboxOffset = Math.min(1.0, Math.max(0.0, (angleDeg - 20.0) / 55.0));
        double normalizedReach = Math.min(1.5, Math.max(0.0, (reachDist - 2.65) / 0.85));
        double aimRoboticness = Math.max(0.0, Math.min(1.0, 1.0 - d.clusterSmoothness));
        double fovRatio = Math.min(1.0, Math.max(0.0, (angleDeg - 30.0) / 65.0));

        double pingJitter = Math.min(1.0, d.clusterPingJitter / 120.0);
        double attackFreq = Math.min(1.0, Math.max(0.0, (d.clicks.size() - 8.0) / 14.0));

        double snapBackVel = 0.0;
        if (prevDeltaYaw > 42.0 && deltaYaw < 4.0) {
            snapBackVel = 1.0;
        } else if (prevDeltaYaw > 30.0 && deltaYaw < 6.0) {
            snapBackVel = 0.55;
        }

        double obstructionFactor = rayHit ? 0.0 : 0.85;

        double[] input = new double[]{
                Math.min(1.5, Math.max(0.0, (deltaYaw - 12.0) / 70.0)),
                Math.min(1.5, Math.max(0.0, (deltaPitch - 10.0) / 55.0)),
                Math.min(1.5, Math.max(0.0, (yawAccel - 15.0) / 55.0)),
                clickRegularity,
                hitboxOffset,
                normalizedReach,
                aimRoboticness,
                fovRatio,
                pingJitter,
                attackFreq,
                snapBackVel,
                obstructionFactor
        };

        double score = forwardPass(input);

        int topIdx = 0;
        double maxWeight = -1.0;
        for (int i = 0; i < INPUT_SIZE; i++) {
            double impact = input[i] * (W1[i][0] + W1[i][1] + W1[i][2] + 0.5);
            if (impact > maxWeight) {
                maxWeight = impact;
                topIdx = i;
            }
        }

        NeuralClassification classification;
        if (score >= 0.84) {
            classification = NeuralClassification.BLATANT_AIMBOT;
        } else if (score >= 0.76) {
            classification = NeuralClassification.ARTIFICIAL_KILL_AURA;
        } else if (score >= 0.48) {
            classification = NeuralClassification.SUSPICIOUS_HEURISTIC;
        } else if (d.clicks.size() >= 11 && deltaYaw < 22.0) {
            classification = NeuralClassification.PVPER_HIGH_SKILL;
        } else {
            classification = NeuralClassification.LEGIT_HUMAN;
        }

        long elapsedNanos = System.nanoTime() - t0;
        d.combatAnomalyScore = (d.combatAnomalyScore * 0.7) + (score * 0.3);

        return new NeuralEvaluation(score, classification, FEATURE_NAMES[topIdx], input[topIdx], elapsedNanos);
    }

    public double selfTestSample(boolean cheatSample) {
        double[] input = cheatSample
                ? new double[]{1.2, 0.8, 1.3, 0.95, 0.9, 1.1, 0.85, 0.9, 0.3, 0.9, 1.0, 0.85}
                : new double[]{0.05, 0.02, 0.04, 0.0, 0.0, 0.1, 0.1, 0.0, 0.08, 0.15, 0.0, 0.0};
        return forwardPass(input);
    }

    private double forwardPass(double[] input) {
        double[] h1 = new double[HIDDEN_1_SIZE];
        for (int j = 0; j < HIDDEN_1_SIZE; j++) {
            double sum = B1[j];
            for (int i = 0; i < INPUT_SIZE; i++) {
                sum += input[i] * W1[i][j];
            }
            h1[j] = Math.max(0.0, sum);
        }

        double[] h2 = new double[HIDDEN_2_SIZE];
        for (int k = 0; k < HIDDEN_2_SIZE; k++) {
            double sum = B2[k];
            for (int j = 0; j < HIDDEN_1_SIZE; j++) {
                sum += h1[j] * W2[j][k];
            }
            h2[k] = Math.max(0.0, sum);
        }

        double logit = B3;
        for (int k = 0; k < HIDDEN_2_SIZE; k++) {
            logit += h2[k] * W3[k];
        }

        double score = 1.0 / (1.0 + Math.exp(-logit));
        return Math.max(0.0, Math.min(1.0, score));
    }
}
