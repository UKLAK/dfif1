package com.javastudio.analytics;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.control.ServerController;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class AutoMitigationEngine {

    private final JavaStudioPlugin plugin;
    private final Logger logger;
    private final ServerController serverController;

    private boolean enabled = false;
    private double msptThreshold = 45.0;
    private double tpsThreshold = 16.5;
    private int requiredLagSeconds = 10;
    private int requiredRecoverySeconds = 15;

    private boolean purgeEntitiesEnabled = true;
    private boolean triggerGcEnabled = true;
    private boolean scaleViewDistanceEnabled = true;
    private int viewDistanceReduction = 2;
    private int minViewDistance = 4;
    private int simDistanceReduction = 2;
    private int minSimDistance = 3;

    private BukkitTask monitorTask;
    private int continuousLagTicks = 0;
    private int continuousHealthyTicks = 0;
    private boolean currentlyMitigating = false;

    private final Map<String, Integer> originalViewDistances = new ConcurrentHashMap<>();
    private final Map<String, Integer> originalSimDistances = new ConcurrentHashMap<>();
    private int totalMitigationsTriggered = 0;
    private long lastMitigationTime = 0;

    public AutoMitigationEngine(JavaStudioPlugin plugin, ServerController serverController) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.serverController = serverController;
        loadConfig();
    }

    public final synchronized void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.enabled = config.getBoolean("auto-mitigation.enabled", false);
        this.msptThreshold = config.getDouble("auto-mitigation.mspt-threshold", 45.0);
        this.tpsThreshold = config.getDouble("auto-mitigation.tps-threshold", 16.5);
        this.requiredLagSeconds = config.getInt("auto-mitigation.required-lag-seconds", 10);
        this.requiredRecoverySeconds = config.getInt("auto-mitigation.required-recovery-seconds", 15);
        this.purgeEntitiesEnabled = config.getBoolean("auto-mitigation.purge-entities", true);
        this.triggerGcEnabled = config.getBoolean("auto-mitigation.trigger-gc", true);
        this.scaleViewDistanceEnabled = config.getBoolean("auto-mitigation.scale-view-distance", true);
        this.viewDistanceReduction = config.getInt("auto-mitigation.view-distance-reduction", 2);
        this.minViewDistance = config.getInt("auto-mitigation.min-view-distance", 4);
        this.simDistanceReduction = config.getInt("auto-mitigation.sim-distance-reduction", 2);
        this.minSimDistance = config.getInt("auto-mitigation.min-sim-distance", 3);
    }

    public synchronized void saveConfig() {
        FileConfiguration config = plugin.getConfig();
        config.set("auto-mitigation.enabled", this.enabled);
        config.set("auto-mitigation.mspt-threshold", this.msptThreshold);
        config.set("auto-mitigation.tps-threshold", this.tpsThreshold);
        config.set("auto-mitigation.required-lag-seconds", this.requiredLagSeconds);
        config.set("auto-mitigation.required-recovery-seconds", this.requiredRecoverySeconds);
        config.set("auto-mitigation.purge-entities", this.purgeEntitiesEnabled);
        config.set("auto-mitigation.trigger-gc", this.triggerGcEnabled);
        config.set("auto-mitigation.scale-view-distance", this.scaleViewDistanceEnabled);
        config.set("auto-mitigation.view-distance-reduction", this.viewDistanceReduction);
        config.set("auto-mitigation.min-view-distance", this.minViewDistance);
        config.set("auto-mitigation.sim-distance-reduction", this.simDistanceReduction);
        config.set("auto-mitigation.min-sim-distance", this.minSimDistance);
        plugin.saveConfig();
    }

    public synchronized void start() {
        if (!enabled || monitorTask != null) return;

        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        logger.info("[AutoMitigation] Đã kích hoạt hệ thống tự động ứng cứu khi server quá tải (MSPT > "
                + msptThreshold + "ms, TPS < " + tpsThreshold + ")!");
    }

    public synchronized void stop() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        if (currentlyMitigating) {
            restoreOriginalSettings();
        }
    }

    private void tick() {
        if (!enabled) return;

        double currentTps = serverController.getTPS()[0];
        double currentMspt = serverController.getMSPT();

        boolean isOverloaded = (currentMspt > msptThreshold && currentMspt > 0) || (currentTps < tpsThreshold && currentTps > 0);

        if (isOverloaded) {
            continuousLagTicks++;
            continuousHealthyTicks = 0;

            if (continuousLagTicks >= requiredLagSeconds && !currentlyMitigating) {
                triggerMitigation("MSPT: " + String.format("%.2f", currentMspt) + "ms | TPS: " + String.format("%.2f", currentTps));
            }
        } else {
            continuousHealthyTicks++;
            continuousLagTicks = 0;

            if (continuousHealthyTicks >= requiredRecoverySeconds && currentlyMitigating) {
                restoreOriginalSettings();
            }
        }
    }

    public synchronized void triggerMitigation(String cause) {
        if (currentlyMitigating) return;
        currentlyMitigating = true;
        totalMitigationsTriggered++;
        lastMitigationTime = System.currentTimeMillis();

        logger.warning("==================================================");
        logger.warning("[AutoMitigation] CẢNH BÁO: Máy chủ quá tải! (" + cause + ")");
        logger.warning("[AutoMitigation] Đang tự động kích hoạt các biện pháp hạ nhiệt CPU...");
        logger.warning("==================================================");

        if (purgeEntitiesEnabled) {
            int purged = serverController.purgeEntities(null, true, true, false);
            logger.info("[AutoMitigation] Bước 1: Đã dọn dẹp " + purged + " thực thể rác/quái vật gây lag.");
        }

        if (triggerGcEnabled) {
            serverController.triggerGC();
        }

        if (scaleViewDistanceEnabled) {
            for (World world : Bukkit.getWorlds()) {
                try {
                    int curView = world.getViewDistance();
                    int curSim = world.getSimulationDistance();

                    originalViewDistances.put(world.getName(), curView);
                    originalSimDistances.put(world.getName(), curSim);

                    int targetView = Math.max(minViewDistance, curView - viewDistanceReduction);
                    int targetSim = Math.max(minSimDistance, curSim - simDistanceReduction);

                    world.setViewDistance(targetView);
                    world.setSimulationDistance(targetSim);
                    logger.info("[AutoMitigation] Bước 3: Đã hạ tầm nhìn thế giới '" + world.getName() + "': View " + curView + " -> " + targetView + ", Sim " + curSim + " -> " + targetSim);
                } catch (Throwable ignored) {}
            }
        }

        if (plugin.getPanelCveProtection() != null) {
            int purgedSessions = plugin.getPanelCveProtection().getSessionRevocationManager().purgeIdleZombieSessions();
            if (purgedSessions > 0) {
                logger.info("[AutoMitigation] Bước 4: Đã dọn dẹp " + purgedSessions + " phiên Zombie không hoạt động để giải phóng bộ nhớ.");
            }
        }

        if (plugin.getDiscordWebhook() != null) {
            plugin.getDiscordWebhook().sendEmbed("Auto-Mitigation Activated", "Server overload detected (" + cause + "). Ground items purged, sessions reaped and view distance scaled down.", 0xFF5555, null);
        }
    }

    public synchronized void restoreOriginalSettings() {
        if (!currentlyMitigating) return;
        currentlyMitigating = false;

        logger.info("==================================================");
        logger.info("[AutoMitigation] Hiệu năng máy chủ đã ổn định trở lại!");
        logger.info("[AutoMitigation] Đang khôi phục tầm nhìn và cấu hình thế giới về ban đầu...");
        logger.info("==================================================");

        if (scaleViewDistanceEnabled) {
            for (World world : Bukkit.getWorlds()) {
                try {
                    Integer origView = originalViewDistances.remove(world.getName());
                    Integer origSim = originalSimDistances.remove(world.getName());

                    if (origView != null) world.setViewDistance(origView);
                    if (origSim != null) world.setSimulationDistance(origSim);
                } catch (Throwable ignored) {}
            }
        }

        originalViewDistances.clear();
        originalSimDistances.clear();

        if (plugin.getDiscordWebhook() != null) {
            plugin.getDiscordWebhook().sendEmbed("Auto-Mitigation Restored", "Server performance stabilized. Original world settings restored.", 0x55FF55, null);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        saveConfig();
        if (enabled && monitorTask == null) {
            start();
        } else if (!enabled && monitorTask != null) {
            stop();
        }
    }

    public boolean isCurrentlyMitigating() {
        return currentlyMitigating;
    }

    public int getTotalMitigationsTriggered() {
        return totalMitigationsTriggered;
    }

    public long getLastMitigationTime() {
        return lastMitigationTime;
    }

    public double getMsptThreshold() {
        return msptThreshold;
    }

    public void setMsptThreshold(double msptThreshold) {
        this.msptThreshold = msptThreshold;
        saveConfig();
    }

    public double getTpsThreshold() {
        return tpsThreshold;
    }

    public void setTpsThreshold(double tpsThreshold) {
        this.tpsThreshold = tpsThreshold;
        saveConfig();
    }

    public int getRequiredLagSeconds() {
        return requiredLagSeconds;
    }

    public void setRequiredLagSeconds(int requiredLagSeconds) {
        this.requiredLagSeconds = requiredLagSeconds;
        saveConfig();
    }

    public int getRequiredRecoverySeconds() {
        return requiredRecoverySeconds;
    }

    public void setRequiredRecoverySeconds(int requiredRecoverySeconds) {
        this.requiredRecoverySeconds = requiredRecoverySeconds;
        saveConfig();
    }

    public boolean isPurgeEntitiesEnabled() {
        return purgeEntitiesEnabled;
    }

    public void setPurgeEntitiesEnabled(boolean purgeEntitiesEnabled) {
        this.purgeEntitiesEnabled = purgeEntitiesEnabled;
        saveConfig();
    }

    public boolean isTriggerGcEnabled() {
        return triggerGcEnabled;
    }

    public void setTriggerGcEnabled(boolean triggerGcEnabled) {
        this.triggerGcEnabled = triggerGcEnabled;
        saveConfig();
    }

    public boolean isScaleViewDistanceEnabled() {
        return scaleViewDistanceEnabled;
    }

    public void setScaleViewDistanceEnabled(boolean scaleViewDistanceEnabled) {
        this.scaleViewDistanceEnabled = scaleViewDistanceEnabled;
        saveConfig();
    }

    public int getViewDistanceReduction() {
        return viewDistanceReduction;
    }

    public void setViewDistanceReduction(int viewDistanceReduction) {
        this.viewDistanceReduction = viewDistanceReduction;
        saveConfig();
    }

    public int getMinViewDistance() {
        return minViewDistance;
    }

    public void setMinViewDistance(int minViewDistance) {
        this.minViewDistance = minViewDistance;
        saveConfig();
    }

    public int getSimDistanceReduction() {
        return simDistanceReduction;
    }

    public void setSimDistanceReduction(int simDistanceReduction) {
        this.simDistanceReduction = simDistanceReduction;
        saveConfig();
    }

    public int getMinSimDistance() {
        return minSimDistance;
    }

    public void setMinSimDistance(int minSimDistance) {
        this.minSimDistance = minSimDistance;
        saveConfig();
    }

    public String getStatusSummary() {
        return "Trạng thái: " + (enabled ? (currentlyMitigating ? "§c[ĐANG ỨNG CỨU GIẢM TẢI]" : "§a[BẬT - ĐANG THEO DÕI]") : "§7[MẶC ĐỊNH TẮT]") +
                "\n§7- MSPT Threshold: §e" + msptThreshold + "ms§7, TPS Threshold: §e" + tpsThreshold +
                "\n§7- Thời gian phát hiện: §b" + requiredLagSeconds + "s§7, Thời gian phục hồi: §b" + requiredRecoverySeconds + "s" +
                "\n§7- Dọn thực thể: " + (purgeEntitiesEnabled ? "§aBẬT" : "§cTẮT") +
                "§7 | Thu gom rác GC: " + (triggerGcEnabled ? "§aBẬT" : "§cTẮT") +
                "§7 | Giảm tầm nhìn: " + (scaleViewDistanceEnabled ? "§aBẬT (Giảm -" + viewDistanceReduction + ", Tối thiểu " + minViewDistance + ")" : "§cTẮT") +
                "\n§7- Đã kích hoạt: §f" + totalMitigationsTriggered + " lần";
    }
}
