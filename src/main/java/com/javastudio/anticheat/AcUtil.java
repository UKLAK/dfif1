package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Tiện ích dùng chung cho các check: phát hiện mặt đất bằng collision shape, môi trường xung quanh,
 * hiệu ứng potion và tốc độ di chuyển (tương thích nhiều phiên bản Paper/Spigot, không dùng ProtocolLib).
 */
final class AcUtil {

    private AcUtil() {
    }

    /** Kết quả quét môi trường quanh vị trí người chơi. */
    static final class Env {
        boolean liquid;
        boolean climbable;
        boolean web;
        boolean bounce;
        boolean ice;
        boolean ceiling;
        boolean liquidBelow;
        boolean bubble;

        boolean special() {
            return liquid || climbable || web || bounce || bubble;
        }
    }

    // ============================================================
    //  MẶT ĐẤT / MÔI TRƯỜNG
    // ============================================================

    static boolean loaded(World w, int bx, int bz) {
        return w.isChunkLoaded(bx >> 4, bz >> 4);
    }

    /**
     * Kiểm tra có khối rắn nâng đỡ người chơi hay không.
     * Đỉnh của khối rắn phải nằm trong khoảng [y - below, y + above] và chồng lên vùng chân (±0.3).
     */
    static boolean hasGround(Location loc, double below, double above) {
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
                    if (m.isAir() || b.isPassable()) continue;
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

    static Env env(Location loc) {
        Env e = new Env();
        World w = loc.getWorld();
        if (w == null) return e;
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();
        int minX = (int) Math.floor(x - 0.3), maxX = (int) Math.floor(x + 0.3);
        int minZ = (int) Math.floor(z - 0.3), maxZ = (int) Math.floor(z + 0.3);
        int feetY = (int) Math.floor(y);
        int minY = (int) Math.floor(y - 0.6), maxY = (int) Math.floor(y + 1.8);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!loaded(w, bx, bz)) continue;
                for (int by = minY; by <= maxY; by++) {
                    Block b = w.getBlockAt(bx, by, bz);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    boolean body = by >= feetY - 0 && by <= feetY + 1;
                    classify(e, b, m, body, by < feetY);
                }
            }
        }
        // Trần nhà phía trên đầu
        int ceilY = (int) Math.floor(y + 2.0);
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

    private static void classify(Env e, Block b, Material m, boolean body, boolean below) {
        if (m == Material.WATER || m == Material.LAVA) {
            if (body) e.liquid = true;
            if (below) e.liquidBelow = true;
            return;
        }
        if (m == Material.BUBBLE_COLUMN) {
            e.bubble = true;
            e.liquid = true;
            return;
        }
        if (m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) {
            if (body) e.liquid = true;
            return;
        }
        if (Tag.CLIMBABLE.isTagged(m)) {
            e.climbable = true;
            return;
        }
        if (m == Material.COBWEB || m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW) {
            e.web = true;
            return;
        }
        if (m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK || Tag.BEDS.isTagged(m)) {
            e.bounce = true;
            return;
        }
        if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE) {
            e.ice = true;
        }
        if (body && b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) {
            e.liquid = true;
        }
    }

    static boolean standingOnEntity(Player p) {
        try {
            for (Entity en : p.getNearbyEntities(0.9, 1.3, 0.9)) {
                if (en instanceof org.bukkit.entity.Vehicle || en instanceof org.bukkit.entity.Shulker) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Folia: truy cập entity ngoài region có thể ném lỗi, coi như không có
        }
        return false;
    }

    // ============================================================
    //  POTION / TỐC ĐỘ
    // ============================================================

    private static final Map<String, PotionEffectType> TYPE_CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("deprecation")
    static PotionEffectType effectType(String... names) {
        String key = names[0];
        PotionEffectType cached = TYPE_CACHE.get(key);
        if (cached != null) return cached;
        for (String n : names) {
            PotionEffectType t = PotionEffectType.getByName(n);
            if (t != null) {
                TYPE_CACHE.put(key, t);
                return t;
            }
        }
        return null;
    }

    /** Trả về cấp độ hiệu ứng (amplifier + 1) hoặc 0 nếu không có. */
    static int effectLevel(Player p, PotionEffectType type) {
        if (type == null) return 0;
        PotionEffect e = p.getPotionEffect(type);
        return e == null ? 0 : e.getAmplifier() + 1;
    }

    static int jumpBoost(Player p) {
        return effectLevel(p, effectType("JUMP_BOOST", "JUMP"));
    }

    static boolean hasEffect(Player p, String... names) {
        return effectLevel(p, effectType(names)) > 0;
    }

    private static Object speedAttr;
    private static boolean speedAttrResolved;

    private static synchronized Object resolveSpeedAttr() {
        if (!speedAttrResolved) {
            speedAttrResolved = true;
            for (String name : new String[]{"MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED"}) {
                try {
                    Object o = Attribute.class.getField(name).get(null);
                    if (o != null) {
                        speedAttr = o;
                        break;
                    }
                } catch (Throwable ignored) {
                    // thử tên khác
                }
            }
        }
        return speedAttr;
    }

    /**
     * Hệ số tốc độ so với tốc độ đi bộ chuẩn (1.0 = đi bộ bình thường, ~1.3 = chạy nhanh).
     * Ưu tiên dùng giá trị Attribute (đã gồm potion, sprint, trang bị), fallback sang tính tay.
     */
    static double speedRatio(Player p) {
        Object attr = resolveSpeedAttr();
        if (attr != null) {
            try {
                AttributeInstance inst = p.getAttribute((Attribute) attr);
                if (inst != null) {
                    return Math.max(0.1, inst.getValue() / 0.1);
                }
            } catch (Throwable ignored) {
                // fallback bên dưới
            }
        }
        double speed = 1.0 + 0.2 * effectLevel(p, effectType("SPEED"));
        speed -= 0.15 * effectLevel(p, effectType("SLOWNESS", "SLOW"));
        speed = Math.max(0.1, speed);
        double ratio = speed * (p.getWalkSpeed() / 0.2);
        return p.isSprinting() ? ratio * 1.3 : ratio;
    }

    static long blockKey(Block b) {
        return ((long) b.getX() & 0x3FFFFFFL) << 38 | ((long) b.getZ() & 0x3FFFFFFL) << 12 | ((long) b.getY() & 0xFFFL);
    }

    /** Điểm gần nhất trên AABB so với điểm cho trước. */
    static double distanceToBox(Location eye, BoundingBox box) {
        double cx = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double cy = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double cz = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        double dx = eye.getX() - cx, dy = eye.getY() - cy, dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static org.bukkit.util.Vector nearestPoint(Location eye, BoundingBox box) {
        return new org.bukkit.util.Vector(
                Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX())),
                Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY())),
                Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ())));
    }

    /**
     * Tính Ước số chung lớn nhất (GCD) cho 2 giá trị delta góc quay để phân tích độ nhạy chuột.
     */
    static double gcd(double a, double b) {
        if (a < b) return gcd(b, a);
        if (Math.abs(b) < 0.001) return a;
        return gcd(b, a - Math.floor(a / b) * b);
    }

    /**
     * Kiểm tra tia nhìn (ray) từ mắt người chơi có giao với BoundingBox hay không.
     * Trả về khoảng cách đến điểm giao nếu cắt, hoặc -1.0 nếu lệch hoàn toàn.
     */
    static double rayIntersectsBox(Location eye, org.bukkit.util.Vector dir, BoundingBox box, double maxDist) {
        org.bukkit.util.RayTraceResult result = box.rayTrace(eye.toVector(), dir, maxDist);
        if (result != null && result.getHitPosition() != null) {
            return eye.toVector().distance(result.getHitPosition());
        }
        return -1.0;
    }

    /**
     * Tính độ lệch chuẩn (Standard Deviation) cho tập khoảng cách click (ms).
     * Tuyển thủ Pro (Butterfly/Jitter) luôn có biến thiên tự nhiên (StdDev > 6ms - 15ms).
     * AutoClicker/Macro có StdDev nhân tạo siêu thấp (< 4.2ms).
     */
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

    /**
     * Tính góc lệch (độ) giữa hai vector 3D trong không gian.
     */
    public static double angleDegrees(org.bukkit.util.Vector a, org.bukkit.util.Vector b) {
        double dot = a.dot(b);
        double len = a.length() * b.length();
        if (len < 1e-6) return 0.0;
        double cos = Math.max(-1.0, Math.min(1.0, dot / len));
        return Math.toDegrees(Math.acos(cos));
    }
}
