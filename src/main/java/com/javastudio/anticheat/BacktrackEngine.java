package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BacktrackEngine {

    public static final int MAX_HISTORY_TICKS = 30;
    public static final double VANILLA_MAX_REACH = 3.00;
    public static final double STRICT_MAX_REACH = 3.05;

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    private final Map<UUID, EntityHistory> historyMap = new ConcurrentHashMap<>();

    private PlatformUtil.TaskHandle recordTaskHandle;
    private int currentTick = 0;
    private boolean enabled = true;

    public BacktrackEngine(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void start() {
        if (recordTaskHandle != null) recordTaskHandle.cancel();
        recordTaskHandle = PlatformUtil.runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (recordTaskHandle != null) {
            recordTaskHandle.cancel();
            recordTaskHandle = null;
        }
        historyMap.clear();
    }

    public void removeEntity(UUID uuid) {
        if (uuid != null) {
            historyMap.remove(uuid);
        }
    }

    private void tick() {
        if (!enabled) return;
        currentTick++;
        long now = System.currentTimeMillis();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isValid() || p.isDead()) continue;
            recordEntity(p, now);
        }

        if (currentTick % 200 == 0) {
            historyMap.entrySet().removeIf(entry -> {
                Player online = Bukkit.getPlayer(entry.getKey());
                if (online != null && online.isOnline()) return false;
                return now - entry.getValue().lastUpdatedAt() > 5000L;
            });
        }
    }

    public void recordEntity(LivingEntity entity, long timestamp) {
        UUID uuid = entity.getUniqueId();
        EntityHistory history = historyMap.computeIfAbsent(uuid, k -> new EntityHistory(MAX_HISTORY_TICKS));
        BoundingBox box = entity.getBoundingBox();
        Location loc = entity.getLocation();
        history.addFrame(new HistoricalFrame(currentTick, timestamp, box.clone(), loc.clone()));
    }

    public BacktrackResult evaluateAttack(Player attacker, Entity victim) {
        Location eye = attacker.getEyeLocation();
        Vector eyeVec = eye.toVector();
        Vector lookDir = eye.getDirection().normalize();
        BoundingBox currentBox = victim.getBoundingBox();

        double directDistance = minEyeDistance(attacker, eyeVec, currentBox);

        EntityHistory history = historyMap.get(victim.getUniqueId());
        if (history == null || history.isEmpty()) {
            boolean rayHits = rayIntersectsBox(eyeVec, lookDir, currentBox.clone().expand(0.12), 6.5);
            return new BacktrackResult(directDistance, directDistance, rayHits, 0, 0, currentBox, currentBox, false);
        }

        int ping = manager.safePing(attacker);
        long latencyMs = Math.max(10L, (ping / 2L) + 25L);
        long now = System.currentTimeMillis();
        long targetTimestamp = now - latencyMs;

        InterpolatedBox interpolated = history.interpolateBox(targetTimestamp);
        BoundingBox historicalBox = interpolated != null ? interpolated.box() : currentBox;
        int ticksBack = interpolated != null ? interpolated.ticksBack() : 0;

        double historicalDistance = minEyeDistance(attacker, eyeVec, historicalBox);

        boolean rayHits = rayIntersectsBox(eyeVec, lookDir, currentBox.clone().expand(0.12), 6.5)
                || rayIntersectsBox(eyeVec, lookDir, historicalBox.clone().expand(0.12), 6.5);

        double minLegitDistance = Math.min(directDistance, historicalDistance);

        InterpolatedBox prevTickBox = history.interpolateBox(targetTimestamp - 50L);
        if (prevTickBox != null) {
            minLegitDistance = Math.min(minLegitDistance, minEyeDistance(attacker, eyeVec, prevTickBox.box()));
            if (!rayHits) {
                rayHits = rayIntersectsBox(eyeVec, lookDir, prevTickBox.box().clone().expand(0.12), 6.5);
            }
        }

        return new BacktrackResult(minLegitDistance, historicalDistance, rayHits, ticksBack, latencyMs, historicalBox, currentBox, true);
    }

    private double minEyeDistance(Player attacker, Vector currentEye, BoundingBox box) {
        double best = AcUtil.distanceToBox(currentEye, box);
        Location pLoc = attacker.getLocation();
        Vector standEye = new Vector(pLoc.getX(), pLoc.getY() + 1.62, pLoc.getZ());
        Vector sneakEye = new Vector(pLoc.getX(), pLoc.getY() + 1.27, pLoc.getZ());
        best = Math.min(best, AcUtil.distanceToBox(standEye, box));
        best = Math.min(best, AcUtil.distanceToBox(sneakEye, box));

        PlayerData d = manager.peek(attacker.getUniqueId());
        if (d != null && d.lastLoc != null && d.lastLoc.getWorld() == pLoc.getWorld()) {
            Vector lastStandEye = new Vector(d.lastLoc.getX(), d.lastLoc.getY() + 1.62, d.lastLoc.getZ());
            best = Math.min(best, AcUtil.distanceToBox(lastStandEye, box));
        }
        return best;
    }

    public static boolean rayIntersectsBox(Vector origin, Vector dir, BoundingBox box, double maxDistance) {
        if (box.contains(origin)) return true;

        double invX = 1.0 / (Math.abs(dir.getX()) < 1e-9 ? 1e-9 : dir.getX());
        double tmin = (box.getMinX() - origin.getX()) * invX;
        double tmax = (box.getMaxX() - origin.getX()) * invX;
        if (tmin > tmax) { double tmp = tmin; tmin = tmax; tmax = tmp; }

        double invY = 1.0 / (Math.abs(dir.getY()) < 1e-9 ? 1e-9 : dir.getY());
        double tymin = (box.getMinY() - origin.getY()) * invY;
        double tymax = (box.getMaxY() - origin.getY()) * invY;
        if (tymin > tymax) { double tmp = tymin; tymin = tymax; tymax = tmp; }

        if ((tmin > tymax) || (tymin > tmax)) return false;
        if (tymin > tmin) tmin = tymin;
        if (tymax < tmax) tmax = tymax;

        double invZ = 1.0 / (Math.abs(dir.getZ()) < 1e-9 ? 1e-9 : dir.getZ());
        double tzmin = (box.getMinZ() - origin.getZ()) * invZ;
        double tzmax = (box.getMaxZ() - origin.getZ()) * invZ;
        if (tzmin > tzmax) { double tmp = tzmin; tzmin = tzmax; tzmax = tmp; }

        if ((tmin > tzmax) || (tzmin > tmax)) return false;
        if (tzmin > tmin) tmin = tzmin;
        if (tzmax < tmax) tmax = tzmax;

        return tmax >= 0 && tmin <= maxDistance;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public record HistoricalFrame(int tick, long timestamp, BoundingBox box, Location location) {}

    public record InterpolatedBox(BoundingBox box, int ticksBack) {}

    public record BacktrackResult(
            double minDistance,
            double historicalDistance,
            boolean rayIntersects,
            int ticksBack,
            long latencyMs,
            BoundingBox historicalBox,
            BoundingBox currentBox,
            boolean hasHistory
    ) {
        public boolean isReachViolation(double maxAllowed) {
            return minDistance > maxAllowed;
        }

        public boolean isMissRayTrace() {
            return !rayIntersects;
        }
    }

    public static final class EntityHistory {
        private final HistoricalFrame[] buffer;
        private int head = 0;
        private int size = 0;
        private final int capacity;
        private volatile long lastUpdated;

        public EntityHistory(int capacity) {
            this.capacity = capacity;
            this.buffer = new HistoricalFrame[capacity];
        }

        public synchronized void addFrame(HistoricalFrame frame) {
            buffer[head] = frame;
            head = (head + 1) % capacity;
            if (size < capacity) size++;
            lastUpdated = frame.timestamp();
        }

        public long lastUpdatedAt() {
            return lastUpdated;
        }

        public synchronized boolean isEmpty() {
            return size == 0;
        }

        public synchronized InterpolatedBox interpolateBox(long targetTimestamp) {
            if (size == 0) return null;

            HistoricalFrame closestBefore = null;
            HistoricalFrame closestAfter = null;

            for (int i = 0; i < size; i++) {
                int idx = (head - 1 - i + capacity) % capacity;
                HistoricalFrame f = buffer[idx];
                if (f == null) continue;

                if (f.timestamp() <= targetTimestamp) {
                    closestBefore = f;
                    break;
                }
                closestAfter = f;
            }

            if (closestBefore == null) {
                int oldestIdx = (head - size + capacity) % capacity;
                return new InterpolatedBox(buffer[oldestIdx].box(), size);
            }

            if (closestAfter == null) {
                int newestIdx = (head - 1 + capacity) % capacity;
                return new InterpolatedBox(buffer[newestIdx].box(), 0);
            }

            if (closestBefore.location() != null && closestAfter.location() != null
                    && closestBefore.location().getWorld() != closestAfter.location().getWorld()) {
                return new InterpolatedBox(closestAfter.box(), 0);
            }

            BoundingBox b1 = closestBefore.box();
            BoundingBox b2 = closestAfter.box();
            if (b1.getCenter().distanceSquared(b2.getCenter()) > 64.0) {
                return new InterpolatedBox(closestAfter.box(), 0);
            }

            long timeDiff = closestAfter.timestamp() - closestBefore.timestamp();
            if (timeDiff <= 0) return new InterpolatedBox(b1, 0);

            double factor = (double) (targetTimestamp - closestBefore.timestamp()) / (double) timeDiff;
            factor = Math.max(0.0, Math.min(1.0, factor));

            BoundingBox interpolated = new BoundingBox(
                    b1.getMinX() + (b2.getMinX() - b1.getMinX()) * factor,
                    b1.getMinY() + (b2.getMinY() - b1.getMinY()) * factor,
                    b1.getMinZ() + (b2.getMinZ() - b1.getMinZ()) * factor,
                    b1.getMaxX() + (b2.getMaxX() - b1.getMaxX()) * factor,
                    b1.getMaxY() + (b2.getMaxY() - b1.getMaxY()) * factor,
                    b1.getMaxZ() + (b2.getMaxZ() - b1.getMaxZ()) * factor
            );

            int ticksBack = Math.abs(closestAfter.tick() - closestBefore.tick());
            return new InterpolatedBox(interpolated, ticksBack);
        }
    }
}
