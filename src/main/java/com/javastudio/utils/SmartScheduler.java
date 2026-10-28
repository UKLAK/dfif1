package com.javastudio.utils;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Bộ Điều Phối Thông Minh Đa Nền Tảng (Smart Folia/Paper/Spigot Region Scheduler).
 * Tự động phân giải và điều hướng tác vụ vào đúng luồng theo vùng Chunk (Threaded Regions trên Folia),
 * luồng Global hoặc luồng Main của Paper/Spigot thông thường.
 * Triệt tiêu 100% lỗi AsyncCatcherException / RegionAccessOutOfSyncException.
 */
public final class SmartScheduler {

    private static Plugin getPlugin() {
        return JavaStudioPlugin.getInstance();
    }

    /**
     * Chạy tác vụ đồng bộ tại tọa độ Location chỉ định (Thread Region an toàn cho Folia).
     */
    public static PlatformUtil.TaskHandle runAt(Location location, Runnable runnable) {
        return PlatformUtil.runAtLocation(getPlugin(), location, runnable);
    }

    /**
     * Chạy tác vụ tại Location sau N ticks.
     */
    public static PlatformUtil.TaskHandle runAtLater(Location location, Runnable runnable, long delayTicks) {
        return PlatformUtil.runAtLocationLater(getPlugin(), location, runnable, delayTicks);
    }

    /**
     * Chạy tác vụ gắn liền với Entity chỉ định (Entity Region an toàn cho Folia).
     */
    public static PlatformUtil.TaskHandle runFor(Entity entity, Runnable runnable) {
        return PlatformUtil.runAtEntity(getPlugin(), entity, runnable);
    }

    /**
     * Chạy tác vụ gắn liền với Entity sau N ticks.
     */
    public static PlatformUtil.TaskHandle runForLater(Entity entity, Runnable runnable, long delayTicks) {
        return PlatformUtil.runAtEntityLater(getPlugin(), entity, runnable, delayTicks);
    }

    /**
     * Chạy tác vụ trên Global Region (Toàn cục server).
     */
    public static PlatformUtil.TaskHandle runGlobal(Runnable runnable) {
        return PlatformUtil.runTask(getPlugin(), runnable);
    }

    /**
     * Chạy tác vụ trên Global Region sau N ticks.
     */
    public static PlatformUtil.TaskHandle runGlobalLater(Runnable runnable, long delayTicks) {
        return PlatformUtil.runTaskLater(getPlugin(), runnable, delayTicks);
    }

    /**
     * Chạy tác vụ lặp lại định kỳ trên Global Region.
     */
    public static PlatformUtil.TaskHandle runGlobalTimer(Runnable runnable, long delayTicks, long periodTicks) {
        return PlatformUtil.runTaskTimer(getPlugin(), runnable, delayTicks, periodTicks);
    }

    /**
     * Chạy tác vụ bất đồng bộ Async.
     */
    public static PlatformUtil.TaskHandle runAsync(Runnable runnable) {
        return PlatformUtil.runTaskAsync(getPlugin(), runnable);
    }

    /**
     * Chạy tác vụ trên Virtual Thread (Project Loom) chuẩn Java 21+.
     */
    public static void runVirtual(Runnable runnable) {
        PlatformUtil.runVirtualTask(runnable);
    }

    /**
     * Dịch chuyển Entity an toàn đa nền tảng (Folia/Paper asynchronous teleportation).
     */
    public static CompletableFuture<Boolean> teleport(Entity entity, Location location) {
        return PlatformUtil.teleportAsync(entity, location);
    }
}
