package com.javastudio.templates;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Các mẫu Dynamic Plugin hoàn chỉnh sẵn sàng sử dụng ngay lập tức.
 */
public class PluginTemplates {

    private static final Map<String, TemplateDefinition> TEMPLATES = new HashMap<>();

    static {
        // 1. Standard Template
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

                        // Đăng ký Event Listener
                        registerListener(this);

                        // Đăng ký lệnh /%COMMAND%
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

        // 2. Teleport Bow Template
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

                        // Lệnh nhận cung dịch chuyển
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

        // 3. Auto Broadcast Template
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

                        // Chạy tác vụ lặp định kỳ
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

        // 4. Custom Item Template
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

        // 5. Zero-Boilerplate Annotation Template
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
