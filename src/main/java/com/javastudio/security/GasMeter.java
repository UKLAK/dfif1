package com.javastudio.security;

import com.javastudio.pipeline.ExecutionWatchdog;
import org.bukkit.Bukkit;

public final class GasMeter {

    public static final long DEFAULT_MAX_OPS = 12_000_000L;
    public static final long DEFAULT_AUTO_BURST_OPS = 25_000_000L;

    private static volatile long serverTick = 0L;

    public static final class GasContext {
        private final long maxOps;
        private final long deadlineNano;
        private long currentOps;

        public GasContext(long maxOps, long maxDurationMs) {
            this.maxOps = maxOps > 0 ? maxOps : DEFAULT_MAX_OPS;
            long durMs = maxDurationMs > 0 ? maxDurationMs : 3000L;
            this.deadlineNano = System.nanoTime() + durMs * 1_000_000L;
            this.currentOps = 0L;
        }

        public boolean consume(int amount) {
            currentOps += amount;
            if (currentOps > maxOps) {
                return true;
            }
            if ((currentOps & 0x7FF) == 0) {
                if (Thread.currentThread().isInterrupted()) {
                    return true;
                }
                if (System.nanoTime() > deadlineNano) {
                    return true;
                }
            }
            return false;
        }

        public long getMaxOps() {
            return maxOps;
        }

        public long getCurrentOps() {
            return currentOps;
        }
    }

    private static final class AutoBurstState {
        long observedTick = -1L;
        long burstStartNano = 0L;
        long lastSampleNano = 0L;
        long burstOps = 0L;
    }

    private static final ThreadLocal<GasContext> CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<AutoBurstState> AUTO_BURST = ThreadLocal.withInitial(AutoBurstState::new);

    private GasMeter() {}

    public static void advanceServerTick() {
        serverTick++;
    }

    public static long getServerTick() {
        return serverTick;
    }

    public static void start(long maxOps, long maxDurationMs) {
        CONTEXT.set(new GasContext(maxOps, maxDurationMs));
    }

    public static void stop() {
        CONTEXT.remove();
    }

    public static GasContext currentContext() {
        return CONTEXT.get();
    }

    public static void tick() {
        consume(1);
    }

    public static boolean checkCondition() {
        consume(1);
        return true;
    }

    public static void consume(int amount) {
        GasContext ctx = CONTEXT.get();
        if (ctx != null) {
            if (ctx.consume(amount)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new GasQuotaExceededException("Tác vụ đã bị ngắt chủ động bởi quản trị viên (Interrupted sau " + ctx.getCurrentOps() + " ops).");
                }
                throw new GasQuotaExceededException("Vượt hạn mức Gas thực thi (" + ctx.getCurrentOps() + " ops / quá thời gian an toàn). Đã tự động ngắt vòng lặp/đệ quy!");
            }
            return;
        }

        if (!LoopWatchdog.isEnabled()) {
            return;
        }

        AutoBurstState state = AUTO_BURST.get();
        state.burstOps += amount;
        if ((state.burstOps & 0xFFF) != 0) {
            return;
        }

        if (Thread.currentThread().isInterrupted()) {
            state.burstOps = 0L;
            throw new GasQuotaExceededException("Luồng thực thi đã bị ngắt chủ động (Interrupted).");
        }

        long now = System.nanoTime();
        boolean isPrimary = false;
        try {
            isPrimary = Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {}

        if (isPrimary) {
            long curTick = serverTick;
            if (curTick != state.observedTick || (state.lastSampleNano > 0 && now - state.lastSampleNano > 45_000_000L && curTick == 0L)) {
                state.observedTick = curTick;
                state.burstStartNano = now;
                state.lastSampleNano = now;
                state.burstOps = amount;
                return;
            }
            state.lastSampleNano = now;
            long limitMs = Math.max(50L, ExecutionWatchdog.getMainThreadLimitMs());
            long elapsedMs = (now - state.burstStartNano) / 1_000_000L;
            if (elapsedMs > limitMs || state.burstOps > DEFAULT_AUTO_BURST_OPS) {
                long ops = state.burstOps;
                state.burstStartNano = now;
                state.burstOps = 0L;
                throw new GasQuotaExceededException("Bảo vệ Main Thread: Phát hiện vòng lặp/đệ quy chiếm dụng 1 tick quá ngưỡng (" + elapsedMs + "ms > " + limitMs + "ms, " + ops + " ops)!");
            }
        } else {
            if (state.lastSampleNano == 0L || now - state.lastSampleNano > 250_000_000L) {
                state.burstStartNano = now;
                state.lastSampleNano = now;
                state.burstOps = amount;
                return;
            }
            state.lastSampleNano = now;
            long maxMs = Math.max(500L, LoopWatchdog.getMaxLoopDurationMs());
            long elapsedMs = (now - state.burstStartNano) / 1_000_000L;
            if (elapsedMs > maxMs || state.burstOps > DEFAULT_AUTO_BURST_OPS * 4) {
                long ops = state.burstOps;
                state.burstStartNano = now;
                state.burstOps = 0L;
                throw new GasQuotaExceededException("Bảo vệ Worker Thread: Vòng lặp/đệ quy chạy liên tục quá giới hạn (" + elapsedMs + "ms > " + maxMs + "ms, " + ops + " ops)!");
            }
        }
    }

    public static String instrumentSource(String source) {
        return LoopWatchdog.instrument(source);
    }
}
