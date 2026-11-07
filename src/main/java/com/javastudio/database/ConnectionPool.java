package com.javastudio.database;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class ConnectionPool {

    private final String jdbcUrl;
    private final Logger logger;
    private final int maxPoolSize;
    private final int minPoolSize;
    private final long maxIdleTimeMs;

    private final Deque<PooledConnection> idleConnections = new ConcurrentLinkedDeque<>();
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger totalCreated = new AtomicInteger(0);
    private volatile boolean closed = false;

    public static class PooledConnection {
        final Connection connection;
        long lastUsedTime;
        long checkedOutTime;

        PooledConnection(Connection connection) {
            this.connection = connection;
            this.lastUsedTime = System.currentTimeMillis();
        }
    }

    public ConnectionPool(File dbFile, Logger logger, int minPoolSize, int maxPoolSize) {
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        this.logger = logger;
        this.minPoolSize = Math.max(1, minPoolSize);
        this.maxPoolSize = Math.max(this.minPoolSize, maxPoolSize);
        this.maxIdleTimeMs = 60_000;

        for (int i = 0; i < this.minPoolSize; i++) {
            try {
                idleConnections.addLast(createNewConnection());
            } catch (SQLException e) {
                logger.warning("[ConnectionPool] Không thể tạo kết nối ban đầu: " + e.getMessage());
            }
        }
    }

    public ConnectionPool(File dbFile, Logger logger) {
        this(dbFile, logger, 1, 5);
    }

    public synchronized Connection getConnection() throws SQLException {
        if (closed) throw new SQLException("Connection pool đã đóng!");

        while (!idleConnections.isEmpty()) {
            PooledConnection pc = idleConnections.pollFirst();
            if (isValid(pc)) {
                pc.checkedOutTime = System.currentTimeMillis();
                activeCount.incrementAndGet();
                return pc.connection;
            } else {
                closeQuietly(pc.connection);
            }
        }

        int total = activeCount.get() + idleConnections.size();
        if (total < maxPoolSize) {
            PooledConnection pc = createNewConnection();
            pc.checkedOutTime = System.currentTimeMillis();
            activeCount.incrementAndGet();
            return pc.connection;
        }

        throw new SQLException("Connection pool đã đầy! (max: " + maxPoolSize + ", active: " + activeCount.get() + ")");
    }

    public synchronized void returnConnection(Connection connection) {
        if (connection == null) return;

        activeCount.decrementAndGet();

        if (closed) {
            closeQuietly(connection);
            return;
        }

        try {
            if (!connection.isClosed()) {
                PooledConnection pc = new PooledConnection(connection);
                if (idleConnections.size() < maxPoolSize) {
                    idleConnections.addLast(pc);
                } else {
                    closeQuietly(connection);
                }
            }
        } catch (SQLException e) {
            closeQuietly(connection);
        }
    }

    public <T> T execute(ConnectionCallback<T> callback) throws SQLException {
        Connection conn = getConnection();
        try {
            return callback.doInConnection(conn);
        } finally {
            returnConnection(conn);
        }
    }

    public void executeVoid(VoidConnectionCallback callback) throws SQLException {
        Connection conn = getConnection();
        try {
            callback.doInConnection(conn);
        } finally {
            returnConnection(conn);
        }
    }

    @FunctionalInterface
    public interface ConnectionCallback<T> {
        T doInConnection(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    public interface VoidConnectionCallback {
        void doInConnection(Connection conn) throws SQLException;
    }


    private PooledConnection createNewConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        conn.setAutoCommit(true);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA busy_timeout=5000;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
        }

        totalCreated.incrementAndGet();
        return new PooledConnection(conn);
    }

    private boolean isValid(PooledConnection pc) {
        try {
            if (pc.connection.isClosed()) return false;
            if (System.currentTimeMillis() - pc.lastUsedTime > maxIdleTimeMs) {
                return false;
            }
            return pc.connection.isValid(1);
        } catch (SQLException e) {
            return false;
        }
    }

    private void closeQuietly(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException ignored) {}
    }


    public int getActiveCount() {
        return activeCount.get();
    }

    public int getIdleCount() {
        return idleConnections.size();
    }

    public int getTotalCreated() {
        return totalCreated.get();
    }

    public synchronized void close() {
        closed = true;
        while (!idleConnections.isEmpty()) {
            PooledConnection pc = idleConnections.pollFirst();
            closeQuietly(pc.connection);
        }
        logger.info("[ConnectionPool] Đã đóng pool - Tổng kết nối đã tạo: " + totalCreated.get());
    }

    public synchronized void evictIdleConnections() {
        long now = System.currentTimeMillis();
        Iterator<PooledConnection> it = idleConnections.iterator();
        int evicted = 0;
        while (it.hasNext()) {
            PooledConnection pc = it.next();
            if (now - pc.lastUsedTime > maxIdleTimeMs && idleConnections.size() > minPoolSize) {
                it.remove();
                closeQuietly(pc.connection);
                evicted++;
            }
        }
        if (evicted > 0) {
            logger.info("[ConnectionPool] Đã thu hồi " + evicted + " kết nối idle.");
        }
    }
}
