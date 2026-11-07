package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.gui.GuiDefinition;
import com.javastudio.api.gui.GuiDesignerEngine;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class JGuiCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final GuiDesignerEngine designerEngine;

    public JGuiCommand(JavaStudioPlugin plugin, GuiDesignerEngine designerEngine) {
        this.plugin = plugin;
        this.designerEngine = designerEngine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) {
            MessageUtil.send(sender, "&cBạn không có quyền sử dụng lệnh này!");
            return true;
        }

        if (!plugin.getSettingsManager().isGuiDesignerEnabled()) {
            MessageUtil.send(sender, "&cPhân hệ Visual GUI Designer (/jgui) đang bị TẮT trong cài đặt!");
            MessageUtil.send(sender, "&7Bật lại bằng lệnh: &e/javastudio toggle gui-designer &7hoặc qua &e/javastudio settings");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "designer", "edit" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.send(sender, "&cLệnh thiết kế chỉ có thể thực hiện bởi người chơi trong game!");
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(player, "&cCú pháp: /jgui designer <tên_gui> [số_hàng: 1-5] [tiêu_đề]");
                    return true;
                }
                String guiId = args[1].toLowerCase(Locale.ROOT);
                int rows = 4;
                if (args.length >= 3) {
                    try {
                        rows = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ignored) {}
                }
                String customTitle = null;
                if (args.length >= 4) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 3; i < args.length; i++) {
                        sb.append(args[i]).append(" ");
                    }
                    customTitle = sb.toString().trim();
                }
                designerEngine.openDesigner(player, guiId, rows, customTitle);
            }
            case "open" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cCú pháp: /jgui open <tên_gui> [người_chơi]");
                    return true;
                }
                String guiId = args[1].toLowerCase(Locale.ROOT);
                Player target = null;
                if (args.length >= 3) {
                    target = Bukkit.getPlayer(args[2]);
                    if (target == null) {
                        MessageUtil.send(sender, "&cKhông tìm thấy người chơi: " + args[2]);
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    MessageUtil.send(sender, "&cTừ console vui lòng chỉ định tên người chơi: /jgui open <id> <player>");
                    return true;
                }
                designerEngine.openLiveGui(target, guiId);
                MessageUtil.send(sender, "&aĐã mở giao diện '&e" + guiId + "&a' cho &b" + target.getName());
            }
            case "export" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cCú pháp: /jgui export <tên_gui>");
                    return true;
                }
                String guiId = args[1].toLowerCase(Locale.ROOT);
                GuiDefinition def = designerEngine.loadGui(guiId);
                if (def == null) {
                    MessageUtil.send(sender, "&cKhông tìm thấy GUI có ID: " + guiId);
                    return true;
                }
                File javaFile = designerEngine.generateJavaCode(def);
                MessageUtil.send(sender, "&a[GuiDesigner] &fĐã sinh mã Java thành công tại: &b" + javaFile.getPath());
                MessageUtil.send(sender, "&7Bạn có thể xem hoặc chỉnh sửa tệp này trong Web IDE (:8085) hoặc biên dịch động.");
            }
            case "list" -> {
                List<String> list = designerEngine.listGuiIds();
                MessageUtil.send(sender, "&6=== Danh Sách GUI Đã Thiết Kế (" + list.size() + ") ===");
                if (list.isEmpty()) {
                    MessageUtil.send(sender, "&7Chưa có GUI nào. Hãy tạo bằng &e/jgui designer <tên_gui>");
                } else {
                    for (String id : list) {
                        MessageUtil.send(sender, "&e - &f" + id + " &7(/jgui open " + id + ")");
                    }
                }
            }
            case "delete" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cCú pháp: /jgui delete <tên_gui>");
                    return true;
                }
                String guiId = args[1].toLowerCase(Locale.ROOT);
                if (designerEngine.deleteGui(guiId)) {
                    MessageUtil.send(sender, "&a[GuiDesigner] Đã xóa thành công GUI '&e" + guiId + "&a'.");
                } else {
                    MessageUtil.send(sender, "&cKhông tìm thấy GUI '&e" + guiId + "&c' để xóa.");
                }
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, "&6=== Hệ Thống Visual In-Game GUI Designer ===");
        MessageUtil.send(sender, "&e/jgui designer <id> [rows] [title] &7- Mở bàn thiết kế GUI kéo thả");
        MessageUtil.send(sender, "&e/jgui open <id> [player]          &7- Mở GUI trực tiếp trong game");
        MessageUtil.send(sender, "&e/jgui export <id>                 &7- Xuất mã nguồn Java SmartGui");
        MessageUtil.send(sender, "&e/jgui list                        &7- Xem danh sách GUI đã tạo");
        MessageUtil.send(sender, "&e/jgui delete <id>                  &7- Xóa cấu hình GUI");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> sub = List.of("designer", "open", "export", "list", "delete");
            List<String> res = new ArrayList<>();
            for (String s : sub) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    res.add(s);
                }
            }
            return res;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("export") || args[0].equalsIgnoreCase("delete"))) {
            List<String> guis = designerEngine.listGuiIds();
            List<String> res = new ArrayList<>();
            for (String g : guis) {
                if (g.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    res.add(g);
                }
            }
            return res;
        }
        return List.of();
    }
}

