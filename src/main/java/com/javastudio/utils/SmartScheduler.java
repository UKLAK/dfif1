package com.javastudio.utils;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

public final class SmartScheduler {

    private static Plugin getPlugin() {
        return JavaStudioPlugin.getInstance();
    }

    public static PlatformUtil.TaskHandle runAt(Location location, Runnable runnable) {
        return PlatformUtil.runAtLocation(getPlugin(), location, runnable);
    }

    public static PlatformUtil.TaskHandle runAtLater(Location location, Runnable runnable, long delayTicks) {
        return PlatformUtil.runAtLocationLater(getPlugin(), location, runnable, delayTicks);
    }

    public static PlatformUtil.TaskHandle runFor(Entity entity, Runnable runnable) {
        return PlatformUtil.runAtEntity(getPlugin(), entity, runnable);
    }

    public static PlatformUtil.TaskHandle runForLater(Entity entity, Runnable runnable, long delayTicks) {
        return PlatformUtil.runAtEntityLater(getPlugin(), entity, runnable, delayTicks);
    }

    public static PlatformUtil.TaskHandle runGlobal(Runnable runnable) {
        return PlatformUtil.runTask(getPlugin(), runnable);
    }

    public static PlatformUtil.TaskHandle runGlobalLater(Runnable runnable, long delayTicks) {
        return PlatformUtil.runTaskLater(getPlugin(), runnable, delayTicks);
    }

    public static PlatformUtil.TaskHandle runGlobalTimer(Runnable runnable, long delayTicks, long periodTicks) {
        return PlatformUtil.runTaskTimer(getPlugin(), runnable, delayTicks, periodTicks);
    }

    public static PlatformUtil.TaskHandle runAsync(Runnable runnable) {
        return PlatformUtil.runTaskAsync(getPlugin(), runnable);
    }

    public static void runVirtual(Runnable runnable) {
        PlatformUtil.runVirtualTask(runnable);
    }

    public static CompletableFuture<Boolean> teleport(Entity entity, Location location) {
        return PlatformUtil.teleportAsync(entity, location);
    }
}
