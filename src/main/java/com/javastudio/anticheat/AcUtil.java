package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class AcUtil {

    private AcUtil() {
    }

    public static final class Env {
        public boolean liquid;
        public boolean climbable;
        public boolean web;
        public boolean bounce;
        public boolean ice;
        public boolean ceiling;
        public boolean liquidBelow;
        public boolean bubble;
        public boolean soulSand;
        public boolean softLanding;
        public boolean piston;
        public boolean halfBlock;

        public boolean special() {
            return liquid || climbable || web || bounce || bubble || piston;
        }
    }

    private static final Map<String, PotionEffectType> EFFECT_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Object>> ATTR_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Enchantment>> ENCHANT_CACHE = new ConcurrentHashMap<>();

    public static boolean loaded(World w, int bx, int bz) {
        return w != null && w.isChunkLoaded(bx >> 4, bz >> 4);
    }

    public static boolean isBedrockPlayer(Player p) {
        if (p == null) return false;
        if (p.getUniqueId().getMostSignificantBits() == 0L) return true;
        String name = p.getName();
        return name != null && name.startsWith(".");
    }

    public static boolean hasGround(Location loc, double below, double above) {
        World w = loc.getWorld();
        if (w == null) return false;
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();
        int minX = (int) Math.floor(x - 0.3), maxX = (int) Math.floor(x + 0.3);
        int minZ = (int) Math.floor(z - 0.3), maxZ = (int) Math.floor(z + 0.3);
        int minY = (int) Math.floor(y - below) - 1, maxY = (int) Math.floor(y + above);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!loaded(w, bx, bz)) continue;
                for (int by = minY; by <= maxY; by++) {
                    Block b = w.getBlockAt(bx, by, bz);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (m == Material.SCAFFOLDING || m == Material.POWDER_SNOW) {
                        double top = by + 1.0;
                        if (top >= y - below && top <= y + above) return true;
                        continue;
                    }
                    if (m == Material.LILY_PAD) {
                        double top = by + 0.09375;
                        if (top >= y - below && top <= y + above) return true;
                        continue;
                    }
                    if (b.isPassable()) continue;
                    if (m.isOccluding()) {
                        double top = by + 1.0;
                        if (top >= y - below && top <= y + above) return true;
                        continue;
                    }
                    Collection<BoundingBox> boxes = b.getCollisionShape().getBoundingBoxes();
                    for (BoundingBox bb : boxes) {
                        if (bx + bb.getMinX() < x + 0.3 && bx + bb.getMaxX() > x - 0.3
                                && bz + bb.getMinZ() < z + 0.3 && bz + bb.getMaxZ() > z - 0.3) {
                            double top = by + bb.getMaxY();
                            if (top >= y - below && top <= y + above) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public static Env env(Location loc) {
        Env e = new Env();
        World w = loc.getWorld();
        if (w == null) return e;
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();
        int minX = (int) Math.floor(x - 0.32), maxX = (int) Math.floor(x + 0.32);
        int minZ = (int) Math.floor(z - 0.32), maxZ = (int) Math.floor(z + 0.32);
        int feetY = (int) Math.floor(y);
        int minY = (int) Math.floor(y - 0.85), maxY = (int) Math.floor(y + 1.85);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!loaded(w, bx, bz)) continue;
                for (int by = minY; by <= maxY; by++) {
                    Block b = w.getBlockAt(bx, by, bz);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    boolean body = by >= feetY && by <= feetY + 1;
                    classify(e, b, m, body, by <= feetY);
                }
            }
        }
        int ceilY = (int) Math.floor(y + 1.95);
        for (int bx = minX; bx <= maxX && !e.ceiling; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!loaded(w, bx, bz)) continue;
                Block b = w.getBlockAt(bx, ceilY, bz);
                if (!b.getType().isAir() && !b.isPassable()) {
                    e.ceiling = true;
                    break;
                }
            }
        }
        return e;
    }

    private static void classify(Env e, Block b, Material m, boolean body, boolean atOrBelowFeet) {
        if (m == Material.WATER || m == Material.LAVA) {
            if (body) e.liquid = true;
            if (atOrBelowFeet) e.liquidBelow = true;
            e.softLanding = true;
            return;
        }
        if (m == Material.BUBBLE_COLUMN) {
            e.bubble = true;
            e.liquid = true;
            e.softLanding = true;
            return;
        }
        if (m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) {
            if (body) e.liquid = true;
            return;
        }
        if (Tag.CLIMBABLE.isTagged(m) || m == Material.SCAFFOLDING) {
            e.climbable = true;
            e.softLanding = true;
            return;
        }
        if (m == Material.COBWEB || m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW) {
            e.web = true;
            e.softLanding = true;
            return;
        }
        if (m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK || Tag.BEDS.isTagged(m)) {
            e.bounce = true;
            e.softLanding = true;
            if (Tag.BEDS.isTagged(m)) e.halfBlock = true;
            return;
        }
        if (m == Material.HAY_BLOCK) {
            e.softLanding = true;
        }
        if (m == Material.SOUL_SAND || m == Material.SOUL_SOIL) {
            e.soulSand = true;
            if (m == Material.SOUL_SAND) e.halfBlock = true;
        }
        if (m == Material.MOVING_PISTON || m == Material.PISTON_HEAD || m == Material.PISTON || m == Material.STICKY_PISTON) {
            e.piston = true;
        }
        if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE) {
            e.ice = true;
        }
        String name = m.name();
        if (name.endsWith("_SLAB") || name.endsWith("_STAIRS") || name.contains("SNOW")
                || name.endsWith("_CARPET") || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE")
                || name.endsWith("_WALL") || m == Material.DIRT_PATH || m == Material.FARMLAND
                || m == Material.LECTERN || m == Material.ENCHANTING_TABLE || m == Material.STONECUTTER
                || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.LANTERN
                || m == Material.SOUL_LANTERN || m == Material.CHEST || m == Material.TRAPPED_CHEST
                || m == Material.ENDER_CHEST || m == Material.DECORATED_POT || m == Material.BREWING_STAND
                || m == Material.CAULDRON || m == Material.WATER_CAULDRON || m == Material.ANVIL
                || m == Material.CHIPPED_ANVIL || m == Material.DAMAGED_ANVIL || m == Material.FLOWER_POT
                || m == Material.AMETHYST_CLUSTER || m == Material.SCULK_SENSOR || m == Material.CALIBRATED_SCULK_SENSOR
                || m == Material.SCULK_SHRIEKER || m == Material.MUD) {
            e.halfBlock = true;
        }
        if ((body || atOrBelowFeet) && b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) {
            if (body) e.liquid = true;
            if (atOrBelowFeet) e.liquidBelow = true;
            e.softLanding = true;
        }
    }

    public static boolean standingOnEntity(Player p) {
        try {
            for (Entity en : p.getNearbyEntities(0.85, 1.35, 0.85)) {
                if (en instanceof org.bukkit.entity.Vehicle || en instanceof org.bukkit.entity.Shulker
                        || en instanceof org.bukkit.entity.Strider
                        || en.getType().name().contains("GHAST")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean hasWallAround(Location loc, double radius) {
        World w = loc.getWorld();
        if (w == null) return false;
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();
        int minX = (int) Math.floor(x - radius), maxX = (int) Math.floor(x + radius);
        int minZ = (int) Math.floor(z - radius), maxZ = (int) Math.floor(z + radius);
        int minY = (int) Math.floor(y + 0.1), maxY = (int) Math.floor(y + 1.7);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!loaded(w, bx, bz)) continue;
                for (int by = minY; by <= maxY; by++) {
                    Block b = w.getBlockAt(bx, by, bz);
                    if (!b.getType().isAir() && !b.isPassable()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    public static PotionEffectType effectType(String... names) {
        String key = names[0];
        PotionEffectType cached = EFFECT_CACHE.get(key);
        if (cached != null) return cached;
        for (String n : names) {
            try {
                PotionEffectType t = PotionEffectType.getByName(n);
                if (t != null) {
                    EFFECT_CACHE.put(key, t);
                    return t;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static int effectLevel(Player p, PotionEffectType type) {
        if (type == null || p == null) return 0;
        PotionEffect e = p.getPotionEffect(type);
        return e == null ? 0 : e.getAmplifier() + 1;
    }

    public static int jumpBoost(Player p) {
        return effectLevel(p, effectType("JUMP_BOOST", "JUMP"));
    }

    public static boolean hasEffect(Player p, String... names) {
        return effectLevel(p, effectType(names)) > 0;
    }

    private static Object resolveAttr(String key, String... fieldNames) {
        Optional<Object> cached = ATTR_CACHE.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        Object found = null;
        for (String name : fieldNames) {
            try {
                Field f = Attribute.class.getField(name);
                Object o = f.get(null);
                if (o != null) {
                    found = o;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        ATTR_CACHE.put(key, Optional.ofNullable(found));
        return found;
    }

    public static double attrValue(Player p, double def, String key, String... fieldNames) {
        if (p == null) return def;
        Object attr = resolveAttr(key, fieldNames);
        if (attr != null) {
            try {
                AttributeInstance inst = p.getAttribute((Attribute) attr);
                if (inst != null) {
                    return inst.getValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    public static double speedRatio(Player p) {
        Object attr = resolveAttr("speed", "MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED");
        if (attr != null) {
            try {
                AttributeInstance inst = p.getAttribute((Attribute) attr);
                if (inst != null) {
                    double attrRatio = inst.getValue() / 0.1;
                    double walkRatio = p.getWalkSpeed() / 0.2;
                    return Math.max(0.1, Math.max(attrRatio, p.isSprinting() ? walkRatio * 1.3 : walkRatio));
                }
            } catch (Throwable ignored) {
            }
        }
        double speed = 1.0 + 0.2 * effectLevel(p, effectType("SPEED"));
        speed -= 0.15 * effectLevel(p, effectType("SLOWNESS", "SLOW"));
        speed = Math.max(0.1, speed);
        double ratio = speed * (p.getWalkSpeed() / 0.2);
        return p.isSprinting() ? ratio * 1.3 : ratio;
    }

    public static double stepHeight(Player p) {
        return Math.max(0.6, attrValue(p, 0.6, "step", "STEP_HEIGHT", "GENERIC_STEP_HEIGHT"));
    }

    public static double jumpStrength(Player p) {
        return Math.max(0.42, attrValue(p, 0.42, "jump", "JUMP_STRENGTH", "GENERIC_JUMP_STRENGTH"));
    }

    public static double gravity(Player p) {
        return attrValue(p, 0.08, "gravity", "GRAVITY", "GENERIC_GRAVITY");
    }

    public static double entityReach(Player p) {
        return attrValue(p, 3.0, "ereach", "ENTITY_INTERACTION_RANGE", "PLAYER_ENTITY_INTERACTION_RANGE");
    }

    public static double blockReach(Player p) {
        return attrValue(p, 4.5, "breach", "BLOCK_INTERACTION_RANGE", "PLAYER_BLOCK_INTERACTION_RANGE");
    }

    public static double knockbackResistance(Player p) {
        return Math.max(0.0, Math.min(1.0, attrValue(p, 0.0, "kbres", "KNOCKBACK_RESISTANCE", "GENERIC_KNOCKBACK_RESISTANCE")));
    }

    @SuppressWarnings("deprecation")
    public static int enchantLevel(ItemStack item, String... names) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
        String key = names[0];
        Optional<Enchantment> cached = ENCHANT_CACHE.get(key);
        Enchantment ench = null;
        if (cached != null) {
            ench = cached.orElse(null);
        } else {
            for (String n : names) {
                try {
                    Field f = Enchantment.class.getField(n);
                    Object o = f.get(null);
                    if (o instanceof Enchantment ec) {
                        ench = ec;
                        break;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    Enchantment byKey = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(n.toLowerCase(Locale.ROOT)));
                    if (byKey != null) {
                        ench = byKey;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            ENCHANT_CACHE.put(key, Optional.ofNullable(ench));
        }
        if (ench == null) return 0;
        try {
            return item.getEnchantmentLevel(ench);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static int bootsEnchantLevel(Player p, String... names) {
        if (p == null || p.getInventory() == null) return 0;
        return enchantLevel(p.getInventory().getBoots(), names);
    }

    public static long minBreakTimeMs(Player p, Block b) {
        if (p == null || b == null) return 0L;
        Material mat = b.getType();
        float hardness = mat.getHardness();
        if (hardness < 0.0f) return Long.MAX_VALUE;
        if (hardness == 0.0f) return 0L;
        if (mat == Material.BAMBOO) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand != null && hand.getType().name().endsWith("_SWORD")) return 0L;
        }
        try {
            float speedPerTick = b.getBreakSpeed(p);
            if (Float.isInfinite(speedPerTick) || speedPerTick >= 0.70f) {
                return 0L;
            }
            if (speedPerTick > 0.0f) {
                double ticks = Math.ceil(1.0 / speedPerTick);
                if (ticks <= 2.0) return 0L;
                return Math.max(0L, (long) ((ticks - 2.0) * 35.0));
            }
        } catch (Throwable ignored) {
        }
        int haste = Math.max(effectLevel(p, effectType("HASTE", "FAST_DIGGING")),
                effectLevel(p, effectType("CONDUIT_POWER")));
        int eff = enchantLevel(p.getInventory().getItemInMainHand(), "EFFICIENCY", "DIG_SPEED");
        double toolMult = 12.0 + (eff > 0 ? (eff * eff + 1.0) : 0.0);
        toolMult *= (1.0 + 0.2 * haste);
        double damagePerTick = toolMult / (hardness * 30.0);
        if (damagePerTick >= 0.70) return 0L;
        double ticks = Math.ceil(1.0 / Math.max(0.001, damagePerTick));
        return Math.max(0L, (long) ((ticks - 2.0) * 35.0));
    }

    public static long blockKey(Block b) {
        return ((long) b.getX() & 0x3FFFFFFL) << 38 | ((long) b.getZ() & 0x3FFFFFFL) << 12 | ((long) b.getY() & 0xFFFL);
    }

    public static double distanceToBox(Location eye, BoundingBox box) {
        double cx = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double cy = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double cz = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        double dx = eye.getX() - cx, dy = eye.getY() - cy, dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double distanceToBox(Vector pos, BoundingBox box) {
        double cx = Math.max(box.getMinX(), Math.min(pos.getX(), box.getMaxX()));
        double cy = Math.max(box.getMinY(), Math.min(pos.getY(), box.getMaxY()));
        double cz = Math.max(box.getMinZ(), Math.min(pos.getZ(), box.getMaxZ()));
        double dx = pos.getX() - cx, dy = pos.getY() - cy, dz = pos.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static Vector nearestPoint(Location eye, BoundingBox box) {
        return new Vector(
                Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX())),
                Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY())),
                Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ())));
    }

    public static float angleDiff(float a, float b) {
        float diff = (a - b) % 360.0f;
        if (diff > 180.0f) diff -= 360.0f;
        if (diff < -180.0f) diff += 360.0f;
        return Math.abs(diff);
    }

    public static double gcd(double a, double b) {
        long scale = 1L << 24;
        long la = Math.round(Math.abs(a) * scale);
        long lb = Math.round(Math.abs(b) * scale);
        if (la < lb) {
            long tmp = la;
            la = lb;
            lb = tmp;
        }
        long limit = Math.round(1e-4 * scale);
        int guard = 0;
        while (lb >= limit && guard++ < 64) {
            long rem = la % lb;
            la = lb;
            lb = rem;
        }
        return (double) la / (double) scale;
    }

    public static double rayIntersectsBox(Location eye, Vector dir, BoundingBox box, double maxDist) {
        if (box.contains(eye.toVector())) return 0.0;
        RayTraceResult result = box.rayTrace(eye.toVector(), dir, maxDist);
        if (result != null && result.getHitPosition() != null) {
            return eye.toVector().distance(result.getHitPosition());
        }
        return -1.0;
    }

    public static double standardDeviation(Iterable<Long> samples) {
        int count = 0;
        double sum = 0.0;
        for (long v : samples) {
            sum += v;
            count++;
        }
        if (count < 2) return 999.0;
        double mean = sum / count;
        double varianceSum = 0.0;
        for (long v : samples) {
            double diff = v - mean;
            varianceSum += diff * diff;
        }
        return Math.sqrt(varianceSum / count);
    }

    public static double angleDegrees(Vector a, Vector b) {
        double dot = a.dot(b);
        double len = a.length() * b.length();
        if (len < 1e-6) return 0.0;
        double cos = Math.max(-1.0, Math.min(1.0, dot / len));
        return Math.toDegrees(Math.acos(cos));
    }
}
