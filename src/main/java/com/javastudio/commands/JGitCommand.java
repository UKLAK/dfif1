package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.manager.SnapshotEngine;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class JGitCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final SnapshotEngine snapshotEngine;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public JGitCommand(JavaStudioPlugin plugin, SnapshotEngine snapshotEngine) {
        this.plugin = plugin;
        this.snapshotEngine = snapshotEngine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) {
            MessageUtil.send(sender, "&cBạn không có quyền thực hiện lệnh này!");
            return true;
        }

        if (!plugin.getSettingsManager().isSnapshotRollbackEnabled()) {
            MessageUtil.send(sender, "&cPhân hệ Snapshot & Rollback (/jgit) đang bị TẮT trong cài đặt!");
            MessageUtil.send(sender, "&7Bật lại bằng lệnh: &e/javastudio toggle snapshot-rollback &7hoặc qua &e/javastudio settings");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "snapshot", "commit", "save" -> {
                String tag = args.length >= 2 ? args[1] : "Manual-Checkpoint";
                SnapshotEngine.SnapshotInfo info = snapshotEngine.createSnapshot(tag);
                MessageUtil.send(sender, "&a[JGit] Đã tạo thành công điểm khôi phục (Snapshot):");
                MessageUtil.send(sender, " &7ID: &e" + info.id() + " &7| Nhãn: &b" + info.label());
                MessageUtil.send(sender, " &7Tập tin: &f" + info.fileCount() + " &7| Dung lượng: &f" + (info.totalBytes() / 1024) + " KB");
            }
            case "rollback", "checkout", "restore" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cCú pháp: /jgit rollback <snapshot_id>");
                    return true;
                }
                String targetId = args[1];
                MessageUtil.send(sender, "&e[JGit] Đang hoàn tác mã nguồn về snapshot: " + targetId + "...");
                boolean success = snapshotEngine.rollback(targetId);
                if (success) {
                    MessageUtil.send(sender, "&a[JGit] Hoàn tác thành công! Hệ thống đã tự động tạo một bản sao lưu an toàn trước khi rollback.");
                    MessageUtil.send(sender, "&7Bạn có thể gõ &e/jplugin reload &7để nạp lại các plugin đã khôi phục.");
                } else {
                    MessageUtil.send(sender, "&c[JGit] Rollback thất bại! Vui lòng kiểm tra lại ID snapshot.");
                }
            }
            case "list", "log", "history" -> {
                List<SnapshotEngine.SnapshotInfo> list = snapshotEngine.listSnapshots();
                MessageUtil.send(sender, "&6=== Lịch Sử Time-Travel Snapshots (" + list.size() + ") ===");
                if (list.isEmpty()) {
                    MessageUtil.send(sender, "&7Chưa có snapshot nào. Hãy tạo bằng &e/jgit snapshot [nhãn]");
                } else {
                    for (SnapshotEngine.SnapshotInfo s : list) {
                        String timeStr = dateFormat.format(new Date(s.timestamp()));
                        MessageUtil.send(sender, "&e - &f" + s.id() + " &7(&b" + s.label() + "&7) [" + timeStr + "] - " + s.fileCount() + " files");
                    }
                }
            }
            case "diff", "status" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cCú pháp: /jgit diff <snapshot_id>");
                    return true;
                }
                String targetId = args[1];
                List<String> diffs = snapshotEngine.diff(targetId);
                MessageUtil.send(sender, "&6=== So Sánh Với Snapshot (" + targetId + ") ===");
                for (String d : diffs) {
                    if (d.startsWith("[+]")) MessageUtil.send(sender, "&a" + d);
                    else if (d.startsWith("[-]")) MessageUtil.send(sender, "&c" + d);
                    else if (d.startsWith("[~]")) MessageUtil.send(sender, "&e" + d);
                    else MessageUtil.send(sender, "&7" + d);
                }
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, "&6=== Hệ Thống Time-Travel Snapshot & Rollback (/jgit) ===");
        MessageUtil.send(sender, "&e/jgit snapshot [nhãn]     &7- Lưu lại toàn bộ mã nguồn projects/ và scripts/");
        MessageUtil.send(sender, "&e/jgit rollback <snap_id>  &7- Hoàn tác tức thì nếu bản cập nhật lỗi");
        MessageUtil.send(sender, "&e/jgit list                &7- Xem lịch sử các điểm khôi phục");
        MessageUtil.send(sender, "&e/jgit diff <snap_id>      &7- Xem chi tiết các tập tin đã thay đổi");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) return List.of();
        if (args.length == 1) {
            List<String> sub = List.of("snapshot", "rollback", "list", "diff");
            List<String> res = new ArrayList<>();
            for (String s : sub) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) res.add(s);
            }
            return res;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("rollback") || args[0].equalsIgnoreCase("diff"))) {
            List<String> res = new ArrayList<>();
            for (SnapshotEngine.SnapshotInfo s : snapshotEngine.listSnapshots()) {
                if (s.id().startsWith(args[1])) res.add(s.id());
            }
            return res;
        }
        return List.of();
    }
}

