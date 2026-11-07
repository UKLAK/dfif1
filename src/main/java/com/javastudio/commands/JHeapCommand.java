package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.inspector.HeapInspector;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class JHeapCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final HeapInspector inspector;

    public JHeapCommand(JavaStudioPlugin plugin, HeapInspector inspector) {
        this.plugin = plugin;
        this.inspector = inspector;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền sử dụng Live Heap Inspector!");
            return true;
        }

        if (!plugin.getSettingsManager().isHeapInspectorEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Live Heap Inspector (/jheap, /jmem) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle heap-inspector");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "get" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cú pháp: /" + label + " get <đường_dẫn_thuộc_tính>");
                    return true;
                }
                String path = args[1];
                try {
                    HeapInspector.PathResult res = inspector.resolve(sender, path);
                    MessageUtil.sendHeader(sender, "LIVE HEAP DUMP (" + path + ")");
                    MessageUtil.sendRaw(sender, "  &7Kiểu dữ liệu: &e" + (res.type() != null ? res.type().getName() : "null"));
                    MessageUtil.sendRaw(sender, "  &7Giá trị hiện tại: &b" + formatValue(res.value()));
                    if (res.targetField() != null) {
                        MessageUtil.sendRaw(sender, "  &7Modifiers: &8" + java.lang.reflect.Modifier.toString(res.targetField().getModifiers()));
                    }
                } catch (Throwable t) {
                    MessageUtil.sendError(sender, "Lỗi phân giải đối tượng: " + t.getMessage());
                }
            }
            case "set" -> {
                if (args.length < 3) {
                    MessageUtil.sendError(sender, "Cú pháp: /" + label + " set <đường_dẫn_thuộc_tính> <giá_trị_mới>");
                    return true;
                }
                String path = args[1];
                String newVal = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                try {
                    Object oldVal = inspector.setProperty(sender, path, newVal);
                    MessageUtil.sendHeader(sender, "CẬP NHẬT BIẾN TRONG RAM THÀNH CÔNG");
                    MessageUtil.sendRaw(sender, "  &7Đường dẫn: &f" + path);
                    MessageUtil.sendRaw(sender, "  &7Giá trị cũ: &c" + formatValue(oldVal));
                    MessageUtil.sendRaw(sender, "  &7Giá trị mới: &a" + newVal);
                } catch (Throwable t) {
                    MessageUtil.sendError(sender, "Lỗi ghi đè trường dữ liệu: " + t.getMessage());
                }
            }
            case "sizeof" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cú pháp: /" + label + " sizeof <tên_plugin|class>");
                    return true;
                }
                String target = args[1];
                Object root = null;
                Plugin p = Bukkit.getPluginManager().getPlugin(target);
                if (p != null) {
                    root = p;
                } else {
                    try {
                        root = Class.forName(target);
                    } catch (Exception ignored) {}
                }

                if (root == null) {
                    MessageUtil.sendError(sender, "Không tìm thấy Plugin hoặc Class: " + target);
                    return true;
                }

                MessageUtil.sendInfo(sender, "Đang duyệt đồ thị cây đối tượng trong RAM cho &b" + target + "&7...");
                HeapInspector.MemorySizeReport report = inspector.calculateSizeOf(root);

                MessageUtil.sendHeader(sender, "ƯỚC TÍNH BỘ NHỚ RAM (" + target + ")");
                MessageUtil.sendRaw(sender, "  &7Dung lượng ước tính: &a" + formatBytes(report.totalBytes()));
                MessageUtil.sendRaw(sender, "  &7Số đối tượng liên kết (Node Count): &e" + String.format("%,d", report.objectCount()));

                if (!report.topFields().isEmpty()) {
                    MessageUtil.sendRaw(sender, "  &7Các trường dữ liệu chiếm dụng lớn:");
                    report.topFields().entrySet().stream()
                            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                            .limit(5)
                            .forEach(e -> MessageUtil.sendRaw(sender, "    &8- &f" + e.getKey() + ": &b" + formatBytes(e.getValue())));
                }
            }
            case "gc" -> {
                long beforeUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long start = System.currentTimeMillis();
                System.gc();
                long elapsed = System.currentTimeMillis() - start;
                long afterUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long freed = beforeUsed - afterUsed;

                MessageUtil.sendHeader(sender, "DỌN RÁC BỘ NHỚ (JVM GARBAGE COLLECTION)");
                MessageUtil.sendRaw(sender, "  &7RAM trước GC: &c" + formatBytes(beforeUsed));
                MessageUtil.sendRaw(sender, "  &7RAM sau GC: &a" + formatBytes(afterUsed));
                MessageUtil.sendRaw(sender, "  &7Dung lượng giải phóng: &b" + (freed > 0 ? "+" + formatBytes(freed) : "0 B") + " &7| Thời gian: &e" + elapsed + "ms");
            }
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private String formatValue(Object val) {
        if (val == null) return "null";
        String str = val.toString();
        if (str.length() > 120) str = str.substring(0, 117) + "...";
        return str;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.2f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private void sendHelp(CommandSender sender, String label) {
        MessageUtil.sendHeader(sender, "THANH TRA BỘ NHỚ RAM (/" + label + ")");
        MessageUtil.sendRaw(sender, "  &e/" + label + " get <path> &7- Đọc giá trị thuộc tính (@p, @server, Plugin...)");
        MessageUtil.sendRaw(sender, "  &e/" + label + " set <path> <val> &7- Ghi đè trực tiếp giá trị trường trong RAM");
        MessageUtil.sendRaw(sender, "  &e/" + label + " sizeof <plugin> &7- Đo lường dung lượng RAM của một Plugin");
        MessageUtil.sendRaw(sender, "  &e/" + label + " gc &7- Kích hoạt JVM Garbage Collector thu hồi RAM");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("get", "set", "sizeof", "gc");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("get") || sub.equals("set")) {
                List<String> list = new ArrayList<>(Arrays.asList("@p.inventory.itemInMainHand", "@p.location", "@server.onlinePlayers", "JavaStudio.instance"));
                for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                    list.add(p.getName());
                }
                return list;
            }
            if (sub.equals("sizeof")) {
                List<String> list = new ArrayList<>();
                for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                    list.add(p.getName());
                }
                return list;
            }
        }
        return Collections.emptyList();
    }
}
