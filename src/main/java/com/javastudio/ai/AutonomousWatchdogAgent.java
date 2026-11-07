package com.javastudio.ai;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.pipeline.CircuitBreakerEngine;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AutonomousWatchdogAgent {

    private final JavaStudioPlugin plugin;
    private final AiAssistant ai;

    private boolean enabled = true;
    private boolean autoMitigate = true;
    private double tpsThreshold = 16.0;
    private long stallThresholdMs = 1500L;

    private PlatformUtil.TaskHandle watchdogTaskHandle;
    private long lastHealthyTime = System.currentTimeMillis();
    private final AtomicBoolean isDiagnosing = new AtomicBoolean(false);
    private final List<WatchdogIncident> incidentHistory = Collections.synchronizedList(new ArrayList<>());

    public record WatchdogIncident(
            String id,
            long timestamp,
            double tps,
            String culpritThread,
            String culpritLocation,
            String aiDiagnosis,
            boolean autoMitigated
    ) {}

    public AutonomousWatchdogAgent(JavaStudioPlugin plugin, AiAssistant ai) {
        this.plugin = plugin;
        this.ai = ai;
        loadConfig();
    }

    public void loadConfig() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("ai-assistant.watchdog.enabled", true);
        this.autoMitigate = cfg.getBoolean("ai-assistant.watchdog.auto-mitigate", true);
        this.tpsThreshold = cfg.getDouble("ai-assistant.watchdog.tps-threshold", 16.0);
    }

    public void start() {
        if (watchdogTaskHandle != null) watchdogTaskHandle.cancel();
        watchdogTaskHandle = PlatformUtil.runTaskTimer(plugin, this::checkHealth, 60L, 60L);
    }

    public void stop() {
        if (watchdogTaskHandle != null) {
            watchdogTaskHandle.cancel();
            watchdogTaskHandle = null;
        }
    }

    private void checkHealth() {
        if (!enabled) return;

        double tps = 20.0;
        try {
            double[] t = plugin.getServerController().getTPS();
            if (t != null && t.length > 0) tps = t[0];
        } catch (Throwable ignored) {}

        long now = System.currentTimeMillis();
        if (tps >= tpsThreshold) {
            lastHealthyTime = now;
            return;
        }

        if (now - lastHealthyTime > stallThresholdMs && !isDiagnosing.get()) {
            triggerIncident(tps);
        }
    }

    public void triggerIncident(double currentTps) {
        if (!isDiagnosing.compareAndSet(false, true)) return;

        PlatformUtil.runTaskAsync(plugin, () -> {
            try {
                String incidentId = "INC-" + System.currentTimeMillis();
                Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();

                Thread mainThread = null;
                for (Thread t : allThreads.keySet()) {
                    if (t.getName().equalsIgnoreCase("Server thread") || t.getName().contains("ServerMain")) {
                        mainThread = t;
                        break;
                    }
                }
                if (mainThread == null) {
                    mainThread = Thread.currentThread();
                }

                StackTraceElement[] traces = allThreads.get(mainThread);
                String culpritInfo = findCulprit(traces);
                String stackTraceText = formatStackTrace(mainThread, traces);

                plugin.getLogger().warning("[AI-Watchdog] ⚠️ PHÁT HIỆN SERVER TỤT TPS: " + String.format(Locale.ROOT, "%.2f", currentTps));
                plugin.getLogger().warning("[AI-Watchdog] Thủ phạm tiềm năng: " + culpritInfo);

                boolean mitigated = false;
                if (autoMitigate && culpritInfo.contains("com.javastudio.projects")) {
                    mitigated = mitigateDynamicPlugin(culpritInfo);
                }

                saveThreadDumpToFile(incidentId, stackTraceText);

                String aiRca = "Chưa kết nối AI";
                if (ai != null && ai.isConfigured()) {
                    try {
                        String prompt = "The Minecraft Server Main Thread experienced severe TPS drop (TPS: " +
                                String.format(Locale.ROOT, "%.2f", currentTps) + "). Culprit trace:\n" +
                                stackTraceText + "\nDiagnose the root-cause (RCA) in 3 sentences and provide practical fix.";
                        aiRca = ai.askQuestion(prompt);
                    } catch (Throwable t) {
                        aiRca = "Lỗi khi tham vấn AI: " + t.getMessage();
                    }
                }

                WatchdogIncident incident = new WatchdogIncident(
                        incidentId, System.currentTimeMillis(), currentTps,
                        mainThread.getName(), culpritInfo, aiRca, mitigated
                );
                incidentHistory.add(incident);
                if (incidentHistory.size() > 50) incidentHistory.remove(0);

                notifyAdminsAndDiscord(incident);

            } finally {
                isDiagnosing.set(false);
            }
        });
    }

    private String findCulprit(StackTraceElement[] traces) {
        if (traces == null || traces.length == 0) return "Không tìm thấy dấu vết luồng";

        for (StackTraceElement el : traces) {
            String cn = el.getClassName();
            if (cn.startsWith("com.javastudio.projects.") || cn.startsWith("com.javastudio.runtime.")) {
                return cn + "#" + el.getMethodName() + " (dòng " + el.getLineNumber() + ") [DynamicPlugin]";
            }
        }
        for (StackTraceElement el : traces) {
            String cn = el.getClassName();
            if (!cn.startsWith("java.") && !cn.startsWith("jdk.") && !cn.startsWith("net.minecraft.")) {
                return cn + "#" + el.getMethodName() + " (dòng " + el.getLineNumber() + ")";
            }
        }
        return traces[0].getClassName() + "#" + traces[0].getMethodName() + " (dòng " + traces[0].getLineNumber() + ")";
    }

    private boolean mitigateDynamicPlugin(String culpritInfo) {
        try {
            int start = culpritInfo.indexOf("com.javastudio.projects.");
            if (start != -1) {
                int end = culpritInfo.indexOf("Plugin", start);
                if (end != -1) {
                    String pName = culpritInfo.substring(start + "com.javastudio.projects.".length(), end);
                    plugin.getLogger().warning("[AI-Watchdog] ⚡ TỰ TRỊ ỨNG CỨU: Tự động ngắt cầu chì (CircuitBreaker) cho dynamic plugin: " + pName);

                    StringBuilder log = new StringBuilder();
                    if (plugin.getPluginManager() != null) {
                        plugin.getPluginManager().unloadPlugin(pName);
                    }
                    return true;
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[AI-Watchdog] Không thể tự động ứng cứu: " + t.getMessage());
        }
        return false;
    }

    private void saveThreadDumpToFile(String incidentId, String text) {
        File dumpsDir = new File(plugin.getDataFolder(), "logs" + File.separator + "watchdog");
        if (!dumpsDir.exists()) dumpsDir.mkdirs();

        File dumpFile = new File(dumpsDir, incidentId + ".dump.txt");
        try (PrintWriter pw = new PrintWriter(new FileWriter(dumpFile, StandardCharsets.UTF_8))) {
            pw.println("=== THREAD DUMP TẠO BỞI AUTONOMOUS AI WATCHDOG ===");
            pw.println("Thời gian: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            pw.println();
            pw.println(text);
        } catch (Throwable ignored) {}
    }

    private String formatStackTrace(Thread thread, StackTraceElement[] traces) {
        StringBuilder sb = new StringBuilder();
        sb.append("Thread: ").append(thread.getName()).append(" [State: ").append(thread.getState()).append("]\n");
        if (traces != null) {
            for (StackTraceElement el : traces) {
                sb.append("    at ").append(el.toString()).append("\n");
            }
        }
        return sb.toString();
    }

    private void notifyAdminsAndDiscord(WatchdogIncident inc) {
        String alertMsg = MessageUtil.colorize(String.format(
                "&8[&c&lAI-WATCHDOG&8] &c[!] Đã kích hoạt ứng cứu sự cố &e%s &c(TPS: &f%.2f&c)\n" +
                "  &7▪ Thủ phạm: &e%s\n" +
                "  &7▪ Ứng cứu: %s\n" +
                "  &7▪ Chuẩn đoán AI: &b%s",
                inc.id(), inc.tps(), inc.culpritLocation(),
                inc.autoMitigated() ? "&aĐã tự động ngắt module gây nghẽn!" : "&eChưa ngắt (Không thuộc dynamic code)",
                inc.aiDiagnosis()
        ));

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("javastudio.admin")) {
                p.sendMessage(alertMsg);
            }
        }

        if (plugin.getDiscordWebhook() != null) {
            plugin.getDiscordWebhook().sendLog(
                    "⚠️ [AI-Watchdog] Báo động quá tải server",
                    "Sự cố: " + inc.id() + "\nTPS: " + inc.tps() + "\nThủ phạm: " + inc.culpritLocation() + "\nChuẩn đoán: " + inc.aiDiagnosis()
            );
        }
    }

    public List<WatchdogIncident> getIncidentHistory() {
        return Collections.unmodifiableList(incidentHistory);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoMitigate() {
        return autoMitigate;
    }

    public void setAutoMitigate(boolean autoMitigate) {
        this.autoMitigate = autoMitigate;
    }
}

