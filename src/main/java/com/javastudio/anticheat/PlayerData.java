package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlayerData {

    public record ScaffoldBlockRecord(String world, int x, int y, int z, Material type, long time) {}

    public final UUID id;
    public final String name;

    public final double[] vl = new double[CheckType.values().length];
    public final double[] buffer = new double[CheckType.values().length];
    public final long[] lastAlert = new long[CheckType.values().length];
    public long totalFlags;

    public final long joinTime = System.currentTimeMillis();
    public volatile long exemptUntil;
    public volatile boolean alerts;
    public volatile boolean debug;
    public volatile boolean forceCheck;
    public long lastTeleport;
    public long lastVelocity;
    public long lastDamage;
    public long lastRiptide;
    public long lastGlideStop;
    public long lastGlideStart;
    public long lastFirework;
    public long lastBounce;
    public long lastIce;
    public long lastFallDamage;
    public long lastGameModeChange;
    public long lastRespawn;
    public long lastSetback;
    public long lastPunish;
    public boolean bypass;
    public long bypassCheckedAt;
    public long lastSpecial;
    public long lastExplosionBoost;
    public long lastWindCharge;
    public long lastMaceSmash;
    AcUtil.Env lastEnv;
    AcUtil.Env prevEnv;

    public Location lastLoc;
    public Location setback;
    public double lastDx, lastDy, lastDz, lastH;
    public boolean serverGround;
    public boolean lastServerGround;
    public int airTicks;
    public int groundTicks;
    public int hoverTicks;
    public int liquidWalkTicks;
    public int fastMoveStreak;
    public int useTicks;
    public int omniTicks;
    public int recentSprintTicks;
    public double fallDist;
    public double realFallDist;
    public long lastSteepDive;
    public int elytraAscendTicks;
    public int elytraFlatTicks;
    public int elytraHoverTicks;
    public final ArrayDeque<Long> glideToggles = new ArrayDeque<>();
    public Location lastSafeGround;
    public long lastMoveMs;
    public long lastPosMoveMs;
    public long prevPosMoveMs;
    public double timerBalance;
    public boolean wasGliding;
    public float lastYaw, lastPitch;
    public boolean hasRotationSample;
    public double lastDeltaYaw;
    public double neuralScore;
    public String neuralClassification = "LEGIT_HUMAN";
    public float yawDelta, pitchDelta;
    public long lastBigFlick;
    public boolean sprintingLast;

    public long pendingVelocityAt;
    public double pendingVelocityH;
    public double pendingVelocityX, pendingVelocityY, pendingVelocityZ;
    public Location pendingVelocityStart;

    public final java.util.concurrent.ConcurrentLinkedDeque<Long> clicks = new java.util.concurrent.ConcurrentLinkedDeque<>();
    public final ArrayDeque<Integer> clickIntervals = new ArrayDeque<>();
    public long lastClick;
    public long lastSwing;
    public long lastAttack;
    public boolean pendingSwingCheck;
    public long pendingSwingAttackAt;
    public int noSwingStreak;
    public final Map<UUID, Long> recentTargets = new HashMap<>();
    public long lastBlockPlace;
    public long lastTotemPop;
    public long lastTotemEquip;
    public final ArrayDeque<Float> yawDeltas = new ArrayDeque<>();
    public final ArrayDeque<Float> pitchDeltas = new ArrayDeque<>();

    public final ArrayDeque<Long> clickDelays = new ArrayDeque<>();
    public final ArrayDeque<Double> pitchGcdHistory = new ArrayDeque<>();
    public UUID lastVictimId;
    public Location lastVictimLoc;
    public long lastTargetSwitchTime;
    public float lastAttackYaw, lastAttackPitch;
    public long lastAttackRotationTime;
    public float preAttackYaw, preAttackPitch;

    public final ArrayDeque<Long> breaks = new ArrayDeque<>();
    public final ArrayDeque<Long> places = new ArrayDeque<>();
    public final ArrayDeque<Long> chestClicks = new ArrayDeque<>();
    public final ArrayDeque<Long> slotChanges = new ArrayDeque<>();
    public final ArrayDeque<ScaffoldBlockRecord> recentScaffoldBlocks = new ArrayDeque<>();
    public long digStart;
    public long digBlockKey = Long.MIN_VALUE;
    public long lastAbortDigTime;
    public long lastInteractBlockTime;
    public long useStart;
    public long lastConsumeTime;
    public Material useItem;
    public long lastInvOpen;
    public long lastInvClose;
    public long lastArmorEquip;

    public Location lastNukerBlockLoc;
    public long lastNukerBreakAt;
    public Vector lastNukerDir;
    public Location lastOreVeinLoc;

    public volatile boolean isFrozen;
    public volatile Location frozenLocation;
    public volatile String frozenBy;
    public volatile long frozenAt;

    public volatile UUID activeAuraBotUuid;
    public volatile long auraBotSpawnedTime;

    public Location lastVehicleLoc;
    public int vehicleAirTicks;
    public long lastVehicleMove;
    public double vehicleLastDy;

    public int cardinalYawStreak;
    public int baritoneMineStreak;
    public Location lastMinedBlockLoc;
    public long lastMinedBlockTime;
    public float lastMinedYaw;
    public long lastPitchSnapTime;
    public float preSnapPitch;

    public long lastMovePacketTime;
    public int pingSpoofBuffer;

    public final ArrayDeque<Float> microYawDeltas = new ArrayDeque<>();
    public final ArrayDeque<Float> microPitchDeltas = new ArrayDeque<>();
    public double combatAnomalyScore;
    public int highAnomalyStreak;
    public long lastAnomalyCheckTime;

    public Location activeHoneypotLoc;
    public long honeypotCreatedTime;
    public int stoneMinedCount;
    public int preciousOreCount;
    public long lastOreMinedTime;

    public volatile boolean inBanWave;
    public volatile String banWaveReason;
    public volatile long banWaveAddedTime;

    public enum InvState { CLOSED, OPENING, OPEN, INTERACTING }
    public InvState invState = InvState.CLOSED;
    public long lastInvStateChange;
    public long lastDropPacketTime;
    public long lastContainerClickTime;
    public int lastClickedSlot = -1;

    public final ArrayDeque<Long> movePackets = new ArrayDeque<>();
    public double expectedVy;
    public int physicsViolationStreak;
    public int timerViolations;

    public enum ClusterGroup { LEGIT_CASUAL, LEGIT_PRO, SUSPICIOUS, DEFINITE_CHEAT }
    public ClusterGroup clusterGroup = ClusterGroup.LEGIT_CASUAL;
    public double clusterCpsStdDev = 5.0;
    public double clusterKurtosis = 3.0;
    public double clusterHitboxProximity = 0.5;
    public double clusterReactionLatency = 200.0;
    public double clusterPingJitter = 10.0;
    public double clusterSmoothness = 0.8;
    public final ArrayDeque<Integer> pingHistory = new ArrayDeque<>();
    public long lastClusterEval;

    public long investigationBoostUntil;
    public int reportCount;
    public long lastReportedTime;
    public long lastReportCommandTime;

    public volatile boolean pendingKick;
    public volatile long pendingKickAt;
    public volatile long packetWindowSec;
    public final AtomicInteger inboundPacketRate = new AtomicInteger();
    public final AtomicInteger nettyCrashStrikes = new AtomicInteger();

    public PlayerData(UUID id, String name) {
        this.id = id;
        this.name = name;
        this.alerts = true;
    }

    public int getLiveCps() {
        long cutoff = System.currentTimeMillis() - 1000L;
        while (true) {
            Long first = clicks.peekFirst();
            if (first == null || first >= cutoff) break;
            clicks.pollFirst();
        }
        return clicks.size();
    }

    public double getTotalVl() {
        double sum = 0;
        for (double v : vl) sum += v;
        return sum;
    }

    public void resetViolations() {
        java.util.Arrays.fill(vl, 0.0);
        java.util.Arrays.fill(buffer, 0.0);
    }

    public void resetMovement() {
        lastLoc = null;
        lastDx = lastDy = lastDz = lastH = 0;
        airTicks = 0;
        groundTicks = 0;
        hoverTicks = 0;
        liquidWalkTicks = 0;
        fastMoveStreak = 0;
        omniTicks = 0;
        recentSprintTicks = 0;
        fallDist = 0;
        physicsViolationStreak = 0;
        timerBalance = 0;
        lastMoveMs = 0;
        lastPosMoveMs = 0;
        prevPosMoveMs = 0;
        pendingVelocityAt = 0;
        pendingVelocityStart = null;
    }
}
