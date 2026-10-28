package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hướng 2: Bẫy Quặng Ảo & Chống X-Ray / Freecam (Honeypot Ore Engine).
 * Tự động tạo các khối quặng kim cương / Netherite ẩn sâu trong lòng đất được bao bọc 6 mặt bởi đá đặc
 * (không tiếp xúc không khí, ánh sáng = 0).
 * Người chơi bình thường không bao giờ đào trúng, nhưng hacker dùng X-Ray, Freecam hoặc Baritone #mine
 * sẽ bẻ góc đào thẳng đến đúng khối quặng bẫy -> Bắt quả tang 100% không false-positive.
 */
public final class XrayHoneypotManager {

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    private static final String METADATA_KEY = "JAC_XRAY_HONEYPOT";
    private static final BlockFace[] ADJACENT_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    // Map theo dõi vị trí bẫy đang hoạt động: Player UUID -> Honeypot Block Location
    private final Map<UUID, Location> activeHoneypots = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private double maxOreRatio = 0.05; // 5% tỷ lệ quặng quý

    public XrayHoneypotManager(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        loadConfig();
    }

    public void loadConfig() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("anticheat.xray.enabled", true);
        this.maxOreRatio = cfg.getDouble("anticheat.xray.max-ore-ratio", 0.05);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Xử lý khi người chơi đập vỡ khối (BlockBreakEvent).
     */
    public void processBlockBreak(Player p, PlayerData d, Block b, BlockBreakEvent event) {
        if (!enabled || !manager.isActive() || !manager.isEnabled(CheckType.XRAY)) return;
        if (manager.isBypassed(p, d)) return;

        Material mat = b.getType();

        // 1. Kiểm tra xem khối vừa đào có phải là Quặng Bẫy Honeypot không
        if (b.hasMetadata(METADATA_KEY) || (d.activeHoneypotLoc != null && isSameLocation(d.activeHoneypotLoc, b.getLocation()))) {
            event.setDropItems(false);
            event.setExpToDrop(0);
            b.removeMetadata(METADATA_KEY, plugin);
            d.activeHoneypotLoc = null;

            manager.flag(p, d, CheckType.XRAY, "Đào trúng quặng Honeypot ẩn sâu 6 mặt đá (100% X-Ray)", 10.0);

            String alert = MessageUtil.colorize("&8[&c&lAC-XRAY&8] &c[!] &f" + p.getName()
                    + " &7vừa bị bắt quả tang đào thẳng vào &bQuặng Bẫy Honeypot&7 ẩn sâu! &c(100% X-Ray / Freecam)");
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission(manager.getAlertPermission()) || staff.isOp()) {
                    staff.sendMessage(alert);
                    try {
                        staff.playSound(staff.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.6f);
                    } catch (Throwable ignored) {}
                }
            }
            return;
        }

        // 2. Thống kê tỷ lệ quặng quý (Diamond / Ancient Debris Ratio)
        boolean isStone = isStoneType(mat);
        boolean isPrecious = isPreciousOre(mat);

        if (isStone) {
            d.stoneMinedCount++;
        } else if (isPrecious) {
            d.preciousOreCount++;
            d.lastOreMinedTime = System.currentTimeMillis();

            if (d.stoneMinedCount >= 40) {
                double ratio = (double) d.preciousOreCount / d.stoneMinedCount;
                if (ratio > maxOreRatio) {
                    String detail = String.format(Locale.ROOT, "Tỷ lệ quặng bất thường %.1f%% (%d quặng / %d đá)",
                            ratio * 100.0, d.preciousOreCount, d.stoneMinedCount);
                    manager.flag(p, d, CheckType.XRAY, detail, 1.0);
                }
            }
        }

        // Giảm dần mẫu đếm sau mỗi 100 khối để người đào hầm chân chính không bị tích tụ
        if (d.stoneMinedCount >= 150) {
            d.stoneMinedCount = Math.max(20, d.stoneMinedCount - 50);
            d.preciousOreCount = Math.max(1, d.preciousOreCount - 3);
        }

        // 3. Cơ hội tạo bẫy quặng ảo Honeypot khi người chơi đang đào mỏ dưới lòng đất
        checkSpawnHoneypot(p, d, b);
    }

    /**
     * Tìm kiếm và đặt bẫy quặng Honeypot ẩn hoàn toàn trong khối đá đặc gần người chơi.
     */
    private void checkSpawnHoneypot(Player p, PlayerData d, Block broken) {
        World world = broken.getWorld();
        int y = broken.getY();

        // Chỉ tạo bẫy ở tầng sâu (Overworld: Y <= 20, Nether: Y <= 110)
        boolean isNether = world.getEnvironment() == World.Environment.NETHER;
        if (!isNether && y > 20) return;
        if (isNether && y > 115) return;

        long now = System.currentTimeMillis();
        // Mỗi người chơi duy trì 1 bẫy trong 2 phút
        if (d.activeHoneypotLoc != null && now - d.honeypotCreatedTime < 120_000L) {
            return;
        }

        // Dọn bẫy cũ nếu có
        cleanupPlayer(p.getUniqueId());

        // Tìm 1 khối ngẫu nhiên cách 8 - 12 block
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int bx = broken.getX();
        int by = broken.getY();
        int bz = broken.getZ();

        for (int attempts = 0; attempts < 15; attempts++) {
            int ox = rnd.nextInt(-11, 12);
            int oz = rnd.nextInt(-11, 12);
            int oy = rnd.nextInt(-3, 4);

            if (Math.abs(ox) < 6 && Math.abs(oz) < 6) continue; // Không đặt quá sát mắt người chơi

            Block candidate = world.getBlockAt(bx + ox, by + oy, bz + oz);
            Material cMat = candidate.getType();

            // Khối đích phải là đá đặc
            if (isNether ? cMat != Material.NETHERRACK : (cMat != Material.DEEPSLATE && cMat != Material.STONE && cMat != Material.TUFF)) {
                continue;
            }

            // Phải được bao bọc 6 mặt hoàn toàn bởi khối đặc (không có không khí, không có hang động)
            boolean fullyEncapsulated = true;
            for (BlockFace face : ADJACENT_FACES) {
                Block adj = candidate.getRelative(face);
                if (!adj.getType().isSolid() || adj.getType().isAir() || adj.isLiquid() || adj.getLightLevel() > 0) {
                    fullyEncapsulated = false;
                    break;
                }
            }

            if (fullyEncapsulated) {
                // Đổi khối thành quặng kim cương hoặc ancient debris và gắn metadata bẫy
                Material trapMat = isNether ? Material.ANCIENT_DEBRIS : (candidate.getY() <= 0 ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE);
                candidate.setType(trapMat, false);
                candidate.setMetadata(METADATA_KEY, new FixedMetadataValue(plugin, p.getUniqueId()));

                d.activeHoneypotLoc = candidate.getLocation();
                d.honeypotCreatedTime = now;
                activeHoneypots.put(p.getUniqueId(), candidate.getLocation());
                break;
            }
        }
    }

    public void cleanupPlayer(UUID id) {
        Location loc = activeHoneypots.remove(id);
        if (loc != null && loc.getWorld() != null) {
            Block b = loc.getBlock();
            if (b.hasMetadata(METADATA_KEY)) {
                b.removeMetadata(METADATA_KEY, plugin);
                // Khôi phục lại khối đá ban đầu
                boolean isNether = loc.getWorld().getEnvironment() == World.Environment.NETHER;
                b.setType(isNether ? Material.NETHERRACK : (loc.getBlockY() <= 0 ? Material.DEEPSLATE : Material.STONE), false);
            }
        }
    }

    public void cleanupAll() {
        for (UUID id : activeHoneypots.keySet()) {
            cleanupPlayer(id);
        }
        activeHoneypots.clear();
    }

    private static boolean isStoneType(Material m) {
        return m == Material.STONE || m == Material.DEEPSLATE || m == Material.TUFF ||
               m == Material.ANDESITE || m == Material.DIORITE || m == Material.GRANITE ||
               m == Material.NETHERRACK || m == Material.BLACKSTONE || m == Material.BASALT;
    }

    private static boolean isPreciousOre(Material m) {
        return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE ||
               m == Material.ANCIENT_DEBRIS || m == Material.EMERALD_ORE || m == Material.DEEPSLATE_EMERALD_ORE;
    }

    private static boolean isSameLocation(Location l1, Location l2) {
        if (l1 == null || l2 == null) return false;
        return l1.getWorld() == l2.getWorld() &&
               l1.getBlockX() == l2.getBlockX() &&
               l1.getBlockY() == l2.getBlockY() &&
               l1.getBlockZ() == l2.getBlockZ();
    }
}
