package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
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

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class XrayHoneypotManager {

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    private static final String METADATA_KEY = "JAC_XRAY_HONEYPOT";
    private static final BlockFace[] ADJACENT_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final Map<UUID, Location> activeHoneypots = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private double maxOreRatio = 0.05;

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

    public void processBlockBreak(Player p, PlayerData d, Block b, BlockBreakEvent event) {
        if (!enabled || !manager.isActive() || !manager.isEnabled(CheckType.XRAY)) return;
        if (manager.isBypassed(p, d)) return;

        Material mat = b.getType();

        if (b.hasMetadata(METADATA_KEY) || (d.activeHoneypotLoc != null && isSameLocation(d.activeHoneypotLoc, b.getLocation()))) {
            event.setDropItems(false);
            event.setExpToDrop(0);
            b.removeMetadata(METADATA_KEY, plugin);
            d.activeHoneypotLoc = null;
            activeHoneypots.remove(p.getUniqueId());

            manager.flag(p, d, CheckType.XRAY, "Đào trúng quặng Honeypot ẩn sâu 6 mặt đá (100% X-Ray)", 6.0);

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

        boolean isStone = isStoneType(mat);
        boolean isPrecious = isPreciousOre(mat);

        if (isStone) {
            d.stoneMinedCount++;
        } else if (isPrecious) {
            Location bLoc = b.getLocation();
            boolean newVein = d.lastOreVeinLoc == null
                    || d.lastOreVeinLoc.getWorld() != bLoc.getWorld()
                    || d.lastOreVeinLoc.distanceSquared(bLoc) > 16.0;
            d.lastOreVeinLoc = bLoc;

            if (newVein && !isCaveExposed(b)) {
                d.preciousOreCount++;
                d.lastOreMinedTime = System.currentTimeMillis();

                if (d.stoneMinedCount >= 60 && d.preciousOreCount >= 4) {
                    double ratio = (double) d.preciousOreCount / d.stoneMinedCount;
                    if (ratio > maxOreRatio) {
                        String detail = String.format(Locale.ROOT, "Tỷ lệ mạch quặng ẩn bất thường %.1f%% (%d mạch ẩn / %d đá)",
                                ratio * 100.0, d.preciousOreCount, d.stoneMinedCount);
                        manager.flag(p, d, CheckType.XRAY, detail, 1.0);
                    }
                }
            }
        }

        if (d.stoneMinedCount >= 180) {
            d.stoneMinedCount = Math.max(30, d.stoneMinedCount - 60);
            d.preciousOreCount = Math.max(0, d.preciousOreCount - 2);
        }

        if (isStone) {
            checkSpawnHoneypot(p, d, b);
        }
    }

    private boolean isCaveExposed(Block ore) {
        World w = ore.getWorld();
        int airNeighbors = 0;
        for (BlockFace face : ADJACENT_FACES) {
            int nx = ore.getX() + face.getModX();
            int ny = ore.getY() + face.getModY();
            int nz = ore.getZ() + face.getModZ();
            if (!AcUtil.loaded(w, nx, nz)) continue;
            Block adj = w.getBlockAt(nx, ny, nz);
            if (adj.getType().isAir() || adj.isLiquid()) {
                airNeighbors++;
            }
        }
        return airNeighbors >= 2;
    }

    private void checkSpawnHoneypot(Player p, PlayerData d, Block broken) {
        World world = broken.getWorld();
        int y = broken.getY();

        boolean isNether = world.getEnvironment() == World.Environment.NETHER;
        if (!isNether && y > 20) return;
        if (isNether && y > 115) return;

        long now = System.currentTimeMillis();
        if (d.activeHoneypotLoc != null && now - d.honeypotCreatedTime < 120_000L) {
            return;
        }

        cleanupPlayer(p.getUniqueId());

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int bx = broken.getX();
        int by = broken.getY();
        int bz = broken.getZ();

        for (int attempts = 0; attempts < 12; attempts++) {
            int ox = rnd.nextInt(-11, 12);
            int oz = rnd.nextInt(-11, 12);
            int oy = rnd.nextInt(-3, 4);

            if (Math.abs(ox) < 6 && Math.abs(oz) < 6) continue;

            int tx = bx + ox;
            int ty = by + oy;
            int tz = bz + oz;
            if (!AcUtil.loaded(world, tx - 1, tz - 1) || !AcUtil.loaded(world, tx + 1, tz + 1)) {
                continue;
            }
            if (ty <= world.getMinHeight() + 2 || ty >= world.getMaxHeight() - 2) {
                continue;
            }

            Block candidate = world.getBlockAt(tx, ty, tz);
            Material cMat = candidate.getType();

            if (isNether ? cMat != Material.NETHERRACK : (cMat != Material.DEEPSLATE && cMat != Material.STONE && cMat != Material.TUFF)) {
                continue;
            }

            boolean fullyEncapsulated = true;
            for (BlockFace face : ADJACENT_FACES) {
                Block adj = candidate.getRelative(face);
                if (!adj.getType().isSolid() || adj.getType().isAir() || adj.isLiquid() || adj.getLightLevel() > 0) {
                    fullyEncapsulated = false;
                    break;
                }
            }

            if (fullyEncapsulated) {
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
            World w = loc.getWorld();
            int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
            if (!AcUtil.loaded(w, bx, bz)) return;
            if (PlatformUtil.isFolia()) {
                PlatformUtil.runAtLocation(plugin, loc, () -> restoreHoneypotBlock(w, bx, by, bz));
            } else {
                restoreHoneypotBlock(w, bx, by, bz);
            }
        }
    }

    public void cleanupAll() {
        for (UUID id : new ArrayList<>(activeHoneypots.keySet())) {
            Location loc = activeHoneypots.remove(id);
            if (loc != null && loc.getWorld() != null) {
                World w = loc.getWorld();
                int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
                if (AcUtil.loaded(w, bx, bz)) {
                    restoreHoneypotBlock(w, bx, by, bz);
                }
            }
        }
        activeHoneypots.clear();
    }

    private void restoreHoneypotBlock(World w, int bx, int by, int bz) {
        try {
            if (!AcUtil.loaded(w, bx, bz)) return;
            Block b = w.getBlockAt(bx, by, bz);
            if (b.hasMetadata(METADATA_KEY)) {
                b.removeMetadata(METADATA_KEY, plugin);
                boolean isNether = w.getEnvironment() == World.Environment.NETHER;
                b.setType(isNether ? Material.NETHERRACK : (by <= 0 ? Material.DEEPSLATE : Material.STONE), false);
            }
        } catch (Throwable ignored) {}
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
