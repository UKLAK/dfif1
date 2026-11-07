package com.javastudio.analytics;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.control.ServerController;
import com.javastudio.profiler.DynamicPluginProfiler;


public final class TelemetryNexus {

    private final JavaStudioPlugin plugin;
    private final ServerController serverController;
    private final HealthMonitor healthMonitor;
    private final MetricsCollector metricsCollector;
    private final DynamicPluginProfiler profiler;
    private final DebugHudManager hudManager;

    public record TelemetrySnapshot(
            double tps,
            double mspt,
            long usedRamMb,
            long maxRamMb,
            int usedPercent,
            int totalEntities,
            int totalChunks,
            int activePluginsCount,
            String slowestPluginInfo,
            int cveBlocked,
            int exploitsBlocked
    ) {}

    public TelemetryNexus(JavaStudioPlugin plugin,
                           ServerController serverController,
                           HealthMonitor healthMonitor,
                           MetricsCollector metricsCollector,
                           DynamicPluginProfiler profiler,
                           DebugHudManager hudManager) {
        this.plugin = plugin;
        this.serverController = serverController;
        this.healthMonitor = healthMonitor;
        this.metricsCollector = metricsCollector;
        this.profiler = profiler;
        this.hudManager = hudManager;
    }

    public TelemetrySnapshot captureSnapshot() {
        double[] tpsArr = serverController != null ? serverController.getTPS() : new double[]{20.0};
        double tps = tpsArr.length > 0 ? tpsArr[0] : 20.0;
        double mspt = serverController != null ? serverController.getMSPT() : 0.0;

        ServerController.MemoryInfo mem = serverController != null ? serverController.getMemoryInfo() : new ServerController.MemoryInfo(1024, 1024, 512, 512, 50);

        int entities = 0;
        int chunks = 0;
        for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
            entities += w.getEntities().size();
            chunks += w.getLoadedChunks().length;
        }

        int activePlugins = plugin.getPluginManager() != null ? plugin.getPluginManager().getActivePlugins().size() : 0;

        String slowestPlugin = "Không có";
        if (profiler != null) {
            var metrics = profiler.getAllMetrics();
            if (!metrics.isEmpty()) {
                String topPl = null;
                long maxTime = -1;
                for (var entry : metrics.entrySet()) {
                    long totalTime = 0;
                    for (var m : entry.getValue().values()) {
                        totalTime += m.totalNanos.get();
                    }
                    if (totalTime > maxTime) {
                        maxTime = totalTime;
                        topPl = entry.getKey();
                    }
                }
                if (topPl != null && maxTime > 0) {
                    slowestPlugin = topPl + " (" + String.format("%.2f", maxTime / 1_000_000.0) + "ms)";
                }
            }
        }

        int cveBlocked = plugin.getPanelCveProtection() != null ? plugin.getPanelCveProtection().getStats().getTotalBlocked() : 0;
        int exploitsBlocked = plugin.getExploitGuard() != null ? (int) plugin.getExploitGuard().getStats().getPacketsBlocked() : 0;

        return new TelemetrySnapshot(
                tps, mspt, mem.usedMb(), mem.maxMb(), mem.usedPercent(),
                entities, chunks, activePlugins, slowestPlugin,
                cveBlocked, exploitsBlocked
        );
    }

    public void executeEmergencyCleanup() {
        if (serverController != null) {
            serverController.purgeEntities(null);
            serverController.triggerGC();
        }
    }

    public ServerController getServerController() { return serverController; }
    public HealthMonitor getHealthMonitor() { return healthMonitor; }
    public MetricsCollector getMetricsCollector() { return metricsCollector; }
    public DynamicPluginProfiler getProfiler() { return profiler; }
    public DebugHudManager getHudManager() { return hudManager; }
}
