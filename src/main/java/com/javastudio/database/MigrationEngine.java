package com.javastudio.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.logging.Logger;

public class MigrationEngine {

    private final Connection connection;
    private final Logger logger;
    private final String migrationTable;
    private final TreeMap<Integer, MigrationStep> migrations = new TreeMap<>();

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

    public MigrationEngine addMigration(int version, String sql) {
        return addMigration(version, "Migration v" + version, sql);
    }

    public MigrationEngine addMigration(int version, String description, String sql) {
        migrations.put(version, new MigrationStep(version, description, sql));
        return this;
    }

    public MigrationEngine addMigrations(List<MigrationStep> steps) {
        for (MigrationStep step : steps) {
            migrations.put(step.version(), step);
        }
        return this;
    }

    public int migrate() {
        int currentVersion = getCurrentVersion();
        int executed = 0;

        for (Map.Entry<Integer, MigrationStep> entry : migrations.entrySet()) {
            int version = entry.getKey();
            if (version <= currentVersion) continue;

            MigrationStep step = entry.getValue();
            logger.info("[Migration] Đang chạy migration v" + version + ": " + step.description());

            try {
                String[] statements = step.sql().split(";");
                for (String sql : statements) {
                    String trimmed = sql.trim();
                    if (!trimmed.isEmpty()) {
                        try (Statement stmt = connection.createStatement()) {
                            stmt.execute(trimmed);
                        }
                    }
                }

                recordMigration(version, step.description());
                executed++;
                logger.info("[Migration] ✔ Migration v" + version + " hoàn tất!");
            } catch (SQLException e) {
                logger.severe("[Migration] ✖ Lỗi tại migration v" + version + ": " + e.getMessage());
                break;
            }
        }

        if (executed > 0) {
            logger.info("[Migration] Đã chạy thành công " + executed + " migration(s). Phiên bản hiện tại: v" + getCurrentVersion());
        }

        return executed;
    }

    public int getCurrentVersion() {
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT MAX(version) FROM " + migrationTable)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

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
