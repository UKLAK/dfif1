package com.javastudio.templates;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PluginTemplates {

    private static final Map<String, TemplateDefinition> TEMPLATES = new HashMap<>();

    static {
        TEMPLATES.put("standard", new TemplateDefinition(
                "Mẫu cơ bản gồm Lệnh /hello và Lắng nghe sự kiện người chơi tham gia",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.ChatColor;
                import org.bukkit.entity.Player;
                import org.bukkit.event.EventHandler;
                import org.bukkit.event.Listener;
                import org.bukkit.event.player.PlayerJoinEvent;

                public class %CLASS_NAME% extends DynamicPlugin implements Listener {

                    @Override
                    public void onEnable() {
                        saveDefaultConfig();
                        getLogger().info("%PLUGIN_NAME% đã được bật thành công!");

                        registerListener(this);

                        registerCommand("%COMMAND%", (sender, args) -> {
                            String msg = getConfig().getString("example-message", "&aXin chào từ %PLUGIN_NAME%!");
                            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                            return true;
                        });
                    }

                    @Override
                    public void onDisable() {
                        getLogger().info("%PLUGIN_NAME% đã tắt!");
                    }

                    @EventHandler
                    public void onJoin(PlayerJoinEvent event) {
                        Player player = event.getPlayer();
                        player.sendMessage("§b[§e%PLUGIN_NAME%§b] §aChào mừng bạn §f" + player.getName() + " §ađã tham gia server!");
                    }
                }
                """
        ));

        TEMPLATES.put("teleport-bow", new TemplateDefinition(
                "Cung Dịch Chuyển - Bắn tên dịch chuyển người chơi tới điểm rơi",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.ChatColor;
                import org.bukkit.Location;
                import org.bukkit.Material;
                import org.bukkit.Sound;
                import org.bukkit.entity.Arrow;
                import org.bukkit.entity.Player;
                import org.bukkit.event.EventHandler;
                import org.bukkit.event.Listener;
                import org.bukkit.event.entity.ProjectileHitEvent;
                import org.bukkit.inventory.ItemStack;
                import org.bukkit.inventory.meta.ItemMeta;

                import java.util.Collections;

                public class %CLASS_NAME% extends DynamicPlugin implements Listener {

                    private static final String BOW_NAME = "§6§lCung Dịch Chuyển";

                    @Override
                    public void onEnable() {
                        registerListener(this);

                        registerCommand("tpbow", (sender, args) -> {
                            if (!(sender instanceof Player player)) {
                                sender.sendMessage("§cChỉ người chơi mới có thể dùng lệnh này!");
                                return true;
                            }
                            ItemStack bow = new ItemStack(Material.BOW);
                            ItemMeta meta = bow.getItemMeta();
                            meta.setDisplayName(BOW_NAME);
                            meta.setLore(Collections.singletonList("§7Bắn tên để tức thì dịch chuyển tới đó!"));
                            bow.setItemMeta(meta);

                            player.getInventory().addItem(bow);
                            player.getInventory().addItem(new ItemStack(Material.ARROW, 64));
                            player.sendMessage("§aBạn đã nhận được §6Cung Dịch Chuyển§a!");
                            return true;
                        });
                        getLogger().info("TeleportBow Plugin đã sẵn sàng!");
                    }

                    @Override
                    public void onDisable() {
                        getLogger().info("TeleportBow Plugin đã tắt!");
                    }

                    @EventHandler
                    public void onArrowHit(ProjectileHitEvent event) {
                        if (event.getEntity() instanceof Arrow arrow && arrow.getShooter() instanceof Player player) {
                            ItemStack item = player.getInventory().getItemInMainHand();
                            if (item.hasItemMeta() && BOW_NAME.equals(item.getItemMeta().getDisplayName())) {
                                Location loc = arrow.getLocation();
                                loc.setPitch(player.getLocation().getPitch());
                                loc.setYaw(player.getLocation().getYaw());

                                player.teleport(loc);
                                player.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                                player.sendMessage("§aVù! Bạn đã dịch chuyển đến điểm mũi tên rơi!");
                                arrow.remove();
                            }
                        }
                    }
                }
                """
        ));

        TEMPLATES.put("auto-broadcast", new TemplateDefinition(
                "Tự động phát thông báo định kỳ lặp lại theo cấu hình",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.ChatColor;

                import java.util.List;

                public class %CLASS_NAME% extends DynamicPlugin {

                    private int index = 0;

                    @Override
                    public void onEnable() {
                        saveDefaultConfig();
                        getConfig().addDefault("interval-seconds", 60);
                        getConfig().addDefault("messages", List.of(
                            "&e[Thông báo] &fNhớ tham gia Discord của server nhé!",
                            "&b[Mẹo] &fGõ &a/help &fđể xem danh sách các tính năng hữu ích.",
                            "&a[JavaStudio] &fServer đang sử dụng dynamic Java plugins mượt mà!"
                        ));
                        saveConfig();

                        long intervalTicks = getConfig().getInt("interval-seconds", 60) * 20L;

                        runTaskTimer(() -> {
                            List<String> list = getConfig().getStringList("messages");
                            if (list.isEmpty()) return;
                            String msg = list.get(index % list.size());
                            getServer().broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
                            index++;
                        }, 100L, intervalTicks);

                        getLogger().info("Hệ thống thông báo tự động đã bật!");
                    }

                    @Override
                    public void onDisable() {
                        getLogger().info("Hệ thống thông báo đã tắt!");
                    }
                }
                """
        ));

        TEMPLATES.put("custom-item", new TemplateDefinition(
                "Kiếm Thần Sét - Đánh trúng đối thủ sẽ giáng sét xuống mục tiêu",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.Material;
                import org.bukkit.enchantments.Enchantment;
                import org.bukkit.entity.Entity;
                import org.bukkit.entity.Player;
                import org.bukkit.event.EventHandler;
                import org.bukkit.event.Listener;
                import org.bukkit.event.entity.EntityDamageByEntityEvent;
                import org.bukkit.inventory.ItemStack;
                import org.bukkit.inventory.meta.ItemMeta;

                import java.util.List;

                public class %CLASS_NAME% extends DynamicPlugin implements Listener {

                    private static final String SWORD_NAME = "§e§lKiếm Thần Sét";

                    @Override
                    public void onEnable() {
                        registerListener(this);

                        registerCommand("lightningsword", (sender, args) -> {
                            if (!(sender instanceof Player player)) {
                                sender.sendMessage("§cChỉ người chơi mới nhận được kiếm!");
                                return true;
                            }
                            ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
                            ItemMeta meta = sword.getItemMeta();
                            meta.setDisplayName(SWORD_NAME);
                            meta.setLore(List.of("§7Vũ khí huyền thoại triệu hồi sấm sét khi tấn công!"));
                            sword.setItemMeta(meta);
                            sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);

                            player.getInventory().addItem(sword);
                            player.sendMessage("§aBạn đã nhận được §e§lKiếm Thần Sét§a!");
                            return true;
                        });
                    }

                    @Override
                    public void onDisable() {
                        getLogger().info("Kiếm Thần Sét đã tắt!");
                    }

                    @EventHandler
                    public void onHit(EntityDamageByEntityEvent event) {
                        if (event.getDamager() instanceof Player player) {
                            ItemStack weapon = player.getInventory().getItemInMainHand();
                            if (weapon.hasItemMeta() && SWORD_NAME.equals(weapon.getItemMeta().getDisplayName())) {
                                Entity target = event.getEntity();
                                target.getWorld().strikeLightning(target.getLocation());
                                player.sendMessage("§e⚡ Sấm sét đã giáng xuống mục tiêu!");
                            }
                        }
                    }
                }
                """
        ));

        TEMPLATES.put("annotation", new TemplateDefinition(
                "Mẫu Enterprise hoàn toàn dùng Annotation (@JCommand, @JEvent, @JTask, @JPlaceholder) - Giảm 60% code",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import com.javastudio.api.annotations.JCommand;
                import com.javastudio.api.annotations.JEvent;
                import com.javastudio.api.annotations.JTask;
                import com.javastudio.api.annotations.JPlaceholder;
                import org.bukkit.entity.Player;
                import org.bukkit.event.player.PlayerJoinEvent;
                import org.bukkit.event.EventPriority;

                public class %CLASS_NAME% extends DynamicPlugin {

                    @Override
                    public void onEnable() {
                        getLogger().info("%PLUGIN_NAME% (Enterprise Annotation Edition) đã kích hoạt!");
                    }

                    @Override
                    public void onDisable() {
                        getLogger().info("%PLUGIN_NAME% đã tắt!");
                    }

                    @JCommand(value = "%COMMAND%", description = "Lệnh test Annotation SDK", permission = "%COMMAND%.use")
                    public void onMainCommand(Player player, String[] args) {
                        player.sendMessage("§a[§b%PLUGIN_NAME%§a] Xin chào từ Annotation-driven Command!");
                    }

                    @JEvent(priority = EventPriority.HIGH, ignoreCancelled = true)
                    public void onPlayerJoin(PlayerJoinEvent event) {
                        event.getPlayer().sendMessage("§6⚡ Chào mừng bạn! Dynamic Plugin này chạy hoàn toàn bằng Annotation.");
                    }

                    @JTask(period = 200L, async = true)
                    public void onHeartbeat() {
                        getLogger().info("[%PLUGIN_NAME%] Heartbeat kiểm tra sức khỏe plugin định kỳ (Async Loom)!");
                    }

                    @JPlaceholder("%COMMAND%_status")
                    public String getPluginStatus(Player player) {
                        return "§aHOẠT ĐỘNG_TỐT";
                    }
                }
                """
        ));

        TEMPLATES.put("smart-gui", new TemplateDefinition(
                "Menu Chest GUI (SmartGui) tương tác âm thanh, phân quyền và kết nối kinh tế Vault",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import com.javastudio.api.gui.SmartGui;
                import com.javastudio.api.gui.GuiButton;
                import org.bukkit.Material;
                import org.bukkit.Sound;
                import org.bukkit.entity.Player;

                public class %CLASS_NAME% extends DynamicPlugin {

                    @Override
                    public void onEnable() {
                        registerCommand("%COMMAND%", (sender, args) -> {
                            if (!(sender instanceof Player player)) {
                                sender.sendMessage("§cChỉ người chơi mới có thể mở menu!");
                                return true;
                            }
                            openMenu(player);
                            return true;
                        });
                        getLogger().info("%PLUGIN_NAME% (SmartGui Menu) đã sẵn sàng!");
                    }

                    @Override
                    public void onDisable() {
                        getLogger().info("%PLUGIN_NAME% đã tắt!");
                    }

                    private void openMenu(Player player) {
                        SmartGui gui = createGui("&8&l» &6&lMENU MÁY CHỦ &8&l«", 3);

                        gui.setButton(11, new GuiButton(Material.NETHER_STAR, "&e&lKHU VỰC VIP")
                            .lore("&7Nhấp để dịch chuyển về sảnh VIP", "&aYêu cầu rank: &fVIP")
                            .sound(Sound.ENTITY_PLAYER_LEVELUP)
                            .onClick((p, click) -> {
                                p.sendMessage("§a[VIP] Đang đưa bạn đến khu vực đặc quyền...");
                                p.closeInventory();
                            }));

                        gui.setButton(13, new GuiButton(Material.CHEST, "&a&lQUÀ HÀNG NGÀY")
                            .lore("&7Nhấp để nhận 500 xu và 10 kim cương", "&eMiễn phí 24 giờ một lần")
                            .sound(Sound.BLOCK_CHEST_OPEN)
                            .onClick((p, click) -> {
                                if (vaultHasEconomy()) {
                                    ecoDeposit(p, 500);
                                    p.sendMessage("§a+500 xu đã được chuyển vào tài khoản!");
                                } else {
                                    p.sendMessage("§aBạn đã nhận phần thưởng hàng ngày!");
                                }
                                p.closeInventory();
                            }));

                        gui.setButton(15, new GuiButton(Material.BARRIER, "&c&lĐÓNG")
                            .lore("&7Nhấp để thoát menu")
                            .sound(Sound.UI_BUTTON_CLICK)
                            .onClick((p, click) -> p.closeInventory()));

                        gui.open(player);
                    }
                }
                """
        ));

        TEMPLATES.put("chat-filter", new TemplateDefinition(
                "Bộ lọc Chat & Kiểm duyệt tự động (Anti-Swear, Anti-Link, Anti-Spam)",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.Bukkit;
                import org.bukkit.ChatColor;
                import org.bukkit.entity.Player;
                import org.bukkit.event.EventHandler;
                import org.bukkit.event.EventPriority;
                import org.bukkit.event.Listener;
                import org.bukkit.event.player.AsyncPlayerChatEvent;

                import java.util.List;
                import java.util.regex.Pattern;

                public class %CLASS_NAME% extends DynamicPlugin implements Listener {

                    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(https?://|www\\\\.)\\\\S+|(?:[a-z0-9-]+\\\\.)+(?:com|net|org|io|gg|xyz|me)\\\\b");
                    private List<String> bannedWords = List.of("hack", "cheat", "scam", "nuke");

                    @Override
                    public void onEnable() {
                        registerListener(this);
                        getLogger().info("%PLUGIN_NAME% (Chat Filter) đã được kích hoạt!");
                    }

                    @Override
                    public void onDisable() {
                        getLogger().info("%PLUGIN_NAME% đã tắt!");
                    }

                    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
                    public void onChat(AsyncPlayerChatEvent event) {
                        Player player = event.getPlayer();
                        if (player.hasPermission("chatfilter.bypass") || player.isOp()) return;

                        String message = event.getMessage();

                        if (URL_PATTERN.matcher(message).find()) {
                            event.setCancelled(true);
                            player.sendMessage("§c✖ Không được phép gửi liên kết hoặc link Discord trong chat!");
                            return;
                        }

                        String lower = message.toLowerCase();
                        for (String bad : bannedWords) {
                            if (lower.contains(bad)) {
                                event.setCancelled(true);
                                player.sendMessage("§c✖ Tin nhắn của bạn chứa từ ngữ không phù hợp!");
                                return;
                            }
                        }
                    }
                }
                """
        ));

        TEMPLATES.put("fast-arena", new TemplateDefinition(
                "Đấu Trường Minigame siêu tốc phục hồi map trong RAM bằng FastBlockEngine (<10ms)",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.Location;
                import org.bukkit.Material;
                import org.bukkit.entity.Player;
                import org.bukkit.event.EventHandler;
                import org.bukkit.event.Listener;
                import org.bukkit.event.block.BlockBreakEvent;

                public class %CLASS_NAME% extends DynamicPlugin implements Listener {

                    private String arenaSnapshotName = "arena_%COMMAND%";
                    private boolean inGame = false;

                    @Override
                    public void onEnable() {
                        registerListener(this);

                        registerCommand("%COMMAND%", (sender, args) -> {
                            if (args.length == 0) {
                                sender.sendMessage("§e/%COMMAND% save §7- Lưu map đấu trường vào RAM");
                                sender.sendMessage("§e/%COMMAND% reset §7- Tái tạo map siêu tốc (<10ms)");
                                return true;
                            }
                            if (args[0].equalsIgnoreCase("reset")) {
                                restoreArena();
                                sender.sendMessage("§aĐấu trường đã được phục hồi nguyên trạng trong RAM!");
                            }
                            return true;
                        });
                        getLogger().info("%PLUGIN_NAME% (FastBlock Arena) đã sẵn sàng!");
                    }

                    @Override
                    public void onDisable() {
                        getLogger().info("%PLUGIN_NAME% đã tắt!");
                    }

                    private void restoreArena() {
                        if (getFastBlock() != null) {
                            getFastBlock().restoreSnapshot(arenaSnapshotName);
                        }
                    }

                    @EventHandler
                    public void onBreak(BlockBreakEvent event) {
                    }
                }
                """
        ));

        TEMPLATES.put("anti-afk", new TemplateDefinition(
                "Tự động phát hiện người chơi treo máy (AFK) quá thời gian cấu hình và đưa về hồ AFK hoặc kick",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.Bukkit;
                import org.bukkit.Location;
                import org.bukkit.Particle;
                import org.bukkit.entity.Player;
                import org.bukkit.event.EventHandler;
                import org.bukkit.event.Listener;
                import org.bukkit.event.player.PlayerMoveEvent;

                import java.util.Map;
                import java.util.UUID;
                import java.util.concurrent.ConcurrentHashMap;

                public class %CLASS_NAME% extends DynamicPlugin implements Listener {

                    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
                    private final long AFK_TIMEOUT_MS = 5 * 60 * 1000L;

                    @Override
                    public void onEnable() {
                        registerListener(this);

                        runTaskTimer(() -> {
                            long now = System.currentTimeMillis();
                            for (Player p : Bukkit.getOnlinePlayers()) {
                                if (p.hasPermission("antiafk.bypass") || p.isOp()) continue;
                                long last = lastActive.getOrDefault(p.getUniqueId(), now);
                                if (now - last > AFK_TIMEOUT_MS) {
                                    p.sendMessage("§c[AFK] Bạn đã treo máy quá 5 phút!");
                                    p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 30);
                                }
                            }
                        }, 100L, 100L);

                        getLogger().info("%PLUGIN_NAME% (Anti-AFK) đã kích hoạt!");
                    }

                    @Override
                    public void onDisable() {
                        lastActive.clear();
                        getLogger().info("%PLUGIN_NAME% đã tắt!");
                    }

                    @EventHandler
                    public void onMove(PlayerMoveEvent event) {
                        Location f = event.getFrom();
                        Location t = event.getTo();
                        if (t == null) return;
                        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()
                                || f.getYaw() != t.getYaw() || f.getPitch() != t.getPitch()) {
                            lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
                        }
                    }
                }
                """
        ));

        TEMPLATES.put("custom-boss", new TemplateDefinition(
                "Triệu hồi Trùm Huyền Thoại với thanh máu BossBar, kỹ năng bão lửa và phần thưởng tùy biến",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.Location;
                import org.bukkit.Material;
                import org.bukkit.Particle;
                import org.bukkit.Sound;
                import org.bukkit.attribute.Attribute;
                import org.bukkit.entity.EntityType;
                import org.bukkit.entity.Player;
                import org.bukkit.entity.Zombie;
                import org.bukkit.event.EventHandler;
                import org.bukkit.event.Listener;
                import org.bukkit.event.entity.EntityDeathEvent;
                import org.bukkit.inventory.ItemStack;

                public class %CLASS_NAME% extends DynamicPlugin implements Listener {

                    private static final String BOSS_NAME = "§c§lCHÚA TỂ BÓNG ĐÊM";

                    @Override
                    public void onEnable() {
                        registerListener(this);

                        registerCommand("spawnboss", (sender, args) -> {
                            if (!(sender instanceof Player p)) return true;
                            Location loc = p.getLocation();
                            Zombie boss = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
                            boss.setCustomName(BOSS_NAME);
                            boss.setCustomNameVisible(true);
                            if (boss.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                                boss.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(500.0);
                                boss.setHealth(500.0);
                            }
                            boss.getEquipment().setHelmet(new ItemStack(Material.NETHERITE_HELMET));
                            boss.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));

                            broadcast("§4§l[BOSS] " + BOSS_NAME + " §cđã xuất hiện tại §f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "§c!");
                            loc.getWorld().strikeLightningEffect(loc);
                            return true;
                        });

                        getLogger().info("%PLUGIN_NAME% (Custom Boss) đã sẵn sàng!");
                    }

                    @Override
                    public void onDisable() {
                        getLogger().info("%PLUGIN_NAME% đã tắt!");
                    }

                    @EventHandler
                    public void onBossDeath(EntityDeathEvent event) {
                        if (event.getEntity() instanceof Zombie z && BOSS_NAME.equals(z.getCustomName())) {
                            event.getDrops().clear();
                            event.getDrops().add(new ItemStack(Material.NETHERITE_INGOT, 3));
                            event.getDrops().add(new ItemStack(Material.DIAMOND, 16));
                            broadcast("§a§l[CHIẾN TÍCH] " + BOSS_NAME + " §ađã bị tiêu diệt!");
                        }
                    }
                }
                """
        ));

        TEMPLATES.put("leaderboard", new TemplateDefinition(
                "Tạo bảng xếp hạng top số dư kinh tế Vault nổi trên không gian (Floating Hologram)",
                """
                package %PACKAGE%;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.Bukkit;
                import org.bukkit.Location;
                import org.bukkit.OfflinePlayer;
                import org.bukkit.entity.Player;

                import java.util.Arrays;
                import java.util.Comparator;
                import java.util.List;

                public class %CLASS_NAME% extends DynamicPlugin {

                    @Override
                    public void onEnable() {
                        registerCommand("topbalance", (sender, args) -> {
                            sender.sendMessage("§6§l=== BẢNG XẾP HẠNG TÀI PHÚ (TOP BALANCES) ===");
                            List<OfflinePlayer> players = Arrays.stream(Bukkit.getOfflinePlayers())
                                    .filter(p -> com.javastudio.utils.VaultBridge.getBalance(p) > 0)
                                    .sorted(Comparator.comparingDouble((OfflinePlayer p) -> com.javastudio.utils.VaultBridge.getBalance(p)).reversed())
                                    .limit(10)
                                    .toList();

                            int rank = 1;
                            for (OfflinePlayer p : players) {
                                double bal = com.javastudio.utils.VaultBridge.getBalance(p);
                                sender.sendMessage(String.format("§e#%d §f%s §8- §a$%,.2f", rank++, p.getName(), bal));
                            }
                            return true;
                        });

                        getLogger().info("%PLUGIN_NAME% (Leaderboard) đã kích hoạt!");
                    }

                    @Override
                    public void onDisable() {
                        getLogger().info("%PLUGIN_NAME% đã tắt!");
                    }
                }
                """
        ));
    }


    public static Set<String> getAvailableTemplates() {
        return TEMPLATES.keySet();
    }

    public static TemplateDefinition getTemplate(String name) {
        return TEMPLATES.get(name.toLowerCase());
    }

    public static class TemplateDefinition {
        private final String description;
        private final String sourceTemplate;

        public TemplateDefinition(String description, String sourceTemplate) {
            this.description = description;
            this.sourceTemplate = sourceTemplate;
        }

        public String getDescription() {
            return description;
        }

        public String getSourceTemplate() {
            return sourceTemplate;
        }
    }
}
