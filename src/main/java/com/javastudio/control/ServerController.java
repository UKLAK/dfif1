package com.javastudio.control;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class ServerController {

    private final JavaStudioPlugin hostPlugin;
    private final long startTime;

    public ServerController(JavaStudioPlugin hostPlugin) {
        this.hostPlugin = hostPlugin;
        this.startTime = System.currentTimeMillis();
    }

    public JavaStudioPlugin getHostPlugin() {
        return hostPlugin;
    }

    public double[] getTPS() {
        try {
            Method getTpsMethod = Bukkit.class.getMethod("getTPS");
            return (double[]) getTpsMethod.invoke(null);
        } catch (Throwable ignored) {}

        try {
            Object server = Bukkit.getServer();
            Field serverField = server.getClass().getDeclaredField("console");
            serverField.setAccessible(true);
            Object minecraftServer = serverField.get(server);
            Field tpsField = minecraftServer.getClass().getField("recentTps");
            return (double[]) tpsField.get(minecraftServer);
        } catch (Throwable ignored) {}

        return new double[]{20.0, 20.0, 20.0};
    }

    public double getMSPT() {
        try {
            Method getAvgTickTimeMethod = Bukkit.class.getMethod("getAverageTickTime");
            return (double) getAvgTickTimeMethod.invoke(null);
        } catch (Throwable ignored) {}
        return 0.0;
    }

    public MemoryInfo getMemoryInfo() {
        Runtime rt = Runtime.getRuntime();
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long totalMb = rt.totalMemory() / (1024 * 1024);
        long freeMb = rt.freeMemory() / (1024 * 1024);
        long usedMb = totalMb - freeMb;
        int usedPercent = maxMb > 0 ? (int) ((usedMb * 100) / maxMb) : 0;
        return new MemoryInfo(maxMb, totalMb, freeMb, usedMb, usedPercent);
    }

    public Map<String, Object> getSystemInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        info.put("java", "Java " + com.javastudio.utils.PlatformUtil.getJavaVersion() + " (" + System.getProperty("java.version") + ", " + System.getProperty("java.vendor") + ")");
        info.put("virtualThreads", com.javastudio.utils.PlatformUtil.isVirtualThreadsSupported());
        info.put("cores", Runtime.getRuntime().availableProcessors());

        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            info.put("systemLoad", osBean.getSystemLoadAverage());
        } catch (Throwable ignored) {}

        try {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            info.put("threads", threadBean.getThreadCount());
            info.put("peakThreads", threadBean.getPeakThreadCount());
        } catch (Throwable ignored) {}

        long uptimeMs = System.currentTimeMillis() - startTime;
        long hours = uptimeMs / (1000 * 60 * 60);
        long minutes = (uptimeMs / (1000 * 60)) % 60;
        info.put("uptime", hours + " giờ " + minutes + " phút");

        return info;
    }

    public List<WorldInfo> getWorldsInfo() {
        List<WorldInfo> list = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            list.add(new WorldInfo(
                    w.getName(),
                    w.getEnvironment().name(),
                    w.getLoadedChunks().length,
                    w.getEntities().size(),
                    w.getPlayers().size()
            ));
        }
        return list;
    }

    public long triggerGC() {
        Runtime rt = Runtime.getRuntime();
        long beforeMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        System.gc();
        long afterMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        return Math.max(0, beforeMb - afterMb);
    }

    public int purgeEntities(World targetWorld) {
        return purgeEntities(targetWorld, true, true, false);
    }

    public int purgeEntities(World targetWorld, boolean groundItems, boolean monsters, boolean strayMobs) {
        List<World> worlds = targetWorld != null ? Collections.singletonList(targetWorld) : Bukkit.getWorlds();
        int count = 0;

        for (World w : worlds) {
            for (Entity e : new ArrayList<>(w.getEntities())) {
                if (e instanceof Player) continue;

                if (groundItems && e instanceof Item) {
                    e.remove();
                    count++;
                } else if (monsters && e instanceof Monster) {
                    if (e.customName() == null) {
                        e.remove();
                        count++;
                    }
                } else if (strayMobs && e instanceof Animals animals) {
                    if (e.customName() == null && !isTamed(animals)) {
                        e.remove();
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private boolean isTamed(Animals animals) {
        if (animals instanceof Tameable tameable) {
            return tameable.isTamed();
        }
        return false;
    }

    public void saveAllWorlds() {
        for (World w : Bukkit.getWorlds()) {
            w.save();
        }
        Bukkit.savePlayers();
    }

    public boolean executeConsoleCommand(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) return false;
        String cmd = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    public void setMaintenanceMode(boolean enabled) {
        Bukkit.setWhitelist(enabled);
        if (enabled) {
            Bukkit.reloadWhitelist();
        }
    }

    public boolean isMaintenanceMode() {
        return Bukkit.hasWhitelist();
    }

    public record MemoryInfo(long maxMb, long totalMb, long freeMb, long usedMb, int usedPercent) {}
    public record WorldInfo(String name, String environment, int loadedChunks, int entitiesCount, int playersCount) {}
}
