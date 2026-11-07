package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.control.EventSpyManager;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

public final class JSpyCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final EventSpyManager spyManager;

    public JSpyCommand(JavaStudioPlugin plugin, EventSpyManager spyManager) {
        this.plugin = plugin;
        this.spyManager = spyManager;
    }

    public JavaStudioPlugin getPlugin() {
        return plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền sử dụng EventSpy!");
            return true;
        }

        if (com.javastudio.JavaStudioPlugin.getInstance() != null && !com.javastudio.JavaStudioPlugin.getInstance().getSettingsManager().isEventSpyEnabled()) {
            MessageUtil.sendError(sender, "Tính năng EventSpy (/jspy) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle event-spy");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "listen", "start", "watch" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " listen <TênSựKiện> (vd: PlayerInteractEvent, BlockBreakEvent)");
                    return true;
                }
                if (sender instanceof Player p) {
                    spyManager.toggleSubscription(p);
                }
                spyManager.startSpy(args[1], sender);
            }
            case "stop", "unwatch" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cách dùng: /" + label + " stop <TênSựKiện>");
                    return true;
                }
                spyManager.stopSpy(args[1], sender);
            }
            case "stopall", "clear" -> spyManager.stopAll(sender);
            case "sub", "toggle" -> {
                if (!(sender instanceof Player p)) {
                    MessageUtil.sendError(sender, "Lệnh này chỉ dùng được trong game!");
                    return true;
                }
                spyManager.toggleSubscription(p);
            }
            case "list" -> {
                Set<String> active = spyManager.getActiveSpies();
                if (active.isEmpty()) {
                    MessageUtil.sendInfo(sender, "Hiện không có sự kiện nào đang được theo dõi.");
                    return true;
                }
                sender.sendMessage("§6§l=== CÁC SỰ KIỆN ĐANG ĐƯỢC EVENTSPY THEO DÕI ===");
                for (String ev : active) {
                    sender.sendMessage(" §8» §e" + ev);
                }
            }
            case "filter" -> {
                if (args.length < 2 || args[1].equalsIgnoreCase("clear")) {
                    spyManager.setPlayerFilter(null);
                    MessageUtil.sendSuccess(sender, "Đã xóa bộ lọc người chơi. Theo dõi tất cả!");
                    return true;
                }
                spyManager.setPlayerFilter(args[1]);
                MessageUtil.sendSuccess(sender, "Đã đặt bộ lọc EventSpy cho người chơi: §e" + args[1]);
            }
            case "cancelled" -> {
                boolean val = args.length >= 2 && Boolean.parseBoolean(args[1]);
                spyManager.setShowCancelledOnly(val);
                MessageUtil.sendInfo(sender, "Chế độ chỉ hiện sự kiện bị Cancelled: " + (val ? "§aBẬT" : "§cTẮT"));
            }
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6§l=== LỆNH GỠ LỖI SỰ KIỆN EVENTSPY ===");
        sender.sendMessage(" §8» §e/" + label + " listen <TênSựKiện> §7- Bắt đầu theo dõi sự kiện (vd: PlayerInteractEvent)");
        sender.sendMessage(" §8» §e/" + label + " stop <TênSựKiện> §7- Dừng theo dõi sự kiện");
        sender.sendMessage(" §8» §e/" + label + " stopall §7- Dừng toàn bộ sự kiện đang theo dõi");
        sender.sendMessage(" §8» §e/" + label + " sub §7- Bật/tắt nhận thông báo EventSpy trong chat");
        sender.sendMessage(" §8» §e/" + label + " list §7- Xem danh sách sự kiện đang lắng nghe");
        sender.sendMessage(" §8» §e/" + label + " filter <player|clear> §7- Lọc sự kiện theo người chơi");
        sender.sendMessage(" §8» §e/" + label + " cancelled <true|false> §7- Chỉ hiện event bị huỷ");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String in = args[0].toLowerCase();
            return List.of("listen", "stop", "stopall", "sub", "list", "filter", "cancelled").stream()
                    .filter(s -> s.startsWith(in))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("listen")) {
            String in = args[1].toLowerCase();
            return List.of(
                    "PlayerInteractEvent", "BlockBreakEvent", "BlockPlaceEvent",
                    "PlayerMoveEvent", "PlayerChatEvent", "EntityDamageByEntityEvent",
                    "InventoryClickEvent", "PlayerDropItemEvent", "PlayerJoinEvent", "PlayerQuitEvent"
            ).stream().filter(s -> s.toLowerCase().startsWith(in)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
            String in = args[1].toLowerCase();
            return spyManager.getActiveSpies().stream().filter(s -> s.toLowerCase().startsWith(in)).toList();
        }
        return Collections.emptyList();
    }
}
