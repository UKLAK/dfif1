package com.javastudio.analytics;

import com.javastudio.JavaStudioPlugin;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.GarbageCollectorMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Trình giám sát sức khỏe hệ thống tiên tiến cho JavaStudio.
 *
 * <p>Thu thập và lưu trữ lịch sử các chỉ số hiệu năng quan trọng theo thời gian thực:</p>
 * <ul>
 *     <li><strong>TPS (Ticks Per Second)</strong>: Tốc độ game loop, tối đa 20.0</li>
 *     <li><strong>MSPT (Milliseconds Per Tick)</strong>: Thời gian xử lý mỗi tick</li>
 *     <li><strong>Heap Memory</strong>: Bộ nhớ JVM heap (used / max)</li>
 *     <li><strong>GC Pressure</strong>: Tần suất và thời gian Garbage Collection</li>
 *     <li><strong>Thread Count</strong>: Số luồng JVM đang hoạt động</li>
 *     <li><strong>Uptime</strong>: Thời gian server đã chạy</li>
 * </ul>
 *
 * <p>Dữ liệu được lưu trữ dưới dạng chuỗi thời gian (TimeSeries) 60 mẫu gần nhất,
 * phục vụ hiển thị biểu đồ live trên Web IDE Dashboard.</p>
 */
public class HealthMonitor {

    private final JavaStudioPlugin plugin;

    // TimeSeries lưu 120 mẫu gần nhất (mỗi 5 giây = 10 phút lịch sử)
    private static final int MAX_SAMPLES = 120;
    private final Deque<HealthSnapshot> history = new ConcurrentLinkedDeque<>();
    private final List<HealthAlert> activeAlerts = new CopyOnWriteArrayList<>();

    // TPS tracker
    private long lastTickTime = System.nanoTime();
    private final Deque<Long> tickIntervals = new ConcurrentLinkedDeque<>();
    private static final int TPS_SAMPLE_SIZE = 100;

    // GC tracker
    private long lastGcCount = 0;
    private long lastGcTime = 0;

    // Alert thresholds
    private double tpsWarningThreshold = 18.0;
    private double tpsCriticalThreshold = 15.0;
    private double memoryWarningPercent = 80.0;
    private double memoryCriticalPercent = 95.0;
    private double msptWarningThreshold = 40.0;

    /**
     * Snapshot chứa toàn bộ chỉ số tại 1 thời điểm.
     */
    public record HealthSnapshot(
            long timestamp,
            double tps,
            double mspt,
            long heapUsedMB,
            long heapMaxMB,
            double heapPercent,
            int threadCount,
            long gcCount,
            long gcTimeMs,
            double gcPressure,
            int onlinePlayers,
            int loadedChunks,
            int entities
    ) {}

    /**
     * Cảnh báo sức khỏe hệ thống.
     */
    public record HealthAlert(long timestamp, AlertLevel level, String category, String message) {}

    public enum AlertLevel {
        INFO, WARNING, CRITICAL
    }

    public HealthMonitor(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        loadThresholds();
    }

    private void loadThresholds() {
        tpsWarningThreshold = plugin.getConfig().getDouble("health.tps-warning", 18.0);
        tpsCriticalThreshold = plugin.getConfig().getDouble("health.tps-critical", 15.0);
        memoryWarningPercent = plugin.getConfig().getDouble("health.memory-warning-percent", 80.0);
        memoryCriticalPercent = plugin.getConfig().getDouble("health.memory-critical-percent", 95.0);
        msptWarningThreshold = plugin.getConfig().getDouble("health.mspt-warning", 40.0);
    }

    /**
     * Bắt đầu giám sát (chạy task mỗi 100 ticks = 5 giây).
     */
    public void start() {
        // TPS Counter task (chạy mỗi tick để đo chính xác)
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long now = System.nanoTime();
            long interval = now - lastTickTime;
            lastTickTime = now;

            tickIntervals.addLast(interval);
            while (tickIntervals.size() > TPS_SAMPLE_SIZE) {
                tickIntervals.pollFirst();
            }
        }, 1L, 1L);

        // Snapshot collector (mỗi 5 giây)
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::collectSnapshot, 100L, 100L);
    }

    /**
     * Thu thập snapshot chỉ số hệ thống tại thời điểm hiện tại.
     */
    public void collectSnapshot() {
        double tps = calculateTps();
        double mspt = calculateMspt();

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
        long heapUsedMB = heapUsage.getUsed() / (1024 * 1024);
        long heapMaxMB = heapUsage.getMax() / (1024 * 1024);
        double heapPercent = heapMaxMB > 0 ? (heapUsedMB * 100.0 / heapMaxMB) : 0;

        int threadCount = Thread.activeCount();

        // GC stats
        long totalGcCount = 0;
        long totalGcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() >= 0) totalGcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() >= 0) totalGcTime += gc.getCollectionTime();
        }
        long deltaGcCount = totalGcCount - lastGcCount;
        long deltaGcTime = totalGcTime - lastGcTime;
        lastGcCount = totalGcCount;
        lastGcTime = totalGcTime;
        double gcPressure = deltaGcTime / 5000.0 * 100.0; // % thời gian GC trong 5 giây

        // Server stats
        int onlinePlayers = plugin.getServer().getOnlinePlayers().size();
        int loadedChunks = 0;
        int entities = 0;
        try {
            for (org.bukkit.World world : plugin.getServer().getWorlds()) {
                loadedChunks += world.getLoadedChunks().length;
                entities += world.getEntities().size();
            }
        } catch (Throwable ignored) {}

        HealthSnapshot snapshot = new HealthSnapshot(
                System.currentTimeMillis(), tps, mspt,
                heapUsedMB, heapMaxMB, heapPercent,
                threadCount, deltaGcCount, deltaGcTime, gcPressure,
                onlinePlayers, loadedChunks, entities
        );

        history.addLast(snapshot);
        while (history.size() > MAX_SAMPLES) {
            history.pollFirst();
        }

        // Kiểm tra ngưỡng cảnh báo
        checkAlerts(snapshot);
    }

    private void checkAlerts(HealthSnapshot s) {
        if (s.tps() < tpsCriticalThreshold) {
            addAlert(AlertLevel.CRITICAL, "TPS", String.format("TPS quá thấp: %.1f (ngưỡng: %.1f)", s.tps(), tpsCriticalThreshold));
        } else if (s.tps() < tpsWarningThreshold) {
            addAlert(AlertLevel.WARNING, "TPS", String.format("TPS thấp: %.1f (ngưỡng: %.1f)", s.tps(), tpsWarningThreshold));
        }

        if (s.heapPercent() > memoryCriticalPercent) {
            addAlert(AlertLevel.CRITICAL, "Memory", String.format("RAM gần đầy: %.1f%% (%dMB/%dMB)", s.heapPercent(), s.heapUsedMB(), s.heapMaxMB()));
            try {
                if (plugin.getServerController() != null) {
                    long freed = plugin.getServerController().triggerGC();
                    if (freed > 0) {
                        plugin.getLogger().warning("[HealthMonitor] Đã tự động kích hoạt Emergency GC, giải phóng " + freed + "MB RAM!");
                    }
                }
            } catch (Throwable ignored) {}
        } else if (s.heapPercent() > memoryWarningPercent) {
            addAlert(AlertLevel.WARNING, "Memory", String.format("RAM cao: %.1f%% (%dMB/%dMB)", s.heapPercent(), s.heapUsedMB(), s.heapMaxMB()));
        }

        if (s.mspt() > msptWarningThreshold) {
            addAlert(AlertLevel.WARNING, "MSPT", String.format("MSPT cao: %.1fms (>%.0fms)", s.mspt(), msptWarningThreshold));
        }

        if (s.gcPressure() > 30.0) {
            addAlert(AlertLevel.WARNING, "GC", String.format("GC Pressure cao: %.1f%% thời gian dành cho GC", s.gcPressure()));
        }
    }

    private void addAlert(AlertLevel level, String category, String message) {
        HealthAlert alert = new HealthAlert(System.currentTimeMillis(), level, category, message);
        activeAlerts.add(alert);
        // Giữ tối đa 50 alerts
        while (activeAlerts.size() > 50) {
            activeAlerts.remove(0);
        }

        if (level == AlertLevel.CRITICAL) {
            plugin.getLogger().severe("[HealthMonitor] " + category + ": " + message);
        }
    }

    // ========================================
    // TPS & MSPT CALCULATION
    // ========================================

    private double calculateTps() {
        if (tickIntervals.size() < 2) return 20.0;

        long totalNanos = 0;
        int count = 0;
        for (Long interval : tickIntervals) {
            totalNanos += interval;
            count++;
        }

        if (count == 0 || totalNanos == 0) return 20.0;

        double avgNanosPerTick = (double) totalNanos / count;
        double tps = 1_000_000_000.0 / avgNanosPerTick;
        return Math.min(20.0, tps);
    }

    private double calculateMspt() {
        if (tickIntervals.isEmpty()) return 0;

        long totalNanos = 0;
        int count = 0;
        for (Long interval : tickIntervals) {
            totalNanos += interval;
            count++;
        }

        if (count == 0) return 0;
        return (double) totalNanos / count / 1_000_000.0;
    }

    // ========================================
    // API cho Web IDE Dashboard
    // ========================================

    /**
     * Lấy snapshot mới nhất.
     */
    public HealthSnapshot getLatestSnapshot() {
        return history.peekLast();
    }

    /**
     * Lấy lịch sử snapshot (120 mẫu = ~10 phút).
     */
    public List<HealthSnapshot> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Lấy danh sách cảnh báo đang hoạt động.
     */
    public List<HealthAlert> getActiveAlerts() {
        return new ArrayList<>(activeAlerts);
    }

    /**
     * Xóa toàn bộ cảnh báo.
     */
    public void clearAlerts() {
        activeAlerts.clear();
    }

    /**
     * Chuyển snapshot thành JSON cho REST API.
     */
    public String toJson(HealthSnapshot s) {
        if (s == null) return "{}";
        return "{" +
                "\"timestamp\":" + s.timestamp() +
                ",\"tps\":" + String.format("%.2f", s.tps()) +
                ",\"mspt\":" + String.format("%.2f", s.mspt()) +
                ",\"heapUsedMB\":" + s.heapUsedMB() +
                ",\"heapMaxMB\":" + s.heapMaxMB() +
                ",\"heapPercent\":" + String.format("%.1f", s.heapPercent()) +
                ",\"threadCount\":" + s.threadCount() +
                ",\"gcCount\":" + s.gcCount() +
                ",\"gcTimeMs\":" + s.gcTimeMs() +
                ",\"gcPressure\":" + String.format("%.1f", s.gcPressure()) +
                ",\"onlinePlayers\":" + s.onlinePlayers() +
                ",\"loadedChunks\":" + s.loadedChunks() +
                ",\"entities\":" + s.entities() +
                "}";
    }

    /**
     * Chuyển toàn bộ lịch sử thành JSON array.
     */
    public String historyToJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (HealthSnapshot s : history) {
            if (!first) sb.append(",");
            sb.append(toJson(s));
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Chuyển danh sách cảnh báo thành JSON.
     */
    public String alertsToJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (HealthAlert a : activeAlerts) {
            if (!first) sb.append(",");
            sb.append("{\"timestamp\":").append(a.timestamp())
                    .append(",\"level\":\"").append(a.level().name())
                    .append("\",\"category\":\"").append(a.category())
                    .append("\",\"message\":\"").append(escapeJson(a.message()))
                    .append("\"}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
