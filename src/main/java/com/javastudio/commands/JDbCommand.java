package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.DynamicPlugin;
import com.javastudio.storage.DataStore;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class JDbCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;

    public JDbCommand(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) {
            MessageUtil.send(sender, "&cBạn không có quyền thực hiện lệnh này!");
            return true;
        }

        if (!plugin.getSettingsManager().isDatabaseQueryEnabled()) {
            MessageUtil.send(sender, "&cPhân hệ Truy Vấn Database (/jdb) đang bị TẮT trong cài đặt!");
            MessageUtil.send(sender, "&7Bật lại bằng lệnh: &e/javastudio toggle database-query &7hoặc qua &e/javastudio settings");
            return true;
        }

        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String pluginName = args[1];

        DynamicPlugin targetPlugin = plugin.getPluginManager().getActivePlugin(pluginName);
        if (targetPlugin == null) {
            MessageUtil.send(sender, "&cKhông tìm thấy DynamicPlugin đang chạy có tên: " + pluginName);
            return true;
        }

        DataStore ds = targetPlugin.getDataStore();
        if (ds == null) {
            MessageUtil.send(sender, "&cPlugin " + pluginName + " chưa khởi tạo DataStore.");
            return true;
        }

        switch (sub) {
            case "query", "sql" -> {
                if (args.length < 3) {
                    MessageUtil.send(sender, "&cCú pháp: /jdb query <tên_plugin> <câu_lệnh_sql>");
                    return true;
                }
                String[] sqlParts = Arrays.copyOfRange(args, 2, args.length);
                String sql = String.join(" ", sqlParts).trim();
                executeQuery(sender, ds, sql);
            }
            case "inspect" -> {
                inspectDatabase(sender, ds, pluginName);
            }
            case "keys" -> {
                MessageUtil.send(sender, "&6=== Danh Sách Khóa DataStore (" + pluginName + ") ===");
                var keys = ds.getKeys();
                if (keys.isEmpty()) {
                    MessageUtil.send(sender, "&7Không có dữ liệu key-value.");
                } else {
                    for (String k : keys) {
                        MessageUtil.send(sender, "&e - &f" + k + " &7= &a" + ds.get(k));
                    }
                }
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void executeQuery(CommandSender sender, DataStore ds, String sql) {
        Connection conn = ds.getConnection();
        if (conn == null) {
            MessageUtil.send(sender, "&cDataStore đang hoạt động ở chế độ Key-Value (Không có kết nối SQL trực tiếp).");
            return;
        }

        try (Statement stmt = conn.createStatement()) {
            boolean isResultSet = stmt.execute(sql);
            if (isResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();

                    StringBuilder header = new StringBuilder("&e[SQL] &f|");
                    for (int i = 1; i <= cols; i++) {
                        header.append(" &b").append(md.getColumnLabel(i)).append(" &f|");
                    }
                    MessageUtil.send(sender, header.toString());

                    int rowCount = 0;
                    while (rs.next() && rowCount < 20) {
                        rowCount++;
                        StringBuilder row = new StringBuilder("&7" + rowCount + ". |");
                        for (int i = 1; i <= cols; i++) {
                            row.append(" ").append(rs.getString(i)).append(" |");
                        }
                        MessageUtil.send(sender, row.toString());
                    }
                    MessageUtil.send(sender, "&aTruy vấn thành công (" + rowCount + " dòng hiển thị).");
                }
            } else {
                int updated = stmt.getUpdateCount();
                MessageUtil.send(sender, "&aCâu lệnh SQL cập nhật thành công: &e" + updated + " &adòng bị ảnh hưởng.");
            }
        } catch (Throwable t) {
            MessageUtil.send(sender, "&cLỗi thực thi SQL: " + t.getMessage());
        }
    }

    private void inspectDatabase(CommandSender sender, DataStore ds, String pluginName) {
        Connection conn = ds.getConnection();
        if (conn == null) {
            MessageUtil.send(sender, "&cDataStore của " + pluginName + " không sử dụng SQL backend.");
            return;
        }

        MessageUtil.send(sender, "&6=== Thông Tin Bảng Dữ Liệu SQL (" + pluginName + ") ===");
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")) {
            int tableCount = 0;
            while (rs.next()) {
                tableCount++;
                String tbl = rs.getString("name");
                MessageUtil.send(sender, "&e - Bảng: &b" + tbl);
            }
            if (tableCount == 0) {
                MessageUtil.send(sender, "&7Chưa có bảng SQL tùy biến nào được tạo.");
            }
        } catch (Throwable t) {
            MessageUtil.send(sender, "&cLỗi inspect SQLite: " + t.getMessage());
        }
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, "&6=== Hệ Thống Quản Trị Cơ Sở Dữ Liệu In-Game (/jdb) ===");
        MessageUtil.send(sender, "&e/jdb query <tên_plugin> <sql...> &7- Thực thi câu lệnh SQL trực tiếp");
        MessageUtil.send(sender, "&e/jdb inspect <tên_plugin>       &7- Kiểm tra cấu trúc các bảng SQL");
        MessageUtil.send(sender, "&e/jdb keys <tên_plugin>          &7- Liệt kê toàn bộ khóa Key-Value");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) return List.of();
        if (args.length == 1) {
            List<String> sub = List.of("query", "inspect", "keys");
            List<String> res = new ArrayList<>();
            for (String s : sub) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) res.add(s);
            }
            return res;
        }
        if (args.length == 2) {
            List<String> res = new ArrayList<>();
            for (String p : plugin.getPluginManager().getActivePlugins().keySet()) {
                if (p.startsWith(args[1].toLowerCase(Locale.ROOT))) res.add(p);
            }
            return res;
        }
        return List.of();
    }
}
