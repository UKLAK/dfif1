package com.javastudio.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.logging.Logger;

/**
 * Trình quản lý Migration cơ sở dữ liệu tự động cho Dynamic Plugins.
 *
 * <p>Tự động phát hiện và chạy các phiên bản migration tuần tự,
 * đảm bảo cấu trúc database luôn đồng bộ với phiên bản plugin.</p>
 *
 * <pre>{@code
 * MigrationEngine migrator = new MigrationEngine(connection, logger);
 *
 * migrator.addMigration(1, "CREATE TABLE players (id INTEGER PRIMARY KEY, name TEXT, coins REAL DEFAULT 0)");
 * migrator.addMigration(2, "ALTER TABLE players ADD COLUMN level INTEGER DEFAULT 1");
 * migrator.addMigration(3, "CREATE INDEX idx_player_name ON players(name)");
 * migrator.addMigration(4, "ALTER TABLE players ADD COLUMN last_login TEXT");
 *
 * migrator.migrate(); // Chỉ chạy những migration chưa chạy
 * }</pre>
 */
public class MigrationEngine {

    private final Connection connection;
    private final Logger logger;
    private final String migrationTable;
    private final TreeMap<Integer, MigrationStep> migrations = new TreeMap<>();

    /**
     * Record chứa thông tin một bước migration.
     */
    public record MigrationStep(int version, String description, String sql) {}

    public MigrationEngine(Connection connection, Logger logger) {
        this(connection, logger, "_migrations");
    }

    public MigrationEngine(Connection connection, Logger logger, String migrationTable) {
        this.connection = connection;
        this.logger = logger;
        this.migrationTable = migrationTable;
        ensureMigrationTable();
    }

    /**
     * Thêm một bước migration.
     *
     * @param version     Số phiên bản (tăng dần, duy nhất)
     * @param sql         Câu SQL cần thực thi
     */
    public MigrationEngine addMigration(int version, String sql) {
        return addMigration(version, "Migration v" + version, sql);
    }

    /**
     * Thêm một bước migration với mô tả.
     *
     * @param version     Số phiên bản (tăng dần, duy nhất)
     * @param description Mô tả ngắn gọn
     * @param sql         Câu SQL cần thực thi
     */
    public MigrationEngine addMigration(int version, String description, String sql) {
        migrations.put(version, new MigrationStep(version, description, sql));
        return this;
    }

    /**
     * Thêm nhiều migrations từ danh sách.
     */
    public MigrationEngine addMigrations(List<MigrationStep> steps) {
        for (MigrationStep step : steps) {
            migrations.put(step.version(), step);
        }
        return this;
    }

    /**
     * Chạy tất cả các migration chưa được thực thi theo thứ tự phiên bản tăng dần.
     *
     * @return Số migration đã chạy thành công
     */
    public int migrate() {
        int currentVersion = getCurrentVersion();
        int executed = 0;

        for (Map.Entry<Integer, MigrationStep> entry : migrations.entrySet()) {
            int version = entry.getKey();
            if (version <= currentVersion) continue;

            MigrationStep step = entry.getValue();
            logger.info("[Migration] Đang chạy migration v" + version + ": " + step.description());

            try {
                // Hỗ trợ nhiều câu SQL trong 1 migration (tách bằng dấu ;)
                String[] statements = step.sql().split(";");
                for (String sql : statements) {
                    String trimmed = sql.trim();
                    if (!trimmed.isEmpty()) {
                        try (Statement stmt = connection.createStatement()) {
                            stmt.execute(trimmed);
                        }
                    }
                }

                // Ghi nhận migration đã chạy thành công
                recordMigration(version, step.description());
                executed++;
                logger.info("[Migration] ✔ Migration v" + version + " hoàn tất!");
            } catch (SQLException e) {
                logger.severe("[Migration] ✖ Lỗi tại migration v" + version + ": " + e.getMessage());
                // Dừng lại nếu gặp lỗi (không skip migration lỗi)
                break;
            }
        }

        if (executed > 0) {
            logger.info("[Migration] Đã chạy thành công " + executed + " migration(s). Phiên bản hiện tại: v" + getCurrentVersion());
        }

        return executed;
    }

    /**
     * Lấy phiên bản migration hiện tại (version cao nhất đã chạy).
     */
    public int getCurrentVersion() {
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT MAX(version) FROM " + migrationTable)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    /**
     * Lấy danh sách tất cả migration đã chạy.
     */
    public List<Map<String, Object>> getExecutedMigrations() {
        List<Map<String, Object>> list = new ArrayList<>();
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT version, description, applied_at FROM " + migrationTable + " ORDER BY version ASC")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("version", rs.getInt("version"));
                row.put("description", rs.getString("description"));
                row.put("applied_at", rs.getString("applied_at"));
                list.add(row);
            }
        } catch (SQLException ignored) {}
        return list;
    }

    /**
     * Lấy danh sách migration đang chờ (chưa được chạy).
     */
    public List<MigrationStep> getPendingMigrations() {
        int current = getCurrentVersion();
        List<MigrationStep> pending = new ArrayList<>();
        for (Map.Entry<Integer, MigrationStep> entry : migrations.entrySet()) {
            if (entry.getKey() > current) {
                pending.add(entry.getValue());
            }
        }
        return pending;
    }

    // ========================================
    // INTERNAL
    // ========================================

    private void ensureMigrationTable() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + migrationTable + " (" +
                    "version INTEGER PRIMARY KEY, " +
                    "description TEXT, " +
                    "applied_at TEXT DEFAULT (datetime('now'))" +
                    ")");
        } catch (SQLException e) {
            logger.warning("[Migration] Không thể tạo bảng migration: " + e.getMessage());
        }
    }

    private void recordMigration(int version, String description) throws SQLException {
        try (var stmt = connection.prepareStatement(
                "INSERT INTO " + migrationTable + " (version, description) VALUES (?, ?)")) {
            stmt.setInt(1, version);
            stmt.setString(2, description);
            stmt.executeUpdate();
        }
    }
}
