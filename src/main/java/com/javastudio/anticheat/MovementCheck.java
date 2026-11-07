package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.Locale;

public final class MovementCheck {

    private final AntiCheatManager manager;

    public MovementCheck(AntiCheatManager manager) {
        this.manager = manager;
    }

    public void processMove(Player p, PlayerData d, PlayerMoveEvent event) {
        processMove(p, d, event, System.currentTimeMillis());
    }

    @SuppressWarnings("deprecation")
    public void processMove(Player p, PlayerData d, PlayerMoveEvent event, long now) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;

        boolean isGliding = p.isGliding();
        if (d.wasGliding && !isGliding) {
            d.lastGlideStop = now;
        } else if (!d.wasGliding && isGliding) {
            d.lastGlideStart = now;
        }
        d.wasGliding = isGliding;

        if (p.isSprinting()) {
            d.recentSprintTicks = 8;
        } else if (d.recentSprintTicks > 0) {
            d.recentSprintTicks--;
        }

        boolean glideGrace = isGliding || (now - d.lastGlideStop < 2500L);
        boolean riptideGrace = (now - d.lastRiptide < 3500L);
        boolean windGrace = (now - d.lastWindCharge < 3000L) || (now - d.lastMaceSmash < 1500L);

        if (d.isFrozen) {
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                Location frozenAt = d.frozenLocation != null && d.frozenLocation.getWorld() == from.getWorld()
                        ? d.frozenLocation : from;
                Location lock = frozenAt.clone();
                lock.setYaw(to.getYaw());
                lock.setPitch(to.getPitch());
                event.setTo(lock);
                p.sendTitle("§c§lĐÃ BỊ ĐÓNG BĂNG", "§eStaff đang kiểm tra! Không được ngắt kết nối!", 0, 40, 10);
            }
            return;
        }

        double toX = to.getX(), toY = to.getY(), toZ = to.getZ();
        if (Double.isNaN(toX) || Double.isNaN(toY) || Double.isNaN(toZ) ||
                Double.isInfinite(toX) || Double.isInfinite(toY) || Double.isInfinite(toZ) ||
                Math.abs(to.getPitch()) > 90.05f) {
            if (manager.flag(p, d, CheckType.INVALIDMOVE, "NaN/Pitch " + to.getPitch(), 1.0)) {
                Location safe = manager.pickSetback(d, from, false);
                if (safe != null) event.setTo(safe);
                manager.markSetback(d);
            }
            return;
        }

        double dx = toX - from.getX();
        double dy = toY - from.getY();
        double dz = toZ - from.getZ();
        double hDist = Math.hypot(dx, dz);

        double maxTeleportH = (glideGrace || riptideGrace || windGrace) ? 28.0 : 9.0;
        double maxTeleportY = (glideGrace || riptideGrace || windGrace) ? 32.0 : 11.0;
        if (hDist > maxTeleportH || Math.abs(dy) > maxTeleportY) {
            if (now - d.lastTeleport > manager.getTeleportGraceMs() && !manager.isMovementExempt(p, d)) {
                Location safe = manager.pickSetback(d, from, true);
                event.setCancelled(true);
                if (safe != null) {
                    event.setTo(safe);
                    try {
                        p.teleport(safe);
                    } catch (Throwable ignored) {}
                    d.lastLoc = safe.clone();
                }
                manager.markSetback(d);
                manager.flag(p, d, CheckType.INVALIDMOVE, "ClickTP dịch chuyển đột ngột h=" + String.format(Locale.ROOT, "%.1f", hDist) + " dy=" + String.format(Locale.ROOT, "%.1f", dy), 1.0);
                return;
            }
        }

        if (manager.isEnabled(CheckType.TIMER) && !manager.isMovementExempt(p, d)) {
            if (d.lastMoveMs > 0) {
                long elapsed = now - d.lastMoveMs;
                if (elapsed > 0) {
                    long clampedElapsed = Math.min(elapsed, 2500L);
                    d.timerBalance += (50.0 - clampedElapsed);
                    if (d.timerBalance < -650.0) d.timerBalance = -650.0;

                    CheckSettings sTimer = manager.settings(CheckType.TIMER);
                    double maxBalance = sTimer.d("max-balance", 220.0);
                    if (glideGrace || riptideGrace) maxBalance += 120.0;
                    if (d.timerBalance > maxBalance && manager.getTps() >= 19.0) {
                        d.buffer[CheckType.TIMER.ordinal()] += 1.0;
                        if (d.buffer[CheckType.TIMER.ordinal()] >= sTimer.d("buffer-limit", 4.0)) {
                            if (manager.flag(p, d, CheckType.TIMER, "Balance " + String.format(Locale.ROOT, "%.0fms", d.timerBalance), 1.0)) {
                                if (sTimer.isSetback()) {
                                    Location safe = manager.pickSetback(d, from, false);
                                    if (safe != null) event.setTo(safe);
                                    manager.markSetback(d);
                                }
                                d.timerBalance = -100.0;
                            }
                        }
                    } else {
                        d.buffer[CheckType.TIMER.ordinal()] = Math.max(0, d.buffer[CheckType.TIMER.ordinal()] - 0.15);
                    }
                }
            }
            d.lastMoveMs = now;
        }

        d.prevEnv = d.lastEnv;
        AcUtil.Env env = AcUtil.env(to);
        d.lastEnv = env;
        if (env.special()) {
            d.lastSpecial = now;
        }
        if (env.ice) {
            d.lastIce = now;
        }
        if (env.bounce) {
            d.lastBounce = now;
        }

        boolean groundBlocks = AcUtil.hasGround(to, 0.16, 0.28);
        boolean serverGround = groundBlocks || (Math.abs(dy) < 0.12 && AcUtil.standingOnEntity(p));
        d.lastServerGround = d.serverGround;
        d.serverGround = serverGround;

        boolean hasSlowFallOrLev = AcUtil.hasEffect(p, "SLOW_FALLING") || AcUtil.hasEffect(p, "LEVITATION");
        if (hasSlowFallOrLev || isGliding || riptideGrace || windGrace || env.liquid || env.climbable || env.web || env.bounce) {
            d.realFallDist = 0;
            d.fallDist = 0;
        }

        if (manager.isMovementExempt(p, d)) {
            d.lastLoc = to.clone();
            d.setback = to.clone();
            double pendingFall = d.realFallDist;
            d.resetMovement();
            d.realFallDist = pendingFall;

            if (serverGround && d.realFallDist > 3.5 && !env.softLanding && !hasSlowFallOrLev) {
                int jb = AcUtil.jumpBoost(p);
                double damage = d.realFallDist - (3.0 + jb);
                p.setFallDistance((float) d.realFallDist);
                if (damage > 0.5) {
                    try { p.damage(damage); } catch (Throwable ignored) {}
                }
                d.realFallDist = 0;
            }
            return;
        }

        if (serverGround) {
            d.groundTicks++;
            d.airTicks = 0;
            d.hoverTicks = 0;

            int jb = AcUtil.jumpBoost(p);
            double safeFallThreshold = 3.5 + jb;
            if (manager.isEnabled(CheckType.NOFALL)
                    && d.realFallDist > safeFallThreshold + 1.2
                    && !glideGrace && !riptideGrace && !windGrace && !hasSlowFallOrLev
                    && !env.softLanding && (d.prevEnv == null || !d.prevEnv.softLanding)
                    && (now - d.lastBounce > 2000L)) {
                float clientFall = p.getFallDistance();
                if (clientFall < 0.5f && d.realFallDist > safeFallThreshold + 2.0) {
                    d.buffer[CheckType.NOFALL.ordinal()] += 1.0;
                    if (d.buffer[CheckType.NOFALL.ordinal()] >= 2.0 || d.realFallDist > 8.0) {
                        manager.flag(p, d, CheckType.NOFALL, String.format(Locale.ROOT, "NoFall landing (real=%.1fm, client=%.1fm)", d.realFallDist, clientFall), 1.0);
                        double damage = d.realFallDist - (3.0 + jb);
                        p.setFallDistance((float) d.realFallDist);
                        if (damage > 0.5) {
                            try {
                                p.damage(damage);
                            } catch (Throwable ignored) {}
                        }
                        d.buffer[CheckType.NOFALL.ordinal()] = 0.5;
                    }
                }
            }
            d.realFallDist = 0;
            d.fallDist = 0;
        } else {
            d.airTicks++;
            d.groundTicks = 0;
            if (isGliding || hasSlowFallOrLev || windGrace) {
                d.realFallDist = 0;
                d.fallDist = 0;
                d.hoverTicks = 0;
            } else if (dy < 0 && !env.liquid && !env.climbable && !env.web && !env.softLanding) {
                d.realFallDist += -dy;
                d.fallDist += -dy;
            }
        }

        if (manager.isEnabled(CheckType.NOFALL) && !glideGrace && !riptideGrace && !windGrace && !hasSlowFallOrLev) {
            boolean clientGround = false;
            try {
                clientGround = p.isOnGround();
            } catch (Throwable ignored) {}

            if (clientGround && !serverGround && d.airTicks > 5 && d.realFallDist > 2.5
                    && !env.special() && !env.softLanding && (now - d.lastBounce > 1800L)) {
                if (!AcUtil.hasGround(to, 0.85, 0.25) && !AcUtil.standingOnEntity(p)) {
                    d.buffer[CheckType.NOFALL.ordinal()] += 1.0;
                    p.setFallDistance((float) d.realFallDist);
                    if (d.buffer[CheckType.NOFALL.ordinal()] >= 2.5) {
                        manager.flag(p, d, CheckType.NOFALL, "Spoofed ground in mid-air (dist=" + String.format(Locale.ROOT, "%.1f", d.realFallDist) + ")", 1.0);
                        d.buffer[CheckType.NOFALL.ordinal()] = 1.0;
                    }
                }
            } else {
                d.buffer[CheckType.NOFALL.ordinal()] = Math.max(0, d.buffer[CheckType.NOFALL.ordinal()] - 0.25);
            }
        }

        if (manager.isEnabled(CheckType.JESUS) && !isGliding && !riptideGrace && !manager.isInVelocityGrace(d)) {
            if (env.liquidBelow && !env.liquid && !env.ice && !serverGround && hDist > 0.17
                    && !AcUtil.hasGround(to, 0.45, 0.25) && !AcUtil.standingOnEntity(p)) {
                boolean flatWalk = Math.abs(dy) < 0.002;
                boolean microBounce = Math.abs(dy) > 0.0 && Math.abs(dy) < 0.09 && Math.abs(d.lastDy) < 0.09 && (dy * d.lastDy <= 0.0);
                if (flatWalk || microBounce) {
                    d.liquidWalkTicks++;
                    if (d.liquidWalkTicks > 6) {
                        if (manager.flag(p, d, CheckType.JESUS, "Walk on liquid (ticks=" + d.liquidWalkTicks + ", dy=" + String.format(Locale.ROOT, "%.4f", dy) + ")", 1.0)) {
                            CheckSettings s = manager.settings(CheckType.JESUS);
                            if (s.isSetback()) {
                                Location safe = manager.pickSetback(d, from, true);
                                if (safe != null) event.setTo(safe);
                                manager.markSetback(d);
                            }
                            d.liquidWalkTicks = 3;
                        }
                    }
                } else {
                    d.liquidWalkTicks = Math.max(0, d.liquidWalkTicks - 1);
                }
            } else {
                d.liquidWalkTicks = 0;
            }
        }

        if (manager.isEnabled(CheckType.JUMP) && !manager.isInVelocityGrace(d) && !glideGrace && !riptideGrace && !windGrace && !hasSlowFallOrLev) {
            if (!d.lastServerGround && d.groundTicks == 0 && dy > 0) {
                int jb = AcUtil.jumpBoost(p);
                double baseJump = Math.max(0.42, AcUtil.jumpStrength(p));
                double maxDy = baseJump + 0.16 + (jb * 0.15);
                if (env.halfBlock || (d.prevEnv != null && d.prevEnv.halfBlock)) {
                    maxDy = Math.max(maxDy, AcUtil.stepHeight(p) + 0.08);
                }
                if (now - d.lastBounce < 2200L) maxDy += 1.6;

                if (dy > maxDy && !env.special() && (d.prevEnv == null || !d.prevEnv.special()) && (now - d.lastSpecial > 1200L)) {
                    d.buffer[CheckType.JUMP.ordinal()] += 1.0;
                    if (d.buffer[CheckType.JUMP.ordinal()] >= manager.settings(CheckType.JUMP).d("buffer-limit", 2.0)) {
                        if (manager.flag(p, d, CheckType.JUMP, "dy=" + String.format(Locale.ROOT, "%.3f", dy) + " max=" + String.format(Locale.ROOT, "%.2f", maxDy), 1.0)) {
                            CheckSettings s = manager.settings(CheckType.JUMP);
                            if (s.isSetback()) {
                                Location safe = manager.pickSetback(d, from, true);
                                if (safe != null) event.setTo(safe);
                                manager.markSetback(d);
                            }
                            d.buffer[CheckType.JUMP.ordinal()] = 0;
                        }
                    }
                } else {
                    d.buffer[CheckType.JUMP.ordinal()] = Math.max(0, d.buffer[CheckType.JUMP.ordinal()] - 0.15);
                }
            }
        }

        if (manager.isEnabled(CheckType.FLY) && !manager.isInVelocityGrace(d) && !glideGrace && !riptideGrace && !windGrace && !hasSlowFallOrLev) {
            boolean inAir = !serverGround && !d.lastServerGround;
            if (inAir && !env.special() && (d.prevEnv == null || !d.prevEnv.special())
                    && (now - d.lastSpecial > 1400L) && (now - d.lastBounce > 2000L)) {
                if (Math.abs(dy) < 0.004 && d.airTicks > 6) {
                    d.hoverTicks++;
                    if (d.hoverTicks > 6) {
                        d.buffer[CheckType.FLY.ordinal()] += 1.0;
                        if (d.buffer[CheckType.FLY.ordinal()] >= manager.settings(CheckType.FLY).d("buffer-limit", 2.0)) {
                            if (manager.flag(p, d, CheckType.FLY, "Hovering dy=" + String.format(Locale.ROOT, "%.4f", dy) + " ticks=" + d.hoverTicks, 1.0)) {
                                CheckSettings s = manager.settings(CheckType.FLY);
                                if (s.isSetback()) {
                                    Location safe = manager.pickSetback(d, from, true);
                                    if (safe != null) event.setTo(safe);
                                    manager.markSetback(d);
                                }
                            }
                        }
                    }
                } else {
                    d.hoverTicks = Math.max(0, d.hoverTicks - 1);
                }

                int jb = AcUtil.jumpBoost(p);
                int maxAscendTicks = 12 + jb * 3;
                if (dy > 0.01 && d.airTicks > maxAscendTicks && d.lastDy >= 0) {
                    d.buffer[CheckType.FLY.ordinal()] += 1.5;
                    if (d.buffer[CheckType.FLY.ordinal()] >= 3.0) {
                        if (manager.flag(p, d, CheckType.FLY, "Ascend in air (airTicks=" + d.airTicks + ", dy=" + String.format(Locale.ROOT, "%.2f", dy) + ")", 1.0)) {
                            CheckSettings s = manager.settings(CheckType.FLY);
                            if (s.isSetback()) {
                                Location safe = manager.pickSetback(d, from, true);
                                if (safe != null) event.setTo(safe);
                                manager.markSetback(d);
                            }
                            d.buffer[CheckType.FLY.ordinal()] = 0;
                        }
                    }
                }
            } else {
                d.hoverTicks = 0;
                d.buffer[CheckType.FLY.ordinal()] = Math.max(0, d.buffer[CheckType.FLY.ordinal()] - 0.25);
            }
        } else if (glideGrace || hasSlowFallOrLev) {
            d.hoverTicks = 0;
            d.buffer[CheckType.FLY.ordinal()] = Math.max(0, d.buffer[CheckType.FLY.ordinal()] - 0.5);
        }

        if (manager.isEnabled(CheckType.FASTCLIMB) && env.climbable && !manager.isInVelocityGrace(d) && !glideGrace && !riptideGrace && !windGrace) {
            boolean jumpTakeoff = d.lastServerGround || d.airTicks <= 3 || (now - d.lastBounce < 1600L);
            double maxClimb = 0.245 + (AcUtil.jumpBoost(p) * 0.12);
            if (!jumpTakeoff && dy > maxClimb) {
                d.buffer[CheckType.FASTCLIMB.ordinal()] += 1.0;
                if (d.buffer[CheckType.FASTCLIMB.ordinal()] >= 2.0) {
                    if (manager.flag(p, d, CheckType.FASTCLIMB, "Climb dy=" + String.format(Locale.ROOT, "%.2f", dy), 1.0)) {
                        CheckSettings s = manager.settings(CheckType.FASTCLIMB);
                        if (s.isSetback()) {
                            Location safe = manager.pickSetback(d, from, false);
                            if (safe != null) event.setTo(safe);
                            manager.markSetback(d);
                        }
                    }
                    d.buffer[CheckType.FASTCLIMB.ordinal()] = 0.5;
                }
            } else {
                d.buffer[CheckType.FASTCLIMB.ordinal()] = Math.max(0, d.buffer[CheckType.FASTCLIMB.ordinal()] - 0.25);
            }
        }

        if (manager.isEnabled(CheckType.NOSLOW) && !manager.isInVelocityGrace(d) && !glideGrace && !riptideGrace) {
            boolean usingItem = p.isHandRaised();
            if (usingItem && !env.ice && (now - d.lastIce > 1200L) && serverGround && d.groundTicks >= 3) {
                double speedRatio = AcUtil.speedRatio(p);
                double maxUseH = 0.245 * speedRatio;
                if (hDist > maxUseH) {
                    d.useTicks++;
                    if (d.useTicks > 5) {
                        if (manager.flag(p, d, CheckType.NOSLOW, "Using item speed=" + String.format(Locale.ROOT, "%.2f", hDist) + " max=" + String.format(Locale.ROOT, "%.2f", maxUseH), 1.0)) {
                            CheckSettings s = manager.settings(CheckType.NOSLOW);
                            if (s.isSetback()) {
                                Location safe = manager.pickSetback(d, from, false);
                                if (safe != null) event.setTo(safe);
                                manager.markSetback(d);
                            }
                            d.useTicks = 2;
                        }
                    }
                } else {
                    d.useTicks = Math.max(0, d.useTicks - 1);
                }
            } else {
                d.useTicks = 0;
            }
        }

        if (manager.isEnabled(CheckType.SPRINT) && p.isSprinting() && !glideGrace && !riptideGrace && !manager.isInVelocityGrace(d)) {
            if (AcUtil.hasEffect(p, "BLINDNESS") && hDist > 0.22) {
                d.omniTicks++;
                if (d.omniTicks >= 5) {
                    if (manager.flag(p, d, CheckType.SPRINT, "Sprint khi đang bị mù (Blindness)", 1.0)) {
                        if (manager.settings(CheckType.SPRINT).isSetback()) {
                            Location safe = manager.pickSetback(d, from, false);
                            if (safe != null) event.setTo(safe);
                            manager.markSetback(d);
                        }
                    }
                    d.omniTicks = 2;
                }
            } else if (serverGround && d.groundTicks >= 4 && !env.ice && (now - d.lastIce > 1200L)
                    && !env.liquid && hDist > 0.20 && !AcUtil.isBedrockPlayer(p)) {
                double rad = Math.toRadians(to.getYaw());
                double lookX = -Math.sin(rad);
                double lookZ = Math.cos(rad);
                double dot = (dx * lookX + dz * lookZ) / hDist;
                if (dot < -0.35) {
                    d.omniTicks++;
                    if (d.omniTicks >= 6) {
                        if (manager.flag(p, d, CheckType.SPRINT, String.format(Locale.ROOT, "Omni-Sprint chạy lùi (dot=%.2f, h=%.2f)", dot, hDist), 1.0)) {
                            if (manager.settings(CheckType.SPRINT).isSetback()) {
                                Location safe = manager.pickSetback(d, from, false);
                                if (safe != null) event.setTo(safe);
                                manager.markSetback(d);
                            }
                        }
                        d.omniTicks = 2;
                    }
                } else {
                    d.omniTicks = Math.max(0, d.omniTicks - 1);
                }
            } else {
                d.omniTicks = Math.max(0, d.omniTicks - 1);
            }
        } else {
            d.omniTicks = Math.max(0, d.omniTicks - 1);
        }

        if (manager.isEnabled(CheckType.SPEED) && !glideGrace && !riptideGrace && !windGrace) {
            double speedRatio = AcUtil.speedRatio(p);
            if (!p.isSprinting() && d.recentSprintTicks > 0) {
                speedRatio *= 1.30;
            }
            double maxH = 0.39 * speedRatio;

            if (!serverGround || !d.lastServerGround) maxH += 0.26;
            if (env.ice || (now - d.lastIce < 1600L)) maxH += 0.45;
            if (env.ceiling || (d.prevEnv != null && d.prevEnv.ceiling)) maxH += 0.28;
            if (env.halfBlock || (d.prevEnv != null && d.prevEnv.halfBlock)) maxH += 0.15;
            if (env.soulSand) {
                int soulSpeed = AcUtil.bootsEnchantLevel(p, "SOUL_SPEED");
                if (soulSpeed > 0) maxH += 0.08 + soulSpeed * 0.06;
            }
            if (env.liquid || (d.prevEnv != null && d.prevEnv.liquid)) {
                int depthStrider = AcUtil.bootsEnchantLevel(p, "DEPTH_STRIDER");
                if (depthStrider > 0) maxH += depthStrider * 0.10;
                if (AcUtil.hasEffect(p, "DOLPHINS_GRACE")) maxH += 0.65;
            }
            if (manager.isInVelocityGrace(d)) maxH += 0.70;

            long packetGap = d.lastMovePacketTime > 0 ? (now - d.lastMovePacketTime) : 50L;
            if (packetGap > 95L && packetGap < 400L) {
                maxH *= Math.min(1.65, packetGap / 55.0);
            }

            CheckSettings sSpeed = manager.settings(CheckType.SPEED);
            double cfgMaxH = sSpeed.d("max-horizontal", 0.0);
            if (cfgMaxH > 0) maxH = Math.max(maxH, cfgMaxH);

            if (hDist > maxH) {
                d.buffer[CheckType.SPEED.ordinal()] += 1.0;
                double maxBuf = sSpeed.d("buffer-limit", 3.0);
                if (d.buffer[CheckType.SPEED.ordinal()] >= maxBuf) {
                    String detail = String.format(Locale.ROOT, "h=%.2f max=%.2f (ratio=%.1f)", hDist, maxH, speedRatio);
                    if (manager.flag(p, d, CheckType.SPEED, detail, 1.0)) {
                        if (sSpeed.isSetback()) {
                            Location safe = manager.pickSetback(d, from, true);
                            if (safe != null) event.setTo(safe);
                            manager.markSetback(d);
                        }
                        d.buffer[CheckType.SPEED.ordinal()] = 1.0;
                    }
                }
            } else {
                d.buffer[CheckType.SPEED.ordinal()] = Math.max(0, d.buffer[CheckType.SPEED.ordinal()] - 0.20);
            }
        }

        if (manager.isEnabled(CheckType.PHASE) && !manager.isInVelocityGrace(d) && !isGliding && !env.piston) {
            if (hDist > 0.16 || Math.abs(dy) > 0.25) {
                Block feet = to.getBlock();
                Block head = to.getWorld().getBlockAt(to.getBlockX(), (int) Math.floor(toY + 1.0), to.getBlockZ());
                Material fm = feet.getType();
                Material hm = head.getType();
                boolean fallingBlock = fm == Material.SAND || fm == Material.RED_SAND || fm == Material.GRAVEL || fm.name().endsWith("CONCRETE_POWDER");
                if (!fallingBlock && fm.isOccluding() && hm.isOccluding() && !feet.isPassable() && !head.isPassable()) {
                    d.buffer[CheckType.PHASE.ordinal()] += 1.0;
                    if (d.buffer[CheckType.PHASE.ordinal()] >= 2.0) {
                        if (manager.flag(p, d, CheckType.PHASE, "In solid " + fm, 1.0)) {
                            Location safe = manager.pickSetback(d, from, false);
                            if (safe != null) event.setTo(safe);
                            manager.markSetback(d);
                        }
                    }
                } else {
                    d.buffer[CheckType.PHASE.ordinal()] = Math.max(0, d.buffer[CheckType.PHASE.ordinal()] - 0.5);
                }
            } else {
                d.buffer[CheckType.PHASE.ordinal()] = Math.max(0, d.buffer[CheckType.PHASE.ordinal()] - 0.25);
            }
        }

        if (isGliding && manager.isEnabled(CheckType.ELYTRA)) {
            double totalSpeed = Math.sqrt(dx * dx + dy * dy + dz * dz);
            CheckSettings sElytra = manager.settings(CheckType.ELYTRA);

            boolean hasFirework = (now - d.lastFirework < 3800L);
            boolean hasRiptide = (now - d.lastRiptide < 3500L);
            boolean hasDamageBoost = (now - d.lastDamage < 2200L) || manager.isInVelocityGrace(d) || windGrace;

            if (dy < -0.18) {
                d.lastSteepDive = now;
            }
            boolean recentDive = (now - d.lastSteepDive < 3000L);

            boolean elyViolation = false;
            String elyReason = null;

            if (dy > 0.10 && !hasFirework && !hasRiptide && !hasDamageBoost && !recentDive) {
                d.elytraAscendTicks++;
                if (d.elytraAscendTicks > 8) {
                    elyViolation = true;
                    elyReason = String.format(Locale.ROOT, "ElytraFly ascend without boost (dy=%.2f)", dy);
                }
            } else {
                d.elytraAscendTicks = Math.max(0, d.elytraAscendTicks - 1);
            }

            if (!elyViolation && dy >= -0.12 && !hasFirework && !hasRiptide && !hasDamageBoost && !recentDive) {
                if (hDist > 1.05) {
                    d.elytraFlatTicks++;
                    if (d.elytraFlatTicks > 10) {
                        elyViolation = true;
                        elyReason = String.format(Locale.ROOT, "ElytraFly flat cruise (h=%.2f, dy=%.2f, max=1.05)", hDist, dy);
                    }
                } else {
                    d.elytraFlatTicks = Math.max(0, d.elytraFlatTicks - 1);
                }
            } else {
                d.elytraFlatTicks = Math.max(0, d.elytraFlatTicks - 1);
            }

            if (!elyViolation && !serverGround && Math.abs(dy) < 0.025 && hDist < 0.10 && !hasFirework && !hasRiptide) {
                d.elytraHoverTicks++;
                if (d.elytraHoverTicks > 18) {
                    elyViolation = true;
                    elyReason = "ElytraFly hover stationary in mid-air";
                }
            } else {
                d.elytraHoverTicks = Math.max(0, d.elytraHoverTicks - 1);
            }

            double cfgMax = sElytra.d("max-speed", 3.6);
            double maxElytra;
            if (hasRiptide) {
                maxElytra = 7.8;
            } else if (hasFirework) {
                maxElytra = 5.8;
            } else if (hasDamageBoost) {
                maxElytra = 3.4;
            } else if (recentDive) {
                maxElytra = Math.max(3.6, cfgMax);
            } else {
                double base = 1.45;
                if (dy < 0) {
                    base += Math.min(2.2, Math.abs(dy) * 3.2);
                }
                long packetGap = d.lastMovePacketTime > 0 ? (now - d.lastMovePacketTime) : 50L;
                if (packetGap > 85L) {
                    base *= Math.min(2.2, packetGap / 50.0);
                }
                if (manager.isLagging() || manager.getTps() < 19.5) {
                    base += 0.45;
                }
                maxElytra = base;
            }

            if (!elyViolation && totalSpeed > maxElytra) {
                elyViolation = true;
                elyReason = String.format(Locale.ROOT, "Speed=%.2f max=%.2f (fw=%s, rip=%s, dive=%s)",
                        totalSpeed, maxElytra, hasFirework ? "yes" : "no", hasRiptide ? "yes" : "no", recentDive ? "yes" : "no");
            }

            if (elyViolation) {
                d.buffer[CheckType.ELYTRA.ordinal()] += 1.0;
                double bufferLimit = sElytra.d("buffer-limit", 4.0);
                if (d.buffer[CheckType.ELYTRA.ordinal()] >= bufferLimit) {
                    if (manager.flag(p, d, CheckType.ELYTRA, elyReason, 1.0)) {
                        if (sElytra.isSetback()) {
                            Location safe = manager.pickSetback(d, from, false);
                            if (safe != null) {
                                event.setTo(safe);
                                try {
                                    p.teleport(safe);
                                } catch (Throwable ignored) {}
                            }
                            manager.markSetback(d);
                        }
                    }
                }
            } else {
                d.buffer[CheckType.ELYTRA.ordinal()] = Math.max(0, d.buffer[CheckType.ELYTRA.ordinal()] - 0.35);
            }
        } else if (!isGliding) {
            d.buffer[CheckType.ELYTRA.ordinal()] = Math.max(0, d.buffer[CheckType.ELYTRA.ordinal()] - 0.5);
            d.elytraAscendTicks = 0;
            d.elytraFlatTicks = 0;
            d.elytraHoverTicks = 0;
        }

        if (manager.isEnabled(CheckType.BARITONE) && !AcUtil.isBedrockPlayer(p)) {
            float rotDelta = AcUtil.angleDiff(to.getYaw(), from.getYaw());
            if (rotDelta > 2.5f) {
                float norm = ((to.getYaw() % 360f) + 360f) % 360f;
                float rem45 = norm % 45f;
                boolean isExactCardinal = rem45 < 0.0008f || (45f - rem45) < 0.0008f;
                if (isExactCardinal) {
                    d.cardinalYawStreak++;
                    if (d.cardinalYawStreak >= 4) {
                        String detail = String.format(Locale.ROOT, "Cardinal yaw-snap yaw=%.4f° (streak=%d)", to.getYaw(), d.cardinalYawStreak);
                        manager.flag(p, d, CheckType.BARITONE, detail, 1.0);
                        d.cardinalYawStreak = 1;
                    }
                } else {
                    d.cardinalYawStreak = Math.max(0, d.cardinalYawStreak - 1);
                }
            }
        }

        if (manager.getPhysicsCheck() != null) {
            boolean setbackNeeded = manager.getPhysicsCheck().processPhysics(p, d, from, to, dx, dy, dz, hDist, serverGround, now);
            if (setbackNeeded) {
                Location safe = manager.pickSetback(d, from, false);
                if (safe != null) {
                    event.setTo(safe);
                    try {
                        p.teleport(safe);
                    } catch (Throwable ignored) {}
                }
                manager.markSetback(d);
            }
        }

        if (serverGround && d.groundTicks >= 3 && hDist < 0.65 && !isGliding && !env.liquid) {
            d.lastSafeGround = to.clone();
            d.setback = to.clone();
        }

        d.lastDx = dx;
        d.lastDy = dy;
        d.lastDz = dz;
        d.lastH = hDist;
        d.lastLoc = to.clone();
    }
}
