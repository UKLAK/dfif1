package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.bot.FakePlayerManager;
import com.javastudio.pipeline.JBotEngine;
import com.javastudio.pipeline.JBotEngine.BotBehavior;
import com.javastudio.pipeline.JBotEngine.SimulatedBot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Lệnh điều khiển Hệ thống Fake Player Netty & Stress-Test (/jbot, /jb).
 */
public final class JBotCommand implements CommandExecutor, TabCompleter {

    private final JBotEngine engine;
    private final JavaStudioPlugin plugin;
    private final FakePlayerManager fallbackManager;

    public JBotCommand(JBotEngine engine) {
        this(JavaStudioPlugin.getInstance(), engine, null);
    }

    public JBotCommand(JavaStudioPlugin plugin, FakePlayerManager fallbackManager) {
        this(plugin, plugin != null ? plugin.getBotEngine() : null, fallbackManager);
    }

    public JBotCommand(JavaStudioPlugin plugin, JBotEngine engine, FakePlayerManager fallbackManager) {
        this.plugin = plugin != null ? plugin : JavaStudioPlugin.getInstance();
        this.engine = engine;
        this.fallbackManager = fallbackManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("javastudio.admin")) {
            sender.sendMessage("§cBạn không có quyền thực thi lệnh này.");
            return true;
        }

        if (plugin != null && !plugin.getSettingsManager().isFakePlayersEnabled()) {
            sender.sendMessage("§cTính năng Giả Lập Người Chơi Ảo (/jbot, /jb) đang bị tắt trong cài đặt!");
            sender.sendMessage("§7Bật lại bằng lệnh: §e/javastudio toggle fake-players");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spawn" -> handleSpawn(sender, label, args);
            case "mass", "bulk" -> handleMassSpawn(sender, label, args);
            case "remove", "kick" -> handleRemove(sender, args);
            case "behavior", "mode" -> handleBehavior(sender, args);
            case "list" -> handleList(sender);
            case "clear", "clearall" -> handleClear(sender);
            case "stresstest" -> handleStressTest(sender, args);
            case "action" -> handleAction(sender, label, args);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void handleSpawn(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cLệnh spawn bot đơn lẻ cần thực hiện bởi người chơi trong game.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cCú pháp: /" + label + " spawn <tên_bot> [idle|wander|combat|spam]");
            return;
        }

        String name = args[1];
        BotBehavior behavior = BotBehavior.WANDER;
        if (args.length >= 3) {
            try {
                behavior = BotBehavior.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        if (engine != null) {
            try {
                SimulatedBot bot = engine.spawnBot(name, player.getLocation(), behavior);
                player.sendMessage(String.format("§a[JBot] §fĐã triệu hồi Bot §e%s §ftại vị trí của bạn (Hành vi: §b%s§f).",
                        bot.getName(), bot.getBehavior().name()));
                return;
            } catch (Exception e) {
                player.sendMessage("§e[JBot] NMS Netty Bot gặp ngoại lệ (" + e.getMessage() + "), chuyển sang FakePlayerManager fallback...");
            }
        }

        if (fallbackManager != null) {
            var b = fallbackManager.spawnBot(name, player.getLocation());
            if (b != null) {
                player.sendMessage("§a[JBot] §fĐã triệu hồi Bot (Entity Mode): §e" + name + " §ftại vị trí của bạn!");
            } else {
                player.sendMessage("§cKhông thể triệu hồi bot.");
            }
        } else {
            player.sendMessage("§cEngine bot chưa sẵn sàng.");
        }
    }

    private void handleMassSpawn(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cLệnh spawn hàng loạt cần thực hiện trong game.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cCú pháp: /" + label + " mass <số_lượng> [idle|wander|combat|spam]");
            return;
        }

        int count;
        try {
            count = Math.max(1, Math.min(200, Integer.parseInt(args[1])));
        } catch (NumberFormatException e) {
            sender.sendMessage("§cSố lượng không hợp lệ.");
            return;
        }

        BotBehavior behavior = BotBehavior.WANDER;
        if (args.length >= 3) {
            try {
                behavior = BotBehavior.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        Location loc = player.getLocation();
        sender.sendMessage(String.format("§e[JBot] §fĐang tiến hành triệu hồi §e%d §fBots kiểm thử tải...", count));

        final BotBehavior finalBehavior = behavior;
        Plugin hostPlugin = plugin != null ? plugin : Bukkit.getPluginManager().getPlugin("JavaStudio");
        if (hostPlugin == null) {
            sender.sendMessage("§cKhông tìm thấy instance plugin chủ.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(hostPlugin, () -> {
            int success = 0;
            for (int i = 1; i <= count; i++) {
                String botName = "Bot_" + i + "_" + UUID.randomUUID().toString().substring(0, 4);
                try {
                    final String name = botName;
                    Bukkit.getScheduler().runTask(hostPlugin, () -> {
                        if (engine != null) {
                            try {
                                engine.spawnBot(name, loc, finalBehavior);
                            } catch (Exception ex) {
                                if (fallbackManager != null) {
                                    fallbackManager.spawnBot(name, loc);
                                }
                            }
                        } else if (fallbackManager != null) {
                            fallbackManager.spawnBot(name, loc);
                        }
                    });
                    success++;
                    Thread.sleep(15);
                } catch (Exception ignored) {}
            }
            final int totalSuccess = success;
            Bukkit.getScheduler().runTask(hostPlugin, () -> {
                sender.sendMessage(String.format("§a[JBot] §fĐã spawn thành công §e%d / %d §fBots vào thế giới!", totalSuccess, count));
            });
        });
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cCú pháp: /jbot remove <tên_bot|all>");
            return;
        }
        String target = args[1];
        if (target.equalsIgnoreCase("all")) {
            handleClear(sender);
            return;
        }

        boolean removed = false;
        if (engine != null && engine.removeBot(target)) {
            removed = true;
        }
        if (fallbackManager != null && fallbackManager.removeBot(target)) {
            removed = true;
        }

        if (removed) {
            sender.sendMessage("§a[JBot] §fĐã xóa Bot §e" + target + "§f.");
        } else {
            sender.sendMessage("§cKhông tìm thấy Bot có tên: §e" + target);
        }
    }

    private void handleClear(CommandSender sender) {
        int count = 0;
        if (engine != null) {
            count += engine.removeAllBots();
        }
        if (fallbackManager != null) {
            count += fallbackManager.clearAllBots();
        }
        sender.sendMessage("§a[JBot] §fĐã ngắt kết nối và xóa toàn bộ §e" + count + " §fBots khỏi máy chủ.");
    }

    private void handleBehavior(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cCú pháp: /jbot behavior <tên_bot|all> <idle|wander|combat|spam>");
            return;
        }
        String target = args[1];
        BotBehavior newBehavior;
        try {
            newBehavior = BotBehavior.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cHành vi không hợp lệ. Chọn: idle, wander, combat, spam");
            return;
        }

        if (engine == null) {
            sender.sendMessage("§cJBotEngine chưa được khởi tạo.");
            return;
        }

        if (target.equalsIgnoreCase("all")) {
            for (SimulatedBot bot : engine.getActiveBots()) {
                bot.setBehavior(newBehavior);
            }
            sender.sendMessage("§a[JBot] §fĐã cập nhật hành vi thành §e" + newBehavior.name() + " §fcho toàn bộ Bots.");
        } else {
            SimulatedBot bot = engine.getBot(target);
            if (bot != null) {
                bot.setBehavior(newBehavior);
                sender.sendMessage("§a[JBot] §fĐã đổi hành vi của Bot §e" + target + " §fsang §b" + newBehavior.name() + "§f.");
            } else {
                sender.sendMessage("§cKhông tìm thấy Bot: §e" + target);
            }
        }
    }

    private void handleList(CommandSender sender) {
        List<String> entries = new ArrayList<>();
        if (engine != null) {
            for (SimulatedBot bot : engine.getActiveBots()) {
                Location l = bot.getLocation();
                entries.add(String.format(" §e• %s §8(§7Mode: §b%s§8) §7- Tọa độ: §f(%d, %d, %d)",
                        bot.getName(), bot.getBehavior().name(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
            }
        }
        if (fallbackManager != null) {
            for (var b : fallbackManager.getActiveBots().values()) {
                Location l = b.entity() != null ? b.entity().getLocation() : b.spawnLocation();
                entries.add(String.format(" §b• %s §8(§7Entity§8) §7- Tọa độ: §f(%d, %d, %d)",
                        b.name(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
            }
        }

        if (entries.isEmpty()) {
            sender.sendMessage("§7[JBot] Hiện không có Bot nào đang hoạt động trên máy chủ.");
            return;
        }

        sender.sendMessage("§8§m------------§r §b§lDANH SÁCH FAKE PLAYERS (JBOT) §8§m------------");
        for (String line : entries) {
            sender.sendMessage(line);
        }
        sender.sendMessage(String.format("§7Tổng số lượng Bots đang chạy: §a%d", entries.size()));
    }

    private void handleStressTest(CommandSender sender, String[] args) {
        if (fallbackManager == null) {
            sender.sendMessage("§cHệ thống StressTest Entity chưa sẵn sàng.");
            return;
        }

        Location loc;
        if (sender instanceof Player p) {
            loc = p.getLocation();
        } else {
            loc = Bukkit.getWorlds().get(0).getSpawnLocation();
        }

        int count = 20;
        int duration = 15;
        if (args.length > 1) {
            try { count = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        }
        if (args.length > 2) {
            try { duration = Integer.parseInt(args[2]); } catch (Exception ignored) {}
        }

        fallbackManager.startStressTest(sender, count, duration, loc);
    }

    private void handleAction(CommandSender sender, String label, String[] args) {
        if (fallbackManager == null) {
            sender.sendMessage("§cTính năng action yêu cầu FakePlayerManager.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§cCú pháp: /" + label + " action <tên|all> <attack|move|jump|clickSlot|chat> [tham_số]");
            return;
        }
        String target = args[1];
        String action = args[2];
        String[] extra = args.length > 3 ? Arrays.copyOfRange(args, 3, args.length) : new String[0];

        int affected = fallbackManager.executeAction(target, action, extra, sender);
        if (affected > 0) {
            sender.sendMessage("§a[JBot] Đã thực thi hành động §e" + action.toUpperCase() + " §atrên §b" + affected + " Bot!");
        } else {
            sender.sendMessage("§cKhông tìm thấy bot phù hợp để ra lệnh.");
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§8§m----------------§r §b§lJAVASTUDIO FAKE PLAYER BOT & STRESS-TEST §8§m----------------");
        sender.sendMessage(" §b/" + label + " spawn <tên> [idle|wander|combat|spam] §7- Triệu hồi Bot");
        sender.sendMessage(" §b/" + label + " mass <số_lượng> [mode] §7- Triệu hồi hàng loạt Bots kiểm thử tải");
        sender.sendMessage(" §b/" + label + " behavior <tên|all> <mode> §7- Đổi hành vi (idle, wander, combat, spam)");
        sender.sendMessage(" §b/" + label + " remove <tên|all> §7- Xóa bot chỉ định hoặc xóa tất cả");
        sender.sendMessage(" §b/" + label + " list §7- Xem danh sách toàn bộ các Bots đang hoạt động");
        sender.sendMessage(" §b/" + label + " stresstest [số_bot] [giây] §7- Phân tán bot nạp Chunks & đo TPS/MSPT");
        sender.sendMessage(" §b/" + label + " action <tên|all> <action> §7- Ra lệnh (attack, move, jump, clickSlot, chat)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("spawn", "mass", "remove", "behavior", "list", "clear", "stresstest", "action").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("remove") || sub.equals("behavior") || sub.equals("action")) {
                Set<String> list = new LinkedHashSet<>();
                list.add("all");
                if (engine != null) {
                    for (SimulatedBot b : engine.getActiveBots()) list.add(b.getName());
                }
                if (fallbackManager != null) {
                    list.addAll(fallbackManager.getActiveBots().keySet());
                }
                return list.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
            }
            if (sub.equals("spawn") || sub.equals("mass")) {
                return List.of("1", "5", "10", "25", "50", "100");
            }
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("spawn") || sub.equals("mass") || sub.equals("behavior")) {
                return List.of("IDLE", "WANDER", "COMBAT", "SPAM_CHAT").stream()
                        .filter(s -> s.startsWith(args[2].toUpperCase(Locale.ROOT)))
                        .toList();
            }
            if (sub.equals("action")) {
                return List.of("attack", "move", "jump", "clickSlot", "chat").stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                        .toList();
            }
        }
        return Collections.emptyList();
    }
}
