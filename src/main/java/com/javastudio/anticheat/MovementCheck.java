package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Locale;

/**
 * Kiểm tra các gian lận về di chuyển (Movement Checks):
 * Speed, Fly, Jump/Step, NoFall, Jesus, Timer, NoSlow, Phase, InvalidMove, Sprint, Elytra, FastClimb.
 * Tương tự phong cách Matrix / Vulcan / Grim (dựa trên trạng thái mặt đất thực tế và buffer).
 */
public final class MovementCheck {

    private final AntiCheatManager manager;

    public MovementCheck(AntiCheatManager manager) {
        this.manager = manager;
    }

    @SuppressWarnings("deprecation")
    public void processMove(Player p, PlayerData d, PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        long now = System.currentTimeMillis();

        // Cập nhật trạng thái lượn cánh cứng (Elytra)
        boolean isGliding = p.isGliding();
        if (d.wasGliding && !isGliding) {
            d.lastGlideStop = now;
        } else if (!d.wasGliding && isGliding) {
            d.lastGlideStart = now;
        }
        d.wasGliding = isGliding;

        // Thời gian ân hạn sau khi dừng lượn Elytra hoặc phóng Riptide đinh ba
        boolean glideGrace = isGliding || (now - d.lastGlideStop < 2500L);
        boolean riptideGrace = (now - d.lastRiptide < 3500L);

        // 0. Freeze Check: Nếu người chơi đang bị Staff đóng băng kiểm tra
        if (d.isFrozen) {
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                Location frozenAt = d.frozenLocation != null ? d.frozenLocation : from;
                Location lock = frozenAt.clone();
                lock.setYaw(to.getYaw());
                lock.setPitch(to.getPitch());
                event.setTo(lock);
                p.sendTitle("§c§lĐÃ BỊ ĐÓNG BĂNG", "§eStaff đang kiểm tra! Không được ngắt kết nối!", 0, 40, 10);
            }
            return;
        }

        // 1. Kiểm tra tọa độ không hợp lệ (InvalidMove / NaN / Inf)
        double toX = to.getX(), toY = to.getY(), toZ = to.getZ();
        if (Double.isNaN(toX) || Double.isNaN(toY) || Double.isNaN(toZ) ||
                Double.isInfinite(toX) || Double.isInfinite(toY) || Double.isInfinite(toZ) ||
                Math.abs(to.getPitch()) > 90.01f) {
            if (manager.flag(p, d, CheckType.INVALIDMOVE, "NaN/Pitch " + to.getPitch(), 1.0)) {
                event.setTo(manager.pickSetback(d, from, false));
                manager.markSetback(d);
            }
            return;
        }

        // Tính khoảng cách di chuyển
        double dx = toX - from.getX();
        double dy = toY - from.getY();
        double dz = toZ - from.getZ();
        double hDist = Math.hypot(dx, dz);

        // Khoảng cách dịch chuyển quá lớn mà không phải do server teleport (ClickTP, Phase, Teleport Exploit)
        // Khi bay Elytra hoặc phóng Riptide với ping cao / load chunk, nâng ngưỡng để tránh giật lùi
        double maxTeleportH = (glideGrace || riptideGrace) ? 25.0 : 8.0;
        double maxTeleportY = (glideGrace || riptideGrace) ? 30.0 : 10.0;
        if (hDist > maxTeleportH || Math.abs(dy) > maxTeleportY) {
            if (now - d.lastTeleport > manager.getTeleportGraceMs()) {
                Location safe = manager.pickSetback(d, from, true);
                event.setCancelled(true);
                event.setTo(safe);
                try {
                    p.teleport(safe);
                } catch (Throwable ignored) {}
                manager.markSetback(d);
                d.lastLoc = safe.clone();
                manager.flag(p, d, CheckType.INVALIDMOVE, "ClickTP dịch chuyển đột ngột h=" + String.format(Locale.ROOT, "%.1f", hDist) + " dy=" + String.format(Locale.ROOT, "%.1f", dy), 1.0);
                return;
            }
        }

        // 2. Timer Check (Bộ đếm nhịp gửi gói tin)
        if (manager.isEnabled(CheckType.TIMER) && !manager.isMovementExempt(p, d)) {
            if (d.lastMoveMs > 0) {
                long elapsed = now - d.lastMoveMs;
                if (elapsed > 0 && elapsed < 1000) {
                    // Mỗi lần di chuyển chuẩn ~ 50ms (20 tick/s)
                    d.timerBalance += (50.0 - elapsed);
                    // Giới hạn buffer âm tránh dự trữ quá nhiều
                    if (d.timerBalance < -400.0) d.timerBalance = -400.0;

                    CheckSettings sTimer = manager.settings(CheckType.TIMER);
                    double maxBalance = sTimer.d("max-balance", 200.0);
                    if (glideGrace || riptideGrace) maxBalance += 100.0; // Bù tải chunk khi bay nhanh
                    if (d.timerBalance > maxBalance) {
                        d.buffer[CheckType.TIMER.ordinal()] += 1.0;
                        if (d.buffer[CheckType.TIMER.ordinal()] >= sTimer.d("buffer-limit", 4.0)) {
                            if (manager.flag(p, d, CheckType.TIMER, "Balance " + String.format("%.0fms", d.timerBalance), 1.0)) {
                                if (sTimer.isSetback()) {
                                    event.setTo(manager.pickSetback(d, from, false));
                                    manager.markSetback(d);
                                }
                                d.timerBalance = 0;
                            }
                        }
                    } else {
                        d.buffer[CheckType.TIMER.ordinal()] = Math.max(0, d.buffer[CheckType.TIMER.ordinal()] - 0.1);
                    }
                }
            }
            d.lastMoveMs = now;
        }

        // Cập nhật môi trường xung quanh
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

        // Kiểm tra mặt đất thực tế từ server
        boolean serverGround = AcUtil.hasGround(to, 0.15, 0.25) || AcUtil.standingOnEntity(p);
        d.lastServerGround = d.serverGround;
        d.serverGround = serverGround;

        // Bỏ qua nếu người chơi được miễn trừ di chuyển (Creative, Spectator, Flying, Vehicle, Lag, Grace...)
        if (manager.isMovementExempt(p, d)) {
            d.lastLoc = to.clone();
            d.setback = to.clone();
            double pendingFall = d.realFallDist;
            d.resetMovement();
            d.realFallDist = pendingFall;

            // Xử lý tiếp đất nếu đang rơi mà rơi trúng đất trong lúc join grace
            if (serverGround && d.realFallDist > 3.0 && !env.liquid && !env.web) {
                double damage = d.realFallDist - 3.0;
                p.setFallDistance((float) d.realFallDist);
                if (damage > 0) {
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

            // Xử lý NoFall khi tiếp đất: Bắt gian lận gửi gói xóa fallDistance
            if (d.realFallDist > 3.0 && !glideGrace && !riptideGrace && !env.liquid && !env.web && (now - d.lastBounce > 1500)) {
                float clientFall = p.getFallDistance();
                if (clientFall < (float)(d.realFallDist - 1.5)) {
                    manager.flag(p, d, CheckType.NOFALL, String.format(Locale.ROOT, "NoFall landing (real=%.1fm, client=%.1fm)", d.realFallDist, clientFall), 1.0);
                    // Ép nhận đúng sát thương rơi của Minecraft Vanilla: (blocks - 3) * 1.0 HP
                    double damage = d.realFallDist - 3.0;
                    p.setFallDistance((float) d.realFallDist);
                    if (damage > 0) {
                        try {
                            p.damage(damage);
                        } catch (Throwable ignored) {}
                    }
                }
            }
            d.realFallDist = 0;
            d.fallDist = 0;
        } else {
            d.airTicks++;
            d.groundTicks = 0;
            if (isGliding) {
                d.realFallDist = 0;
                d.fallDist = 0;
                d.hoverTicks = 0;
            } else if (dy < 0 && !env.liquid && !env.climbable && !env.web) {
                d.realFallDist += -dy;
                d.fallDist += -dy;
            }
        }

        // 3. NoFall Check giữa trời (Client báo onGround nhưng server thấy đang rơi giữa không trung)
        if (manager.isEnabled(CheckType.NOFALL) && !glideGrace && !riptideGrace) {
            boolean clientGround = false;
            try {
                clientGround = p.isOnGround();
            } catch (Throwable ignored) {}

            if (clientGround && !serverGround && d.airTicks > 4 && d.realFallDist > 2.0 && !env.liquid && !env.climbable && !env.web) {
                if (!AcUtil.hasGround(to, 0.7, 0.1)) {
                    d.buffer[CheckType.NOFALL.ordinal()] += 1.0;
                    // Ép lại fallDistance cho player entity trên server
                    p.setFallDistance((float) d.realFallDist);
                    if (d.buffer[CheckType.NOFALL.ordinal()] >= 2.0) {
                        manager.flag(p, d, CheckType.NOFALL, "Spoofed ground in mid-air (dist=" + String.format(Locale.ROOT, "%.1f", d.realFallDist) + ")", 1.0);
                        d.buffer[CheckType.NOFALL.ordinal()] = 1.0;
                    }
                }
            } else {
                d.buffer[CheckType.NOFALL.ordinal()] = Math.max(0, d.buffer[CheckType.NOFALL.ordinal()] - 0.2);
            }
        }

        // 4. Jesus Check (Đi bộ / lướt trên mặt nước/dung nham)
        if (manager.isEnabled(CheckType.JESUS) && !isGliding) {
            if (env.liquidBelow && !env.liquid && !serverGround && hDist > 0.2) {
                if (Math.abs(dy) < 0.001) {
                    d.liquidWalkTicks++;
                    if (d.liquidWalkTicks > 4) {
                        if (manager.flag(p, d, CheckType.JESUS, "Hover on liquid (ticks=" + d.liquidWalkTicks + ")", 1.0)) {
                            CheckSettings s = manager.settings(CheckType.JESUS);
                            if (s.isSetback()) {
                                event.setTo(manager.pickSetback(d, from, true));
                                manager.markSetback(d);
                            }
                        }
                    }
                } else {
                    d.liquidWalkTicks = Math.max(0, d.liquidWalkTicks - 1);
                }
            } else {
                d.liquidWalkTicks = 0;
            }
        }

        // 5. Jump / HighStep Check (Nhảy quá cao hoặc bước lên khối quá 0.6)
        if (manager.isEnabled(CheckType.JUMP) && !manager.isInVelocityGrace(d) && !glideGrace && !riptideGrace) {
            if (!d.lastServerGround && d.groundTicks == 0 && dy > 0) {
                int jb = AcUtil.jumpBoost(p);
                double maxDy = 0.55 + (jb * 0.15);
                if (now - d.lastBounce < 1500) maxDy += 1.5; // Đệm nảy slime/bed

                if (dy > maxDy && !env.special()) {
                    d.buffer[CheckType.JUMP.ordinal()] += 1.0;
                    if (d.buffer[CheckType.JUMP.ordinal()] >= 2.0) {
                        if (manager.flag(p, d, CheckType.JUMP, "dy=" + String.format("%.3f", dy) + " max=" + String.format("%.2f", maxDy), 1.0)) {
                            CheckSettings s = manager.settings(CheckType.JUMP);
                            if (s.isSetback()) {
                                event.setTo(manager.pickSetback(d, from, true));
                                manager.markSetback(d);
                            }
                            d.buffer[CheckType.JUMP.ordinal()] = 0;
                        }
                    }
                } else {
                    d.buffer[CheckType.JUMP.ordinal()] = Math.max(0, d.buffer[CheckType.JUMP.ordinal()] - 0.1);
                }
            }
        }

        // 6. Fly Check (Bay lơ lửng, bay lên không trọng lực)
        if (manager.isEnabled(CheckType.FLY) && !manager.isInVelocityGrace(d) && !glideGrace && !riptideGrace) {
            boolean inAir = !serverGround && !d.lastServerGround;
            if (inAir && !env.special() && (now - d.lastSpecial > 1200) && (now - d.lastBounce > 1500)) {
                // Hover (lơ lửng giữa không trung)
                if (Math.abs(dy) < 0.005) {
                    d.hoverTicks++;
                    if (d.hoverTicks > 6) {
                        d.buffer[CheckType.FLY.ordinal()] += 1.0;
                        if (d.buffer[CheckType.FLY.ordinal()] >= 2.0) {
                            if (manager.flag(p, d, CheckType.FLY, "Hovering dy=" + String.format("%.4f", dy) + " ticks=" + d.hoverTicks, 1.0)) {
                                CheckSettings s = manager.settings(CheckType.FLY);
                                if (s.isSetback()) {
                                    event.setTo(manager.pickSetback(d, from, true));
                                    manager.markSetback(d);
                                }
                            }
                        }
                    }
                } else {
                    d.hoverTicks = Math.max(0, d.hoverTicks - 1);
                }

                // Ascend (bay lên giữa trời mà không có vận tốc đệm)
                if (dy > 0 && d.airTicks > 12 && d.lastDy >= 0) {
                    d.buffer[CheckType.FLY.ordinal()] += 1.5;
                    if (d.buffer[CheckType.FLY.ordinal()] >= 3.0) {
                        if (manager.flag(p, d, CheckType.FLY, "Ascend in air (airTicks=" + d.airTicks + ", dy=" + String.format("%.2f", dy) + ")", 1.0)) {
                            CheckSettings s = manager.settings(CheckType.FLY);
                            if (s.isSetback()) {
                                event.setTo(manager.pickSetback(d, from, true));
                                manager.markSetback(d);
                            }
                            d.buffer[CheckType.FLY.ordinal()] = 0;
                        }
                    }
                }
            } else {
                d.hoverTicks = 0;
                d.buffer[CheckType.FLY.ordinal()] = Math.max(0, d.buffer[CheckType.FLY.ordinal()] - 0.2);
            }
        } else if (glideGrace) {
            d.hoverTicks = 0;
            d.buffer[CheckType.FLY.ordinal()] = Math.max(0, d.buffer[CheckType.FLY.ordinal()] - 0.5);
        }

        // 7. FastClimb Check (Leo thang / dây leo với tốc độ phi lý)
        if (manager.isEnabled(CheckType.FASTCLIMB) && env.climbable && !manager.isInVelocityGrace(d) && !glideGrace) {
            if (dy > 0.288) { // Tốc độ leo tối đa bình thường ~0.15
                if (manager.flag(p, d, CheckType.FASTCLIMB, "Climb dy=" + String.format("%.2f", dy), 1.0)) {
                    CheckSettings s = manager.settings(CheckType.FASTCLIMB);
                    if (s.isSetback()) {
                        event.setTo(manager.pickSetback(d, from, false));
                        manager.markSetback(d);
                    }
                }
            }
        }

        // 8. NoSlow Check (Di chuyển nhanh bất thường khi đang ăn/giương cung/chắn khiên)
        if (manager.isEnabled(CheckType.NOSLOW) && !manager.isInVelocityGrace(d)) {
            boolean usingItem = p.isHandRaised();
            if (usingItem && !env.ice && (now - d.lastIce > 1000) && serverGround) {
                // Khi dùng item tốc độ tối đa khoảng ~0.15 (0.2 * 0.2)
                double speedRatio = AcUtil.speedRatio(p);
                double maxUseH = 0.24 * speedRatio;
                if (hDist > maxUseH) {
                    d.useTicks++;
                    if (d.useTicks > 4) {
                        if (manager.flag(p, d, CheckType.NOSLOW, "Using item speed=" + String.format("%.2f", hDist) + " max=" + String.format("%.2f", maxUseH), 1.0)) {
                            CheckSettings s = manager.settings(CheckType.NOSLOW);
                            if (s.isSetback()) {
                                event.setTo(manager.pickSetback(d, from, false));
                                manager.markSetback(d);
                            }
                        }
                    }
                } else {
                    d.useTicks = Math.max(0, d.useTicks - 1);
                }
            } else {
                d.useTicks = 0;
            }
        }

        // 9. Speed Check (Tốc độ ngang vượt trần vật lý)
        if (manager.isEnabled(CheckType.SPEED) && !glideGrace && !riptideGrace) {
            double speedRatio = AcUtil.speedRatio(p);
            // Giới hạn tốc độ cơ sở: đi bộ ~0.22, chạy ~0.28, nhảy chạy ~0.45
            double maxH = 0.38 * speedRatio;

            // Thêm dung sai môi trường
            if (!serverGround || !d.lastServerGround) maxH += 0.25; // Nhảy hoặc bay đà
            if (env.ice || (now - d.lastIce < 1500)) maxH += 0.40; // Trượt băng
            if (env.ceiling) maxH += 0.25; // Đầu đụng trần nhà khi chạy nhảy
            if (manager.isInVelocityGrace(d)) maxH += 0.60; // Knockback

            CheckSettings sSpeed = manager.settings(CheckType.SPEED);
            double cfgMaxH = sSpeed.d("max-horizontal", 0.0);
            if (cfgMaxH > 0) maxH = Math.max(maxH, cfgMaxH);

            if (hDist > maxH) {
                d.buffer[CheckType.SPEED.ordinal()] += 1.0;
                double maxBuf = sSpeed.d("buffer-limit", 3.0);
                if (d.buffer[CheckType.SPEED.ordinal()] >= maxBuf) {
                    String detail = String.format("h=%.2f max=%.2f (ratio=%.1f)", hDist, maxH, speedRatio);
                    if (manager.flag(p, d, CheckType.SPEED, detail, 1.0)) {
                        if (sSpeed.isSetback()) {
                            event.setTo(manager.pickSetback(d, from, true));
                            manager.markSetback(d);
                        }
                    }
                }
            } else {
                d.buffer[CheckType.SPEED.ordinal()] = Math.max(0, d.buffer[CheckType.SPEED.ordinal()] - 0.15);
            }
        }

        // 10. Phase Check (Đi xuyên khối rắn ngột ngạt)
        // Khi bay Elytra chiều cao hitbox người chơi giảm còn 0.6 block, bỏ qua kiểm tra đầu ở +1.0
        if (manager.isEnabled(CheckType.PHASE) && !manager.isInVelocityGrace(d) && !isGliding) {
            Block feet = to.getBlock();
            Block head = to.clone().add(0, 1.0, 0).getBlock();
            if (feet.getType().isOccluding() && head.getType().isOccluding()) {
                // Cả chân và đầu đều chìm trong khối đặc
                if (!feet.isPassable() && !head.isPassable()) {
                    d.buffer[CheckType.PHASE.ordinal()] += 1.0;
                    if (d.buffer[CheckType.PHASE.ordinal()] >= 2.0) {
                        if (manager.flag(p, d, CheckType.PHASE, "In solid " + feet.getType(), 1.0)) {
                            event.setTo(manager.pickSetback(d, from, false));
                            manager.markSetback(d);
                        }
                    }
                }
            } else {
                d.buffer[CheckType.PHASE.ordinal()] = Math.max(0, d.buffer[CheckType.PHASE.ordinal()] - 0.5);
            }
        }

        // 11. Elytra Check (Tốc độ bay cánh cứng)
        // 11. Elytra Check V2 (Chống ElytraFly: Flat Cruise, Ascend, Hover, Speed Cap)
        if (isGliding && manager.isEnabled(CheckType.ELYTRA)) {
            double totalSpeed = Math.sqrt(dx * dx + dy * dy + dz * dz);
            CheckSettings sElytra = manager.settings(CheckType.ELYTRA);

            boolean hasFirework = (now - d.lastFirework < 3500L);
            boolean hasRiptide = (now - d.lastRiptide < 3000L);
            boolean hasDamageBoost = (now - d.lastDamage < 2000L) || manager.isInVelocityGrace(d);

            // Ghi nhận bổ nhào dốc (Steep Dive): Trong vanilla tốc độ cao chỉ sinh ra khi chúc đầu lao xuống (dy < -0.40)
            if (dy < -0.40) {
                d.lastSteepDive = now;
            }
            boolean recentDive = (now - d.lastSteepDive < 2500L);

            boolean elyViolation = false;
            String elyReason = null;

            // Subcheck 11.1: ElytraFly Ascend (Bay ngửa lên trời liên tục mà không có pháo hoa/đinh ba/đà dive)
            if (dy > 0.08 && !hasFirework && !hasRiptide && !hasDamageBoost && !recentDive) {
                d.elytraAscendTicks++;
                if (d.elytraAscendTicks > 6) {
                    elyViolation = true;
                    elyReason = String.format(Locale.ROOT, "ElytraFly ascend without boost (dy=%.2f)", dy);
                }
            } else {
                d.elytraAscendTicks = Math.max(0, d.elytraAscendTicks - 1);
            }

            // Subcheck 11.2: ElytraFly Flat Cruise (Bay ngang lượn bằng phẳng với tốc độ cao bất tận mà không mất cao độ)
            // Trong Vanilla không có pháo hoa: bay ngang (dy >= -0.15) sẽ nhanh chóng giảm tốc xuống dưới 0.65m/tick
            if (!elyViolation && dy >= -0.15 && !hasFirework && !hasRiptide && !hasDamageBoost && !recentDive) {
                if (hDist > 0.85) {
                    d.elytraFlatTicks++;
                    if (d.elytraFlatTicks > 7) {
                        elyViolation = true;
                        elyReason = String.format(Locale.ROOT, "ElytraFly flat cruise (h=%.2f, dy=%.2f, max=0.85)", hDist, dy);
                    }
                } else {
                    d.elytraFlatTicks = Math.max(0, d.elytraFlatTicks - 1);
                }
            } else {
                d.elytraFlatTicks = Math.max(0, d.elytraFlatTicks - 1);
            }

            // Subcheck 11.3: ElytraFly Hover (Treo mình lơ lửng một chỗ giữa không trung khi đang bật cánh)
            if (!elyViolation && !serverGround && Math.abs(dy) < 0.03 && hDist < 0.12 && !hasFirework && !hasRiptide) {
                d.elytraHoverTicks++;
                if (d.elytraHoverTicks > 16) {
                    elyViolation = true;
                    elyReason = "ElytraFly hover stationary in mid-air";
                }
            } else {
                d.elytraHoverTicks = Math.max(0, d.elytraHoverTicks - 1);
            }

            // Subcheck 11.4: Dynamic Speed Cap
            double maxElytra;
            if (hasRiptide) {
                maxElytra = 7.5;
            } else if (hasFirework) {
                maxElytra = 5.5;
            } else if (hasDamageBoost) {
                maxElytra = 3.0;
            } else if (recentDive) {
                maxElytra = 3.6; // Đang hoặc vừa bổ nhào dốc có gia tốc trọng lực
            } else {
                maxElytra = 1.15; // Bay lượn thông thường không có động lực hỗ trợ
            }

            if (!elyViolation && totalSpeed > maxElytra) {
                elyViolation = true;
                elyReason = String.format(Locale.ROOT, "Speed=%.2f max=%.2f (fw=%s, rip=%s, dive=%s)",
                        totalSpeed, maxElytra, hasFirework ? "yes" : "no", hasRiptide ? "yes" : "no", recentDive ? "yes" : "no");
            }

            // Xử lý vi phạm Elytra
            if (elyViolation) {
                d.buffer[CheckType.ELYTRA.ordinal()] += 1.0;
                double bufferLimit = sElytra.d("buffer-limit", 3.0);
                if (d.buffer[CheckType.ELYTRA.ordinal()] >= bufferLimit) {
                    if (manager.flag(p, d, CheckType.ELYTRA, elyReason, 1.0)) {
                        if (sElytra.isSetback()) {
                            Location safe = manager.pickSetback(d, from, false);
                            event.setTo(safe);
                            try {
                                p.teleport(safe);
                            } catch (Throwable ignored) {}
                            manager.markSetback(d);
                        }
                    }
                }
            } else {
                d.buffer[CheckType.ELYTRA.ordinal()] = Math.max(0, d.buffer[CheckType.ELYTRA.ordinal()] - 0.25);
            }
        } else if (!isGliding) {
            d.buffer[CheckType.ELYTRA.ordinal()] = Math.max(0, d.buffer[CheckType.ELYTRA.ordinal()] - 0.5);
            d.elytraAscendTicks = 0;
            d.elytraFlatTicks = 0;
            d.elytraHoverTicks = 0;
        }

        // 12. Baritone Cardinal Yaw-Snap Check (Hướng 4: Phát hiện bot/baritone snap góc quay chính xác 0/45/90/135/180/225/270/315 độ)
        if (manager.isEnabled(CheckType.BARITONE)) {
            float toYaw = to.getYaw();
            float fromYaw = from.getYaw();
            float rotDelta = Math.abs(toYaw - fromYaw);
            if (rotDelta > 2.0f) {
                float norm = ((toYaw % 360f) + 360f) % 360f;
                float rem45 = norm % 45f;
                boolean isExactCardinal = rem45 < 0.0008f || (45f - rem45) < 0.0008f;
                if (isExactCardinal) {
                    d.cardinalYawStreak++;
                    if (d.cardinalYawStreak >= 3) {
                        String detail = String.format(Locale.ROOT, "Cardinal yaw-snap yaw=%.4f° (streak=%d)", toYaw, d.cardinalYawStreak);
                        manager.flag(p, d, CheckType.BARITONE, detail, 1.0);
                    }
                } else {
                    d.cardinalYawStreak = Math.max(0, d.cardinalYawStreak - 1);
                }
            }
        }

        // 13. Exact Physics Simulation & Timer/Blink Guard (Hướng 2)
        if (manager.getPhysicsCheck() != null) {
            boolean setbackNeeded = manager.getPhysicsCheck().processPhysics(p, d, from, to, dx, dy, dz, hDist, serverGround);
            if (setbackNeeded) {
                Location safe = manager.pickSetback(d, from, false);
                event.setTo(safe);
                try {
                    p.teleport(safe);
                } catch (Throwable ignored) {}
                manager.markSetback(d);
            }
        }

        // Cập nhật vị trí mặt đất an toàn hợp lệ (Chống ClickTP/Relog Exploit)
        // Chỉ lưu khi người chơi thực sự đứng trên đất, không đang bay, và tốc độ bình thường
        if (serverGround && d.groundTicks >= 3 && hDist < 0.65 && !isGliding) {
            d.lastSafeGround = to.clone();
            d.setback = to.clone();
        }

        // Lưu trạng thái tick này
        d.lastDx = dx;
        d.lastDy = dy;
        d.lastDz = dz;
        d.lastH = hDist;
        d.lastLoc = to.clone();
    }
}
