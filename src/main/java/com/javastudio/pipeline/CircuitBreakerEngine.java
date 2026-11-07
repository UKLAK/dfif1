package com.javastudio.pipeline;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class CircuitBreakerEngine {

    public enum BreakerState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private volatile int maxErrorsPerSecond = 15;
    private volatile double maxExecutionMs = 40.0;
    private volatile int maxConsecutiveSlowCalls = 5;
    private volatile long recoveryCooldownMs = 30_000L;

    private final Map<String, BreakerNode> breakers = new ConcurrentHashMap<>();
    private final Map<String, CrashSnapshot> crashVault = new ConcurrentHashMap<>();
    private final Deque<String> crashOrder = new ConcurrentLinkedDeque<>();
    private static final int MAX_CRASH_SNAPSHOTS = 50;

    private final Map<UUID, TraceSession> activeWatchers = new ConcurrentHashMap<>();

    public int instrumentPlugin(Plugin targetPlugin) {
        int hookedCount = 0;
        try {
            Field executorField = RegisteredListener.class.getDeclaredField("executor");
            executorField.setAccessible(true);

            for (HandlerList handlerList : HandlerList.getHandlerLists()) {
                for (RegisteredListener regListener : handlerList.getRegisteredListeners()) {
                    if (targetPlugin != null && !regListener.getPlugin().equals(targetPlugin)) {
                        continue;
                    }
                    EventExecutor currentExec = (EventExecutor) executorField.get(regListener);
                    if (currentExec instanceof MonitoredEventExecutor) {
                        continue;
                    }

                    String listenerClass = regListener.getListener().getClass().getSimpleName();
                    String pluginName = regListener.getPlugin().getName();
                    String nodeId = pluginName + ":" + listenerClass + "@" + Integer.toHexString(System.identityHashCode(regListener));

                    BreakerNode node = new BreakerNode(nodeId, pluginName, listenerClass, regListener.getPriority().name());
                    breakers.put(nodeId, node);

                    MonitoredEventExecutor wrapped = new MonitoredEventExecutor(currentExec, node);
                    executorField.set(regListener, wrapped);
                    hookedCount++;
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[JavaStudio-Breaker] Không thể gắn cầu chì: " + e.getMessage());
        }
        return hookedCount;
    }

    private final class MonitoredEventExecutor implements EventExecutor {
        private final EventExecutor delegate;
        private final BreakerNode node;

        MonitoredEventExecutor(EventExecutor delegate, BreakerNode node) {
            this.delegate = delegate;
            this.node = node;
        }

        @Override
        public void execute(Listener listener, Event event) throws EventException {
            String eventName = event.getEventName();

            if (node.state == BreakerState.OPEN) {
                long now = System.currentTimeMillis();
                if (now - node.trippedTimestamp >= recoveryCooldownMs) {
                    node.state = BreakerState.HALF_OPEN;
                } else {
                    node.skippedCalls.incrementAndGet();
                    return;
                }
            }

            long startNano = System.nanoTime();
            boolean preCancelled = (event instanceof Cancellable c) && c.isCancelled();
            Throwable caughtError = null;

            try {
                delegate.execute(listener, event);

                if (node.state == BreakerState.HALF_OPEN) {
                    node.reset();
                    broadcastToAdmins("§a[CircuitBreaker] §fĐã tự động phục hồi §e" + node.id + " §fsau khi ổn định.");
                }
            } catch (Throwable t) {
                caughtError = (t instanceof EventException && t.getCause() != null) ? t.getCause() : t;
                handleExecutionFailure(listener, event, caughtError);
            } finally {
                long durationNano = System.nanoTime() - startNano;
                double durationMs = durationNano / 1_000_000.0;
                node.recordExecution(durationNano);

                if (Bukkit.isPrimaryThread() && durationMs >= maxExecutionMs) {
                    int slowStreak = node.consecutiveSlowCalls.incrementAndGet();
                    if (slowStreak >= maxConsecutiveSlowCalls && node.state != BreakerState.OPEN) {
                        tripBreaker(node, String.format(Locale.US, "Chiếm dụng Main Thread %.2fms liên tiếp %d lần (Sự kiện: %s)",
                                durationMs, slowStreak, eventName));
                    }
                } else if (durationMs < maxExecutionMs) {
                    node.consecutiveSlowCalls.set(0);
                }

                if (!activeWatchers.isEmpty()) {
                    boolean postCancelled = (event instanceof Cancellable c) && c.isCancelled();
                    dispatchTraceLog(node, event, durationMs, preCancelled, postCancelled, caughtError);
                }
            }
        }

        private void handleExecutionFailure(Listener listener, Event event, Throwable error) {
            int currentSecErrors = node.incrementErrorCount();

            String crashId = "ERR-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            CrashSnapshot snapshot = new CrashSnapshot(
                    crashId,
                    node.id,
                    event.getEventName(),
                    error,
                    inspectObjectFields(listener),
                    inspectObjectFields(event)
            );
            storeCrashSnapshot(snapshot);

            if (currentSecErrors >= maxErrorsPerSecond || node.state == BreakerState.HALF_OPEN) {
                tripBreaker(node, "Phát sinh " + currentSecErrors + " lỗi/giây (" + error.getClass().getSimpleName()
                        + "). Mã hộp đen: §e" + crashId);
            }
        }
    }

    private void tripBreaker(BreakerNode node, String reason) {
        node.state = BreakerState.OPEN;
        node.trippedTimestamp = System.currentTimeMillis();
        node.tripReason = reason;
        broadcastToAdmins("§c§l[CẦU CHÌ NGẮT MẠCH] §e" + node.id + " §fđã bị cô lập tự động!\n"
                + " §7▪ Lý do: §f" + reason + "\n"
                + " §7▪ Gõ §b/jtrace dump §7hoặc §a/jtrace reset " + node.id + " §7để xử lý.");
    }

    private void dispatchTraceLog(BreakerNode node, Event event, double durationMs,
                                  boolean preCancel, boolean postCancel, Throwable error) {
        String targetSignature = (node.pluginName + ":" + node.listenerClass + ":" + event.getEventName()).toLowerCase();

        for (Map.Entry<UUID, TraceSession> entry : activeWatchers.entrySet()) {
            Player admin = Bukkit.getPlayer(entry.getKey());
            if (admin == null || !admin.isOnline()) {
                activeWatchers.remove(entry.getKey());
                continue;
            }

            TraceSession session = entry.getValue();
            if (durationMs < session.minDurationMs) continue;
            if (!session.keyword.equals("all") && !targetSignature.contains(session.keyword)) continue;

            String timeColor = durationMs > 20.0 ? "§c" : (durationMs > 5.0 ? "§e" : "§a");
            String cancelBadge = postCancel ? (preCancel ? " §7[Đã hủy sẵn]" : " §d[Vừa hủy sự kiện]") : "";
            String errorBadge = (error != null) ? " §c[LỖI: " + error.getClass().getSimpleName() + "]" : "";

            admin.sendMessage(String.format(Locale.US, "§8[§bJTrace§8] §e%s§7#§f%s §8(§7%s§8) §7→ %s%.3fms%s%s",
                    node.pluginName, node.listenerClass, event.getEventName(),
                    timeColor, durationMs, cancelBadge, errorBadge));
        }
    }

    private Map<String, String> inspectObjectFields(Object target) {
        Map<String, String> dump = new LinkedHashMap<>();
        if (target == null) return dump;

        Class<?> clazz = target.getClass();
        int fieldLimit = 0;
        while (clazz != null && clazz != Object.class && fieldLimit < 25) {
            for (Field field : clazz.getDeclaredFields()) {
                if (fieldLimit++ >= 25) break;
                try {
                    field.setAccessible(true);
                    Object val = field.get(target);
                    dump.put(field.getName() + " (" + field.getType().getSimpleName() + ")",
                            val == null ? "null" : String.valueOf(val));
                } catch (Exception e) {
                    dump.put(field.getName(), "<Không thể đọc>");
                }
            }
            clazz = clazz.getSuperclass();
        }
        return dump;
    }

    private void storeCrashSnapshot(CrashSnapshot snapshot) {
        crashVault.put(snapshot.id, snapshot);
        crashOrder.addFirst(snapshot.id);
        while (crashOrder.size() > MAX_CRASH_SNAPSHOTS) {
            String oldest = crashOrder.pollLast();
            if (oldest != null) crashVault.remove(oldest);
        }
    }

    private void broadcastToAdmins(String message) {
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("javastudio.admin")) {
                p.sendMessage(message);
            }
        }
    }


    public void startWatching(UUID playerUuid, String keyword, double minMs) {
        activeWatchers.put(playerUuid, new TraceSession(keyword.toLowerCase(), minMs));
    }

    public boolean stopWatching(UUID playerUuid) {
        return activeWatchers.remove(playerUuid) != null;
    }

    public Collection<BreakerNode> getAllBreakers() {
        return breakers.values();
    }

    public int resetBreaker(String idOrAll) {
        int count = 0;
        for (BreakerNode node : breakers.values()) {
            if (idOrAll.equalsIgnoreCase("all") || node.id.equalsIgnoreCase(idOrAll)
                    || node.pluginName.equalsIgnoreCase(idOrAll)) {
                node.reset();
                count++;
            }
        }
        return count;
    }

    public CrashSnapshot getCrashSnapshot(String id) {
        if (id == null || id.equalsIgnoreCase("latest")) {
            String latestId = crashOrder.peekFirst();
            return latestId != null ? crashVault.get(latestId) : null;
        }
        return crashVault.get(id.toUpperCase());
    }

    public List<CrashSnapshot> getRecentCrashes(int limit) {
        List<CrashSnapshot> list = new ArrayList<>();
        for (String id : crashOrder) {
            if (list.size() >= limit) break;
            CrashSnapshot s = crashVault.get(id);
            if (s != null) list.add(s);
        }
        return list;
    }

    public CrashSnapshot getLatestCrashForPlugin(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return null;
        String needle = pluginName.toLowerCase(Locale.ROOT);
        for (String id : crashOrder) {
            CrashSnapshot s = crashVault.get(id);
            if (s == null) continue;
            if (s.nodeId.toLowerCase(Locale.ROOT).contains(needle)
                    || s.fullStackTrace.toLowerCase(Locale.ROOT).contains(needle)) {
                return s;
            }
        }
        return null;
    }

    public void setMaxErrorsPerSecond(int val) { this.maxErrorsPerSecond = Math.max(1, val); }
    public void setMaxExecutionMs(double val) { this.maxExecutionMs = Math.max(1.0, val); }
    public int getMaxErrorsPerSecond() { return maxErrorsPerSecond; }
    public double getMaxExecutionMs() { return maxExecutionMs; }


    public record TraceSession(String keyword, double minDurationMs) {}

    public static final class BreakerNode {
        public final String id;
        public final String pluginName;
        public final String listenerClass;
        public final String priority;

        public volatile BreakerState state = BreakerState.CLOSED;
        public volatile long trippedTimestamp = 0L;
        public volatile String tripReason = "Bình thường";

        public final AtomicLong totalCalls = new AtomicLong();
        public final AtomicLong totalTimeNano = new AtomicLong();
        public final AtomicLong maxTimeNano = new AtomicLong();
        public final AtomicLong totalErrors = new AtomicLong();
        public final AtomicLong skippedCalls = new AtomicLong();
        public final AtomicInteger consecutiveSlowCalls = new AtomicInteger();

        private volatile long currentSecondWindow = System.currentTimeMillis() / 1000L;
        private final AtomicInteger errorsInCurrentSecond = new AtomicInteger();

        public BreakerNode(String id, String pluginName, String listenerClass, String priority) {
            this.id = id;
            this.pluginName = pluginName;
            this.listenerClass = listenerClass;
            this.priority = priority;
        }

        public void recordExecution(long nano) {
            totalCalls.incrementAndGet();
            totalTimeNano.addAndGet(nano);
            maxTimeNano.accumulateAndGet(nano, Math::max);
        }

        public int incrementErrorCount() {
            totalErrors.incrementAndGet();
            long nowSec = System.currentTimeMillis() / 1000L;
            if (nowSec != currentSecondWindow) {
                currentSecondWindow = nowSec;
                errorsInCurrentSecond.set(1);
                return 1;
            }
            return errorsInCurrentSecond.incrementAndGet();
        }

        public double getAverageMs() {
            long calls = totalCalls.get();
            return calls == 0 ? 0.0 : (totalTimeNano.get() / 1_000_000.0) / calls;
        }

        public double getMaxMs() {
            return maxTimeNano.get() / 1_000_000.0;
        }

        public void reset() {
            state = BreakerState.CLOSED;
            consecutiveSlowCalls.set(0);
            errorsInCurrentSecond.set(0);
            tripReason = "Bình thường";
        }
    }

    public static final class CrashSnapshot {
        public final String id;
        public final String nodeId;
        public final String eventName;
        public final String timestamp;
        public final String exceptionType;
        public final String exceptionMessage;
        public final String shortStackTrace;
        public final String fullStackTrace;
        public final Map<String, String> listenerFields;
        public final Map<String, String> eventFields;

        public CrashSnapshot(String id, String nodeId, String eventName, Throwable error,
                             Map<String, String> listenerFields, Map<String, String> eventFields) {
            this.id = id;
            this.nodeId = nodeId;
            this.eventName = eventName;
            this.timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            this.exceptionType = error.getClass().getName();
            this.exceptionMessage = error.getMessage() != null ? error.getMessage() : "Không có thông điệp";
            this.listenerFields = listenerFields;
            this.eventFields = eventFields;

            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            this.fullStackTrace = sw.toString();
            String[] lines = this.fullStackTrace.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(lines.length, 6); i++) {
                sb.append("  §c").append(lines[i].trim()).append("\n");
            }
            this.shortStackTrace = sb.toString();
        }
    }
}
