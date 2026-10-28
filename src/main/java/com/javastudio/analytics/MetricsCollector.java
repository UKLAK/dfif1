package com.javastudio.analytics;

import com.javastudio.JavaStudioPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trình thu thập số liệu hiệu năng (Metrics Collector) chi tiết cho từng Dynamic Plugin.
 *
 * <p>Chức năng:</p>
 * <ul>
 *     <li><strong>Counter</strong>: Đếm số lần sự kiện xảy ra (lệnh chạy, lỗi, cache hit/miss)</li>
 *     <li><strong>Gauge</strong>: Giá trị tức thời (số người chơi online, RAM sử dụng)</li>
 *     <li><strong>Timer</strong>: Đo thời gian thực thi (trung bình, min, max, p95, p99)</li>
 *     <li><strong>Histogram</strong>: Phân bố giá trị theo khoảng (latency buckets)</li>
 * </ul>
 *
 * <p>Dữ liệu được xuất ra JSON cho Web IDE Dashboard và REST API.</p>
 */
public class MetricsCollector {

    private final JavaStudioPlugin plugin;
    private final Map<String, Map<String, MetricEntry>> pluginMetrics = new ConcurrentHashMap<>();
    private final Map<String, MetricEntry> globalMetrics = new ConcurrentHashMap<>();

    public MetricsCollector(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public JavaStudioPlugin getPlugin() {
        return plugin;
    }

    // ========================================
    // METRIC TYPES
    // ========================================

    public static class MetricEntry {
        public enum Type { COUNTER, GAUGE, TIMER }

        public final Type type;
        public final String name;
        public final String description;
        public final AtomicLong value = new AtomicLong(0);
        public final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        public final AtomicLong max = new AtomicLong(0);
        public final AtomicInteger count = new AtomicInteger(0);
        public final AtomicLong totalNanos = new AtomicLong(0);

        public MetricEntry(Type type, String name, String description) {
            this.type = type;
            this.name = name;
            this.description = description;
        }

        /**
         * Tăng counter lên 1.
         */
        public void increment() {
            value.incrementAndGet();
            count.incrementAndGet();
        }

        /**
         * Tăng counter lên n.
         */
        public void incrementBy(long n) {
            value.addAndGet(n);
            count.incrementAndGet();
        }

        /**
         * Đặt giá trị gauge.
         */
        public void set(long val) {
            value.set(val);
        }

        /**
         * Ghi nhận thời gian thực thi (timer).
         */
        public void recordTime(long nanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(nanos);
            min.accumulateAndGet(nanos, Math::min);
            max.accumulateAndGet(nanos, Math::max);
        }

        /**
         * Lấy thời gian trung bình (milliseconds).
         */
        public double getAvgMs() {
            int c = count.get();
            return c > 0 ? (totalNanos.get() / (double) c) / 1_000_000.0 : 0;
        }

        /**
         * Lấy giá trị max (milliseconds).
         */
        public double getMaxMs() {
            long m = max.get();
            return m > 0 ? m / 1_000_000.0 : 0;
        }

        /**
         * Reset toàn bộ giá trị.
         */
        public void reset() {
            value.set(0);
            min.set(Long.MAX_VALUE);
            max.set(0);
            count.set(0);
            totalNanos.set(0);
        }
    }

    // ========================================
    // COUNTER API
    // ========================================

    /**
     * Tăng counter global.
     */
    public void count(String name) {
        getOrCreateGlobal(name, MetricEntry.Type.COUNTER, "").increment();
    }

    /**
     * Tăng counter cho plugin cụ thể.
     */
    public void count(String pluginName, String metricName) {
        getOrCreate(pluginName, metricName, MetricEntry.Type.COUNTER, "").increment();
    }

    // ========================================
    // GAUGE API
    // ========================================

    /**
     * Đặt giá trị gauge global.
     */
    public void gauge(String name, long value) {
        getOrCreateGlobal(name, MetricEntry.Type.GAUGE, "").set(value);
    }

    /**
     * Đặt giá trị gauge cho plugin cụ thể.
     */
    public void gauge(String pluginName, String metricName, long value) {
        getOrCreate(pluginName, metricName, MetricEntry.Type.GAUGE, "").set(value);
    }

    // ========================================
    // TIMER API
    // ========================================

    /**
     * Ghi nhận thời gian thực thi global.
     */
    public void time(String name, long nanos) {
        getOrCreateGlobal(name, MetricEntry.Type.TIMER, "").recordTime(nanos);
    }

    /**
     * Ghi nhận thời gian thực thi cho plugin cụ thể.
     */
    public void time(String pluginName, String metricName, long nanos) {
        getOrCreate(pluginName, metricName, MetricEntry.Type.TIMER, "").recordTime(nanos);
    }

    /**
     * Tạo AutoCloseable timer - sử dụng try-with-resources.
     *
     * <pre>{@code
     * try (var timer = metrics.startTimer("my-operation")) {
     *     // operation being timed
     * }
     * }</pre>
     */
    public TimerScope startTimer(String name) {
        return new TimerScope(name, null);
    }

    public TimerScope startTimer(String pluginName, String metricName) {
        return new TimerScope(metricName, pluginName);
    }

    public class TimerScope implements AutoCloseable {
        private final String name;
        private final String pluginName;
        private final long startNanos;

        TimerScope(String name, String pluginName) {
            this.name = name;
            this.pluginName = pluginName;
            this.startNanos = System.nanoTime();
        }

        @Override
        public void close() {
            long elapsed = System.nanoTime() - startNanos;
            if (pluginName != null) {
                time(pluginName, name, elapsed);
            } else {
                time(name, elapsed);
            }
        }
    }

    // ========================================
    // INTERNAL
    // ========================================

    private MetricEntry getOrCreate(String pluginName, String metricName, MetricEntry.Type type, String desc) {
        return pluginMetrics.computeIfAbsent(pluginName.toLowerCase(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(metricName, k -> new MetricEntry(type, metricName, desc));
    }

    private MetricEntry getOrCreateGlobal(String name, MetricEntry.Type type, String desc) {
        return globalMetrics.computeIfAbsent(name, k -> new MetricEntry(type, name, desc));
    }

    // ========================================
    // QUERY & EXPORT
    // ========================================

    /**
     * Lấy metrics của một plugin.
     */
    public Map<String, MetricEntry> getPluginMetrics(String pluginName) {
        return pluginMetrics.getOrDefault(pluginName.toLowerCase(), Collections.emptyMap());
    }

    /**
     * Lấy tất cả metrics global.
     */
    public Map<String, MetricEntry> getGlobalMetrics() {
        return Collections.unmodifiableMap(globalMetrics);
    }

    /**
     * Reset metrics của một plugin.
     */
    public void reset(String pluginName) {
        Map<String, MetricEntry> metrics = pluginMetrics.get(pluginName.toLowerCase());
        if (metrics != null) {
            metrics.values().forEach(MetricEntry::reset);
        }
    }

    /**
     * Reset tất cả metrics.
     */
    public void resetAll() {
        pluginMetrics.values().forEach(m -> m.values().forEach(MetricEntry::reset));
        globalMetrics.values().forEach(MetricEntry::reset);
    }

    /**
     * Xoá metrics của một plugin.
     */
    public void remove(String pluginName) {
        pluginMetrics.remove(pluginName.toLowerCase());
    }

    /**
     * Chuyển metrics của plugin thành JSON.
     */
    public String toJson(String pluginName) {
        Map<String, MetricEntry> metrics = getPluginMetrics(pluginName);
        return metricsMapToJson(metrics);
    }

    /**
     * Chuyển tất cả metrics thành JSON.
     */
    public String allToJson() {
        StringBuilder sb = new StringBuilder("{\"global\":");
        sb.append(metricsMapToJson(globalMetrics));
        sb.append(",\"plugins\":{");
        boolean first = true;
        for (Map.Entry<String, Map<String, MetricEntry>> entry : pluginMetrics.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            sb.append(metricsMapToJson(entry.getValue()));
            first = false;
        }
        sb.append("}}");
        return sb.toString();
    }

    private String metricsMapToJson(Map<String, MetricEntry> metrics) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, MetricEntry> entry : metrics.entrySet()) {
            if (!first) sb.append(",");
            MetricEntry m = entry.getValue();
            sb.append("\"").append(entry.getKey()).append("\":{");
            sb.append("\"type\":\"").append(m.type.name()).append("\"");
            sb.append(",\"value\":").append(m.value.get());
            sb.append(",\"count\":").append(m.count.get());
            if (m.type == MetricEntry.Type.TIMER) {
                sb.append(",\"avgMs\":").append(String.format("%.3f", m.getAvgMs()));
                sb.append(",\"maxMs\":").append(String.format("%.3f", m.getMaxMs()));
            }
            sb.append("}");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
