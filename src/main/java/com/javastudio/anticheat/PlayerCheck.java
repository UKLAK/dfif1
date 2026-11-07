package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Locale;

public final class PlayerCheck {

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    public PlayerCheck(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void processVelocity(Player p, PlayerData d, PlayerVelocityEvent event) {
        if (manager.isBypassed(p, d)) return;
        Vector v = event.getVelocity();
        double h = Math.hypot(v.getX(), v.getZ());
        long now = System.currentTimeMillis();
        d.lastVelocity = now;

        if (!manager.isEnabled(CheckType.VELOCITY)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR
                || p.isFlying() || p.getAllowFlight() || p.isInsideVehicle()) {
            return;
        }

        double kbRes = AcUtil.knockbackResistance(p);
        if (kbRes >= 0.35) {
            return;
        }

        if (h > 0.28) {
            final long velStamp = now;
            final double expectedForce = h * (1.0 - kbRes);
            final Location start = p.getLocation().clone();
            d.pendingVelocityAt = velStamp;
            d.pendingVelocityH = expectedForce;
            d.pendingVelocityStart = start;

            int delayTicks = Math.min(14, Math.max(7, (manager.safePing(p) / 50) + 6));
            PlatformUtil.runAtEntityLater(plugin, p, () -> {
                if (!p.isOnline() || manager.isBypassed(p, d) || manager.isLagging()) return;
                if (d.pendingVelocityAt != velStamp) return;
                if (start.getWorld() != p.getWorld() || p.isInsideVehicle() || p.isDead()) return;
                if (now - d.lastTeleport < manager.getTeleportGraceMs()) return;

                AcUtil.Env envStart = AcUtil.env(start);
                AcUtil.Env envEnd = AcUtil.env(p.getLocation());
                if (envStart.special() || envEnd.special() || envStart.ceiling || envEnd.ceiling) return;
                if (AcUtil.hasWallAround(start, 0.68) || AcUtil.hasWallAround(p.getLocation(), 0.68)) return;

                double actualDist = Math.hypot(p.getLocation().getX() - start.getX(), p.getLocation().getZ() - start.getZ());
                double expectedMin = expectedForce * 0.28;

                if (actualDist < expectedMin && actualDist < 0.08) {
                    d.buffer[CheckType.VELOCITY.ordinal()] += 1.0;
                    if (d.buffer[CheckType.VELOCITY.ordinal()] >= 2.5) {
                        String detail = String.format(Locale.ROOT, "AntiKB moved=%.3f expected=%.2f (force=%.2f)",
                                actualDist, expectedMin, expectedForce);
                        manager.flag(p, d, CheckType.VELOCITY, detail, 1.0);
                        d.buffer[CheckType.VELOCITY.ordinal()] = 0.5;
                    }
                } else {
                    d.buffer[CheckType.VELOCITY.ordinal()] = Math.max(0, d.buffer[CheckType.VELOCITY.ordinal()] - 0.35);
                }
            }, delayTicks);
        }
    }

    public void processConsume(Player p, PlayerData d, PlayerItemConsumeEvent event) {
        if (manager.isBypassed(p, d)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (manager.isLagging()) return;

        long now = System.currentTimeMillis();
        Material mat = event.getItem().getType();

        if (manager.isEnabled(CheckType.FASTEAT)) {
            long minExpected = (mat == Material.DRIED_KELP) ? 550L : 1100L;
            long duration = (d.useStart > 0 && d.useItem == mat) ? (now - d.useStart) : Long.MAX_VALUE;
            long sincePrevConsume = d.lastConsumeTime > 0 ? (now - d.lastConsumeTime) : Long.MAX_VALUE;
            long observed = Math.min(duration, sincePrevConsume);

            if (observed < minExpected) {
                if (manager.flag(p, d, CheckType.FASTEAT, "Ăn " + mat + " trong " + observed + "ms (min=" + minExpected + ")", 1.0)) {
                    if (manager.settings(CheckType.FASTEAT).isCancel()) {
                        event.setCancelled(true);
                    }
                }
            }
        }
        d.lastConsumeTime = now;
        d.useStart = 0;
        d.useItem = null;
    }

    public void processShootBow(Player p, PlayerData d, EntityShootBowEvent event) {
        if (manager.isBypassed(p, d)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (manager.isLagging()) return;

        ItemStack bow = event.getBow();
        if (bow != null && bow.getType() == Material.CROSSBOW) {
            d.useStart = 0;
            d.useItem = null;
            return;
        }

        if (manager.isEnabled(CheckType.FASTBOW)) {
            float force = event.getForce();
            long now = System.currentTimeMillis();
            if (d.useStart > 0 && d.useItem == Material.BOW && force >= 0.85f) {
                long drawTime = now - d.useStart;
                if (drawTime < 450L) {
                    if (manager.flag(p, d, CheckType.FASTBOW, "Bắn full lực (force=" + String.format(Locale.ROOT, "%.2f", force) + ") trong " + drawTime + "ms", 1.0)) {
                        if (manager.settings(CheckType.FASTBOW).isCancel()) {
                            event.setCancelled(true);
                        }
                    }
                }
            }
        }
        d.useStart = 0;
        d.useItem = null;
    }

    public void processToggleSprint(Player p, PlayerData d, PlayerToggleSprintEvent event) {
        if (manager.isBypassed(p, d)) return;
        if (event.isSprinting() && manager.isEnabled(CheckType.SPRINT)) {
            if (p.getFoodLevel() <= 6 && p.getGameMode() == GameMode.SURVIVAL && !p.getAllowFlight()) {
                if (manager.flag(p, d, CheckType.SPRINT, "Sprint with food=" + p.getFoodLevel(), 1.0)) {
                    event.setCancelled(true);
                    p.setSprinting(false);
                }
            }
        }
    }
}
