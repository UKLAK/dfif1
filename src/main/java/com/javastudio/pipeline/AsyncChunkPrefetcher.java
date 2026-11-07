package com.javastudio.pipeline;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AsyncChunkPrefetcher {

    private final JavaStudioPlugin plugin;
    private PlatformUtil.TaskHandle tickerHandle;

    private boolean enabled = true;
    private double speedThreshold = 0.25;
    private int maxLookaheadChunks = 4;
    private int maxConcurrentLoads = 16;
    private double minTpsThreshold = 18.0;

    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Set<Long> pendingChunkKeys = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> recentLoadsTtl = new ConcurrentHashMap<>();

    private final AtomicLong totalPrefetched = new AtomicLong(0);
    private final AtomicLong skippedDueToTps = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicInteger currentConcurrentLoads = new AtomicInteger(0);

    public AsyncChunkPrefetcher(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("performance.chunk-prefetcher.enabled", true);
        this.speedThreshold = cfg.getDouble("performance.chunk-prefetcher.speed-threshold", 0.25);
        this.maxLookaheadChunks = cfg.getInt("performance.chunk-prefetcher.max-lookahead-chunks", 4);
        this.maxConcurrentLoads = cfg.getInt("performance.chunk-prefetcher.max-concurrent-loads", 16);
        this.minTpsThreshold = cfg.getDouble("performance.chunk-prefetcher.min-tps", 18.0);
    }

    public void start() {
        if (tickerHandle != null) {
            tickerHandle.cancel();
        }
        tickerHandle = PlatformUtil.runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void stop() {
        if (tickerHandle != null) {
            tickerHandle.cancel();
            tickerHandle = null;
        }
        lastLocations.clear();
        pendingChunkKeys.clear();
        recentLoadsTtl.clear();
    }

    private void tick() {
        if (!enabled) return;

        double currentTps = 20.0;
        try {
            double[] tpsArr = plugin.getServerController().getTPS();
            if (tpsArr != null && tpsArr.length > 0) {
                currentTps = tpsArr[0];
            }
        } catch (Throwable ignored) {}

        if (currentTps < minTpsThreshold) {
            skippedDueToTps.incrementAndGet();
            return;
        }

        long now = System.currentTimeMillis();
        recentLoadsTtl.entrySet().removeIf(e -> now - e.getValue() > 8000L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isValid()) continue;

            UUID uuid = p.getUniqueId();
            Location currentLoc = p.getLocation();
            Location prevLoc = lastLocations.put(uuid, currentLoc);

            if (prevLoc == null || prevLoc.getWorld() != currentLoc.getWorld()) {
                continue;
            }

            double dx = currentLoc.getX() - prevLoc.getX();
            double dz = currentLoc.getZ() - prevLoc.getZ();
            double speedSq = dx * dx + dz * dz;

            if (speedSq < speedThreshold * speedThreshold) {
                continue;
            }

            double speed = Math.sqrt(speedSq);
            double dirX = dx / speed;
            double dirZ = dz / speed;

            World world = currentLoc.getWorld();
            int currentChunkX = currentLoc.getBlockX() >> 4;
            int currentChunkZ = currentLoc.getBlockZ() >> 4;

            int lookahead = Math.min(maxLookaheadChunks, Math.max(1, (int) Math.round(speed * 3.5)));

            for (int step = 1; step <= lookahead; step++) {
                if (currentConcurrentLoads.get() >= maxConcurrentLoads) {
                    break;
                }

                int targetChunkX = currentChunkX + (int) Math.round(dirX * step);
                int targetChunkZ = currentChunkZ + (int) Math.round(dirZ * step);

                long chunkKey = (((long) targetChunkX) << 32) | (targetChunkZ & 0xFFFFFFFFL);

                if (world.isChunkLoaded(targetChunkX, targetChunkZ)) {
                    cacheHits.incrementAndGet();
                    continue;
                }

                if (recentLoadsTtl.containsKey(chunkKey) || !pendingChunkKeys.add(chunkKey)) {
                    continue;
                }

                recentLoadsTtl.put(chunkKey, now);
                dispatchAsyncChunkLoad(world, targetChunkX, targetChunkZ, chunkKey);
            }
        }
    }

    private void dispatchAsyncChunkLoad(World world, int cx, int cz, long chunkKey) {
        currentConcurrentLoads.incrementAndGet();

        try {
            CompletableFuture<Chunk> future = world.getChunkAtAsync(cx, cz, true);
            future.whenComplete((chunk, throwable) -> {
                pendingChunkKeys.remove(chunkKey);
                currentConcurrentLoads.decrementAndGet();
                if (throwable == null && chunk != null) {
                    totalPrefetched.incrementAndGet();
                }
            });
        } catch (Throwable t) {
            pendingChunkKeys.remove(chunkKey);
            currentConcurrentLoads.decrementAndGet();
        }
    }

    public void removePlayer(UUID uuid) {
        lastLocations.remove(uuid);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTotalPrefetched() {
        return totalPrefetched.get();
    }

    public long getSkippedDueToTps() {
        return skippedDueToTps.get();
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public int getCurrentConcurrentLoads() {
        return currentConcurrentLoads.get();
    }

    public int getTrackedPlayerCount() {
        return lastLocations.size();
    }

    public void resetStats() {
        totalPrefetched.set(0);
        skippedDueToTps.set(0);
        cacheHits.set(0);
    }
}

