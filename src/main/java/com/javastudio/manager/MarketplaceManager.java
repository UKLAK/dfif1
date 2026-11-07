package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public final class MarketplaceManager {

    private final JavaStudioPlugin plugin;

    public record MarketPackage(
            String id,
            String name,
            String version,
            String author,
            String description,
            String category,
            String sourceCode
    ) {}

    private final Map<String, MarketPackage> registry = new LinkedHashMap<>();

    public MarketplaceManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        registerBuiltinPackages();
    }

    private void registerBuiltinPackages() {
        register(new MarketPackage(
                "tree-feller",
                "TreeFeller",
                "1.0.0",
                "JavaStudio Team",
                "Tự động đốn hạ toàn bộ thân cây khi người chơi chặt 1 khúc gỗ bằng rìu.",
                "Gameplay",
                """
                package com.javastudio.projects;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                import org.bukkit.entity.Player;
                import org.bukkit.event.EventHandler;
                import org.bukkit.event.Listener;
                import org.bukkit.event.block.BlockBreakEvent;

                import java.util.*;

                public class TreeFeller extends DynamicPlugin implements Listener {

                    @Override
                    public void onEnable() {
                        registerListener(this);
                        getLogger().info("TreeFeller đã kích hoạt!");
                    }

                    @EventHandler
                    public void onBlockBreak(BlockBreakEvent event) {
                        Player player = event.getPlayer();
                        Block start = event.getBlock();
                        String mat = start.getType().name();

                        if (!mat.endsWith("_LOG")) return;
                        if (!player.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) return;

                        Queue<Block> queue = new LinkedList<>();
                        Set<Block> visited = new HashSet<>();
                        queue.add(start);
                        visited.add(start);

                        int broken = 0;
                        while (!queue.isEmpty() && broken < 64) {
                            Block current = queue.poll();
                            for (int x = -1; x <= 1; x++) {
                                for (int y = -1; y <= 1; y++) {
                                    for (int z = -1; z <= 1; z++) {
                                        Block relative = current.getRelative(x, y, z);
                                        if (relative.getType() == start.getType() && visited.add(relative)) {
                                            queue.add(relative);
                                            relative.breakNaturally(player.getInventory().getItemInMainHand());
                                            broken++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                """
        ));

        register(new MarketPackage(
                "custom-enchants",
                "CustomEnchants",
                "1.0.0",
                "JavaStudio Team",
                "Hiệu ứng phù phép đặc biệt: Kiếm sét khi đánh và Cung bắn tên phát nổ.",
                "Combat",
                """
                package com.javastudio.projects;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.Particle;
                import org.bukkit.Sound;
                import org.bukkit.entity.Arrow;
                import org.bukkit.entity.LivingEntity;
                import org.bukkit.entity.Player;
                import org.bukkit.event.EventHandler;
                import org.bukkit.event.Listener;
                import org.bukkit.event.entity.EntityDamageByEntityEvent;
                import org.bukkit.event.entity.ProjectileHitEvent;

                public class CustomEnchants extends DynamicPlugin implements Listener {

                    @Override
                    public void onEnable() {
                        registerListener(this);
                        getLogger().info("CustomEnchants đã sẵn sàng!");
                    }

                    @EventHandler
                    public void onHit(EntityDamageByEntityEvent event) {
                        if (event.getDamager() instanceof Player player && event.getEntity() instanceof LivingEntity target) {
                            String item = player.getInventory().getItemInMainHand().getType().name();
                            if (item.contains("SWORD") && Math.random() < 0.25) {
                                target.getWorld().strikeLightningEffect(target.getLocation());
                                target.damage(4.0, player);
                                player.sendMessage("§e⚡ Kiếm Lôi Thần giáng sét trúng mục tiêu!");
                            }
                        }
                    }

                    @EventHandler
                    public void onArrowHit(ProjectileHitEvent event) {
                        if (event.getEntity() instanceof Arrow arrow && arrow.getShooter() instanceof Player shooter) {
                            arrow.getWorld().spawnParticle(Particle.EXPLOSION, arrow.getLocation(), 2);
                            arrow.getWorld().playSound(arrow.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
                        }
                    }
                }
                """
        ));

        register(new MarketPackage(
                "anti-lag-purge",
                "AntiLagPurge",
                "1.0.0",
                "JavaStudio Team",
                "Tự động phát hiện khi TPS tụt dưới 18 để dọn dẹp các entity rác làm lag máy chủ.",
                "Optimization",
                """
                package com.javastudio.projects;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.Bukkit;
                import org.bukkit.World;
                import org.bukkit.entity.Item;
                import org.bukkit.entity.Monster;

                public class AntiLagPurge extends DynamicPlugin {

                    @Override
                    public void onEnable() {
                        runTaskTimer(() -> {
                            double[] tps = getServerController().getRecentTps();
                            if (tps[0] < 18.0) {
                                int cleared = 0;
                                for (World w : Bukkit.getWorlds()) {
                                    for (var e : w.getEntities()) {
                                        if (e instanceof Item) {
                                            e.remove();
                                            cleared++;
                                        }
                                    }
                                }
                                if (cleared > 0) {
                                    Bukkit.broadcastMessage("§c[AntiLag] §eĐã dọn dẹp " + cleared + " items rơi để khôi phục TPS server!");
                                }
                            }
                        }, 200L, 600L);
                    }
                }
                """
        ));

        register(new MarketPackage(
                "auto-broadcast",
                "AutoBroadcast",
                "1.0.0",
                "JavaStudio Team",
                "Tự động phát thông báo định kỳ kèm âm thanh thông báo cho toàn máy chủ.",
                "Utility",
                """
                package com.javastudio.projects;

                import com.javastudio.api.DynamicPlugin;
                import org.bukkit.Bukkit;
                import org.bukkit.Sound;
                import org.bukkit.entity.Player;

                public class AutoBroadcast extends DynamicPlugin {

                    private final String[] messages = new String[] {
                        "§6[THÔNG BÁO] §fTham gia Discord cộng đồng để nhận quà hàng tuần!",
                        "§6[THÔNG BÁO] §fGõ §e/help §fđể xem danh sách các hướng dẫn cho tân thủ.",
                        "§6[THÔNG BÁO] §fĐừng quên vote cho máy chủ để nhận chìa khóa rương báu!"
                    };
                    private int index = 0;

                    @Override
                    public void onEnable() {
                        runTaskTimer(() -> {
                            String msg = messages[index % messages.length];
                            index++;
                            for (Player p : Bukkit.getOnlinePlayers()) {
                                p.sendMessage(msg);
                                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
                            }
                        }, 100L, 2400L);
                    }
                }
                """
        ));
    }

    public final void register(MarketPackage pkg) {
        registry.put(pkg.id().toLowerCase(), pkg);
    }

    public List<MarketPackage> getAllPackages() {
        return new ArrayList<>(registry.values());
    }

    public MarketPackage getPackage(String id) {
        if (id == null) return null;
        return registry.get(id.toLowerCase());
    }

    public boolean installPackage(String packageId, StringBuilder log) {
        MarketPackage pkg = getPackage(packageId);
        if (pkg == null) {
            if (log != null) log.append("Không tìm thấy package: ").append(packageId);
            return false;
        }

        String projectName = pkg.name();
        File projectsDir = plugin.getPluginManager().getProjectsFolder();
        File projectDir = new File(projectsDir, projectName);
        File srcDir = new File(projectDir, "src" + File.separator + "com" + File.separator + "javastudio" + File.separator + "projects");
        srcDir.mkdirs();

        File mainJava = new File(srcDir, projectName + ".java");
        try {
            Files.writeString(mainJava.toPath(), pkg.sourceCode(), StandardCharsets.UTF_8);

            File metaFile = new File(projectDir, "metadata.yml");
            YamlConfiguration meta = new YamlConfiguration();
            meta.set("name", projectName);
            meta.set("main", "com.javastudio.projects." + projectName);
            meta.set("version", pkg.version());
            meta.set("author", pkg.author());
            meta.set("description", pkg.description());
            meta.save(metaFile);

            File dataDir = new File(projectDir, "data");
            dataDir.mkdirs();
            File configFile = new File(dataDir, "config.yml");
            if (!configFile.exists()) {
                YamlConfiguration cfg = new YamlConfiguration();
                cfg.set("enabled", true);
                cfg.save(configFile);
            }
        } catch (IOException e) {
            if (log != null) log.append("Lỗi tạo mã nguồn hoặc metadata: ").append(e.getMessage());
            return false;
        }

        boolean success = plugin.getPluginManager().loadPlugin(projectName, log);
        if (success && log != null) {
            log.append("Đã cài đặt và kích hoạt thành công package: ").append(projectName);
        }
        return success;
    }
}
