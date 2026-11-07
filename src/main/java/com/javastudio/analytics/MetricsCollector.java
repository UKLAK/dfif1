package com.javastudio.analytics;

import com.javastudio.JavaStudioPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

        public void increment() {
            value.incrementAndGet();
            count.incrementAndGet();
        }

        public void incrementBy(long n) {
            value.addAndGet(n);
            count.incrementAndGet();
        }

        public void set(long val) {
            value.set(val);
        }

        public void recordTime(long nanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(nanos);
            min.accumulateAndGet(nanos, Math::min);
            max.accumulateAndGet(nanos, Math::max);
        }

        public double getAvgMs() {
            int c = count.get();
            return c > 0 ? (totalNanos.get() / (double) c) / 1_000_000.0 : 0;
        }

        public double getMaxMs() {
            long m = max.get();
            return m > 0 ? m / 1_000_000.0 : 0;
        }

        public void reset() {
            value.set(0);
            min.set(Long.MAX_VALUE);
            max.set(0);
            count.set(0);
            totalNanos.set(0);
        }
    }


    public void count(String name) {
        getOrCreateGlobal(name, MetricEntry.Type.COUNTER, "").increment();
    }

    public void count(String pluginName, String metricName) {
        getOrCreate(pluginName, metricName, MetricEntry.Type.COUNTER, "").increment();
    }


    public void gauge(String name, long value) {
        getOrCreateGlobal(name, MetricEntry.Type.GAUGE, "").set(value);
    }

    public void gauge(String pluginName, String metricName, long value) {
        getOrCreate(pluginName, metricName, MetricEntry.Type.GAUGE, "").set(value);
    }


    public void time(String name, long nanos) {
        getOrCreateGlobal(name, MetricEntry.Type.TIMER, "").recordTime(nanos);
    }

    public void time(String pluginName, String metricName, long nanos) {
        getOrCreate(pluginName, metricName, MetricEntry.Type.TIMER, "").recordTime(nanos);
    }

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


    private MetricEntry getOrCreate(String pluginName, String metricName, MetricEntry.Type type, String desc) {
        return pluginMetrics.computeIfAbsent(pluginName.toLowerCase(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(metricName, k -> new MetricEntry(type, metricName, desc));
    }

    private MetricEntry getOrCreateGlobal(String name, MetricEntry.Type type, String desc) {
        return globalMetrics.computeIfAbsent(name, k -> new MetricEntry(type, name, desc));
    }


    public Map<String, MetricEntry> getPluginMetrics(String pluginName) {
        return pluginMetrics.getOrDefault(pluginName.toLowerCase(), Collections.emptyMap());
    }

    public Map<String, MetricEntry> getGlobalMetrics() {
        return Collections.unmodifiableMap(globalMetrics);
    }

    public void reset(String pluginName) {
        Map<String, MetricEntry> metrics = pluginMetrics.get(pluginName.toLowerCase());
        if (metrics != null) {
            metrics.values().forEach(MetricEntry::reset);
        }
    }

    public void resetAll() {
        pluginMetrics.values().forEach(m -> m.values().forEach(MetricEntry::reset));
        globalMetrics.values().forEach(MetricEntry::reset);
    }

    public void remove(String pluginName) {
        pluginMetrics.remove(pluginName.toLowerCase());
    }

    public String toJson(String pluginName) {
        Map<String, MetricEntry> metrics = getPluginMetrics(pluginName);
        return metricsMapToJson(metrics);
    }

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
