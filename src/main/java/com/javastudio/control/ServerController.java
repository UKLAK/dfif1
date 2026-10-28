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

/**
 * Trình điều khiển Toàn bộ Máy chủ (Server & Software Controller) cho phép:
 * - Giám sát hiệu năng phần cứng: TPS (1m, 5m, 15m), MSPT (thời gian xử lý tick), RAM, CPU, Luồng JVM
 * - Dọn dẹp tài nguyên: Kích hoạt Garbage Collector (GC), dọn sạch Entities / Drop Items gây lag
 * - Quản lý Thế giới (Worlds): Lưu thế giới, kiểm tra Chunks nạp, đếm số lượng thực thể
 * - Điều khiển hệ thống: Thực thi lệnh console, bật/tắt chế độ bảo trì (Whitelist)
 */
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

    /**
     * Lấy mảng TPS của máy chủ [1m, 5m, 15m]. Tương thích Paper, Purpur, Spigot và Folia.
     */
    public double[] getTPS() {
        try {
            Method getTpsMethod = Bukkit.class.getMethod("getTPS");
            return (double[]) getTpsMethod.invoke(null);
        } catch (Throwable ignored) {}

        // Fallback qua CraftServer / MinecraftServer
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

    /**
     * Lấy thời gian xử lý trung bình mỗi tick (MSPT - Milliseconds Per Tick).
     */
    public double getMSPT() {
        try {
            Method getAvgTickTimeMethod = Bukkit.class.getMethod("getAverageTickTime");
            return (double) getAvgTickTimeMethod.invoke(null);
        } catch (Throwable ignored) {}
        return 0.0;
    }

    /**
     * Lấy thông tin chi tiết về bộ nhớ RAM (MB).
     */
    public MemoryInfo getMemoryInfo() {
        Runtime rt = Runtime.getRuntime();
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long totalMb = rt.totalMemory() / (1024 * 1024);
        long freeMb = rt.freeMemory() / (1024 * 1024);
        long usedMb = totalMb - freeMb;
        int usedPercent = maxMb > 0 ? (int) ((usedMb * 100) / maxMb) : 0;
        return new MemoryInfo(maxMb, totalMb, freeMb, usedMb, usedPercent);
    }

    /**
     * Lấy thông tin hệ thống (Hệ điều hành, CPU, Java, Cores).
     */
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

    /**
     * Lấy thông tin thống kê của toàn bộ Worlds.
     */
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

    /**
     * Kích hoạt ép thu gom rác bộ nhớ (Garbage Collector) và trả về số MB đã giải phóng.
     */
    public long triggerGC() {
        Runtime rt = Runtime.getRuntime();
        long beforeMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        System.gc();
        long afterMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        return Math.max(0, beforeMb - afterMb);
    }

    /**
     * Dọn dẹp thực thể gây lag trên thế giới chỉ định hoặc toàn bộ thế giới.
     *
     * @param targetWorld null nếu muốn dọn tất cả thế giới
     * @param groundItems Có xóa item rơi vãi dưới đất không
     * @param monsters    Có xóa quái vật không
     * @param strayMobs   Có xóa động vật hoang dã (không thuần hóa, không đặt tên) không
     * @return Số lượng entities đã xóa
     */
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

    /**
     * Ép buộc toàn bộ thế giới lưu dữ liệu xuống đĩa cứng ngay lập tức.
     */
    public void saveAllWorlds() {
        for (World w : Bukkit.getWorlds()) {
            w.save();
        }
        Bukkit.savePlayers();
    }

    /**
     * Thực thi lệnh trực tiếp từ Console của server.
     */
    public boolean executeConsoleCommand(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) return false;
        String cmd = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    /**
     * Bật hoặc tắt chế độ bảo trì (Whitelist).
     */
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
