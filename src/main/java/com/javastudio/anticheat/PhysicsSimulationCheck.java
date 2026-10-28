package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/**
 * Hướng 2: Mô phỏng vật lý chuẩn Minecraft (Exact Physics Simulation)
 * và bảo vệ chống Timer Hack / Blink Disabler.
 */
public final class PhysicsSimulationCheck {

    private final AntiCheatManager manager;

    public PhysicsSimulationCheck(AntiCheatManager manager) {
        this.manager = manager;
    }

    /**
     * Kiểm tra vật lý chuyển động và tần số gói tin.
     */
    public boolean processPhysics(Player p, PlayerData d, Location from, Location to,
                                  double dx, double dy, double dz, double hDist, boolean serverGround) {
        long now = System.currentTimeMillis();

        // 1. Packet Frequency & Timer Check
        if (manager.isEnabled(CheckType.TIMER)) {
            d.movePackets.addLast(now);
            while (!d.movePackets.isEmpty() && now - d.movePackets.peekFirst() > 1000L) {
                d.movePackets.pollFirst();
            }

            int pps = d.movePackets.size();
            // Client chuẩn gửi ~20 PPS. Nếu > 22 PPS liên tục khi TPS server >= 19.0
            if (pps > 22 && manager.getTps() >= 18.5) {
                d.buffer[CheckType.TIMER.ordinal()] += 1.0;
                if (d.buffer[CheckType.TIMER.ordinal()] >= 3.0) {
                    String detail = String.format(Locale.ROOT, "Timer speedup packet flood (PPS=%d/s, max=22)", pps);
                    manager.flag(p, d, CheckType.TIMER, detail, 1.0);
                    d.buffer[CheckType.TIMER.ordinal()] = 0;
                    return true; // Setback
                }
            } else {
                d.buffer[CheckType.TIMER.ordinal()] = Math.max(0, d.buffer[CheckType.TIMER.ordinal()] - 0.2);
            }
        }

        // 2. Blink Disabler Check
        if (manager.isEnabled(CheckType.BLINK)) {
            if (d.lastMovePacketTime > 0) {
                long packetGap = now - d.lastMovePacketTime;
                // Nếu dừng gửi gói tin > 1.2 giây nhưng sau đó nhảy vọt khoảng cách lớn (> 4.5 blocks trong 1 tick)
                boolean teleportGrace = (now - d.lastTeleport < manager.getTeleportGraceMs());
                if (packetGap > 1200L && hDist > 4.5 && !teleportGrace) {
                    String detail = String.format(Locale.ROOT, "Blink packet choke & burst (gap=%dms, dist=%.2fm)", packetGap, hDist);
                    manager.flag(p, d, CheckType.BLINK, detail, 1.0);
                    d.lastMovePacketTime = now;
                    return true; // Setback
                }
            }
            d.lastMovePacketTime = now;
        }

        // 3. Exact Physics Simulation (Trọng lực & Ma sát)
        if (manager.isEnabled(CheckType.PHYSICS)) {
            // Ân hạn vật lý khi đang bay, bơi, lượn elytra hoặc dính hiệu ứng
            boolean isFlying = p.isFlying() || p.getAllowFlight();
            boolean isGliding = p.isGliding() || (now - d.lastGlideStop < 2500L);
            boolean inLiquid = p.isInWater() || (d.lastEnv != null && (d.lastEnv.liquid || d.lastEnv.liquidBelow));
            boolean onClimbable = d.lastEnv != null && (d.lastEnv.climbable || d.lastEnv.web || d.lastEnv.bounce);
            boolean hasPotion = p.hasPotionEffect(PotionEffectType.JUMP_BOOST)
                    || p.hasPotionEffect(PotionEffectType.LEVITATION)
                    || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);
            boolean inGrace = (now - d.lastTeleport < manager.getTeleportGraceMs()) || manager.isInVelocityGrace(d) || (now - d.lastDamage < 1500L);

            if (!isFlying && !isGliding && !inLiquid && !onClimbable && !hasPotion && !inGrace) {
                // Kiểm tra 3.1: AirJump / Tự bay lên khi đang ở giữa không trung (airTicks >= 3)
                if (!serverGround && d.airTicks >= 3 && dy > 0.06) {
                    d.physicsViolationStreak++;
                    if (d.physicsViolationStreak >= 2) {
                        String detail = String.format(Locale.ROOT, "Mid-air upward acceleration (dy=%.3f, airTicks=%d)", dy, d.airTicks);
                        manager.flag(p, d, CheckType.PHYSICS, detail, 1.0);
                        return true;
                    }
                } else if (!serverGround && d.airTicks >= 4 && Math.abs(dy) < 0.003 && hDist > 0.15) {
                    // Kiểm tra 3.2: Mid-air Zero-G Hover/Glide (Lơ lửng không mất độ cao khi di chuyển ngang)
                    d.physicsViolationStreak++;
                    if (d.physicsViolationStreak >= 3) {
                        String detail = String.format(Locale.ROOT, "Mid-air zero gravity hover (dy=%.4f, airTicks=%d)", dy, d.airTicks);
                        manager.flag(p, d, CheckType.PHYSICS, detail, 1.0);
                        return true;
                    }
                } else if (!serverGround && d.airTicks >= 2) {
                    // Kiểm tra 3.3: Dự đoán vận tốc rơi tự do Minecraft (v_y = (v_prev - 0.08) * 0.98)
                    double expectedNextDy = (d.lastDy - 0.08) * 0.98;
                    double dyError = Math.abs(dy - expectedNextDy);

                    // Sai lệch trọng lực lớn (> 0.25) liên tục mà không có va chạm
                    if (dyError > 0.28 && dy > expectedNextDy) {
                        d.buffer[CheckType.PHYSICS.ordinal()] += 1.0;
                        if (d.buffer[CheckType.PHYSICS.ordinal()] >= 3.0) {
                            String detail = String.format(Locale.ROOT, "Gravity mismatch (dy=%.3f, expected=%.3f, diff=%.3f)",
                                    dy, expectedNextDy, dyError);
                            manager.flag(p, d, CheckType.PHYSICS, detail, 1.0);
                            d.buffer[CheckType.PHYSICS.ordinal()] = 0;
                            return true;
                        }
                    } else {
                        d.buffer[CheckType.PHYSICS.ordinal()] = Math.max(0, d.buffer[CheckType.PHYSICS.ordinal()] - 0.2);
                        d.physicsViolationStreak = Math.max(0, d.physicsViolationStreak - 1);
                    }
                } else {
                    d.physicsViolationStreak = 0;
                }
            }
        }

        return false;
    }
}
