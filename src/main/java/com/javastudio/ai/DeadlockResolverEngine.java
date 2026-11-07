package com.javastudio.ai;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.PlatformUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DeadlockResolverEngine {

    private final JavaStudioPlugin plugin;
    private final ThreadMXBean threadBean;
    private PlatformUtil.TaskHandle checkTask;
    private final AtomicBoolean resolving = new AtomicBoolean(false);
    private final List<DeadlockReport> incidents = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, Integer> threadInterruptAttempts = new ConcurrentHashMap<>();

    private boolean autoResolve = true;
    private long checkIntervalTicks = 100L;

    public record DeadlockReport(
            String incidentId,
            long timestamp,
            int threadCount,
            List<String> threadDetails,
            List<String> cycleSummary,
            boolean resolvedSuccessfully
    ) {}

    public DeadlockResolverEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.autoResolve = plugin.getConfig().getBoolean("security.deadlock-resolver.auto-resolve", true);
    }

    public void start() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        checkTask = PlatformUtil.runTaskTimer(plugin, this::scanForDeadlocks, checkIntervalTicks, checkIntervalTicks);
    }

    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    public void scanForDeadlocks() {
        if (!resolving.compareAndSet(false, true)) {
            return;
        }

        PlatformUtil.runTaskAsync(plugin, () -> {
            try {
                long[] deadlockedIds = threadBean.findDeadlockedThreads();
                if (deadlockedIds == null || deadlockedIds.length == 0) {
                    deadlockedIds = threadBean.findMonitorDeadlockedThreads();
                }

                if (deadlockedIds != null && deadlockedIds.length > 0) {
                    handleDeadlock(deadlockedIds);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[DeadlockResolver] Lỗi trong quá trình quét deadlock: " + t.getMessage());
            } finally {
                resolving.set(false);
            }
        });
    }

    private void handleDeadlock(long[] threadIds) {
        String incidentId = "DL-" + System.currentTimeMillis();
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds, true, true);

        List<String> details = new ArrayList<>();
        List<String> cycleSummary = new ArrayList<>();
        List<Thread> candidateThreadsToInterrupt = new ArrayList<>();

        Map<Long, Thread> liveThreads = new HashMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            liveThreads.put(t.threadId(), t);
        }

        plugin.getLogger().severe("====================================================");
        plugin.getLogger().severe("[DeadlockResolver] 🚨 PHÁT HIỆN DEADLOCK HỆ THỐNG (" + threadIds.length + " luồng bị khóa lẫn nhau)!");
        plugin.getLogger().severe("[DeadlockResolver] Mã sự cố: " + incidentId);

        for (ThreadInfo info : threadInfos) {
            if (info == null) continue;

            String threadDesc = String.format(Locale.ROOT,
                    "Luồng #%d [%s] Trạng thái: %s | Đang chờ Lock: %s (sở hữu bởi #%d [%s])",
                    info.getThreadId(),
                    info.getThreadName(),
                    info.getThreadState(),
                    info.getLockName(),
                    info.getLockOwnerId(),
                    info.getLockOwnerName()
            );
            details.add(threadDesc);
            plugin.getLogger().severe(" -> " + threadDesc);

            cycleSummary.add(info.getThreadName() + " -> " + info.getLockOwnerName());

            Thread t = liveThreads.get(info.getThreadId());
            if (t != null && !isCriticalServerThread(t)) {
                candidateThreadsToInterrupt.add(t);
            }
        }

        dumpToFile(incidentId, threadInfos);

        boolean resolved = false;
        if (autoResolve && !candidateThreadsToInterrupt.isEmpty()) {
            resolved = attemptAutoResolution(candidateThreadsToInterrupt);
        }

        DeadlockReport report = new DeadlockReport(
                incidentId,
                System.currentTimeMillis(),
                threadIds.length,
                details,
                cycleSummary,
                resolved
        );
        incidents.add(report);
        if (incidents.size() > 50) {
            incidents.remove(0);
        }

        plugin.getLogger().severe("[DeadlockResolver] Trạng thái xử lý tự động: " + (resolved ? "ĐÃ GIẢI TỎA THÀNH CÔNG" : "CẦN CAN THIỆP THỦ CÔNG"));
        plugin.getLogger().severe("====================================================");

        if (plugin.getDiscordWebhook() != null) {
            plugin.getDiscordWebhook().sendLog(
                    "🚨 Deadlock Detected: " + incidentId,
                    "Detected deadlock cycle among " + threadIds.length + " threads.\nCycle: " + String.join(" -> ", cycleSummary) + "\nAuto-resolved: " + resolved
            );
        }
    }

    private boolean isCriticalServerThread(Thread t) {
        String name = t.getName().toLowerCase(Locale.ROOT);
        return name.contains("server thread") || name.contains("servermain") || name.contains("connection");
    }

    private boolean attemptAutoResolution(List<Thread> candidates) {
        for (Thread t : candidates) {
            int attempts = threadInterruptAttempts.getOrDefault(t.threadId(), 0);
            if (attempts < 3) {
                threadInterruptAttempts.put(t.threadId(), attempts + 1);
                plugin.getLogger().warning("[DeadlockResolver] Đang can thiệp ngắt luồng không cốt lõi để phá vỡ vòng lặp khóa: " + t.getName() + " (Lần " + (attempts + 1) + ")");
                try {
                    t.interrupt();
                    Thread.sleep(100);
                    long[] remaining = threadBean.findDeadlockedThreads();
                    if (remaining == null || remaining.length == 0) {
                        plugin.getLogger().info("[DeadlockResolver] Vòng lặp khóa đã được giải tỏa thành công sau khi ngắt luồng " + t.getName());
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private void dumpToFile(String incidentId, ThreadInfo[] threadInfos) {
        try {
            File dir = new File(plugin.getDataFolder(), "logs/deadlocks");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File dumpFile = new File(dir, incidentId + ".txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(dumpFile, StandardCharsets.UTF_8))) {
                writer.println("JavaStudio Deadlock Diagnostic Report");
                writer.println("Timestamp: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                writer.println("Incident ID: " + incidentId);
                writer.println("==================================================");
                for (ThreadInfo info : threadInfos) {
                    if (info == null) continue;
                    writer.println(info);
                    StackTraceElement[] stack = info.getStackTrace();
                    for (StackTraceElement elem : stack) {
                        writer.println("    at " + elem);
                    }
                    writer.println();
                }
            }
        } catch (Throwable ignored) {}
    }

    public List<DeadlockReport> getIncidents() {
        return Collections.unmodifiableList(incidents);
    }

    public boolean isAutoResolve() {
        return autoResolve;
    }

    public void setAutoResolve(boolean autoResolve) {
        this.autoResolve = autoResolve;
    }
}

