package com.javastudio.database;

import java.sql.*;
import java.util.*;

/**
 * Trình xây dựng câu truy vấn SQL (Fluent Query Builder) cho Dynamic Plugins.
 *
 * <p>Cho phép viết câu SQL ngắn gọn, an toàn (Prepared Statement) và dễ đọc:</p>
 *
 * <pre>{@code
 * // SELECT
 * List<Map<String, Object>> results = QueryBuilder.select("players")
 *     .columns("name", "level", "coins")
 *     .where("level", ">", 10)
 *     .and("coins", ">=", 500)
 *     .orderBy("level", false)
 *     .limit(20)
 *     .execute(connection);
 *
 * // INSERT
 * QueryBuilder.insert("players")
 *     .set("name", "Steve")
 *     .set("level", 1)
 *     .set("coins", 100)
 *     .execute(connection);
 *
 * // UPDATE
 * QueryBuilder.update("players")
 *     .set("coins", 500)
 *     .set("level", 10)
 *     .where("name", "=", "Steve")
 *     .execute(connection);
 *
 * // DELETE
 * QueryBuilder.delete("players")
 *     .where("level", "<", 5)
 *     .execute(connection);
 *
 * // CREATE TABLE (Migration)
 * QueryBuilder.createTable("players")
 *     .column("id", "INTEGER PRIMARY KEY AUTOINCREMENT")
 *     .column("name", "TEXT NOT NULL UNIQUE")
 *     .column("level", "INTEGER DEFAULT 1")
 *     .column("coins", "REAL DEFAULT 0")
 *     .execute(connection);
 * }</pre>
 */
public class QueryBuilder {

    public enum Type { SELECT, INSERT, UPDATE, DELETE, CREATE_TABLE, RAW }

    private final Type type;
    private final String table;
    private final List<String> selectColumns = new ArrayList<>();
    private final List<WhereClause> whereClauses = new ArrayList<>();
    private final Map<String, Object> setValues = new LinkedHashMap<>();
    private final List<String> tableColumns = new ArrayList<>();
    private String orderByColumn;
    private boolean orderAsc = true;
    private int limitCount = -1;
    private int offsetCount = -1;
    private String rawSql;
    private final List<Object> rawParams = new ArrayList<>();

    private record WhereClause(String connector, String column, String operator, Object value) {}

    private QueryBuilder(Type type, String table) {
        this.type = type;
        this.table = table;
    }

    // ========================================
    // STATIC FACTORY METHODS
    // ========================================

    public static QueryBuilder select(String table) { return new QueryBuilder(Type.SELECT, table); }
    public static QueryBuilder insert(String table) { return new QueryBuilder(Type.INSERT, table); }
    public static QueryBuilder update(String table) { return new QueryBuilder(Type.UPDATE, table); }
    public static QueryBuilder delete(String table) { return new QueryBuilder(Type.DELETE, table); }
    public static QueryBuilder createTable(String table) { return new QueryBuilder(Type.CREATE_TABLE, table); }
    public static QueryBuilder raw(String sql, Object... params) {
        QueryBuilder qb = new QueryBuilder(Type.RAW, "");
        qb.rawSql = sql;
        Collections.addAll(qb.rawParams, params);
        return qb;
    }

    // ========================================
    // SELECT BUILDERS
    // ========================================

    public QueryBuilder columns(String... cols) {
        selectColumns.addAll(Arrays.asList(cols));
        return this;
    }

    // ========================================
    // WHERE BUILDERS
    // ========================================

    public QueryBuilder where(String column, String operator, Object value) {
        whereClauses.add(new WhereClause("AND", column, operator, value));
        return this;
    }

    public QueryBuilder and(String column, String operator, Object value) {
        whereClauses.add(new WhereClause("AND", column, operator, value));
        return this;
    }

    public QueryBuilder or(String column, String operator, Object value) {
        whereClauses.add(new WhereClause("OR", column, operator, value));
        return this;
    }

    public QueryBuilder whereEquals(String column, Object value) {
        return where(column, "=", value);
    }

    public QueryBuilder whereLike(String column, String pattern) {
        return where(column, "LIKE", pattern);
    }

    public QueryBuilder whereIn(String column, List<?> values) {
        String placeholders = String.join(",", values.stream().map(v -> "?").toList());
        whereClauses.add(new WhereClause("AND", column, "IN (" + placeholders + ")", values));
        return this;
    }

    public QueryBuilder whereNotNull(String column) {
        whereClauses.add(new WhereClause("AND", column, "IS NOT NULL", null));
        return this;
    }

    // ========================================
    // SET BUILDERS (INSERT / UPDATE)
    // ========================================

    public QueryBuilder set(String column, Object value) {
        setValues.put(column, value);
        return this;
    }

    // ========================================
    // ORDER, LIMIT, OFFSET
    // ========================================

    public QueryBuilder orderBy(String column, boolean ascending) {
        this.orderByColumn = column;
        this.orderAsc = ascending;
        return this;
    }

    public QueryBuilder orderBy(String column) { return orderBy(column, true); }

    public QueryBuilder limit(int count) {
        this.limitCount = count;
        return this;
    }

    public QueryBuilder offset(int count) {
        this.offsetCount = count;
        return this;
    }

    // ========================================
    // CREATE TABLE BUILDERS
    // ========================================

    public QueryBuilder column(String name, String definition) {
        tableColumns.add(name + " " + definition);
        return this;
    }

    // ========================================
    // BUILD SQL
    // ========================================

    /**
     * Xây dựng câu SQL và danh sách tham số.
     */
    public PreparedQuery build() {
        return switch (type) {
            case SELECT -> buildSelect();
            case INSERT -> buildInsert();
            case UPDATE -> buildUpdate();
            case DELETE -> buildDelete();
            case CREATE_TABLE -> buildCreateTable();
            case RAW -> new PreparedQuery(rawSql, rawParams);
        };
    }

    private PreparedQuery buildSelect() {
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(selectColumns.isEmpty() ? "*" : String.join(", ", selectColumns));
        sql.append(" FROM ").append(table);
        List<Object> params = appendWhere(sql);
        if (orderByColumn != null) sql.append(" ORDER BY ").append(orderByColumn).append(orderAsc ? " ASC" : " DESC");
        if (limitCount > 0) sql.append(" LIMIT ").append(limitCount);
        if (offsetCount > 0) sql.append(" OFFSET ").append(offsetCount);
        return new PreparedQuery(sql.toString(), params);
    }

    private PreparedQuery buildInsert() {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
        StringBuilder values = new StringBuilder(") VALUES (");
        boolean first = true;
        for (Map.Entry<String, Object> entry : setValues.entrySet()) {
            if (!first) { sql.append(", "); values.append(", "); }
            sql.append(entry.getKey());
            values.append("?");
            params.add(entry.getValue());
            first = false;
        }
        sql.append(values).append(")");
        return new PreparedQuery(sql.toString(), params);
    }

    private PreparedQuery buildUpdate() {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        boolean first = true;
        for (Map.Entry<String, Object> entry : setValues.entrySet()) {
            if (!first) sql.append(", ");
            sql.append(entry.getKey()).append(" = ?");
            params.add(entry.getValue());
            first = false;
        }
        params.addAll(appendWhere(sql));
        return new PreparedQuery(sql.toString(), params);
    }

    private PreparedQuery buildDelete() {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(table);
        List<Object> params = appendWhere(sql);
        return new PreparedQuery(sql.toString(), params);
    }

    private PreparedQuery buildCreateTable() {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (");
        sql.append(String.join(", ", tableColumns));
        sql.append(")");
        return new PreparedQuery(sql.toString(), Collections.emptyList());
    }

    private List<Object> appendWhere(StringBuilder sql) {
        List<Object> params = new ArrayList<>();
        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ");
            for (int i = 0; i < whereClauses.size(); i++) {
                WhereClause wc = whereClauses.get(i);
                if (i > 0) sql.append(" ").append(wc.connector()).append(" ");
                if (wc.value() == null) {
                    sql.append(wc.column()).append(" ").append(wc.operator());
                } else if (wc.value() instanceof List<?> list) {
                    sql.append(wc.column()).append(" ").append(wc.operator());
                    params.addAll(list);
                } else {
                    sql.append(wc.column()).append(" ").append(wc.operator()).append(" ?");
                    params.add(wc.value());
                }
            }
        }
        return params;
    }

    // ========================================
    // EXECUTE
    // ========================================

    /**
     * Thực thi truy vấn SELECT và trả về danh sách kết quả.
     */
    public List<Map<String, Object>> execute(Connection conn) throws SQLException {
        PreparedQuery pq = build();
        try (PreparedStatement stmt = conn.prepareStatement(pq.sql())) {
            bindParams(stmt, pq.params());
            if (type == Type.SELECT) {
                try (ResultSet rs = stmt.executeQuery()) {
                    return resultSetToList(rs);
                }
            } else {
                stmt.executeUpdate();
                return Collections.emptyList();
            }
        }
    }

    /**
     * Thực thi truy vấn UPDATE/INSERT/DELETE và trả về số dòng ảnh hưởng.
     */
    public int executeUpdate(Connection conn) throws SQLException {
        PreparedQuery pq = build();
        try (PreparedStatement stmt = conn.prepareStatement(pq.sql())) {
            bindParams(stmt, pq.params());
            return stmt.executeUpdate();
        }
    }

    /**
     * Thực thi SELECT và trả về 1 dòng đầu tiên (hoặc null).
     */
    public Map<String, Object> executeOne(Connection conn) throws SQLException {
        this.limitCount = 1;
        List<Map<String, Object>> results = execute(conn);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Thực thi SELECT COUNT(*) và trả về số dòng.
     */
    public long count(Connection conn) throws SQLException {
        selectColumns.clear();
        selectColumns.add("COUNT(*)");
        PreparedQuery pq = build();
        try (PreparedStatement stmt = conn.prepareStatement(pq.sql())) {
            bindParams(stmt, pq.params());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private void bindParams(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object val = params.get(i);
            if (val == null) stmt.setNull(i + 1, Types.NULL);
            else if (val instanceof String s) stmt.setString(i + 1, s);
            else if (val instanceof Integer n) stmt.setInt(i + 1, n);
            else if (val instanceof Long l) stmt.setLong(i + 1, l);
            else if (val instanceof Double d) stmt.setDouble(i + 1, d);
            else if (val instanceof Float f) stmt.setFloat(i + 1, f);
            else if (val instanceof Boolean b) stmt.setBoolean(i + 1, b);
            else if (val instanceof byte[] bytes) stmt.setBytes(i + 1, bytes);
            else stmt.setString(i + 1, val.toString());
        }
    }

    private List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            results.add(row);
        }
        return results;
    }

    /**
     * Record chứa câu SQL đã build và danh sách tham số.
     */
    public record PreparedQuery(String sql, List<Object> params) {}
}
