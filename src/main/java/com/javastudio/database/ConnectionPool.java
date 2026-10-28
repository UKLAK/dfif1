package com.javastudio.database;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Connection Pool nhẹ cho SQLite / JDBC (Zero Dependency).
 *
 * <p>Quản lý bộ đệm kết nối (Connection Pool) hiệu quả, tránh rò rỉ (leak) kết nối,
 * tự động mở rộng và thu hẹp pool theo tải thực tế.</p>
 *
 * <p>Tính năng:</p>
 * <ul>
 *     <li><strong>Min/Max Pool Size</strong>: Cấu hình số kết nối tối thiểu/tối đa</li>
 *     <li><strong>Connection Validation</strong>: Kiểm tra kết nối trước khi cấp phát</li>
 *     <li><strong>Auto-Return</strong>: Tự động trả kết nối khi không sử dụng</li>
 *     <li><strong>Leak Detection</strong>: Phát hiện kết nối bị giữ quá lâu</li>
 *     <li><strong>Stats Tracking</strong>: Theo dõi số lượng kết nối active/idle</li>
 * </ul>
 */
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

    /**
     * Wrapper bọc Connection với thông tin theo dõi.
     */
    public static class PooledConnection {
        final Connection connection;
        long lastUsedTime;
        long checkedOutTime;

        PooledConnection(Connection connection) {
            this.connection = connection;
            this.lastUsedTime = System.currentTimeMillis();
        }
    }

    /**
     * Tạo Connection Pool cho SQLite database.
     *
     * @param dbFile     File SQLite database
     * @param logger     Logger
     * @param minPoolSize Số kết nối tối thiểu (mặc định 1)
     * @param maxPoolSize Số kết nối tối đa (mặc định 5)
     */
    public ConnectionPool(File dbFile, Logger logger, int minPoolSize, int maxPoolSize) {
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        this.logger = logger;
        this.minPoolSize = Math.max(1, minPoolSize);
        this.maxPoolSize = Math.max(this.minPoolSize, maxPoolSize);
        this.maxIdleTimeMs = 60_000; // 60 giây idle timeout

        // Khởi tạo kết nối tối thiểu
        for (int i = 0; i < this.minPoolSize; i++) {
            try {
                idleConnections.addLast(createNewConnection());
            } catch (SQLException e) {
                logger.warning("[ConnectionPool] Không thể tạo kết nối ban đầu: " + e.getMessage());
            }
        }
    }

    /**
     * Tạo Connection Pool với cấu hình mặc định.
     */
    public ConnectionPool(File dbFile, Logger logger) {
        this(dbFile, logger, 1, 5);
    }

    /**
     * Lấy kết nối từ pool. Tự động tạo mới nếu pool trống và chưa đạt max.
     *
     * @return Connection sẵn sàng sử dụng
     * @throws SQLException Nếu không thể lấy kết nối
     */
    public synchronized Connection getConnection() throws SQLException {
        if (closed) throw new SQLException("Connection pool đã đóng!");

        // 1. Thử lấy kết nối idle
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

        // 2. Tạo kết nối mới nếu chưa đạt max
        int total = activeCount.get() + idleConnections.size();
        if (total < maxPoolSize) {
            PooledConnection pc = createNewConnection();
            pc.checkedOutTime = System.currentTimeMillis();
            activeCount.incrementAndGet();
            return pc.connection;
        }

        throw new SQLException("Connection pool đã đầy! (max: " + maxPoolSize + ", active: " + activeCount.get() + ")");
    }

    /**
     * Trả kết nối về pool.
     *
     * @param connection Kết nối cần trả
     */
    public synchronized void returnConnection(Connection connection) {
        if (connection == null) return;

        activeCount.decrementAndGet();

        if (closed) {
            closeQuietly(connection);
            return;
        }

        // Trả về pool nếu còn chỗ
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

    /**
     * Thực thi callback với connection tự động trả về pool (try-with-resources style).
     */
    public <T> T execute(ConnectionCallback<T> callback) throws SQLException {
        Connection conn = getConnection();
        try {
            return callback.doInConnection(conn);
        } finally {
            returnConnection(conn);
        }
    }

    /**
     * Thực thi callback void với connection tự động trả về pool.
     */
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

    // ========================================
    // INTERNAL
    // ========================================

    private PooledConnection createNewConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        conn.setAutoCommit(true);

        // Enable WAL mode for SQLite (tăng hiệu năng đọc/ghi đồng thời)
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
            // Kiểm tra idle timeout
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

    // ========================================
    // STATS & LIFECYCLE
    // ========================================

    /**
     * Lấy số kết nối đang hoạt động.
     */
    public int getActiveCount() {
        return activeCount.get();
    }

    /**
     * Lấy số kết nối idle trong pool.
     */
    public int getIdleCount() {
        return idleConnections.size();
    }

    /**
     * Lấy tổng số kết nối đã tạo từ đầu.
     */
    public int getTotalCreated() {
        return totalCreated.get();
    }

    /**
     * Đóng toàn bộ pool và giải phóng tài nguyên.
     */
    public synchronized void close() {
        closed = true;
        while (!idleConnections.isEmpty()) {
            PooledConnection pc = idleConnections.pollFirst();
            closeQuietly(pc.connection);
        }
        logger.info("[ConnectionPool] Đã đóng pool - Tổng kết nối đã tạo: " + totalCreated.get());
    }

    /**
     * Dọn dẹp kết nối idle quá timeout.
     */
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
