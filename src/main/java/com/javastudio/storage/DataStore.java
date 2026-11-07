package com.javastudio.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class DataStore implements Closeable {

    private final File dataFolder;
    private final File dbFile;
    private final File fallbackFile;
    private final Logger logger;

    private Connection connection;
    private boolean useSqlite = false;
    private final Map<String, String> cacheMap = new ConcurrentHashMap<>();

    public DataStore(File dataFolder) {
        this.dataFolder = dataFolder;
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.dbFile = new File(dataFolder, "storage.db");
        this.fallbackFile = new File(dataFolder, "storage.json");
        this.logger = Logger.getLogger("JavaStudio[DataStore]");
        initStorage();
    }

    public File getDataFolder() {
        return dataFolder;
    }

    private void initStorage() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL;");
                st.execute("PRAGMA synchronous = NORMAL;");
                st.execute("PRAGMA busy_timeout = 5000;");
                st.execute("CREATE TABLE IF NOT EXISTS kv_store (k TEXT PRIMARY KEY, v TEXT, updated_at INTEGER);");
            }
            useSqlite = true;
        } catch (Throwable t) {
            useSqlite = false;
            loadFallback();
        }
    }

    public synchronized void set(String key, Object value) {
        if (key == null) return;
        String strVal = String.valueOf(value);
        cacheMap.put(key, strVal);

        if (useSqlite && connection != null) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO kv_store (k, v, updated_at) VALUES (?, ?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at;")) {
                ps.setString(1, key);
                ps.setString(2, strVal);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
                return;
            } catch (SQLException e) {
                logger.warning("Lỗi SQLite set(): " + e.getMessage() + " - Chuyển sang fallback.");
            }
        }
        saveFallback();
    }

    public synchronized String get(String key) {
        if (key == null) return null;
        if (cacheMap.containsKey(key)) {
            return cacheMap.get(key);
        }

        if (useSqlite && connection != null) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT v FROM kv_store WHERE k = ?;")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String v = rs.getString("v");
                        cacheMap.put(key, v);
                        return v;
                    }
                }
            } catch (SQLException ignored) {}
        }

        return null;
    }

    public String getString(String key, String def) {
        String val = get(key);
        return val != null ? val : def;
    }

    public int getInt(String key, int def) {
        String val = get(key);
        if (val == null) return def;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public double getDouble(String key, double def) {
        String val = get(key);
        if (val == null) return def;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBoolean(String key, boolean def) {
        String val = get(key);
        if (val == null) return def;
        return Boolean.parseBoolean(val);
    }

    public synchronized boolean has(String key) {
        return get(key) != null;
    }

    public synchronized boolean remove(String key) {
        if (key == null) return false;
        cacheMap.remove(key);

        if (useSqlite && connection != null) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM kv_store WHERE k = ?;")) {
                ps.setString(1, key);
                return ps.executeUpdate() > 0;
            } catch (SQLException ignored) {}
        }

        saveFallback();
        return true;
    }

    public synchronized List<String> getKeys() {
        if (useSqlite && connection != null) {
            List<String> list = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT k FROM kv_store;")) {
                while (rs.next()) {
                    list.add(rs.getString("k"));
                }
                return list;
            } catch (SQLException ignored) {}
        }
        return new ArrayList<>(cacheMap.keySet());
    }

    public Connection getConnection() {
        return connection;
    }

    public boolean isUsingSqlite() {
        return useSqlite;
    }

    private void loadFallback() {
        if (!fallbackFile.exists()) return;
        try {
            String json = Files.readString(fallbackFile.toPath(), StandardCharsets.UTF_8).trim();
            if (json.startsWith("{") && json.endsWith("}")) {
                json = json.substring(1, json.length() - 1);
                String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                for (String p : pairs) {
                    String[] kv = p.split(":", 2);
                    if (kv.length == 2) {
                        String k = kv[0].trim().replace("\"", "");
                        String v = kv[1].trim();
                        if (v.startsWith("\"") && v.endsWith("\"")) {
                            v = v.substring(1, v.length() - 1);
                        }
                        cacheMap.put(k, v);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveFallback() {
        StringBuilder sb = new StringBuilder("{\n");
        int count = 0;
        for (Map.Entry<String, String> entry : cacheMap.entrySet()) {
            sb.append("  \"").append(entry.getKey()).append("\": \"")
              .append(entry.getValue().replace("\"", "\\\"")).append("\"");
            if (++count < cacheMap.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("}\n");

        try {
            Files.writeString(fallbackFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {}
            connection = null;
        }
        cacheMap.clear();
    }
}
