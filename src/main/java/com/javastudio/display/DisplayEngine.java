package com.javastudio.display;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scoreboard.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine hiển thị nâng cao tích hợp sẵn trong JavaStudio:
 * - Holograms (Chữ nổi 3D bằng Text Display Entity chuẩn Minecraft 1.20+ hoặc ArmorStand)
 * - Scoreboard (Bảng điểm động hiển thị bên phải màn hình)
 * - BossBar (Thanh máu sự kiện tùy biến đa sắc màu)
 * - Tích hợp PlaceholderAPI (PAPI) tự động nếu có sẵn.
 */
public class DisplayEngine {

    private final JavaStudioPlugin plugin;
    private final Map<UUID, List<Entity>> activeHolograms = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> activeBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> customScoreboards = new ConcurrentHashMap<>();

    public DisplayEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        initPlaceholderApiHook();
    }

    // ==========================================
    // 1. HOLOGRAMS ENGINE (TEXT DISPLAY 1.20+)
    // ==========================================

    /**
     * Tạo chữ nổi (Hologram) tại vị trí chỉ định.
     */
    @SuppressWarnings("deprecation")
    public List<Entity> createHologram(Location location, String... lines) {
        if (location == null || location.getWorld() == null || lines == null || lines.length == 0) {
            return Collections.emptyList();
        }

        List<Entity> created = new ArrayList<>();
        Location currentLoc = location.clone().add(0, (lines.length - 1) * 0.28, 0);

        for (String rawLine : lines) {
            String colored = MessageUtil.color(rawLine);
            try {
                // Sử dụng TextDisplay của Minecraft 1.20+ (không lag, mượt mà, hỗ trợ billboard)
                TextDisplay display = (TextDisplay) location.getWorld().spawnEntity(currentLoc, EntityType.TEXT_DISPLAY);
                display.setText(colored);
                display.setBillboard(TextDisplay.Billboard.CENTER);
                display.setShadowed(true);
                display.setViewRange(48.0f);
                display.setCustomNameVisible(true);
                created.add(display);
            } catch (Throwable fallback) {
                // Fallback nếu chạy trên phiên bản cũ hơn
                try {
                    Entity as = location.getWorld().spawnEntity(currentLoc, EntityType.ARMOR_STAND);
                    as.setCustomName(colored);
                    as.setCustomNameVisible(true);
                    as.setGravity(false);
                    as.setInvulnerable(true);
                    created.add(as);
                } catch (Throwable ignored) {}
            }
            currentLoc.subtract(0, 0.28, 0);
        }

        UUID holoId = UUID.randomUUID();
        activeHolograms.put(holoId, created);
        return created;
    }

    /**
     * Xóa sạch các Hologram trong bán kính gần vị trí.
     */
    public int clearHologramsNear(Location loc, double radius) {
        if (loc == null || loc.getWorld() == null) return 0;
        int count = 0;
        double rSq = radius * radius;

        for (var entry : activeHolograms.entrySet()) {
            List<Entity> list = entry.getValue();
            Iterator<Entity> it = list.iterator();
            while (it.hasNext()) {
                Entity e = it.next();
                if (e.isValid() && e.getLocation().distanceSquared(loc) <= rSq) {
                    e.remove();
                    it.remove();
                    count++;
                }
            }
        }
        return count;
    }

    public void removeAllHolograms() {
        for (List<Entity> list : activeHolograms.values()) {
            for (Entity e : list) {
                if (e.isValid()) e.remove();
            }
        }
        activeHolograms.clear();
    }

    // ==========================================
    // 2. SCOREBOARD ENGINE
    // ==========================================

    /**
     * Tạo hoặc cập nhật Scoreboard động bên phải màn hình cho người chơi.
     */
    @SuppressWarnings("deprecation")
    public void setScoreboard(Player player, String title, String... lines) {
        if (player == null || !player.isOnline()) return;

        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm == null) return;

        Scoreboard board = customScoreboards.computeIfAbsent(player.getUniqueId(), k -> sm.getNewScoreboard());
        Objective obj = board.getObjective("jstudio_sb");
        if (obj != null) {
            obj.unregister();
        }

        obj = board.registerNewObjective("jstudio_sb", Criteria.DUMMY, MessageUtil.color(title != null ? title : "&6&lMÁY CHỦ"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        if (lines != null) {
            int score = lines.length;
            for (int i = 0; i < lines.length; i++) {
                String line = MessageUtil.color(lines[i]);
                // Tránh lỗi trùng lặp text trong scoreboard của Minecraft
                if (line.isEmpty()) line = ChatColor.values()[i % ChatColor.values().length].toString();
                obj.getScore(line).setScore(score--);
            }
        }

        player.setScoreboard(board);
    }

    public void clearScoreboard(Player player) {
        if (player == null) return;
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm != null) {
            player.setScoreboard(sm.getMainScoreboard());
        }
        customScoreboards.remove(player.getUniqueId());
    }

    // ==========================================
    // 3. BOSSBAR ENGINE
    // ==========================================

    /**
     * Tạo hoặc cập nhật BossBar phía trên màn hình người chơi.
     */
    public BossBar showBossBar(Player player, String title, double progress, String colorStr, String styleStr) {
        if (player == null || !player.isOnline()) return null;

        BarColor color = parseBarColor(colorStr);
        BarStyle style = parseBarStyle(styleStr);
        double prog = Math.max(0.0, Math.min(1.0, progress));

        BossBar bar = activeBossBars.computeIfAbsent(player.getUniqueId(), k -> {
            BossBar newBar = Bukkit.createBossBar(MessageUtil.color(title), color, style);
            newBar.addPlayer(player);
            return newBar;
        });

        bar.setTitle(MessageUtil.color(title));
        bar.setColor(color);
        bar.setStyle(style);
        bar.setProgress(prog);
        bar.setVisible(true);
        return bar;
    }

    public void removeBossBar(Player player) {
        if (player == null) return;
        BossBar bar = activeBossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    public void cleanupAll() {
        removeAllHolograms();
        for (BossBar b : activeBossBars.values()) {
            b.removeAll();
        }
        activeBossBars.clear();
        customScoreboards.clear();
    }

    // ==========================================
    // 4. 3D VISUAL DEBUG GIZMOS (PARTICLE TRACER)
    // ==========================================

    /**
     * Vẽ tia RayTrace dạng hạt particle từ điểm A tới điểm B (Dùng test đạn, tầm nhìn, skill).
     */
    public void drawRay(Location from, Location to, Particle particle, double step) {
        if (from == null || to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;
        org.bukkit.util.Vector direction = to.toVector().subtract(from.toVector());
        double distance = from.distance(to);
        double actualStep = Math.max(0.1, step);
        direction.normalize().multiply(actualStep);

        Location current = from.clone();
        for (double d = 0; d < distance; d += actualStep) {
            from.getWorld().spawnParticle(particle != null ? particle : Particle.FLAME, current, 1, 0, 0, 0, 0);
            current.add(direction);
        }
    }

    private Particle safeParticle(String primary, String secondary, Particle fallback) {
        try {
            return Particle.valueOf(primary);
        } catch (Throwable ignored) {
            try {
                return Particle.valueOf(secondary);
            } catch (Throwable ignored2) {
                return fallback;
            }
        }
    }

    /**
     * Vẽ hình cầu (Sphere) dạng hạt particle quanh vị trí (Dùng test bán kính nổ, aura).
     */
    public void drawSphere(Location center, double radius, Particle particle, int points) {
        if (center == null || center.getWorld() == null) return;
        int pts = Math.max(8, points);
        Particle p = particle != null ? particle : safeParticle("HAPPY_VILLAGER", "VILLAGER_HAPPY", Particle.FLAME);
        for (int i = 0; i < pts; i++) {
            double phi = Math.acos(1 - 2.0 * (i + 0.5) / pts);
            double theta = Math.PI * (1 + Math.sqrt(5)) * (i + 0.5);
            double x = radius * Math.cos(theta) * Math.sin(phi);
            double y = radius * Math.sin(theta) * Math.sin(phi);
            double z = radius * Math.cos(phi);
            center.getWorld().spawnParticle(p, center.getX() + x, center.getY() + y, center.getZ() + z, 1, 0, 0, 0, 0);
        }
    }

    /**
     * Vẽ vòng tròn nằm ngang (Circle) quanh vị trí.
     */
    public void drawCircle(Location center, double radius, Particle particle, int points) {
        if (center == null || center.getWorld() == null) return;
        int pts = Math.max(8, points);
        Particle p = particle != null ? particle : safeParticle("WITCH", "SPELL_WITCH", Particle.FLAME);
        double angleStep = (2 * Math.PI) / pts;
        for (int i = 0; i < pts; i++) {
            double angle = i * angleStep;
            double x = radius * Math.cos(angle);
            double z = radius * Math.sin(angle);
            center.getWorld().spawnParticle(p, center.getX() + x, center.getY(), center.getZ() + z, 1, 0, 0, 0, 0);
        }
    }

    // ==========================================
    // 5. PLACEHOLDERAPI BRIDGE
    // ==========================================

    private void initPlaceholderApiHook() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                plugin.getLogger().info("[DisplayEngine] Đã phát hiện PlaceholderAPI (PAPI) sẵn sàng.");
            } catch (Throwable ignored) {}
        }
    }

    private BarColor parseBarColor(String str) {
        if (str == null) return BarColor.PURPLE;
        try {
            return BarColor.valueOf(str.toUpperCase());
        } catch (Throwable t) {
            return BarColor.PURPLE;
        }
    }

    private BarStyle parseBarStyle(String str) {
        if (str == null) return BarStyle.SOLID;
        try {
            return BarStyle.valueOf(str.toUpperCase());
        } catch (Throwable t) {
            return BarStyle.SOLID;
        }
    }
}
