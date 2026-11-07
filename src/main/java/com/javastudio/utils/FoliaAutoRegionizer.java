package com.javastudio.utils;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class FoliaAutoRegionizer {

    @FunctionalInterface
    public interface ChunkRegionTask {
        void execute(Chunk chunk, int startX, int endX, int minY, int maxY, int startZ, int endZ);
    }

    private static final boolean IS_FOLIA;
    private static Method regionExecuteMethod;
    private static Method globalExecuteMethod;
    private static Object regionSchedulerInstance;
    private static Object globalRegionSchedulerInstance;

    static {
        boolean folia = false;
        try {
            Method getRegionScheduler = Bukkit.class.getMethod("getRegionScheduler");
            regionSchedulerInstance = getRegionScheduler.invoke(null);
            regionExecuteMethod = regionSchedulerInstance.getClass().getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class);

            Method getGlobalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
            globalRegionSchedulerInstance = getGlobalRegionScheduler.invoke(null);
            globalExecuteMethod = globalRegionSchedulerInstance.getClass().getMethod("execute", Plugin.class, Runnable.class);

            folia = true;
        } catch (Throwable ignored) {
            folia = false;
        }
        IS_FOLIA = folia;
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    public static void runAtLocation(Plugin plugin, Location location, Runnable task) {
        if (location == null || task == null) return;
        World world = location.getWorld();
        if (world == null) return;

        if (IS_FOLIA && regionExecuteMethod != null && regionSchedulerInstance != null) {
            try {
                int cx = location.getBlockX() >> 4;
                int cz = location.getBlockZ() >> 4;
                regionExecuteMethod.invoke(regionSchedulerInstance, plugin, world, cx, cz, task);
                return;
            } catch (Throwable ignored) {}
        }

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runAtChunk(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        if (world == null || task == null) return;

        if (IS_FOLIA && regionExecuteMethod != null && regionSchedulerInstance != null) {
            try {
                regionExecuteMethod.invoke(regionSchedulerInstance, plugin, world, chunkX, chunkZ, task);
                return;
            } catch (Throwable ignored) {}
        }

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runAtChunkAsync(Plugin plugin, World world, int chunkX, int chunkZ, Consumer<Chunk> action) {
        if (world == null || action == null) return;
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            runAtChunk(plugin, world, chunkX, chunkZ, () -> {
                Chunk c = world.getChunkAt(chunkX, chunkZ);
                action.accept(c);
            });
        } else {
            try {
                world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> {
                    runAtChunk(plugin, world, chunkX, chunkZ, () -> action.accept(chunk));
                });
            } catch (Throwable t) {
                runAtChunk(plugin, world, chunkX, chunkZ, () -> {
                    Chunk c = world.getChunkAt(chunkX, chunkZ);
                    action.accept(c);
                });
            }
        }
    }

    public static CompletableFuture<Integer> runInCuboid(Plugin plugin, World world,
                                                         int minX, int minY, int minZ,
                                                         int maxX, int maxY, int maxZ,
                                                         ChunkRegionTask task) {
        if (world == null || task == null) return CompletableFuture.completedFuture(0);

        int minCx = minX >> 4;
        int maxCx = maxX >> 4;
        int minCz = minZ >> 4;
        int maxCz = maxZ >> 4;

        int totalChunks = (maxCx - minCx + 1) * (maxCz - minCz + 1);
        CompletableFuture<Integer> future = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(totalChunks);

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                final int chunkX = cx;
                final int chunkZ = cz;
                final int startX = Math.max(minX, chunkX << 4);
                final int endX = Math.min(maxX, (chunkX << 4) + 15);
                final int startZ = Math.max(minZ, chunkZ << 4);
                final int endZ = Math.min(maxZ, (chunkZ << 4) + 15);

                runAtChunkAsync(plugin, world, chunkX, chunkZ, chunk -> {
                    try {
                        task.execute(chunk, startX, endX, minY, maxY, startZ, endZ);
                    } catch (Throwable ignored) {
                    } finally {
                        if (remaining.decrementAndGet() == 0 && !future.isDone()) {
                            future.complete(totalChunks);
                        }
                    }
                });
            }
        }
        return future;
    }

    public static void runForEntity(Plugin plugin, Entity entity, Runnable task) {
        if (entity == null || task == null) return;

        if (IS_FOLIA) {
            try {
                Method getScheduler = entity.getClass().getMethod("getScheduler");
                Object entityScheduler = getScheduler.invoke(entity);
                Method execute = entityScheduler.getClass().getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
                execute.invoke(entityScheduler, plugin, task, null, 1L);
                return;
            } catch (Throwable ignored) {}
        }

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runGlobal(Plugin plugin, Runnable task) {
        if (task == null) return;

        if (IS_FOLIA && globalExecuteMethod != null && globalRegionSchedulerInstance != null) {
            try {
                globalExecuteMethod.invoke(globalRegionSchedulerInstance, plugin, task);
                return;
            } catch (Throwable ignored) {}
        }

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
