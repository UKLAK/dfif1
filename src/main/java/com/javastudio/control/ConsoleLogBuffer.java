package com.javastudio.control;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Bộ đệm lưu trữ nhật ký console máy chủ (Console Log Buffer).
 * Ghi lại các dòng log từ Server Console vào bộ đệm vòng tròn (Ring Buffer)
 * để truyền phát thời gian thực tới Web Terminal trên Web IDE.
 */
public class ConsoleLogBuffer {

    public record LogEntry(long id, long timestamp, String level, String message) {}

    private static final int MAX_ENTRIES = 600;
    private final ConcurrentLinkedDeque<LogEntry> buffer = new ConcurrentLinkedDeque<>();
    private long idCounter = 0;
    private Handler attachedHandler;

    public ConsoleLogBuffer() {
        attachLogger();
    }

    private synchronized void attachLogger() {
        try {
            Logger rootLogger = Logger.getLogger("");
            attachedHandler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record == null) return;
                    String msg = record.getMessage();
                    if (msg == null || msg.isBlank()) return;
                    addLog(record.getLevel().getName(), msg);
                }

                @Override
                public void flush() {}

                @Override
                public void close() throws SecurityException {}
            };
            rootLogger.addHandler(attachedHandler);
        } catch (Throwable ignored) {}
    }

    public synchronized void addLog(String level, String message) {
        long id = ++idCounter;
        String sanitized = com.javastudio.security.cve.SensitiveLogRedactor.redact(message);
        buffer.addLast(new LogEntry(id, System.currentTimeMillis(), level, sanitized));
        while (buffer.size() > MAX_ENTRIES) {
            buffer.pollFirst();
        }
    }

    public List<LogEntry> getLogsSince(long lastId) {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : buffer) {
            if (entry.id() > lastId) {
                result.add(entry);
            }
        }
        return result;
    }

    public List<LogEntry> getRecentLogs(int limit) {
        List<LogEntry> all = new ArrayList<>(buffer);
        if (all.size() <= limit) return all;
        return all.subList(all.size() - limit, all.size());
    }

    public void detach() {
        if (attachedHandler != null) {
            try {
                Logger.getLogger("").removeHandler(attachedHandler);
            } catch (Throwable ignored) {}
        }
    }
}
