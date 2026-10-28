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
import org.bukkit.util.Vector;

/**
 * Kiểm tra các hành vi người chơi đặc thù (Player Checks):
 * FastEat, FastBow, Velocity (Anti-Knockback), Sprint bất thường.
 */
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
        // Chỉ theo dõi nếu lực đẩy ngang đáng kể (> 0.25)
        if (h > 0.25) {
            d.lastVelocity = System.currentTimeMillis();
            d.pendingVelocityAt = d.lastVelocity;
            d.pendingVelocityH = h;
            d.pendingVelocityStart = p.getLocation().clone();

            // Kiểm tra sau 8 tick xem người chơi có thực sự bị văng đi không
            PlatformUtil.runAtEntityLater(plugin, p, () -> {
                if (!p.isOnline() || manager.isBypassed(p, d)) return;
                Location start = d.pendingVelocityStart;
                if (start == null || start.getWorld() != p.getWorld()) return;

                double actualDist = Math.hypot(p.getLocation().getX() - start.getX(), p.getLocation().getZ() - start.getZ());
                double expectedMin = d.pendingVelocityH * 0.35; // Ít nhất 35% lực dự kiến

                // Bỏ qua nếu có chướng ngại vật (nước, mạng nhện, leo thang, khối trước mặt)
                AcUtil.Env env = AcUtil.env(p.getLocation());
                if (env.special() || manager.isLagging()) return;

                if (actualDist < expectedMin && actualDist < 0.15) {
                    d.buffer[CheckType.VELOCITY.ordinal()] += 1.0;
                    if (d.buffer[CheckType.VELOCITY.ordinal()] >= 2.0) {
                        String detail = String.format("AntiKB moved=%.2f expected=%.2f (force=%.2f)",
                                actualDist, expectedMin, d.pendingVelocityH);
                        manager.flag(p, d, CheckType.VELOCITY, detail, 1.0);
                        d.buffer[CheckType.VELOCITY.ordinal()] = 0;
                    }
                } else {
                    d.buffer[CheckType.VELOCITY.ordinal()] = Math.max(0, d.buffer[CheckType.VELOCITY.ordinal()] - 0.2);
                }
            }, 8L);
        }
    }

    public void processConsume(Player p, PlayerData d, PlayerItemConsumeEvent event) {
        if (manager.isBypassed(p, d)) return;
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (manager.isLagging()) return;

        long now = System.currentTimeMillis();
        Material mat = event.getItem().getType();

        if (manager.isEnabled(CheckType.FASTEAT)) {
            if (d.useStart > 0 && d.useItem == mat) {
                long duration = now - d.useStart;
                long minExpected = (mat == Material.DRIED_KELP) ? 550L : 1150L;
                if (duration < minExpected) {
                    if (manager.flag(p, d, CheckType.FASTEAT, "Ăn " + mat + " trong " + duration + "ms (min=" + minExpected + ")", 1.0)) {
                        if (manager.settings(CheckType.FASTEAT).isCancel()) {
                            event.setCancelled(true);
                        }
                    }
                }
            }
        }
        d.useStart = 0;
        d.useItem = null;
    }

    public void processShootBow(Player p, PlayerData d, EntityShootBowEvent event) {
        if (manager.isBypassed(p, d)) return;
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (manager.isLagging()) return;

        if (manager.isEnabled(CheckType.FASTBOW)) {
            float force = event.getForce();
            long now = System.currentTimeMillis();
            if (d.useStart > 0 && force >= 0.85f) {
                long drawTime = now - d.useStart;
                // Giương hết lực cần tối thiểu ~650ms
                if (drawTime < 450L) {
                    if (manager.flag(p, d, CheckType.FASTBOW, "Bắn full lực (force=" + String.format("%.2f", force) + ") trong " + drawTime + "ms", 1.0)) {
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
            // Chạy nhanh khi đói (Food level <= 6)
            if (p.getFoodLevel() <= 6 && p.getGameMode() == GameMode.SURVIVAL) {
                if (manager.flag(p, d, CheckType.SPRINT, "Sprint with food=" + p.getFoodLevel(), 1.0)) {
                    event.setCancelled(true);
                    p.setSprinting(false);
                }
            }
        }
    }
}
