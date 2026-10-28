package com.javastudio.pipeline;

import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ExecutionWatchdog: Giám sát giới hạn thực thi (Instruction Quota & Timeouts) trên Main Thread.
 * Ngăn chặn 100% tình trạng một đoạn mã Java (/jexec, script hoặc dynamic plugin)
 * chiếm dụng luồng chính quá 200ms làm giật lag hoặc treo máy chủ.
 */
public final class ExecutionWatchdog {

    public static final long MAIN_THREAD_MAX_NANOS = 200_000_000L; // 200 milliseconds
    private static final AtomicLong ID_SEQ = new AtomicLong(0);

    private static volatile boolean enabled = true;
    private static volatile long mainThreadLimitNanos = MAIN_THREAD_MAX_NANOS;

    public record ExecutionHandle(
            String id,
            Thread thread,
            long startNano,
            String caller,
            String codeSnippet,
            boolean mainThread,
            AtomicLong loopIterations
    ) {}

    private static final Map<String, ExecutionHandle> activeExecutions = new ConcurrentHashMap<>();

    private ExecutionWatchdog() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean val) {
        enabled = val;
    }

    public static long getMainThreadLimitMs() {
        return mainThreadLimitNanos / 1_000_000L;
    }

    public static void setMainThreadLimitMs(long ms) {
        mainThreadLimitNanos = ms * 1_000_000L;
    }

    /**
     * Bắt đầu theo dõi một phiên thực thi code
     */
    public static ExecutionHandle start(String caller, String code) {
        String id = "exec-" + ID_SEQ.incrementAndGet();
        Thread current = Thread.currentThread();
        boolean isMain = Bukkit.isPrimaryThread();
        String snippet = (code != null) ? (code.length() > 60 ? code.substring(0, 57) + "..." : code) : "N/A";
        snippet = snippet.replace("\n", " ").replace("\r", "");

        ExecutionHandle handle = new ExecutionHandle(
                id,
                current,
                System.nanoTime(),
                caller != null ? caller : "Console",
                snippet,
                isMain,
                new AtomicLong(0)
        );

        activeExecutions.put(id, handle);
        return handle;
    }

    /**
     * Kết thúc phiên thực thi
     */
    public static void stop(ExecutionHandle handle) {
        if (handle != null) {
            activeExecutions.remove(handle.id());
        }
    }

    /**
     * Kiểm tra hạn ngạch thực thi. Được gọi trong vòng lặp hoặc checkpoint.
     * Nếu đang chạy trên Main Thread và vượt quá hạn ngạch (200ms), ném TimeoutException ngay lập tức.
     */
    public static void checkQuota(ExecutionHandle handle) {
        if (!enabled || handle == null) return;
        handle.loopIterations().incrementAndGet();

        if (handle.mainThread()) {
            long elapsedNanos = System.nanoTime() - handle.startNano();
            if (elapsedNanos > mainThreadLimitNanos) {
                long elapsedMs = elapsedNanos / 1_000_000L;
                long limitMs = mainThreadLimitNanos / 1_000_000L;
                throw new ExecutionTimeoutException(String.format(
                        "Thực thi [%s] đã bị hủy! Chiếm dụng Main Thread quá thời hạn an toàn: %dms > %dms. Đoạn mã: '%s'",
                        handle.id(), elapsedMs, limitMs, handle.codeSnippet()
                ));
            }
        }
    }

    /**
     * Phương thức tĩnh dùng cho bytecode / AST injection tự động
     */
    public static void checkCurrentQuota(long startNano, String execId) {
        if (!enabled) return;
        if (Bukkit.isPrimaryThread()) {
            long elapsedNanos = System.nanoTime() - startNano;
            if (elapsedNanos > mainThreadLimitNanos) {
                long elapsedMs = elapsedNanos / 1_000_000L;
                long limitMs = mainThreadLimitNanos / 1_000_000L;
                throw new ExecutionTimeoutException(String.format(
                        "Main Thread Quota Exceeded! Vòng lặp vượt ngưỡng %dms > %dms. Đã ngắt script [%s] an toàn.",
                        elapsedMs, limitMs, execId != null ? execId : "script"
                ));
            }
        }
    }

    /**
     * Hủy/Ngắt một phiên thực thi đang chạy theo ID
     */
    public static boolean killExecution(String id) {
        ExecutionHandle handle = activeExecutions.remove(id);
        if (handle == null) return false;

        Thread t = handle.thread();
        if (t != null && t.isAlive() && t != Thread.currentThread()) {
            try {
                t.interrupt();
            } catch (Throwable ignored) {}
            return true;
        }
        return false;
    }

    public static List<ExecutionHandle> getActiveExecutions() {
        return new ArrayList<>(activeExecutions.values());
    }

    public static ExecutionHandle getExecution(String id) {
        return activeExecutions.get(id);
    }

    public static class ExecutionTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ExecutionTimeoutException(String message) {
            super(message);
        }
    }
}
