package com.javastudio.profiler;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.CommandSender;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DynamicPluginProfiler {

    private final JavaStudioPlugin plugin;
    private final Set<String> activeProfiling = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, EventMetric>> metrics = new ConcurrentHashMap<>();

    public static class EventMetric {
        public final AtomicInteger calls = new AtomicInteger(0);
        public final AtomicLong totalNanos = new AtomicLong(0);
        public final AtomicLong maxNanos = new AtomicLong(0);
        public final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
        public final AtomicInteger lagSpikes = new AtomicInteger(0);
    }

    public DynamicPluginProfiler(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public void startProfiling(String pluginName) {
        String key = pluginName.toLowerCase();
        activeProfiling.add(key);
        metrics.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
    }

    public void stopProfiling(String pluginName) {
        activeProfiling.remove(pluginName.toLowerCase());
    }

    public boolean isProfiling(String pluginName) {
        return activeProfiling.contains(pluginName.toLowerCase()) || activeProfiling.contains("*");
    }

    public void startAll() {
        activeProfiling.add("*");
    }

    public void stopAll() {
        activeProfiling.clear();
    }

    public void clear(String pluginName) {
        metrics.remove(pluginName.toLowerCase());
    }

    public void record(String pluginName, String actionName, long elapsedNanos) {
        String key = pluginName.toLowerCase();
        if (!isProfiling(key)) return;

        Map<String, EventMetric> pluginMetrics = metrics.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        EventMetric m = pluginMetrics.computeIfAbsent(actionName, k -> new EventMetric());

        m.calls.incrementAndGet();
        m.totalNanos.addAndGet(elapsedNanos);

        m.maxNanos.accumulateAndGet(elapsedNanos, Math::max);
        m.minNanos.accumulateAndGet(elapsedNanos, Math::min);

        if (elapsedNanos > 10_000_000) {
            m.lagSpikes.incrementAndGet();
            double ms = elapsedNanos / 1_000_000.0;
            plugin.getLogger().warning(String.format("[Profiler] [LAG SPIKE] Plugin '%s' trong '%s' mất %.2fms (>10ms)! Nguy cơ tụt TPS!", pluginName, actionName, ms));
        }
    }

    public Map<String, EventMetric> getMetrics(String pluginName) {
        return metrics.getOrDefault(pluginName.toLowerCase(), Collections.emptyMap());
    }

    public Map<String, Map<String, EventMetric>> getAllMetrics() {
        return Collections.unmodifiableMap(metrics);
    }

    public void printReport(CommandSender sender, String pluginName) {
        String key = pluginName.toLowerCase();
        Map<String, EventMetric> data = metrics.get(key);
        if (data == null || data.isEmpty()) {
            MessageUtil.sendInfo(sender, "Chưa có dữ liệu đo lường cho plugin &e" + pluginName + "&7. Hãy chắc chắn profiler đang chạy (`/jplugin profile " + pluginName + " start`).");
            return;
        }

        MessageUtil.send(sender, "&6=== KẾT QUẢ ĐO HIỆU NĂNG: &b" + pluginName + " &6===");
        MessageUtil.sendRaw(sender, String.format("&7%-28s | %-6s | %-8s | %-8s | %-6s", "Tên Sự Kiện / Hàm", "Số lần", "Trung bình", "Cao nhất", "LagSpikes"));
        MessageUtil.sendRaw(sender, "&8------------------------------------------------------------------");

        for (Map.Entry<String, EventMetric> entry : data.entrySet()) {
            String name = entry.getKey();
            if (name.length() > 27) name = name.substring(0, 24) + "...";
            EventMetric m = entry.getValue();
            int calls = m.calls.get();
            if (calls == 0) continue;

            double avgMs = (m.totalNanos.get() / (double) calls) / 1_000_000.0;
            double maxMs = m.maxNanos.get() / 1_000_000.0;
            int spikes = m.lagSpikes.get();

            String spikeColor = spikes > 0 ? "&c" : "&a";
            String avgColor = avgMs > 5.0 ? "&c" : (avgMs > 1.0 ? "&e" : "&a");

            MessageUtil.sendRaw(sender, String.format("&e%-28s &7| &f%-6d &7| " + avgColor + "%-6.2fms &7| &f%-6.2fms &7| " + spikeColor + "%-6d",
                    name, calls, avgMs, maxMs, spikes));
        }
    }

    public String toFlameGraphJson() {
        Map<String, Map<String, long[]>> merged = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, EventMetric>> entry : metrics.entrySet()) {
            Map<String, long[]> actions = merged.computeIfAbsent(entry.getKey(), k -> new LinkedHashMap<>());
            for (Map.Entry<String, EventMetric> ae : entry.getValue().entrySet()) {
                EventMetric em = ae.getValue();
                actions.put(ae.getKey(), new long[]{em.totalNanos.get(), em.calls.get(), em.lagSpikes.get(), em.maxNanos.get()});
            }
        }

        if (plugin.getCircuitBreakerEngine() != null) {
            for (var node : plugin.getCircuitBreakerEngine().getAllBreakers()) {
                long nanos = node.totalTimeNano.get();
                long calls = node.totalCalls.get();
                if (calls <= 0) continue;
                Map<String, long[]> actions = merged.computeIfAbsent(node.pluginName, k -> new LinkedHashMap<>());
                String label = node.listenerClass + " [" + node.priority + "]";
                actions.putIfAbsent(label, new long[]{nanos, calls, node.totalErrors.get(), node.maxTimeNano.get()});
            }
        }

        long totalServerNanos = 0;
        for (Map<String, long[]> pMap : merged.values()) {
            for (long[] stat : pMap.values()) {
                totalServerNanos += stat[0];
            }
        }
        if (totalServerNanos <= 0) totalServerNanos = 1000;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"Server Root\",\"value\":").append(totalServerNanos).append(",\"children\":[");
        int pIndex = 0;
        for (Map.Entry<String, Map<String, long[]>> entry : merged.entrySet()) {
            String pName = entry.getKey().replace("\\", "\\\\").replace("\"", "\\\"");
            Map<String, long[]> actionMap = entry.getValue();
            long pTotal = 0;
            for (long[] stat : actionMap.values()) {
                pTotal += stat[0];
            }
            if (pIndex > 0) sb.append(",");
            sb.append("{\"name\":\"").append(pName).append("\",\"value\":").append(pTotal).append(",\"children\":[");
            int aIndex = 0;
            for (Map.Entry<String, long[]> aEntry : actionMap.entrySet()) {
                String aName = aEntry.getKey().replace("\\", "\\\\").replace("\"", "\\\"");
                long[] stat = aEntry.getValue();
                if (aIndex > 0) sb.append(",");
                sb.append("{\"name\":\"").append(aName).append("\",\"value\":").append(stat[0])
                        .append(",\"calls\":").append(stat[1])
                        .append(",\"lagSpikes\":").append(stat[2])
                        .append(",\"maxMs\":").append(String.format(Locale.US, "%.3f", stat[3] / 1_000_000.0))
                        .append("}");
                aIndex++;
            }
            sb.append("]}");
            pIndex++;
        }
        sb.append("]}");
        return sb.toString();
    }
}
