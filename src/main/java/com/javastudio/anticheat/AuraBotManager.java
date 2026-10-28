package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hướng 1: Quản lý bẫy AuraBot tàng hình (Invisible KillAura / AimAssist Trap).
 * Tạo một decoy entity tàng hình quỹ đạo liên tục ở điểm mù (180 độ sau lưng) người chơi.
 * Người chơi bình thường không bao giờ nhìn thấy hoặc đánh trúng, nhưng cheat KillAura / TriggerBot
 * tự động quét mảng entity xung quanh và lập tức snap 180 độ tấn công bẫy -> Bắt quả tang 100% không false-positive.
 */
public final class AuraBotManager {

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    // Map: Suspect UUID -> Active Bot Session
    private final Map<UUID, BotSession> activeSessions = new ConcurrentHashMap<>();
    // Map: ArmorStand UUID -> Suspect UUID
    private final Map<UUID, UUID> botEntityMap = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private boolean autoSpawn = true;
    private double vlTrigger = 4.0;
    private int defaultDurationTicks = 120; // 6 giây

    public AuraBotManager(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        loadConfig();
    }

    public void loadConfig() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("anticheat.aurabot.enabled", true);
        this.autoSpawn = cfg.getBoolean("anticheat.aurabot.auto-spawn", true);
        this.vlTrigger = cfg.getDouble("anticheat.aurabot.vl-trigger", 4.0);
        this.defaultDurationTicks = cfg.getInt("anticheat.aurabot.duration-ticks", 120);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAutoSpawn() {
        return autoSpawn;
    }

    public double getVlTrigger() {
        return vlTrigger;
    }

    /**
     * Kiểm tra xem thực thể có phải là AuraBot decoy hay không.
     */
    public boolean isAuraBot(Entity entity) {
        if (entity == null) return false;
        return botEntityMap.containsKey(entity.getUniqueId());
    }

    /**
     * Lấy UUID nghi phạm gắn liền với AuraBot này.
     */
    public UUID getSuspectId(Entity entity) {
        if (entity == null) return null;
        return botEntityMap.get(entity.getUniqueId());
    }

    /**
     * Triệu hồi 1 AuraBot tàng hình bay sau lưng nghi phạm.
     * @param suspect Người chơi nghi vấn
     * @param durationTicks Thời gian tồn tại của bot (ticks)
     * @return true nếu triệu hồi thành công
     */
    @SuppressWarnings("deprecation")
    public boolean spawnAuraBot(Player suspect, int durationTicks) {
        if (!enabled || suspect == null || !suspect.isOnline()) return false;
        if (activeSessions.containsKey(suspect.getUniqueId())) {
            return false; // Đã có bot đang theo dõi người này
        }

        Location eye = suspect.getEyeLocation();
        if (eye.getWorld() == null) return false;

        // Tính vị trí ban đầu: 2.5 khối ngay sau lưng (180 độ)
        double yawRad = Math.toRadians(suspect.getLocation().getYaw());
        double initX = eye.getX() + Math.sin(yawRad) * 2.5;
        double initZ = eye.getZ() - Math.cos(yawRad) * 2.5;
        Location spawnLoc = new Location(eye.getWorld(), initX, eye.getY() - 0.4, initZ, suspect.getLocation().getYaw(), 0f);

        ArmorStand stand;
        try {
            stand = eye.getWorld().spawn(spawnLoc, ArmorStand.class, s -> {
                s.setVisible(false);
                s.setGravity(false);
                s.setInvulnerable(false); // Cho phép nhận event sát thương
                s.setSilent(true);
                s.setCollidable(false);
                s.setSmall(false);
                s.setArms(true);
                s.setBasePlate(false);
                s.setCustomName("§8[AC-Trap]");
                s.setCustomNameVisible(false);
                s.setRemoveWhenFarAway(true);
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("[AntiCheat] Không thể spawn AuraBot: " + t.getMessage());
            return false;
        }

        int duration = durationTicks > 0 ? durationTicks : defaultDurationTicks;
        BotSession session = new BotSession(suspect.getUniqueId(), stand, duration);
        activeSessions.put(suspect.getUniqueId(), session);
        botEntityMap.put(stand.getUniqueId(), suspect.getUniqueId());

        PlayerData d = manager.peek(suspect.getUniqueId());
        if (d != null) {
            d.activeAuraBotUuid = stand.getUniqueId();
            d.auraBotSpawnedTime = System.currentTimeMillis();
        }

        // Bắt đầu vòng lặp quỹ đạo di chuyển bám sau lưng nghi phạm
        session.taskHandle = PlatformUtil.runTaskTimer(plugin, () -> {
            session.ticksElapsed += 2;

            Player curSuspect = Bukkit.getPlayer(session.suspectId);
            if (curSuspect == null || !curSuspect.isOnline() || !session.stand.isValid() || session.stand.isDead()) {
                despawn(session.suspectId);
                return;
            }

            if (session.ticksElapsed >= session.maxTicks) {
                despawn(session.suspectId);
                return;
            }

            // Cập nhật vị trí bám sát góc mù sau lưng (160 - 180 độ) kèm dao động nhẹ
            Location pLoc = curSuspect.getLocation();
            double curYaw = Math.toRadians(pLoc.getYaw());
            // Dao động góc quanh 170-190 độ sau lưng
            double angle = curYaw + Math.PI + Math.sin(session.ticksElapsed * 0.12) * 0.40;
            double dist = 2.4 + Math.cos(session.ticksElapsed * 0.08) * 0.3; // 2.1m - 2.7m
            double targetX = pLoc.getX() + Math.sin(-angle) * dist;
            double targetZ = pLoc.getZ() + Math.cos(-angle) * dist;
            double targetY = curSuspect.getEyeLocation().getY() - 0.45;

            Location newLoc = new Location(pLoc.getWorld(), targetX, targetY, targetZ, (float) Math.toDegrees(angle), 0f);
            session.stand.teleport(newLoc);
        }, 1L, 2L);

        return true;
    }

    /**
     * Xử lý khi có người chơi tấn công trúng AuraBot.
     */
    public void handleBotAttacked(Player attacker, Entity bot, EntityDamageByEntityEvent event) {
        event.setCancelled(true); // Hủy sát thương, không phá hỏng bot

        UUID suspectId = botEntityMap.get(bot.getUniqueId());
        PlayerData d = manager.data(attacker);

        // Đánh trúng một thực thể hoàn toàn tàng hình ở điểm mù sau lưng là 100% KillAura
        manager.flag(attacker, d, CheckType.KILLAURA, "Đánh trúng AuraBot tàng hình sau lưng (100% Aura Trap)", 10.0);

        String alertMsg = MessageUtil.colorize("&8[&c&lAC-TRAP&8] &c[!] &f" + attacker.getName()
                + " &7đã tấn công &eAuraBot tàng hình &7ở điểm mù sau lưng! &c(Bắt quả tang KillAura 100%)");

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(manager.getAlertPermission()) || staff.isOp()) {
                staff.sendMessage(alertMsg);
                try {
                    staff.playSound(staff.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.5f);
                } catch (Throwable ignored) {}
            }
        }

        BotSession session = suspectId != null ? activeSessions.get(suspectId) : null;
        if (session != null) {
            session.hitsReceived++;
            if (session.hitsReceived >= 2) {
                despawn(suspectId);
            }
        }
    }

    /**
     * Gỡ bỏ và hủy AuraBot của người chơi.
     */
    public void despawn(UUID suspectId) {
        if (suspectId == null) return;
        BotSession session = activeSessions.remove(suspectId);
        if (session != null) {
            botEntityMap.remove(session.stand.getUniqueId());
            if (session.taskHandle != null) {
                session.taskHandle.cancel();
            }
            try {
                if (session.stand.isValid()) {
                    session.stand.remove();
                }
            } catch (Throwable ignored) {}

            PlayerData d = manager.peek(suspectId);
            if (d != null) {
                d.activeAuraBotUuid = null;
            }
        }
    }

    /**
     * Xóa toàn bộ AuraBot đang hoạt động khi reload/shutdown.
     */
    public void despawnAll() {
        for (UUID suspectId : activeSessions.keySet()) {
            despawn(suspectId);
        }
        activeSessions.clear();
        botEntityMap.clear();
    }

    public int getActiveBotCount() {
        return activeSessions.size();
    }

    private static final class BotSession {
        final UUID suspectId;
        final ArmorStand stand;
        final int maxTicks;
        int ticksElapsed;
        int hitsReceived;
        PlatformUtil.TaskHandle taskHandle;

        BotSession(UUID suspectId, ArmorStand stand, int maxTicks) {
            this.suspectId = suspectId;
            this.stand = stand;
            this.maxTicks = maxTicks;
        }
    }
}
