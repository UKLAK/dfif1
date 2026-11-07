package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.pipeline.MockEventEngine;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class JMockCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final MockEventEngine mockEngine;

    public JMockCommand(JavaStudioPlugin plugin, MockEventEngine mockEngine) {
        this.plugin = plugin;
        this.mockEngine = mockEngine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) {
            MessageUtil.send(sender, "&cBạn không có quyền thực hiện lệnh này!");
            return true;
        }

        if (!plugin.getSettingsManager().isMockEventsEnabled()) {
            MessageUtil.send(sender, "&cPhân hệ Giả Lập Event (/jmock) đang bị TẮT trong cài đặt!");
            MessageUtil.send(sender, "&7Bật lại bằng lệnh: &e/javastudio toggle mock-events &7hoặc qua &e/javastudio settings");
            return true;
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("event")) {
            MessageUtil.send(sender, "&6=== Hệ Thống Giả Lập Bắn Sự Kiện (/jmock) ===");
            MessageUtil.send(sender, "&e/jmock event join            &7- Bắn giả lập PlayerJoinEvent");
            MessageUtil.send(sender, "&e/jmock event quit            &7- Bắn giả lập PlayerQuitEvent");
            MessageUtil.send(sender, "&e/jmock event chat <tin_nhắn> &7- Bắn giả lập AsyncPlayerChatEvent");
            MessageUtil.send(sender, "&e/jmock event break           &7- Bắn giả lập BlockBreakEvent");
            return true;
        }

        Player player = (sender instanceof Player p) ? p : null;
        String eventName = args[1];
        String[] restArgs = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : new String[0];

        MockEventEngine.MockDispatchResult result = mockEngine.fireMockEvent(player, eventName, restArgs);
        if (result.success()) {
            MessageUtil.send(sender, "&a[JMock] &fBắn thành công Event &b" + result.eventName() + " &7(Thời gian xử lý: &e" + String.format(Locale.ROOT, "%.3f", result.elapsedMs()) + " ms&7)");
            MessageUtil.send(sender, " &7Trạng thái hủy (Cancelled): " + (result.cancelled() ? "&cYES (Bị chặn bởi listener)" : "&aNO (Được thông qua)"));
        } else {
            MessageUtil.send(sender, "&c[JMock] Thất bại khi bắn Event: " + result.message());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("javastudio.admin")) return List.of();
        if (args.length == 1) {
            return List.of("event");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("event")) {
            List<String> list = List.of("join", "quit", "chat", "break");
            List<String> res = new ArrayList<>();
            for (String s : list) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) res.add(s);
            }
            return res;
        }
        return List.of();
    }
}

