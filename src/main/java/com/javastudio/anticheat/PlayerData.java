package com.javastudio.anticheat;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dữ liệu theo dõi của một người chơi cho Anti-Cheat.
 * Chỉ được thao tác từ thread sở hữu người chơi (region thread trên Folia / main thread trên Paper),
 * ngoại trừ các mảng VL được đọc bởi tác vụ decay và lệnh quản trị.
 */
public final class PlayerData {

    public final UUID id;
    public final String name;

    // ===== Violation Level / buffer theo từng check =====
    public final double[] vl = new double[CheckType.values().length];
    public final double[] buffer = new double[CheckType.values().length];
    public final long[] lastAlert = new long[CheckType.values().length];
    public long totalFlags;

    // ===== Trạng thái chung =====
    public final long joinTime = System.currentTimeMillis();
    public volatile long exemptUntil;
    public volatile boolean alerts;
    public volatile boolean debug;
    public volatile boolean forceCheck; // Bật chế độ tự kiểm thử (quét cả khi có OP/bypass)
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
    AcUtil.Env lastEnv;

    // ===== Di chuyển =====
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
    public double fallDist;
    public double realFallDist;
    public long lastSteepDive;
    public int elytraAscendTicks;
    public int elytraFlatTicks;
    public int elytraHoverTicks;
    public final ArrayDeque<Long> glideToggles = new ArrayDeque<>();
    public Location lastSafeGround;
    public long lastMoveMs;
    public double timerBalance;
    public boolean wasGliding;
    public float lastYaw, lastPitch;
    public float yawDelta, pitchDelta;
    public long lastBigFlick;
    public boolean sprintingLast;

    // Velocity (AntiKB)
    public long pendingVelocityAt;
    public double pendingVelocityH;
    public Location pendingVelocityStart;

    // ===== Chiến đấu =====
    public final ArrayDeque<Long> clicks = new ArrayDeque<>();
    public final ArrayDeque<Integer> clickIntervals = new ArrayDeque<>();
    public long lastClick;
    public long lastSwing;
    public long lastAttack;
    public int noSwingStreak;
    public final Map<UUID, Long> recentTargets = new HashMap<>();
    public long lastBlockPlace;
    public long lastTotemPop;
    public long lastTotemEquip;
    public final ArrayDeque<Float> yawDeltas = new ArrayDeque<>();
    public final ArrayDeque<Float> pitchDeltas = new ArrayDeque<>();

    // ===== Combat V2 Heuristics (GCD, Snap, Multi-Target, Click Variance) =====
    public final ArrayDeque<Long> clickDelays = new ArrayDeque<>();
    public final ArrayDeque<Double> pitchGcdHistory = new ArrayDeque<>();
    public UUID lastVictimId;
    public Location lastVictimLoc;
    public long lastTargetSwitchTime;
    public float lastAttackYaw, lastAttackPitch;
    public long lastAttackRotationTime;
    public float preAttackYaw, preAttackPitch;

    // ===== Thế giới & Túi đồ & Packets =====
    public final ArrayDeque<Long> breaks = new ArrayDeque<>();
    public final ArrayDeque<Long> places = new ArrayDeque<>();
    public final ArrayDeque<Long> chestClicks = new ArrayDeque<>();
    public final ArrayDeque<Long> slotChanges = new ArrayDeque<>();
    public long digStart;
    public long digBlockKey = Long.MIN_VALUE;
    public long useStart;
    public org.bukkit.Material useItem;
    public long lastInvOpen;
    public long lastInvClose;
    public long lastArmorEquip;

    // ===== Staff Freeze System =====
    public volatile boolean isFrozen;
    public volatile Location frozenLocation;
    public volatile String frozenBy;
    public volatile long frozenAt;

    // ===== AuraBot Decoy Trap =====
    public volatile UUID activeAuraBotUuid;
    public volatile long auraBotSpawnedTime;

    // ===== Vehicle & BoatFly Checks =====
    public Location lastVehicleLoc;
    public int vehicleAirTicks;
    public long lastVehicleMove;
    public double vehicleLastDy;

    // ===== Baritone / Bot & AutoPot Heuristics =====
    public int cardinalYawStreak;
    public int baritoneMineStreak;
    public Location lastMinedBlockLoc;
    public long lastMinedBlockTime;
    public float lastMinedYaw;
    public long lastPitchSnapTime;
    public float preSnapPitch;

    // ===== Disabler / Ping Spoof =====
    public long lastMovePacketTime;
    public int pingSpoofBuffer;

    // ===== Hướng 1: Entropy & Physiological Tremor Anomaly Score =====
    public final ArrayDeque<Float> microYawDeltas = new ArrayDeque<>();
    public final ArrayDeque<Float> microPitchDeltas = new ArrayDeque<>();
    public double combatAnomalyScore;
    public int highAnomalyStreak;
    public long lastAnomalyCheckTime;

    // ===== Hướng 2: X-Ray Honeypot Trap & Ore Ratio =====
    public Location activeHoneypotLoc;
    public long honeypotCreatedTime;
    public int stoneMinedCount;
    public int preciousOreCount;
    public long lastOreMinedTime;

    // ===== Hướng 3: Ban Wave System =====
    public volatile boolean inBanWave;
    public volatile String banWaveReason;
    public volatile long banWaveAddedTime;

    // ===== Hướng 4: Inventory FSM Shield =====
    public enum InvState { CLOSED, OPENING, OPEN, INTERACTING }
    public InvState invState = InvState.CLOSED;
    public long lastInvStateChange;
    public long lastDropPacketTime;
    public long lastContainerClickTime;
    public int lastClickedSlot = -1;

    // ===== Hướng 2 (New): Physics Simulation & Timer/Blink Guard =====
    public final ArrayDeque<Long> movePackets = new ArrayDeque<>();
    public double expectedVy;
    public int physicsViolationStreak;
    public int timerViolations;

    // ===== Hướng 3 (New): Heuristic Clustering Profile & Metrics =====
    public enum ClusterGroup { LEGIT_CASUAL, LEGIT_PRO, SUSPICIOUS, DEFINITE_CHEAT }
    public ClusterGroup clusterGroup = ClusterGroup.LEGIT_CASUAL;
    public double clusterCpsStdDev = 5.0;
    public double clusterKurtosis = 3.0;
    public double clusterHitboxProximity = 0.5;
    public double clusterReactionLatency = 200.0;
    public double clusterPingJitter = 10.0;
    public double clusterSmoothness = 0.8;
    public final ArrayDeque<Integer> pingHistory = new ArrayDeque<>();

    // ===== Hướng 4 (New): Smart Report & Investigation =====
    public long investigationBoostUntil;
    public int reportCount;
    public long lastReportedTime;
    public long lastReportCommandTime;

    public PlayerData(UUID id, String name) {
        this.id = id;
        this.name = name;
        this.alerts = true;
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

    /** Đặt lại trạng thái di chuyển (khi teleport / đổi world / respawn). */
    public void resetMovement() {
        lastLoc = null;
        lastDx = lastDy = lastDz = lastH = 0;
        airTicks = 0;
        groundTicks = 0;
        hoverTicks = 0;
        liquidWalkTicks = 0;
        fastMoveStreak = 0;
        fallDist = 0;
        timerBalance = 0;
        lastMoveMs = 0;
        pendingVelocityAt = 0;
        pendingVelocityStart = null;
    }
}
