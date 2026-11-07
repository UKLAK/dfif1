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
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuraBotManager {

    private static final int MAX_CONCURRENT_BOTS = 8;

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    private final Map<UUID, BotSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> botEntityMap = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private boolean autoSpawn = true;
    private double vlTrigger = 4.0;
    private int defaultDurationTicks = 120;

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

    public boolean isAuraBot(Entity entity) {
        if (entity == null) return false;
        return botEntityMap.containsKey(entity.getUniqueId());
    }

    public UUID getSuspectId(Entity entity) {
        if (entity == null) return null;
        return botEntityMap.get(entity.getUniqueId());
    }

    @SuppressWarnings("deprecation")
    public boolean spawnAuraBot(Player suspect, int durationTicks) {
        if (!enabled || suspect == null || !suspect.isOnline()) return false;
        if (activeSessions.size() >= MAX_CONCURRENT_BOTS) return false;
        if (activeSessions.containsKey(suspect.getUniqueId())) {
            return false;
        }

        Location eye = suspect.getEyeLocation();
        if (eye.getWorld() == null) return false;

        double yawRad = Math.toRadians(suspect.getLocation().getYaw());
        double initX = eye.getX() + Math.sin(yawRad) * 2.5;
        double initZ = eye.getZ() - Math.cos(yawRad) * 2.5;
        Location spawnLoc = new Location(eye.getWorld(), initX, eye.getY() - 0.4, initZ, suspect.getLocation().getYaw(), 0f);

        ArmorStand stand;
        try {
            stand = eye.getWorld().spawn(spawnLoc, ArmorStand.class, s -> {
                s.setVisible(false);
                s.setGravity(false);
                s.setInvulnerable(false);
                s.setSilent(true);
                s.setCollidable(false);
                s.setSmall(false);
                s.setArms(true);
                s.setBasePlate(false);
                s.setPersistent(false);
                s.setCustomName("§8[AC-Trap]");
                s.setCustomNameVisible(false);
                s.setRemoveWhenFarAway(true);
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("[AntiCheat] Không thể spawn AuraBot: " + t.getMessage());
            return false;
        }

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.getUniqueId().equals(suspect.getUniqueId())) {
                try {
                    other.hideEntity(plugin, stand);
                } catch (Throwable ignored) {}
            }
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

        session.taskHandle = PlatformUtil.runTaskTimer(plugin, () -> {
            session.ticksElapsed += 2;

            Player curSuspect = Bukkit.getPlayer(session.suspectId);
            if (curSuspect == null || !curSuspect.isOnline() || !session.stand.isValid() || session.stand.isDead()) {
                despawn(session.suspectId);
                return;
            }

            if (session.ticksElapsed >= session.maxTicks || curSuspect.getWorld() != session.stand.getWorld()) {
                despawn(session.suspectId);
                return;
            }

            Location pLoc = curSuspect.getLocation();
            double curYaw = Math.toRadians(pLoc.getYaw());
            double angle = curYaw + Math.PI + Math.sin(session.ticksElapsed * 0.12) * 0.40;
            double dist = 2.4 + Math.cos(session.ticksElapsed * 0.08) * 0.3;
            double targetX = pLoc.getX() + Math.sin(-angle) * dist;
            double targetZ = pLoc.getZ() + Math.cos(-angle) * dist;
            double targetY = curSuspect.getEyeLocation().getY() - 0.45;

            Location newLoc = new Location(pLoc.getWorld(), targetX, targetY, targetZ, (float) Math.toDegrees(angle), 0f);
            com.javastudio.utils.SmartScheduler.teleport(session.stand, newLoc);
        }, 1L, 2L);

        return true;
    }

    public void handleBotAttacked(Player attacker, Entity bot, EntityDamageByEntityEvent event) {
        event.setCancelled(true);
        event.setDamage(0.0);

        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }

        UUID suspectId = botEntityMap.get(bot.getUniqueId());
        if (suspectId == null || !attacker.getUniqueId().equals(suspectId)) {
            return;
        }

        PlayerData d = manager.data(attacker);
        if (manager.isBypassed(attacker, d)) {
            despawn(suspectId);
            return;
        }

        BotSession session = activeSessions.get(suspectId);
        if (session == null) return;

        session.hitsReceived++;
        if (session.hitsReceived == 1) {
            manager.flag(attacker, d, CheckType.KILLAURA, "Đánh trúng AuraBot sau lưng (1/2 xác nhận)", 3.0);
            return;
        }

        manager.flag(attacker, d, CheckType.KILLAURA, "Đánh trúng AuraBot tàng hình sau lưng 2 lần liên tiếp (100% Aura Trap)", 8.0);

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

        despawn(suspectId);
    }

    public void despawn(UUID suspectId) {
        if (suspectId == null) return;
        BotSession session = activeSessions.remove(suspectId);
        if (session != null) {
            botEntityMap.remove(session.stand.getUniqueId());
            if (session.taskHandle != null) {
                session.taskHandle.cancel();
            }
            PlatformUtil.runAtEntity(plugin, session.stand, () -> {
                try {
                    if (session.stand.isValid()) {
                        session.stand.remove();
                    }
                } catch (Throwable ignored) {}
            });

            PlayerData d = manager.peek(suspectId);
            if (d != null) {
                d.activeAuraBotUuid = null;
            }
        }
    }

    public void despawnAll() {
        for (UUID suspectId : new ArrayList<>(activeSessions.keySet())) {
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
            }
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
