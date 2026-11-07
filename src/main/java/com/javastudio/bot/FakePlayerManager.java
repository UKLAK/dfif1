package com.javastudio.bot;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("deprecation")
public class FakePlayerManager {

    private final JavaStudioPlugin plugin;
    private final Map<String, SimulatedBot> activeBots = new ConcurrentHashMap<>();
    private BukkitTask stressTestTask = null;

    public record SimulatedBot(String name, UUID uuid, LivingEntity entity, Location spawnLocation, long spawnedAt) {}

    public FakePlayerManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public SimulatedBot spawnBot(String name, Location location) {
        if (activeBots.containsKey(name.toLowerCase())) {
            removeBot(name);
        }

        World world = location.getWorld();
        if (world == null) return null;

        ArmorStand stand = world.spawn(location, ArmorStand.class, as -> {
            as.setCustomName("§b[BOT] §f" + name);
            as.setCustomNameVisible(true);
            as.setArms(true);
            as.setBasePlate(false);
            as.setGravity(true);
            as.getEquipment().setHelmet(new ItemStack(Material.PLAYER_HEAD));
            as.getEquipment().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            as.getEquipment().setLeggings(new ItemStack(Material.IRON_LEGGINGS));
            as.getEquipment().setBoots(new ItemStack(Material.IRON_BOOTS));
            as.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        });

        SimulatedBot bot = new SimulatedBot(name, stand.getUniqueId(), stand, location.clone(), System.currentTimeMillis());
        activeBots.put(name.toLowerCase(), bot);
        return bot;
    }

    public List<SimulatedBot> spawnBots(String baseName, int count, Location location) {
        List<SimulatedBot> list = new ArrayList<>();
        double radius = Math.max(2.0, Math.sqrt(count));
        for (int i = 1; i <= count; i++) {
            double angle = (2 * Math.PI / count) * i;
            double offsetX = Math.cos(angle) * radius;
            double offsetZ = Math.sin(angle) * radius;
            Location loc = location.clone().add(offsetX, 0, offsetZ);
            loc.setY(location.getWorld().getHighestBlockYAt(loc) + 1);

            String botName = baseName + "_" + i;
            SimulatedBot b = spawnBot(botName, loc);
            if (b != null) list.add(b);
        }
        return list;
    }

    public int executeAction(String botTarget, String action, String[] extraArgs, CommandSender feedbackSender) {
        List<SimulatedBot> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(botTarget)) {
            targets.addAll(activeBots.values());
        } else {
            SimulatedBot b = activeBots.get(botTarget.toLowerCase());
            if (b != null) targets.add(b);
        }

        if (targets.isEmpty()) {
            if (feedbackSender != null) {
                MessageUtil.sendError(feedbackSender, "Không tìm thấy bot nào phù hợp với tên: " + botTarget);
            }
            return 0;
        }

        int count = 0;
        for (SimulatedBot bot : targets) {
            if (bot.entity() == null || !bot.entity().isValid()) continue;

            switch (action.toLowerCase()) {
                case "attack" -> {
                    List<Entity> nearby = bot.entity().getNearbyEntities(3.5, 3.5, 3.5);
                    Entity victim = null;
                    for (Entity e : nearby) {
                        if (e != bot.entity() && e instanceof LivingEntity) {
                            victim = e;
                            break;
                        }
                    }
                    if (victim instanceof LivingEntity le) {
                        le.damage(4.0, bot.entity());
                    }
                    bot.entity().setVelocity(bot.entity().getLocation().getDirection().multiply(0.2));
                    count++;
                }
                case "move" -> {
                    double rx = (ThreadLocalRandom.current().nextDouble() - 0.5) * 1.5;
                    double rz = (ThreadLocalRandom.current().nextDouble() - 0.5) * 1.5;
                    Location from = bot.entity().getLocation();
                    Location to = from.clone().add(rx, 0, rz);
                    to.setY(from.getWorld().getHighestBlockYAt(to) + 1);
                    bot.entity().teleport(to);
                    count++;
                }
                case "jump" -> {
                    bot.entity().setVelocity(new Vector(0, 0.42, 0));
                    count++;
                }
                case "clickslot" -> {
                    int slot = 0;
                    if (extraArgs != null && extraArgs.length > 0) {
                        try {
                            slot = Integer.parseInt(extraArgs[0]);
                        } catch (NumberFormatException ignored) {}
                    }
                    Player dummyPlayer = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                    if (dummyPlayer != null) {
                        Inventory topInv = dummyPlayer.getOpenInventory().getTopInventory();
                        if (topInv != null && topInv.getType() != InventoryType.CRAFTING) {
                            InventoryClickEvent clickEv = new InventoryClickEvent(
                                    dummyPlayer.getOpenInventory(),
                                    InventoryType.SlotType.CONTAINER,
                                    slot,
                                    org.bukkit.event.inventory.ClickType.LEFT,
                                    org.bukkit.event.inventory.InventoryAction.PICKUP_ALL
                            );
                            Bukkit.getPluginManager().callEvent(clickEv);
                        }
                    }
                    count++;
                }
                case "chat" -> {
                    String msg = extraArgs != null && extraArgs.length > 0 ? String.join(" ", extraArgs) : "Xin chào từ JavaStudio Bot!";
                    Set<Player> recipients = new HashSet<>(Bukkit.getOnlinePlayers());
                    AsyncPlayerChatEvent chatEvent = new AsyncPlayerChatEvent(
                            false,
                            Bukkit.getOnlinePlayers().stream().findFirst().orElse(null),
                            "[" + bot.name() + "] " + msg,
                            recipients
                    );
                    Bukkit.getPluginManager().callEvent(chatEvent);
                    Bukkit.broadcastMessage("§b[BOT " + bot.name() + "] §f" + msg);
                    count++;
                }
            }
        }

        return count;
    }

    public boolean startStressTest(CommandSender sender, int botCount, int durationSeconds, Location center) {
        if (stressTestTask != null) {
            MessageUtil.sendError(sender, "Một bài kiểm thử Stress-Test đang chạy dở! Hãy đợi kết thúc.");
            return false;
        }

        botCount = Math.clamp(botCount, 1, 100);
        durationSeconds = Math.clamp(durationSeconds, 5, 120);

        MessageUtil.sendHeader(sender, "KHỞI CHẠY STRESS-TEST MÁY CHỦ (" + botCount + " BOTS)");
        MessageUtil.sendRaw(sender, "  &7Thời gian kiểm thử: &e" + durationSeconds + " giây &7| Trung tâm: &f" +
                center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ());
        MessageUtil.sendRaw(sender, "  &aĐang sinh " + botCount + " Bot giả lập và bắt đầu phân tán tải Chunks...");

        activeBots.keySet().removeIf(k -> k.startsWith("stress_"));

        List<SimulatedBot> bots = spawnBots("stress", botCount, center);

        double initialTps = plugin.getServerController() != null ? plugin.getServerController().getTPS()[0] : 20.0;
        double initialMspt = plugin.getServerController() != null ? plugin.getServerController().getMSPT() : 5.0;
        int initialChunks = center.getWorld().getLoadedChunks().length;

        final int totalDuration = durationSeconds;
        final int[] elapsed = {0};
        final double[] minTps = {initialTps};
        final double[] maxMspt = {initialMspt};

        stressTestTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            elapsed[0]++;

            for (SimulatedBot bot : bots) {
                if (bot.entity() != null && bot.entity().isValid()) {
                    double angle = ThreadLocalRandom.current().nextDouble() * 2 * Math.PI;
                    double dist = 8.0 + (elapsed[0] * 3.0);
                    Location target = center.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
                    target.setY(center.getWorld().getHighestBlockYAt(target) + 1);
                    bot.entity().teleport(target);
                }
            }

            double currentTps = plugin.getServerController() != null ? plugin.getServerController().getTPS()[0] : 20.0;
            double currentMspt = plugin.getServerController() != null ? plugin.getServerController().getMSPT() : 5.0;

            if (currentTps < minTps[0]) minTps[0] = currentTps;
            if (currentMspt > maxMspt[0]) maxMspt[0] = currentMspt;

            if (elapsed[0] >= totalDuration) {
                if (stressTestTask != null) {
                    stressTestTask.cancel();
                    stressTestTask = null;
                }

                for (SimulatedBot b : bots) {
                    removeBot(b.name());
                }

                int finalChunks = center.getWorld().getLoadedChunks().length;

                MessageUtil.sendHeader(sender, "BÁO CÁO KẾT QUẢ STRESS-TEST");
                MessageUtil.sendRaw(sender, "  &7Số lượng Bot: &b" + bots.size() + " bots &7| Thời gian: &e" + totalDuration + "s");
                MessageUtil.sendRaw(sender, "  &7TPS Ban đầu: &a" + String.format("%.2f", initialTps) + " &7→ Thấp nhất: " +
                        (minTps[0] < 18.0 ? "&c" : "&a") + String.format("%.2f", minTps[0]));
                MessageUtil.sendRaw(sender, "  &7MSPT Ban đầu: &a" + String.format("%.2f", initialMspt) + "ms &7→ Cao nhất: " +
                        (maxMspt[0] > 40.0 ? "&c" : "&e") + String.format("%.2f", maxMspt[0]) + "ms");
                MessageUtil.sendRaw(sender, "  &7Chunks ban đầu: &f" + initialChunks + " &7→ Chunks sau test: &e" + finalChunks);

                double drop = initialTps - minTps[0];
                if (drop < 1.0) {
                    MessageUtil.sendSuccess(sender, "Đánh giá hiệu năng: XUẤT SẮC (Server giữ vững 20 TPS, độ trễ không đáng kể)!");
                } else if (drop < 3.0) {
                    MessageUtil.sendWarning(sender, "Đánh giá hiệu năng: KHÁ ỔN ĐỊNH (Sụt nhẹ " + String.format("%.2f", drop) + " TPS khi tải nhiều Chunk).");
                } else {
                    MessageUtil.sendError(sender, "Đánh giá hiệu năng: CẢNH BÁO TẮC NGHẼN (Sụt " + String.format("%.2f", drop) + " TPS, hãy tối ưu view-distance/threads).");
                }
            }
        }, 20L, 20L);

        return true;
    }

    public boolean removeBot(String name) {
        SimulatedBot b = activeBots.remove(name.toLowerCase());
        if (b != null) {
            if (b.entity() != null && b.entity().isValid()) {
                b.entity().remove();
            }
            return true;
        }
        return false;
    }

    public int clearAllBots() {
        int c = activeBots.size();
        for (SimulatedBot b : activeBots.values()) {
            if (b.entity() != null && b.entity().isValid()) {
                b.entity().remove();
            }
        }
        activeBots.clear();
        return c;
    }

    public Map<String, SimulatedBot> getActiveBots() {
        return Collections.unmodifiableMap(activeBots);
    }

    public void shutdown() {
        if (stressTestTask != null) {
            stressTestTask.cancel();
            stressTestTask = null;
        }
        clearAllBots();
    }
}
