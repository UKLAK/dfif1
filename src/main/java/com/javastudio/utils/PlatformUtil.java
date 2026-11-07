package com.javastudio.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class PlatformUtil {

    private static final boolean IS_FOLIA;
    private static final boolean IS_PURPUR;
    private static final boolean IS_PAPER;
    private static final boolean IS_ADVENTURE;
    private static final String PLATFORM_NAME;

    private static final int JAVA_FEATURE_VERSION;
    private static final String JAVA_VERSION_STRING;
    private static final boolean VIRTUAL_THREADS_SUPPORTED;
    private static final java.util.concurrent.ExecutorService VIRTUAL_EXECUTOR;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (Throwable ignored) {}
        IS_FOLIA = folia;

        boolean purpur = false;
        try {
            Class.forName("org.purpurmc.purpur.PurpurConfig");
            purpur = true;
        } catch (Throwable ignored) {}
        IS_PURPUR = purpur;

        boolean paper = false;
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            paper = true;
        } catch (Throwable ignored) {
            try {
                Class.forName("io.papermc.paper.configuration.Configuration");
                paper = true;
            } catch (Throwable ignored2) {}
        }
        IS_PAPER = paper || purpur || folia;

        boolean adventure = false;
        try {
            Class.forName("net.kyori.adventure.text.Component");
            Class.forName("net.kyori.adventure.audience.Audience");
            adventure = true;
        } catch (Throwable ignored) {}
        IS_ADVENTURE = adventure;

        if (IS_FOLIA) {
            PLATFORM_NAME = "Folia";
        } else if (IS_PURPUR) {
            PLATFORM_NAME = "Purpur";
        } else if (IS_PAPER) {
            PLATFORM_NAME = "Paper";
        } else {
            PLATFORM_NAME = "Spigot";
        }

        int jv = 21;
        try {
            jv = Runtime.version().feature();
        } catch (Throwable t) {
            try {
                String spec = System.getProperty("java.specification.version");
                if (spec != null) {
                    jv = Integer.parseInt(spec.contains(".") ? spec.substring(spec.lastIndexOf('.') + 1) : spec);
                }
            } catch (Throwable ignored) {}
        }
        JAVA_FEATURE_VERSION = jv;
        JAVA_VERSION_STRING = System.getProperty("java.version", "21");

        boolean vt = false;
        java.util.concurrent.ExecutorService vExec = null;
        try {
            vExec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            vt = true;
        } catch (Throwable ignored) {}
        VIRTUAL_THREADS_SUPPORTED = vt;
        VIRTUAL_EXECUTOR = vExec;
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    public static boolean isPurpur() {
        return IS_PURPUR;
    }

    public static boolean isPaper() {
        return IS_PAPER;
    }

    public static boolean isAdventureSupported() {
        return IS_ADVENTURE;
    }

    public static String getPlatformName() {
        return PLATFORM_NAME;
    }

    public static int getJavaVersion() {
        return JAVA_FEATURE_VERSION;
    }

    public static String getJavaVersionString() {
        return JAVA_VERSION_STRING;
    }

    public static boolean isJavaAtLeast(int version) {
        return JAVA_FEATURE_VERSION >= version;
    }

    public static boolean isVirtualThreadsSupported() {
        return VIRTUAL_THREADS_SUPPORTED;
    }

    public static java.util.concurrent.ExecutorService getVirtualThreadExecutor() {
        return VIRTUAL_EXECUTOR;
    }

    public static void runVirtualTask(Runnable runnable) {
        if (runnable == null) return;
        if (VIRTUAL_THREADS_SUPPORTED && VIRTUAL_EXECUTOR != null && !VIRTUAL_EXECUTOR.isShutdown()) {
            VIRTUAL_EXECUTOR.submit(runnable);
        } else {
            java.util.concurrent.ForkJoinPool.commonPool().submit(runnable);
        }
    }

    public static void shutdown() {
        if (VIRTUAL_EXECUTOR != null && !VIRTUAL_EXECUTOR.isShutdown()) {
            try {
                VIRTUAL_EXECUTOR.shutdown();
            } catch (Throwable ignored) {}
        }
    }

    public static String getMinecraftVersion() {
        try {
            String bv = Bukkit.getBukkitVersion();
            int dash = bv.indexOf('-');
            return dash > 0 ? bv.substring(0, dash) : bv;
        } catch (Throwable t) {
            return "Unknown";
        }
    }

    public interface TaskHandle {
        void cancel();
        boolean isCancelled();
    }

    public static TaskHandle runTask(Plugin plugin, Runnable runnable) {
        if (IS_FOLIA) {
            return scheduleFoliaGlobal(plugin, (task) -> runnable.run(), 0);
        }
        BukkitTask bt = Bukkit.getScheduler().runTask(plugin, runnable);
        return wrapBukkitTask(bt);
    }

    public static TaskHandle runTaskAsync(Plugin plugin, Runnable runnable) {
        if (IS_FOLIA) {
            return scheduleFoliaAsync(plugin, (task) -> runnable.run(), 0);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        return wrapBukkitTask(bt);
    }

    public static TaskHandle runTaskLater(Plugin plugin, Runnable runnable, long delayTicks) {
        if (IS_FOLIA) {
            return scheduleFoliaGlobal(plugin, (task) -> runnable.run(), Math.max(1, delayTicks));
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        return wrapBukkitTask(bt);
    }

    public static TaskHandle runTaskTimer(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        if (IS_FOLIA) {
            return scheduleFoliaGlobalRepeating(plugin, (task) -> runnable.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
        return wrapBukkitTask(bt);
    }

    public static TaskHandle runAtLocation(Plugin plugin, Location location, Runnable runnable) {
        if (IS_FOLIA && location != null && location.getWorld() != null) {
            return scheduleFoliaRegion(plugin, location, (task) -> runnable.run(), 0);
        }
        return runTask(plugin, runnable);
    }

    public static TaskHandle runAtLocationLater(Plugin plugin, Location location, Runnable runnable, long delayTicks) {
        if (IS_FOLIA && location != null && location.getWorld() != null) {
            return scheduleFoliaRegion(plugin, location, (task) -> runnable.run(), Math.max(1, delayTicks));
        }
        return runTaskLater(plugin, runnable, delayTicks);
    }

    public static TaskHandle runAtEntity(Plugin plugin, Entity entity, Runnable runnable) {
        if (IS_FOLIA && entity != null && entity.isValid()) {
            return scheduleFoliaEntity(plugin, entity, (task) -> runnable.run(), 0);
        }
        return runTask(plugin, runnable);
    }

    public static TaskHandle runAtEntityLater(Plugin plugin, Entity entity, Runnable runnable, long delayTicks) {
        if (IS_FOLIA && entity != null && entity.isValid()) {
            return scheduleFoliaEntity(plugin, entity, (task) -> runnable.run(), Math.max(1, delayTicks));
        }
        return runTaskLater(plugin, runnable, delayTicks);
    }

    public static java.util.concurrent.CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
        if (entity == null || location == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        try {
            Method m = entity.getClass().getMethod("teleportAsync", Location.class);
            @SuppressWarnings("unchecked")
            java.util.concurrent.CompletableFuture<Boolean> future = (java.util.concurrent.CompletableFuture<Boolean>) m.invoke(entity, location);
            if (future != null) return future;
        } catch (Throwable ignored) {}

        try {
            boolean success = entity.teleport(location);
            return java.util.concurrent.CompletableFuture.completedFuture(success);
        } catch (Throwable t) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
    }

    private static TaskHandle wrapBukkitTask(BukkitTask bt) {
        return new TaskHandle() {
            @Override
            public void cancel() {
                try {
                    bt.cancel();
                } catch (Throwable ignored) {}
            }

            @Override
            public boolean isCancelled() {
                try {
                    return bt.isCancelled();
                } catch (Throwable ignored) {
                    return false;
                }
            }
        };
    }

    private static TaskHandle scheduleFoliaGlobal(Plugin plugin, Consumer<Object> consumer, long delayTicks) {
        try {
            Method getGlobalSchedulerMethod = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Object globalScheduler = getGlobalSchedulerMethod.invoke(null);
            if (delayTicks <= 0) {
                Method runMethod = globalScheduler.getClass().getMethod("run", Plugin.class, Consumer.class);
                Object task = runMethod.invoke(globalScheduler, plugin, consumer);
                return wrapFoliaTask(task);
            } else {
                Method runDelayedMethod = globalScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                Object task = runDelayedMethod.invoke(globalScheduler, plugin, consumer, delayTicks);
                return wrapFoliaTask(task);
            }
        } catch (Throwable t) {
            BukkitTask bt = Bukkit.getScheduler().runTask(plugin, () -> consumer.accept(null));
            return wrapBukkitTask(bt);
        }
    }

    private static TaskHandle scheduleFoliaGlobalRepeating(Plugin plugin, Consumer<Object> consumer, long delayTicks, long periodTicks) {
        try {
            Method getGlobalSchedulerMethod = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Object globalScheduler = getGlobalSchedulerMethod.invoke(null);
            Method runAtFixedRateMethod = globalScheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
            Object task = runAtFixedRateMethod.invoke(globalScheduler, plugin, consumer, delayTicks, periodTicks);
            return wrapFoliaTask(task);
        } catch (Throwable t) {
            BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, () -> consumer.accept(null), delayTicks, periodTicks);
            return wrapBukkitTask(bt);
        }
    }

    private static TaskHandle scheduleFoliaAsync(Plugin plugin, Consumer<Object> consumer, long delayMs) {
        try {
            Method getAsyncSchedulerMethod = Bukkit.class.getMethod("getAsyncScheduler");
            Object asyncScheduler = getAsyncSchedulerMethod.invoke(null);
            if (delayMs <= 0) {
                Method runNowMethod = asyncScheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
                Object task = runNowMethod.invoke(asyncScheduler, plugin, consumer);
                return wrapFoliaTask(task);
            } else {
                Method runDelayedMethod = asyncScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
                Object task = runDelayedMethod.invoke(asyncScheduler, plugin, consumer, delayMs, TimeUnit.MILLISECONDS);
                return wrapFoliaTask(task);
            }
        } catch (Throwable t) {
            BukkitTask bt = Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> consumer.accept(null));
            return wrapBukkitTask(bt);
        }
    }

    private static TaskHandle scheduleFoliaRegion(Plugin plugin, Location location, Consumer<Object> consumer, long delayTicks) {
        try {
            Method getRegionSchedulerMethod = Bukkit.class.getMethod("getRegionScheduler");
            Object regionScheduler = getRegionSchedulerMethod.invoke(null);
            if (delayTicks <= 0) {
                Method runMethod = regionScheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
                Object task = runMethod.invoke(regionScheduler, plugin, location, consumer);
                return wrapFoliaTask(task);
            } else {
                Method runDelayedMethod = regionScheduler.getClass().getMethod("runDelayed", Plugin.class, Location.class, Consumer.class, long.class);
                Object task = runDelayedMethod.invoke(regionScheduler, plugin, location, consumer, delayTicks);
                return wrapFoliaTask(task);
            }
        } catch (Throwable t) {
            if (delayTicks <= 0) {
                return runTask(plugin, () -> consumer.accept(null));
            } else {
                return runTaskLater(plugin, () -> consumer.accept(null), delayTicks);
            }
        }
    }

    private static TaskHandle scheduleFoliaEntity(Plugin plugin, Entity entity, Consumer<Object> consumer, long delayTicks) {
        try {
            Method getSchedulerMethod = entity.getClass().getMethod("getScheduler");
            Object entityScheduler = getSchedulerMethod.invoke(entity);
            if (delayTicks <= 0) {
                Method runMethod = entityScheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                Object task = runMethod.invoke(entityScheduler, plugin, consumer, (Runnable) null);
                return wrapFoliaTask(task);
            } else {
                Method runDelayedMethod = entityScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
                Object task = runDelayedMethod.invoke(entityScheduler, plugin, consumer, (Runnable) null, delayTicks);
                return wrapFoliaTask(task);
            }
        } catch (Throwable t) {
            if (delayTicks <= 0) {
                return runTask(plugin, () -> consumer.accept(null));
            } else {
                return runTaskLater(plugin, () -> consumer.accept(null), delayTicks);
            }
        }
    }

    private static TaskHandle wrapFoliaTask(Object scheduledTask) {
        return new TaskHandle() {
            @Override
            public void cancel() {
                if (scheduledTask == null) return;
                try {
                    Method cancelMethod = scheduledTask.getClass().getMethod("cancel");
                    cancelMethod.invoke(scheduledTask);
                } catch (Throwable ignored) {}
            }

            @Override
            public boolean isCancelled() {
                if (scheduledTask == null) return true;
                try {
                    Method isCancelledMethod = scheduledTask.getClass().getMethod("isCancelled");
                    return (boolean) isCancelledMethod.invoke(scheduledTask);
                } catch (Throwable ignored) {
                    return false;
                }
            }
        };
    }
}
