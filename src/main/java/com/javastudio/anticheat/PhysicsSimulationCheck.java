package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class PhysicsSimulationCheck {

    private final AntiCheatManager manager;

    public PhysicsSimulationCheck(AntiCheatManager manager) {
        this.manager = manager;
    }

    public boolean processPhysics(Player p, PlayerData d, Location from, Location to,
                                  double dx, double dy, double dz, double hDist, boolean serverGround) {
        return processPhysics(p, d, from, to, dx, dy, dz, hDist, serverGround, System.currentTimeMillis());
    }

    public boolean processPhysics(Player p, PlayerData d, Location from, Location to,
                                  double dx, double dy, double dz, double hDist, boolean serverGround, long now) {
        long packetGap = d.lastMovePacketTime > 0 ? (now - d.lastMovePacketTime) : 50L;

        if (manager.isEnabled(CheckType.TIMER)) {
            if (dx * dx + dy * dy + dz * dz > 1.0e-9) {
                d.movePackets.addLast(now);
            }
            while (!d.movePackets.isEmpty() && now - d.movePackets.peekFirst() > 1000L) {
                d.movePackets.pollFirst();
            }

            int pps = d.movePackets.size();
            boolean isLagRecovery = packetGap > 120L || d.timerBalance < 90.0 || manager.getTps() < 19.3 || manager.isLagging();

            if (pps > 30 && !isLagRecovery) {
                d.timerViolations++;
                if (d.timerViolations >= 6) {
                    String detail = String.format(Locale.ROOT, "Timer speedup packet flood (PPS=%d/s, max=30, balance=%.0fms)", pps, d.timerBalance);
                    manager.flag(p, d, CheckType.TIMER, detail, 1.0);
                    d.timerViolations = 0;
                    return true;
                }
            } else {
                d.timerViolations = Math.max(0, d.timerViolations - 1);
            }
        }

        if (manager.isEnabled(CheckType.BLINK)) {
            if (d.lastMovePacketTime > 0 && !manager.isLagging()) {
                boolean teleportGrace = (now - d.lastTeleport < manager.getTeleportGraceMs())
                        || (now - d.lastRespawn < manager.getTeleportGraceMs());
                boolean glideOrRip = p.isGliding() || (now - d.lastGlideStop < 2000L) || (now - d.lastRiptide < 3000L);
                if (packetGap > 1150L && hDist > 5.0 && !teleportGrace && !glideOrRip && manager.safePing(p) < 550) {
                    String detail = String.format(Locale.ROOT, "Blink packet choke & burst (gap=%dms, dist=%.2fm)", packetGap, hDist);
                    manager.flag(p, d, CheckType.BLINK, detail, 1.0);
                    return true;
                }
            }
        }

        if (manager.isEnabled(CheckType.PHYSICS)) {
            boolean isFlying = p.isFlying() || p.getAllowFlight();
            boolean isGliding = p.isGliding() || (now - d.lastGlideStop < 2500L);
            boolean inLiquid = p.isInWater()
                    || (d.lastEnv != null && (d.lastEnv.liquid || d.lastEnv.liquidBelow))
                    || (d.prevEnv != null && (d.prevEnv.liquid || d.prevEnv.liquidBelow));
            boolean onSpecial = (d.lastEnv != null && d.lastEnv.special())
                    || (d.prevEnv != null && d.prevEnv.special())
                    || (now - d.lastSpecial < 1400L)
                    || (now - d.lastBounce < 2200L)
                    || (now - d.lastRiptide < 3500L);
            boolean hasPotion = AcUtil.hasEffect(p, "JUMP_BOOST", "JUMP")
                    || AcUtil.hasEffect(p, "LEVITATION")
                    || AcUtil.hasEffect(p, "SLOW_FALLING");
            boolean inGrace = (now - d.lastTeleport < manager.getTeleportGraceMs())
                    || manager.isInVelocityGrace(d)
                    || (now - d.lastDamage < 1600L)
                    || (now - d.lastMaceSmash < 1600L)
                    || manager.isLagging()
                    || packetGap > 110L;

            if (!isFlying && !isGliding && !inLiquid && !onSpecial && !hasPotion && !inGrace) {
                boolean nearHalfBlock = (d.lastEnv != null && (d.lastEnv.halfBlock || d.lastEnv.ceiling))
                        || (d.prevEnv != null && (d.prevEnv.halfBlock || d.prevEnv.ceiling))
                        || hasStepAssistance(p, from, to);

                if (!serverGround && !d.lastServerGround && d.airTicks >= 2 && !nearHalfBlock) {
                    double gravity = AcUtil.gravity(p);
                    if (gravity < 0.01) {
                        d.physicsViolationStreak = 0;
                        return false;
                    }

                    boolean isUpwardAcc = (d.airTicks >= 3 && dy > d.lastDy + 0.035 && dy > 0.05)
                            || (d.airTicks >= 3 && d.lastDy < -0.08 && dy > 0.08)
                            || (d.airTicks >= 11 && dy > 0.08);

                    if (isUpwardAcc && !AcUtil.hasGround(from, 0.35, 0.35) && !AcUtil.hasGround(to, 0.35, 0.35)) {
                        d.physicsViolationStreak++;
                        if (d.physicsViolationStreak >= 3) {
                            String detail = String.format(Locale.ROOT, "Mid-air upward acceleration (dy=%.3f, lastDy=%.3f, airTicks=%d)", dy, d.lastDy, d.airTicks);
                            manager.flag(p, d, CheckType.PHYSICS, detail, 1.0);
                            d.physicsViolationStreak = 1;
                            return true;
                        }
                    } else if (d.airTicks >= 9 && Math.abs(dy) < 0.004 && hDist > 0.16) {
                        d.physicsViolationStreak++;
                        if (d.physicsViolationStreak >= 3) {
                            String detail = String.format(Locale.ROOT, "Mid-air zero gravity hover (dy=%.4f, airTicks=%d)", dy, d.airTicks);
                            manager.flag(p, d, CheckType.PHYSICS, detail, 1.0);
                            d.physicsViolationStreak = 1;
                            return true;
                        }
                    } else if (d.airTicks >= 3) {
                        double expectedNextDy = (d.lastDy - gravity) * 0.98;
                        double dyError = dy - expectedNextDy;

                        if (dyError > 0.32) {
                            d.buffer[CheckType.PHYSICS.ordinal()] += 1.0;
                            if (d.buffer[CheckType.PHYSICS.ordinal()] >= 3.0) {
                                String detail = String.format(Locale.ROOT, "Gravity mismatch (dy=%.3f, expected=%.3f, diff=+%.3f)",
                                        dy, expectedNextDy, dyError);
                                manager.flag(p, d, CheckType.PHYSICS, detail, 1.0);
                                d.buffer[CheckType.PHYSICS.ordinal()] = 0;
                                return true;
                            }
                        } else {
                            d.buffer[CheckType.PHYSICS.ordinal()] = Math.max(0, d.buffer[CheckType.PHYSICS.ordinal()] - 0.25);
                            d.physicsViolationStreak = Math.max(0, d.physicsViolationStreak - 1);
                        }

                        if (dy < -3.98 && gravity <= 0.09) {
                            String detail = String.format(Locale.ROOT, "Terminal velocity exceeded / FastFall (dy=%.3f, max=-3.92)", dy);
                            manager.flag(p, d, CheckType.PHYSICS, detail, 1.5);
                            return true;
                        }
                    } else {
                        d.physicsViolationStreak = Math.max(0, d.physicsViolationStreak - 1);
                    }
                } else {
                    d.physicsViolationStreak = 0;
                    d.buffer[CheckType.PHYSICS.ordinal()] = Math.max(0, d.buffer[CheckType.PHYSICS.ordinal()] - 0.25);
                }

                double maxStep = Math.max(0.62, AcUtil.stepHeight(p) + 0.05);
                if (dy > maxStep && !nearHalfBlock) {
                    d.buffer[CheckType.JUMP.ordinal()] += 1.0;
                    if (d.buffer[CheckType.JUMP.ordinal()] >= 2.0) {
                        String detail = String.format(Locale.ROOT, "Step height violation (dy=%.3f, max=%.2f)", dy, maxStep);
                        manager.flag(p, d, CheckType.JUMP, detail, 1.0);
                        d.buffer[CheckType.JUMP.ordinal()] = 0;
                        return true;
                    }
                } else {
                    d.buffer[CheckType.JUMP.ordinal()] = Math.max(0, d.buffer[CheckType.JUMP.ordinal()] - 0.2);
                }
            } else {
                d.physicsViolationStreak = 0;
            }
        }

        return false;
    }

    private boolean hasStepAssistance(Player p, Location from, Location to) {
        if (p.isInsideVehicle()) return true;
        Block fromBlock = from.getBlock();
        Block toBlock = to.getBlock();
        Block below = toBlock.getRelative(0, -1, 0);

        Material m1 = fromBlock.getType();
        Material m2 = toBlock.getType();
        Material m3 = below.getType();

        return isStepMaterial(m1) || isStepMaterial(m2) || isStepMaterial(m3);
    }

    private boolean isStepMaterial(Material mat) {
        String name = mat.name();
        return name.endsWith("_SLAB") || name.endsWith("_STAIRS")
                || name.contains("STEP") || name.contains("SNOW")
                || name.contains("CARPET") || name.contains("LILY_PAD")
                || name.contains("BED") || name.contains("DAYLIGHT")
                || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE")
                || name.endsWith("_WALL") || mat == Material.SOUL_SAND
                || mat == Material.MUD || mat == Material.DIRT_PATH
                || mat == Material.FARMLAND || mat == Material.CHEST
                || mat == Material.ENDER_CHEST || mat == Material.TRAPPED_CHEST;
    }
}
