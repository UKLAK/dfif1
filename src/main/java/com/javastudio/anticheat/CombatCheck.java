package com.javastudio.anticheat;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.UUID;

/**
 * Kiểm tra các gian lận trong giao tranh (Combat Checks V2):
 * Reach V2, KillAura V2 (Angle, Multi-Target, Snap-Back), HitThroughWall,
 * AimAssist V2 (GCD Sensitivity & Pitch Lock), AutoClicker V2 (CPS & StdDev),
 * NoSwing, Criticals, MultiActions, Hitbox V2.
 *
 * Mốc chuẩn hóa: Normal Player -> Pro PvP -> Hack (Phi thực tế).
 */
public final class CombatCheck {

    private final AntiCheatManager manager;

    public CombatCheck(AntiCheatManager manager) {
        this.manager = manager;
    }

    /**
     * Xử lý khi người chơi tấn công thực thể khác (EntityDamageByEntityEvent).
     */
    @SuppressWarnings("deprecation")
    public void processAttack(Player attacker, PlayerData d, Entity victim, EntityDamageByEntityEvent event) {
        if (manager.isBypassed(attacker, d)) return;
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) return;
        if (manager.isLagging()) return;

        long now = System.currentTimeMillis();
        d.lastAttack = now;

        Location eye = attacker.getEyeLocation();
        BoundingBox victimBox = victim.getBoundingBox();

        // 1. NoSwing Check (Tấn công mà không vung tay)
        if (manager.isEnabled(CheckType.NOSWING)) {
            if (now - d.lastSwing > 350) {
                d.noSwingStreak++;
                if (d.noSwingStreak >= 2) {
                    if (manager.flag(attacker, d, CheckType.NOSWING, "Không vung tay streak=" + d.noSwingStreak, 1.0)) {
                        if (manager.settings(CheckType.NOSWING).isCancel()) {
                            event.setCancelled(true);
                        }
                    }
                }
            } else {
                d.noSwingStreak = 0;
            }
        }

        // 2. MultiActions Check (Vừa ăn/giương cung vừa chém)
        if (manager.isEnabled(CheckType.MULTIACTIONS)) {
            if (attacker.isHandRaised()) {
                if (manager.flag(attacker, d, CheckType.MULTIACTIONS, "Chém khi đang dùng item", 1.0)) {
                    if (manager.settings(CheckType.MULTIACTIONS).isCancel()) {
                        event.setCancelled(true);
                    }
                }
            }
        }

        // 3. Reach Check V2 (Khoảng cách đánh vượt quá giới hạn vật lý sinh tồn)
        // - Người thường: 2.60m - 2.90m
        // - Pro PvP: 2.95m - 3.00m (Trần sinh tồn Minecraft vanilla = 3.00m)
        // - Bù trừ Ping & Đà giật lùi nạn nhân (Backtracking) tối đa lên tới 3.30m - 3.40m
        // - Vượt mốc phi thực tế: > 3.45m trong mọi điều kiện vật lý
        if (manager.isEnabled(CheckType.REACH)) {
            double distance = AcUtil.distanceToBox(eye, victimBox);
            CheckSettings sReach = manager.settings(CheckType.REACH);
            double baseProLimit = sReach.d("max-reach", 3.05);

            int ping = manager.safePing(attacker);
            double pingBonus = Math.min(0.30, ping * 0.0006); // 100ms = 0.06m, trần 0.30m
            double victimMotion = 0.0;
            if (victim instanceof LivingEntity lv && lv.getVelocity().lengthSquared() > 0.04) {
                victimMotion = Math.min(0.20, lv.getVelocity().length() * 0.25);
            }

            double proMaxAllowed = baseProLimit + pingBonus + victimMotion;
            double impossibleThreshold = Math.min(3.45, proMaxAllowed + 0.15);

            if (distance > impossibleThreshold) {
                d.buffer[CheckType.REACH.ordinal()] += 1.0;
                if (d.buffer[CheckType.REACH.ordinal()] >= 2.0) {
                    String detail = String.format(Locale.ROOT, "Reach=%.2fm (ProMax=%.2fm, Ping=%dms)",
                            distance, proMaxAllowed, ping);
                    if (manager.flag(attacker, d, CheckType.REACH, detail, 1.0)) {
                        if (sReach.isCancel()) {
                            event.setCancelled(true);
                        }
                    }
                }
            } else {
                d.buffer[CheckType.REACH.ordinal()] = Math.max(0, d.buffer[CheckType.REACH.ordinal()] - 0.2);
            }
        }

        // 4. HitThroughWall Check (Chém xuyên tường đặc)
        if (manager.isEnabled(CheckType.WALLHIT) && attacker.getWorld() == victim.getWorld()) {
            Vector targetPoint = AcUtil.nearestPoint(eye, victimBox);
            Vector dir = targetPoint.clone().subtract(eye.toVector());
            double dist = dir.length();
            if (dist > 0.5) {
                dir.normalize();
                RayTraceResult trace = attacker.getWorld().rayTraceBlocks(eye, dir, dist - 0.25,
                        FluidCollisionMode.NEVER, true);
                if (trace != null && trace.getHitBlock() != null && trace.getHitBlock().getType().isOccluding()) {
                    if (manager.flag(attacker, d, CheckType.WALLHIT, "Xuyên qua " + trace.getHitBlock().getType(), 1.0)) {
                        if (manager.settings(CheckType.WALLHIT).isCancel()) {
                            event.setCancelled(true);
                        }
                    }
                }
            }
        }

        // 5. KillAura V2 Check (Angle Offset, Silent Snap-Back, Multi-Target Switch)
        if (manager.isEnabled(CheckType.KILLAURA)) {
            Vector lookDir = eye.getDirection().normalize();
            Vector toVictim = victimBox.getCenter().subtract(eye.toVector()).normalize();
            double angleOffset = AcUtil.angleDegrees(lookDir, toVictim);
            double dot = lookDir.dot(toVictim);

            // A. Góc chém lệch tâm phi thực tế (Pro flick tối đa ~55°, >70° hoặc dot < -0.1 là Aura)
            if (angleOffset > 70.0 || dot < -0.10) {
                d.buffer[CheckType.KILLAURA.ordinal()] += 1.0;
                if (d.buffer[CheckType.KILLAURA.ordinal()] >= 2.0) {
                    String detail = String.format(Locale.ROOT, "Lệch tâm %.1f° (ProMax=55°, dot=%.2f)", angleOffset, dot);
                    if (manager.flag(attacker, d, CheckType.KILLAURA, detail, 1.0)) {
                        if (manager.settings(CheckType.KILLAURA).isCancel()) {
                            event.setCancelled(true);
                        }
                    }
                }
            } else {
                d.buffer[CheckType.KILLAURA.ordinal()] = Math.max(0, d.buffer[CheckType.KILLAURA.ordinal()] - 0.2);
            }

            // B. Multi-Target Switch siêu tốc (Đổi mục tiêu góc lệch > 50° trong dưới 65ms)
            UUID victimId = victim.getUniqueId();
            if (d.lastVictimId != null && !d.lastVictimId.equals(victimId)) {
                long switchTime = now - d.lastTargetSwitchTime;
                if (d.lastVictimLoc != null && d.lastVictimLoc.getWorld() == victim.getWorld() && switchTime > 0) {
                    Vector v1 = d.lastVictimLoc.toVector().subtract(eye.toVector()).normalize();
                    Vector v2 = victim.getLocation().toVector().subtract(eye.toVector()).normalize();
                    double targetAngle = AcUtil.angleDegrees(v1, v2);

                    if (targetAngle > 50.0 && switchTime < 65) {
                        String detail = String.format(Locale.ROOT, "Switch %.0f° trong %dms (ProMin=100ms)", targetAngle, switchTime);
                        if (manager.flag(attacker, d, CheckType.KILLAURA, detail, 1.0)) {
                            if (manager.settings(CheckType.KILLAURA).isCancel()) {
                                event.setCancelled(true);
                            }
                        }
                    }
                }
            }
            d.lastVictimId = victimId;
            d.lastVictimLoc = victim.getLocation().clone();
            d.lastTargetSwitchTime = now;

            // C. Silent Aim / Snap-and-Return Detection
            // Ghi nhận góc quay tại thời điểm chém để đối chiếu với tick tiếp theo trong processRotation
            d.lastAttackYaw = eye.getYaw();
            d.lastAttackPitch = eye.getPitch();
            d.lastAttackRotationTime = now;
        }

        // 6. Criticals Check (Gây sát thương chí mạng giả)
        if (manager.isEnabled(CheckType.CRITICALS)) {
            if (attacker.getFallDistance() > 0 && !attacker.isOnGround()) {
                if (d.serverGround && Math.abs(d.lastDy) < 0.001 && d.airTicks == 0) {
                    d.buffer[CheckType.CRITICALS.ordinal()] += 1.0;
                    if (d.buffer[CheckType.CRITICALS.ordinal()] >= 2.0) {
                        manager.flag(attacker, d, CheckType.CRITICALS, "Crit giả trên mặt đất", 1.0);
                        d.buffer[CheckType.CRITICALS.ordinal()] = 0;
                    }
                }
            } else {
                d.buffer[CheckType.CRITICALS.ordinal()] = Math.max(0, d.buffer[CheckType.CRITICALS.ordinal()] - 0.2);
            }
        }

        // 7. Hitbox Expand V2 Check (Hack nới rộng kích thước thực thể / Silent Aura)
        if (manager.isEnabled(CheckType.HITBOX)) {
            Vector dir = eye.getDirection();
            int ping = manager.safePing(attacker);
            // Dung sai tối đa cho tuyển thủ Pro có ping cao: 0.38 + 0.10 = 0.48 block
            double expand = 0.38 + Math.min(0.12, ping * 0.0004);
            BoundingBox expandedBox = victimBox.clone().expand(expand);
            double hitDist = AcUtil.rayIntersectsBox(eye, dir, expandedBox, 6.0);
            if (hitDist < 0) {
                d.buffer[CheckType.HITBOX.ordinal()] += 1.0;
                if (d.buffer[CheckType.HITBOX.ordinal()] >= 2.0) {
                    String detail = String.format(Locale.ROOT, "Lệch ngoài Hitbox (nới rộng=%.2fm, ping=%dms)", expand, ping);
                    if (manager.flag(attacker, d, CheckType.HITBOX, detail, 1.0)) {
                        if (manager.settings(CheckType.HITBOX).isCancel()) {
                            event.setCancelled(true);
                        }
                    }
                    d.buffer[CheckType.HITBOX.ordinal()] = 0;
                }
            } else {
                d.buffer[CheckType.HITBOX.ordinal()] = Math.max(0, d.buffer[CheckType.HITBOX.ordinal()] - 0.2);
            }
        }

        // Tự động kích hoạt bẫy AuraBot khi điểm nghi ngờ KillAura / AimAssist đạt ngưỡng (Hướng 1)
        if (manager.getAuraBotManager() != null && manager.getAuraBotManager().isAutoSpawn()) {
            double totalCombatVl = d.vl[CheckType.KILLAURA.ordinal()] + d.vl[CheckType.AIM.ordinal()];
            if (totalCombatVl >= manager.getAuraBotManager().getVlTrigger() && d.activeAuraBotUuid == null) {
                manager.getAuraBotManager().spawnAuraBot(attacker, 140);
            }
        }
    }

    /**
     * Phân tích chuỗi góc quay (Yaw/Pitch) qua từng tick để phát hiện GCD bất hợp lệ,
     * Snap-and-Return (Silent Aim) và Pitch Lock của AimAssist/KillAura.
     */
    public void processRotation(Player p, PlayerData d, float yawDelta, float pitchDelta) {
        if (manager.isBypassed(p, d)) return;
        long now = System.currentTimeMillis();

        // Theo dõi pitch snap nhanh (AutoPot Heuristic - Hướng 4)
        if (pitchDelta > 60.0f && p.getLocation().getPitch() > 80.0f) {
            d.lastPitchSnapTime = now;
            d.preSnapPitch = p.getLocation().getPitch() - pitchDelta;
        }

        // 1. Silent Aim Snap-Back Check (Chém xong lập tức snap ngược lại góc cũ trong 80ms)
        if (manager.isEnabled(CheckType.AIM) && now - d.lastAttackRotationTime < 80 && d.lastAttackRotationTime > 0) {
            float returnYawDelta = Math.abs(p.getLocation().getYaw() - d.lastAttackYaw);
            if (d.yawDelta > 45.0f && returnYawDelta > 38.0f) {
                String detail = String.format(Locale.ROOT, "Snap-Back Silent Aim (flick=%.1f°, return=%.1f°)",
                        d.yawDelta, returnYawDelta);
                if (manager.flag(p, d, CheckType.AIM, detail, 1.0)) {
                    d.lastAttackRotationTime = 0;
                }
            }
        }

        if (!manager.isEnabled(CheckType.AIM)) return;
        if (yawDelta <= 0.001f && pitchDelta <= 0.001f) return;

        d.yawDeltas.addLast(yawDelta);
        d.pitchDeltas.addLast(pitchDelta);
        while (d.yawDeltas.size() > 20) d.yawDeltas.pollFirst();
        while (d.pitchDeltas.size() > 20) d.pitchDeltas.pollFirst();

        // Phân tích Shannon Entropy & Physiological Tremor (Hướng 1)
        if (manager.getAnomalyEngine() != null) {
            manager.getAnomalyEngine().analyzeCombatMotion(p, d, yawDelta, pitchDelta);
        }

        // 2. GCD & Rotation Smoothness Analysis V2
        // Chỉ phân tích khi người chơi vừa giao tranh hoặc vung tay trong 1200ms
        if (d.pitchDeltas.size() >= 14 && (now - d.lastAttack < 1200 || now - d.lastSwing < 1000)) {
            // Lọc các bước nhảy pitch hợp lệ (chuột di chuyển thật > 0.05 và < 25.0)
            double gcdPitch = 0.0;
            int validSamples = 0;
            for (float pf : d.pitchDeltas) {
                if (pf > 0.05f && pf < 25.0f) {
                    if (gcdPitch == 0.0) {
                        gcdPitch = pf;
                    } else {
                        gcdPitch = AcUtil.gcd(gcdPitch, pf);
                    }
                    validSamples++;
                }
            }

            // Minecraft Sensitivity GCD chuẩn:
            // sens 0% -> gcd 0.0096, sens cao -> gcd 0.03 - 0.08
            // Chuột người chơi thật không bao giờ sinh ra GCD < 0.0009
            // Thuật toán nội suy AimAssist (Bézier/Sin/Lerp) tạo ra các số thực liên tục có GCD cực nhỏ
            if (validSamples >= 8 && gcdPitch > 0.0 && gcdPitch < 0.0009) {
                d.buffer[CheckType.AIM.ordinal()] += 1.0;
                if (d.buffer[CheckType.AIM.ordinal()] >= 3.0) {
                    String detail = String.format(Locale.ROOT, "Smooth Aim GCD=%.6f (ProMin=0.005000)", gcdPitch);
                    manager.flag(p, d, CheckType.AIM, detail, 1.0);
                    d.buffer[CheckType.AIM.ordinal()] = 0;
                }
            } else {
                d.buffer[CheckType.AIM.ordinal()] = Math.max(0, d.buffer[CheckType.AIM.ordinal()] - 0.15);
            }

            // 3. Pitch Lock Check (Khóa tuyệt đối trục Y khi vung chuột nhanh trong giao tranh)
            // Người thật vẩy chuột nhanh (Yaw > 20°) tay luôn có độ rung cơ học (Pitch > 0.02°)
            // Aimbot bám mục tiêu theo chiều ngang thường khóa Pitch == 0.00000°
            if (now - d.lastAttack < 600 && yawDelta > 22.0f && pitchDelta < 0.001f) {
                d.pitchGcdHistory.addLast(1.0);
                while (d.pitchGcdHistory.size() > 6) d.pitchGcdHistory.pollFirst();
                if (d.pitchGcdHistory.size() >= 4) {
                    manager.flag(p, d, CheckType.AIM, "Pitch Lock (Yaw=" + String.format("%.1f", yawDelta) + "°, Pitch=0.00°)", 1.0);
                    d.pitchGcdHistory.clear();
                }
            } else {
                d.pitchGcdHistory.clear();
            }
        }
    }

    /**
     * Xử lý phát hiện AutoTotem: tự trang bị Totem vào offhand siêu tốc sau khi vừa nổ totem.
     */
    public void processTotemEquip(Player p, PlayerData d, org.bukkit.event.Cancellable event) {
        if (manager.isBypassed(p, d) || !manager.isEnabled(CheckType.AUTOTOTEM)) return;
        long now = System.currentTimeMillis();
        long diff = now - d.lastTotemPop;
        if (diff < 45 && d.lastTotemPop > 0) {
            if (manager.flag(p, d, CheckType.AUTOTOTEM, "AutoTotem trong " + diff + "ms", 1.0)) {
                if (manager.settings(CheckType.AUTOTOTEM).isCancel()) {
                    event.setCancelled(true);
                }
            }
        }
        d.lastTotemEquip = now;
    }

    /**
     * Xử lý khi người chơi vung tay (PlayerAnimationEvent) để đo CPS và phân tích độ lệch chuẩn (AutoClicker V2).
     */
    public void processArmSwing(Player p, PlayerData d, PlayerAnimationEvent event) {
        if (manager.isBypassed(p, d)) return;
        long now = System.currentTimeMillis();

        if (d.lastSwing > 0) {
            long delay = now - d.lastSwing;
            if (delay > 0 && delay < 1000) {
                d.clickDelays.addLast(delay);
                while (d.clickDelays.size() > 20) d.clickDelays.pollFirst();
            }
        }
        d.lastSwing = now;

        if (!manager.isEnabled(CheckType.AUTOCLICKER)) return;
        // Bỏ qua nếu đang đào khối
        if (now - d.digStart < 1000) return;

        d.clicks.addLast(now);
        while (!d.clicks.isEmpty() && now - d.clicks.peekFirst() > 1000) {
            d.clicks.pollFirst();
        }

        int cps = d.clicks.size();
        CheckSettings sAuto = manager.settings(CheckType.AUTOCLICKER);
        int cfgMaxCps = sAuto.i("max-cps", 20);

        // Mốc tuyển thủ Pro:
        // - Pro click tối đa: 18 - 20 CPS (Butterfly/Jitter burst).
        // - Bất kể tuyển thủ nào, ngón tay người luôn có biến thiên tự nhiên: StdDev > 6.0ms.
        // - Ngưỡng phi thực tế: CPS > 22 hoặc CPS >= 14 với StdDev < 4.2ms (máy bấm đều chằn chặn).
        boolean impossibleCps = cps > Math.max(22, cfgMaxCps);
        boolean impossibleStdDev = false;
        double stdDev = 999.0;

        if (cps >= 14 && d.clickDelays.size() >= 14) {
            stdDev = AcUtil.standardDeviation(d.clickDelays);
            if (stdDev < 4.2) {
                impossibleStdDev = true;
            }
        }

        if (impossibleCps || impossibleStdDev) {
            d.buffer[CheckType.AUTOCLICKER.ordinal()] += 1.0;
            if (d.buffer[CheckType.AUTOCLICKER.ordinal()] >= 2.0) {
                String detail;
                if (impossibleStdDev) {
                    detail = String.format(Locale.ROOT, "CPS=%d stdDev=%.1fms (ProMinStd=6.0ms)", cps, stdDev);
                } else {
                    detail = String.format(Locale.ROOT, "CPS=%d (ProMax=20)", cps);
                }
                if (manager.flag(p, d, CheckType.AUTOCLICKER, detail, 1.0)) {
                    d.buffer[CheckType.AUTOCLICKER.ordinal()] = 0;
                }
            }
        } else {
            d.buffer[CheckType.AUTOCLICKER.ordinal()] = Math.max(0, d.buffer[CheckType.AUTOCLICKER.ordinal()] - 0.1);
        }
    }
}
