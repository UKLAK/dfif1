package com.javastudio.anticheat;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.UUID;

public final class CombatCheck {

    private final AntiCheatManager manager;

    public CombatCheck(AntiCheatManager manager) {
        this.manager = manager;
    }

    @SuppressWarnings("deprecation")
    public void processAttack(Player attacker, PlayerData d, Entity victim, EntityDamageByEntityEvent event) {
        if (manager.isBypassed(attacker, d)) return;
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) return;
        if (manager.isLagging()) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (attacker.isBlocking()) return;

        long now = System.currentTimeMillis();
        d.lastAttack = now;

        Location eye = attacker.getEyeLocation();
        BoundingBox victimBox = victim.getBoundingBox();
        boolean complexEntity = victim instanceof ComplexLivingEntity || victim instanceof ComplexEntityPart;
        boolean bedrock = AcUtil.isBedrockPlayer(attacker);

        if (manager.isEnabled(CheckType.NOSWING)) {
            if (d.pendingSwingCheck && (now - d.pendingSwingAttackAt > 65L) && (now - d.pendingSwingAttackAt < 4000L)) {
                d.noSwingStreak++;
                if (d.noSwingStreak >= 3) {
                    if (manager.flag(attacker, d, CheckType.NOSWING, "Không vung tay streak=" + d.noSwingStreak, 1.0)) {
                        if (manager.settings(CheckType.NOSWING).isCancel()) {
                            event.setCancelled(true);
                            event.setDamage(0.0);
                        }
                    }
                }
            }
            if (now - d.lastSwing <= 25L) {
                d.pendingSwingCheck = false;
                d.noSwingStreak = 0;
            } else {
                d.pendingSwingCheck = true;
                d.pendingSwingAttackAt = now;
            }
        }

        if (manager.isEnabled(CheckType.MULTIACTIONS)) {
            if (attacker.isHandRaised() && !attacker.isBlocking()) {
                if (manager.flag(attacker, d, CheckType.MULTIACTIONS, "Chém khi đang dùng item", 1.0)) {
                    if (manager.settings(CheckType.MULTIACTIONS).isCancel()) {
                        event.setCancelled(true);
                        event.setDamage(0.0);
                    }
                }
            } else if (d.invState == PlayerData.InvState.OPEN && (now - d.lastContainerClickTime < 1200L)) {
                if (manager.flag(attacker, d, CheckType.MULTIACTIONS, "Chém khi đang mở & thao tác rương/túi đồ", 1.0)) {
                    if (manager.settings(CheckType.MULTIACTIONS).isCancel()) {
                        event.setCancelled(true);
                        event.setDamage(0.0);
                    }
                }
            }
        }

        BacktrackEngine.BacktrackResult bt = (!complexEntity && manager.getBacktrackEngine() != null)
                ? manager.getBacktrackEngine().evaluateAttack(attacker, victim)
                : null;

        if (!complexEntity && manager.isEnabled(CheckType.REACH)) {
            double distance = (bt != null) ? bt.minDistance() : AcUtil.distanceToBox(eye, victimBox);
            CheckSettings sReach = manager.settings(CheckType.REACH);
            double attrReach = AcUtil.entityReach(attacker);
            double baseProLimit = Math.max(sReach.d("max-reach", 3.05), attrReach + 0.05);

            int ping = manager.safePing(attacker);
            double maxAllowed;
            if (bt != null && bt.hasHistory()) {
                maxAllowed = baseProLimit + 0.10 + Math.min(0.18, ping * 0.0004);
            } else {
                maxAllowed = baseProLimit + 0.22 + Math.min(0.35, ping * 0.0012);
            }
            if (bedrock) {
                maxAllowed += 0.25;
            }

            if (distance > maxAllowed) {
                d.buffer[CheckType.REACH.ordinal()] += 1.0;
                if (d.buffer[CheckType.REACH.ordinal()] >= 2.0) {
                    int ticksBack = (bt != null) ? bt.ticksBack() : 0;
                    String detail = String.format(Locale.ROOT, "Reach=%.2fm (Max=%.2fm, Ping=%dms, Backtrack=%dticks, RayHit=%s)",
                            distance, maxAllowed, ping, ticksBack, (bt != null && bt.rayIntersects()));
                    if (manager.flag(attacker, d, CheckType.REACH, detail, 1.0)) {
                        if (sReach.isCancel()) {
                            event.setCancelled(true);
                            event.setDamage(0.0);
                        }
                    }
                }
            } else {
                d.buffer[CheckType.REACH.ordinal()] = Math.max(0, d.buffer[CheckType.REACH.ordinal()] - 0.25);
            }

            if (manager.getNeuralHeuristicEngine() != null && !bedrock) {
                var eval = manager.getNeuralHeuristicEngine().evaluateAttack(attacker, d, victim, distance, bt != null && bt.rayIntersects());
                d.neuralScore = eval.anomalyScore();
                d.neuralClassification = eval.classification().name();
                if (eval.isFlagWorthy() && manager.isEnabled(CheckType.KILLAURA)) {
                    d.buffer[CheckType.KILLAURA.ordinal()] += 1.0;
                    if (d.buffer[CheckType.KILLAURA.ordinal()] >= 3.0) {
                        String neuralDetail = String.format(Locale.ROOT, "NeuralScore=%.2f (%s, TopFeature=%s, Ping=%dms)",
                                eval.anomalyScore(), eval.classification(), eval.primaryFeature(), ping);
                        if (manager.flag(attacker, d, CheckType.KILLAURA, neuralDetail, 1.0)) {
                            if (manager.settings(CheckType.KILLAURA).isCancel()) {
                                event.setCancelled(true);
                                event.setDamage(0.0);
                            }
                        }
                        d.buffer[CheckType.KILLAURA.ordinal()] = 1.0;
                    }
                } else if (eval.isSuspicious() && manager.getAuraBotManager() != null && d.activeAuraBotUuid == null) {
                    manager.getAuraBotManager().spawnAuraBot(attacker, 120);
                }
            }
        }

        if (!complexEntity && manager.isEnabled(CheckType.WALLHIT) && attacker.getWorld() == victim.getWorld()) {
            if (isCompletelyWallOccluded(eye, victimBox)) {
                d.buffer[CheckType.WALLHIT.ordinal()] += 1.0;
                if (manager.settings(CheckType.WALLHIT).isCancel()) {
                    event.setCancelled(true);
                    event.setDamage(0.0);
                }
                if (d.buffer[CheckType.WALLHIT.ordinal()] >= 2.0) {
                    manager.flag(attacker, d, CheckType.WALLHIT, "Đánh xuyên tường đặc (0/3 tia nhìn thấy mục tiêu)", 1.0);
                    d.buffer[CheckType.WALLHIT.ordinal()] = 1.0;
                }
            } else {
                d.buffer[CheckType.WALLHIT.ordinal()] = Math.max(0, d.buffer[CheckType.WALLHIT.ordinal()] - 0.5);
            }
        }

        if (!complexEntity && manager.isEnabled(CheckType.KILLAURA)) {
            Vector lookDir = eye.getDirection().normalize();
            BoundingBox checkBox = (bt != null && bt.hasHistory()) ? bt.historicalBox() : victimBox;
            Vector toVictim = checkBox.getCenter().subtract(eye.toVector());
            double distCenter = toVictim.length();
            if (distCenter > 0.85) {
                toVictim.multiply(1.0 / distCenter);
                double angleOffset = AcUtil.angleDegrees(lookDir, toVictim);
                double dot = lookDir.dot(toVictim);

                if (angleOffset > 78.0 || dot < -0.15) {
                    d.buffer[CheckType.KILLAURA.ordinal()] += 1.0;
                    if (d.buffer[CheckType.KILLAURA.ordinal()] >= 2.0) {
                        String detail = String.format(Locale.ROOT, "Lệch tâm %.1f° (ProMax=65°, dot=%.2f)", angleOffset, dot);
                        if (manager.flag(attacker, d, CheckType.KILLAURA, detail, 1.0)) {
                            if (manager.settings(CheckType.KILLAURA).isCancel()) {
                                event.setCancelled(true);
                                event.setDamage(0.0);
                            }
                        }
                    }
                } else {
                    d.buffer[CheckType.KILLAURA.ordinal()] = Math.max(0, d.buffer[CheckType.KILLAURA.ordinal()] - 0.2);
                }
            }

            UUID victimId = victim.getUniqueId();
            if (d.lastVictimId != null && !d.lastVictimId.equals(victimId)) {
                long switchTime = now - d.lastTargetSwitchTime;
                if (d.lastVictimLoc != null && d.lastVictimLoc.getWorld() == victim.getWorld() && switchTime > 5L) {
                    Vector v1 = d.lastVictimLoc.toVector().subtract(eye.toVector());
                    Vector v2 = victim.getLocation().toVector().subtract(eye.toVector());
                    if (v1.lengthSquared() > 0.5 && v2.lengthSquared() > 0.5) {
                        v1.normalize();
                        v2.normalize();
                        double targetAngle = AcUtil.angleDegrees(v1, v2);
                        if (targetAngle > 55.0 && switchTime < 55L) {
                            String detail = String.format(Locale.ROOT, "Switch %.0f° trong %dms (ProMin=100ms)", targetAngle, switchTime);
                            if (manager.flag(attacker, d, CheckType.KILLAURA, detail, 1.0)) {
                                if (manager.settings(CheckType.KILLAURA).isCancel()) {
                                    event.setCancelled(true);
                                    event.setDamage(0.0);
                                }
                            }
                        }
                    }
                }
            }
            d.lastVictimId = victimId;
            d.lastVictimLoc = victim.getLocation().clone();
            d.lastTargetSwitchTime = now;

            d.lastAttackYaw = eye.getYaw();
            d.lastAttackPitch = eye.getPitch();
            d.lastAttackRotationTime = now;
        }

        if (manager.isEnabled(CheckType.CRITICALS)) {
            boolean nearSpecial = d.lastEnv != null && (d.lastEnv.special() || d.lastEnv.halfBlock || d.lastEnv.ceiling);
            boolean claimingCrit = attacker.getFallDistance() > 0.0f && !attacker.isOnGround()
                    && !attacker.isInsideVehicle() && !attacker.isInWater() && !attacker.isClimbing();
            if (claimingCrit && !nearSpecial && !manager.isInVelocityGrace(d)) {
                boolean groundSpoofCrit = d.serverGround && d.airTicks == 0 && Math.abs(d.lastDy) < 0.065;
                boolean packetMicroCrit = d.realFallDist < 0.065 && Math.abs(d.lastDy) < 0.070 && d.airTicks <= 2;
                if (groundSpoofCrit || packetMicroCrit) {
                    attacker.setFallDistance(0.0f);
                    if (manager.settings(CheckType.CRITICALS).isCancel()) {
                        event.setCancelled(true);
                        event.setDamage(0.0);
                    }
                    d.buffer[CheckType.CRITICALS.ordinal()] += 1.0;
                    if (d.buffer[CheckType.CRITICALS.ordinal()] >= 1.5) {
                        String reason = groundSpoofCrit ? "Crit giả trên mặt đất" : "Packet/Mini-Jump Criticals";
                        manager.flag(attacker, d, CheckType.CRITICALS, reason, 1.0);
                        event.setCancelled(true);
                        event.setDamage(0.0);
                        d.buffer[CheckType.CRITICALS.ordinal()] = 0.5;
                    }
                } else {
                    d.buffer[CheckType.CRITICALS.ordinal()] = Math.max(0, d.buffer[CheckType.CRITICALS.ordinal()] - 0.25);
                }
            } else {
                d.buffer[CheckType.CRITICALS.ordinal()] = Math.max(0, d.buffer[CheckType.CRITICALS.ordinal()] - 0.2);
            }
        }

        if (!complexEntity && manager.isEnabled(CheckType.HITBOX)) {
            Vector dir = eye.getDirection();
            int ping = manager.safePing(attacker);
            double expand = 0.40 + Math.min(0.18, ping * 0.0005) + (bedrock ? 0.18 : 0.0);
            BoundingBox expandedCurrent = victimBox.clone().expand(expand);
            boolean hitAny = AcUtil.rayIntersectsBox(eye, dir, expandedCurrent, 6.5) >= 0.0;
            if (!hitAny && bt != null && bt.hasHistory()) {
                BoundingBox expandedHist = bt.historicalBox().clone().expand(expand);
                hitAny = AcUtil.rayIntersectsBox(eye, dir, expandedHist, 6.5) >= 0.0;
            }
            if (!hitAny) {
                d.buffer[CheckType.HITBOX.ordinal()] += 1.0;
                if (d.buffer[CheckType.HITBOX.ordinal()] >= 2.5) {
                    String detail = String.format(Locale.ROOT, "Lệch ngoài Hitbox (nới rộng=%.2fm, ping=%dms)", expand, ping);
                    if (manager.flag(attacker, d, CheckType.HITBOX, detail, 1.0)) {
                        if (manager.settings(CheckType.HITBOX).isCancel()) {
                            event.setCancelled(true);
                            event.setDamage(0.0);
                        }
                    }
                    d.buffer[CheckType.HITBOX.ordinal()] = 0.5;
                }
            } else {
                d.buffer[CheckType.HITBOX.ordinal()] = Math.max(0, d.buffer[CheckType.HITBOX.ordinal()] - 0.25);
            }
        }

        if (manager.getAuraBotManager() != null && manager.getAuraBotManager().isAutoSpawn()) {
            double totalCombatVl = d.vl[CheckType.KILLAURA.ordinal()] + d.vl[CheckType.AIM.ordinal()];
            if (totalCombatVl >= manager.getAuraBotManager().getVlTrigger() && d.activeAuraBotUuid == null) {
                manager.getAuraBotManager().spawnAuraBot(attacker, 140);
            }
        }
    }

    private boolean isCompletelyWallOccluded(Location eye, BoundingBox victimBox) {
        if (eye.getWorld() == null) return false;
        double h = victimBox.getHeight();
        Vector[] targets = new Vector[]{
                AcUtil.nearestPoint(eye, victimBox),
                new Vector(victimBox.getCenterX(), victimBox.getMinY() + h * 0.82, victimBox.getCenterZ()),
                new Vector(victimBox.getCenterX(), victimBox.getMinY() + h * 0.35, victimBox.getCenterZ())
        };
        Vector eyeVec = eye.toVector();
        int checked = 0;
        int blocked = 0;
        for (Vector pt : targets) {
            Vector diff = pt.clone().subtract(eyeVec);
            double dist = diff.length();
            if (dist <= 0.65) {
                return false;
            }
            checked++;
            diff.multiply(1.0 / dist);
            RayTraceResult trace = eye.getWorld().rayTraceBlocks(eye, diff, dist - 0.20, FluidCollisionMode.NEVER, true);
            if (trace != null && trace.getHitBlock() != null
                    && trace.getHitBlock().getType().isOccluding() && !trace.getHitBlock().isPassable()) {
                blocked++;
            } else {
                return false;
            }
        }
        return checked == 3 && blocked == 3;
    }

    public void processRotation(Player p, PlayerData d, float yawDelta, float pitchDelta) {
        processRotation(p, d, yawDelta, pitchDelta, d.yawDelta);
    }

    public void processRotation(Player p, PlayerData d, float yawDelta, float pitchDelta, float prevYawDelta) {
        if (manager.isBypassed(p, d)) return;
        long now = System.currentTimeMillis();

        float curPitch = p.getLocation().getPitch();
        if (pitchDelta > 55.0f && curPitch > 78.0f) {
            d.lastPitchSnapTime = now;
            d.preSnapPitch = curPitch - pitchDelta;
        }

        if (manager.isEnabled(CheckType.AIM) && d.lastAttackRotationTime > 0 && now - d.lastAttackRotationTime < 85L) {
            float returnYawDelta = AcUtil.angleDiff(p.getLocation().getYaw(), d.lastAttackYaw);
            if (prevYawDelta > 45.0f && yawDelta > 38.0f && returnYawDelta > 35.0f) {
                String detail = String.format(Locale.ROOT, "Snap-Back Silent Aim (flick=%.1f°, return=%.1f°)",
                        prevYawDelta, returnYawDelta);
                if (manager.flag(p, d, CheckType.AIM, detail, 1.0)) {
                    d.lastAttackRotationTime = 0;
                }
            }
        }

        if (!manager.isEnabled(CheckType.AIM) || AcUtil.isBedrockPlayer(p)) return;
        if (yawDelta <= 0.001f && pitchDelta <= 0.001f) return;

        d.yawDeltas.addLast(yawDelta);
        if (Math.abs(curPitch) < 88.5f) {
            d.pitchDeltas.addLast(pitchDelta);
        }
        while (d.yawDeltas.size() > 20) d.yawDeltas.pollFirst();
        while (d.pitchDeltas.size() > 20) d.pitchDeltas.pollFirst();

        if (manager.getAnomalyEngine() != null) {
            manager.getAnomalyEngine().analyzeCombatMotion(p, d, yawDelta, pitchDelta);
        }

        if (d.pitchDeltas.size() >= 14 && (now - d.lastAttack < 1000L)) {
            Float prevPf = null;
            int lowGcdPairs = 0;
            int totalPairs = 0;
            double worstGcd = 1.0;

            for (float pf : d.pitchDeltas) {
                if (pf > 0.08f && pf < 20.0f) {
                    if (prevPf != null && Math.abs(pf - prevPf) > 0.02f) {
                        double pairGcd = AcUtil.gcd(prevPf, pf);
                        totalPairs++;
                        if (pairGcd < 0.0045) {
                            lowGcdPairs++;
                            worstGcd = Math.min(worstGcd, pairGcd);
                        }
                    }
                    prevPf = pf;
                }
            }

            if (totalPairs >= 8 && lowGcdPairs >= totalPairs - 1) {
                d.buffer[CheckType.AIM.ordinal()] += 1.0;
                if (d.buffer[CheckType.AIM.ordinal()] >= 3.5) {
                    String detail = String.format(Locale.ROOT, "Smooth Aim GCD=%.6f (Pairs=%d/%d)", worstGcd, lowGcdPairs, totalPairs);
                    manager.flag(p, d, CheckType.AIM, detail, 1.0);
                    d.buffer[CheckType.AIM.ordinal()] = 0.5;
                }
            } else {
                d.buffer[CheckType.AIM.ordinal()] = Math.max(0, d.buffer[CheckType.AIM.ordinal()] - 0.20);
            }

            if (now - d.lastAttack < 550L && yawDelta > 24.0f && pitchDelta == 0.0f && Math.abs(curPitch) < 85.0f) {
                d.pitchGcdHistory.addLast(1.0);
                while (d.pitchGcdHistory.size() > 6) d.pitchGcdHistory.pollFirst();
                if (d.pitchGcdHistory.size() >= 5) {
                    manager.flag(p, d, CheckType.AIM, "Pitch Lock (Yaw=" + String.format(Locale.ROOT, "%.1f", yawDelta) + "°, Pitch=0.00°)", 1.0);
                    d.pitchGcdHistory.clear();
                }
            } else if (pitchDelta > 0.01f) {
                d.pitchGcdHistory.clear();
            }
        }
    }

    public void processTotemEquip(Player p, PlayerData d, Cancellable event) {
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

    public void processArmSwing(Player p, PlayerData d, PlayerAnimationEvent event) {
        long now = System.currentTimeMillis();
        d.pendingSwingCheck = false;
        d.noSwingStreak = 0;

        if (manager.isBypassed(p, d)) {
            d.lastSwing = now;
            return;
        }

        if (now - d.digStart < 1200L
                || now - d.lastInteractBlockTime < 250L
                || now - d.lastAbortDigTime < 250L
                || now - d.lastDropPacketTime < 100L
                || now - d.lastBlockPlace < 100L) {
            d.lastSwing = now;
            return;
        }

        if (d.clicks.size() >= 10) {
            try {
                Block targetBlock = p.getTargetBlockExact(5);
                if (targetBlock != null && !targetBlock.getType().isAir() && !targetBlock.isLiquid()) {
                    d.lastSwing = now;
                    return;
                }
            } catch (Throwable ignored) {}
        }

        if (d.lastSwing > 0) {
            long delay = now - d.lastSwing;
            if (delay > 2L && delay < 1000L) {
                d.clickDelays.addLast(delay);
                while (d.clickDelays.size() > 20) d.clickDelays.pollFirst();
            }
        }
        d.lastSwing = now;

        if (!manager.isEnabled(CheckType.AUTOCLICKER)) return;

        d.clicks.addLast(now);
        while (!d.clicks.isEmpty() && now - d.clicks.peekFirst() > 1000L) {
            d.clicks.pollFirst();
        }

        int cps = d.clicks.size();
        CheckSettings sAuto = manager.settings(CheckType.AUTOCLICKER);
        int cfgMaxCps = sAuto.i("max-cps", 20);

        boolean impossibleCps = cps > Math.max(22, cfgMaxCps);
        boolean impossibleStdDev = false;
        double stdDev = 999.0;

        if (cps >= 15 && d.clickDelays.size() >= 15) {
            stdDev = AcUtil.standardDeviation(d.clickDelays);
            if (stdDev < 3.8) {
                impossibleStdDev = true;
            }
        }

        if (impossibleCps || impossibleStdDev) {
            d.buffer[CheckType.AUTOCLICKER.ordinal()] += 1.0;
            if (d.buffer[CheckType.AUTOCLICKER.ordinal()] >= 2.5) {
                String detail;
                if (impossibleStdDev) {
                    detail = String.format(Locale.ROOT, "CPS=%d stdDev=%.1fms (ProMinStd=4.5ms)", cps, stdDev);
                } else {
                    detail = String.format(Locale.ROOT, "CPS=%d (ProMax=%d)", cps, Math.max(22, cfgMaxCps));
                }
                if (manager.flag(p, d, CheckType.AUTOCLICKER, detail, 1.0)) {
                    d.buffer[CheckType.AUTOCLICKER.ordinal()] = 0.5;
                }
            }
        } else {
            d.buffer[CheckType.AUTOCLICKER.ordinal()] = Math.max(0, d.buffer[CheckType.AUTOCLICKER.ordinal()] - 0.15);
        }
    }
}
