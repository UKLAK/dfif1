package com.javastudio.security;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LoopWatchdog: Hệ thống bảo vệ chống treo (Freeze) luồng chính (Main Thread) của Minecraft Server.
 * Tự động chèn các lệnh kiểm tra thời gian vào mọi vòng lặp (while, for, do-while)
 * trước khi biên dịch mã nguồn Java, đảm bảo không một vòng lặp vô tận nào có thể làm đơ server.
 */
public final class LoopWatchdog {

    public static final long DEFAULT_MAX_LOOP_MS = 3000L; // 3 giây tối đa cho một vòng lặp
    private static final AtomicLong LOOP_COUNTER = new AtomicLong(0);

    private static boolean enabled = true;
    private static long maxLoopDurationMs = DEFAULT_MAX_LOOP_MS;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static long getMaxLoopDurationMs() {
        return maxLoopDurationMs;
    }

    public static void setMaxLoopDurationMs(long ms) {
        maxLoopDurationMs = ms;
    }

    /**
     * Phương thức được chèn vào thân vòng lặp trong bytecode / source code.
     * Kiểm tra thời gian đã trôi qua kể từ khi vòng lặp bắt đầu.
     *
     * @param startNano  Thời điểm bắt đầu tính theo System.nanoTime()
     * @param maxMs      Thời gian chạy tối đa cho phép (ms)
     * @param loopLabel  Tên hoặc vị trí định danh của vòng lặp
     * @throws LoopTimeoutException nếu thời gian vượt quá ngưỡng an toàn
     */
    public static void check(long startNano, long maxMs, String loopLabel) {
        if (!enabled) return;
        boolean isMain = false;
        try {
            isMain = org.bukkit.Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {}

        long allowedMs = isMain ? Math.min(maxMs, com.javastudio.pipeline.ExecutionWatchdog.getMainThreadLimitMs()) : maxMs;
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
        if (elapsedMs > allowedMs) {
            throw new LoopTimeoutException("Phát hiện vòng lặp [" + loopLabel + "] chiếm dụng " + (isMain ? "Main Thread" : "Thread") + " quá lâu: " + elapsedMs + "ms > " + allowedMs + "ms! Đã tự động ngắt để bảo vệ Server.");
        }
    }

    /**
     * Tự động biến đổi (instrument) mã nguồn Java trước khi đưa vào compiler.
     * Chèn biến đếm thời gian và lệnh gọi LoopWatchdog.check() vào các vòng lặp.
     *
     * @param sourceCode Mã nguồn Java gốc
     * @return Mã nguồn sau khi đã được chèn bảo vệ an toàn
     */
    public static String instrument(String sourceCode) {
        if (!enabled || sourceCode == null || sourceCode.isBlank()) {
            return sourceCode;
        }

        // Không can thiệp nếu code đã có LoopWatchdog
        if (sourceCode.contains("LoopWatchdog.check(")) {
            return sourceCode;
        }

        String result = sourceCode;

        try {
            // 1. Biến đổi vòng lặp while: while(...) {
            result = instrumentWhileLoops(result);

            // 2. Biến đổi vòng lặp for: for(...) {
            result = instrumentForLoops(result);

            // 3. Biến đổi vòng lặp do: do {
            result = instrumentDoWhileLoops(result);
        } catch (Throwable t) {
            // Nếu có lỗi parse regex, trả về code gốc để không chặn quá trình biên dịch
            return sourceCode;
        }

        return result;
    }

    private static String instrumentWhileLoops(String code) {
        // Tìm pattern: while\s*\((.*?)\)\s*\{
        Pattern pattern = Pattern.compile("\\bwhile\\s*\\(([^)]+)\\)\\s*\\{");
        Matcher matcher = pattern.matcher(code);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            long id = LOOP_COUNTER.incrementAndGet();
            String cond = matcher.group(1);
            String replacement = "long _lw_s" + id + " = System.nanoTime();\n" +
                    "while (" + cond + ") {\n" +
                    "    com.javastudio.security.LoopWatchdog.check(_lw_s" + id + ", " + maxLoopDurationMs + "L, \"while-" + id + "\");";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String instrumentForLoops(String code) {
        // Tìm pattern: for\s*\((.*?)\)\s*\{
        Pattern pattern = Pattern.compile("\\bfor\\s*\\(([^)]+)\\)\\s*\\{");
        Matcher matcher = pattern.matcher(code);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            long id = LOOP_COUNTER.incrementAndGet();
            String inner = matcher.group(1);
            String replacement = "long _lf_s" + id + " = System.nanoTime();\n" +
                    "for (" + inner + ") {\n" +
                    "    com.javastudio.security.LoopWatchdog.check(_lf_s" + id + ", " + maxLoopDurationMs + "L, \"for-" + id + "\");";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String instrumentDoWhileLoops(String code) {
        // Tìm pattern: do\s*\{
        Pattern pattern = Pattern.compile("\\bdo\\s*\\{");
        Matcher matcher = pattern.matcher(code);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            long id = LOOP_COUNTER.incrementAndGet();
            String replacement = "long _ld_s" + id + " = System.nanoTime();\n" +
                    "do {\n" +
                    "    com.javastudio.security.LoopWatchdog.check(_ld_s" + id + ", " + maxLoopDurationMs + "L, \"do-" + id + "\");";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Ngoại lệ ném ra khi vòng lặp chạy quá thời gian cho phép.
     */
    public static class LoopTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LoopTimeoutException(String message) {
            super(message);
        }
    }
}
