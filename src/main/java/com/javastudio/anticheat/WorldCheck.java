package com.javastudio.anticheat;

import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.Locale;

public final class WorldCheck {

    private static final BlockFace[] SIX_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final AntiCheatManager manager;

    public WorldCheck(AntiCheatManager manager) {
        this.manager = manager;
    }

    public void processBlockDamage(Player p, PlayerData d, BlockDamageEvent event) {
        if (manager.isBypassed(p, d)) return;
        d.digStart = System.currentTimeMillis();
        d.digBlockKey = AcUtil.blockKey(event.getBlock());
    }

    public void processBlockBreak(Player p, PlayerData d, BlockBreakEvent event) {
        if (manager.isBypassed(p, d)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (manager.isLagging()) return;

        Block b = event.getBlock();
        Location eye = p.getEyeLocation();
        long now = System.currentTimeMillis();
        Material mat = b.getType();
        float hardness = mat.getHardness();

        BoundingBox blockBox = new BoundingBox(b.getX(), b.getY(), b.getZ(), b.getX() + 1.0, b.getY() + 1.0, b.getZ() + 1.0);
        double distToBox = AcUtil.distanceToBox(eye, blockBox);

        if (manager.isEnabled(CheckType.BLOCKREACH)) {
            CheckSettings sReach = manager.settings(CheckType.BLOCKREACH);
            double attrMax = AcUtil.blockReach(p) + 0.85 + (AcUtil.isBedrockPlayer(p) ? 0.65 : 0.0);
            double maxDist = Math.max(sReach.d("max-distance", 5.4), attrMax);
            if (distToBox > maxDist) {
                if (manager.flag(p, d, CheckType.BLOCKREACH, String.format(Locale.ROOT, "Break dist=%.2f max=%.2f", distToBox, maxDist), 1.0)) {
                    if (sReach.isCancel()) event.setCancelled(true);
                    return;
                }
            }
        }

        if (manager.isEnabled(CheckType.MULTIACTIONS)) {
            if (p.isHandRaised() && hardness > 0.0f) {
                if (manager.flag(p, d, CheckType.MULTIACTIONS, "Đào khối khi đang ăn/dùng vật phẩm", 1.0)) {
                    if (manager.settings(CheckType.MULTIACTIONS).isCancel()) event.setCancelled(true);
                    return;
                }
            } else if (d.invState == PlayerData.InvState.OPEN && (now - d.lastContainerClickTime < 900L)) {
                if (manager.flag(p, d, CheckType.MULTIACTIONS, "Đào khối khi đang mở rương/túi đồ", 1.0)) {
                    if (manager.settings(CheckType.MULTIACTIONS).isCancel()) event.setCancelled(true);
                    return;
                }
            }
        }

        if (manager.isEnabled(CheckType.NUKER)) {
            CheckSettings sNuker = manager.settings(CheckType.NUKER);
            if (distToBox > 1.25 && isFullySealedBySolidBlocks(b)) {
                if (manager.flag(p, d, CheckType.NUKER, "ThroughWall/Nuker đào khối bịt kín 6 mặt (" + mat + ")", 2.0)) {
                    if (sNuker.isCancel()) event.setCancelled(true);
                    return;
                }
            }

            boolean highValueTarget = Tag.BEDS.isTagged(mat) || mat == Material.DRAGON_EGG
                    || mat == Material.SPAWNER || mat == Material.BEACON;
            if (highValueTarget && distToBox > 1.5 && isBlockOccludedFromEye(eye, b, blockBox)) {
                if (manager.flag(p, d, CheckType.NUKER, "BedNuker phá xuyên tường (" + mat + ")", 2.0)) {
                    if (sNuker.isCancel()) event.setCancelled(true);
                    return;
                }
            }

            if (hardness > 0.0f && !AcUtil.isBedrockPlayer(p)) {
                Vector toBlock = blockBox.getCenter().subtract(eye.toVector());
                if (toBlock.lengthSquared() > 1.44) {
                    Vector dir = toBlock.clone().normalize();
                    Vector look = eye.getDirection().normalize();
                    double lookAngle = AcUtil.angleDegrees(look, dir);

                    long dt = d.lastNukerBreakAt > 0 ? (now - d.lastNukerBreakAt) : 9999L;
                    double sweepAngle = (d.lastNukerDir != null && d.lastNukerBlockLoc != null
                            && d.lastNukerBlockLoc.getWorld() == b.getWorld()
                            && !d.lastNukerBlockLoc.equals(b.getLocation()))
                            ? AcUtil.angleDegrees(d.lastNukerDir, dir) : 0.0;

                    if ((dt < 85L && sweepAngle > 68.0 && lookAngle > 45.0) || (lookAngle > 85.0 && distToBox > 1.8)) {
                        d.buffer[CheckType.NUKER.ordinal()] += 1.0;
                        if (sNuker.isCancel()) event.setCancelled(true);
                        if (d.buffer[CheckType.NUKER.ordinal()] >= 2.0) {
                            String detail = String.format(Locale.ROOT, "Nuker đào đa góc (sweep=%.0f°, lệch tâm=%.0f°, dt=%dms)",
                                    sweepAngle, lookAngle, dt);
                            manager.flag(p, d, CheckType.NUKER, detail, 1.0);
                            d.buffer[CheckType.NUKER.ordinal()] = 0.5;
                            return;
                        }
                    } else {
                        d.buffer[CheckType.NUKER.ordinal()] = Math.max(0, d.buffer[CheckType.NUKER.ordinal()] - 0.25);
                    }

                    d.lastNukerDir = dir;
                    d.lastNukerBlockLoc = b.getLocation();
                    d.lastNukerBreakAt = now;
                }
            }
        }

        long minBreakMs = AcUtil.minBreakTimeMs(p, b);

        d.breaks.addLast(now);
        while (!d.breaks.isEmpty() && now - d.breaks.peekFirst() > 1000L) {
            d.breaks.pollFirst();
        }

        int rate = d.breaks.size();
        if (manager.isEnabled(CheckType.FASTBREAK)) {
            CheckSettings sFast = manager.settings(CheckType.FASTBREAK);
            int maxPerSec = sFast.i("max-per-second", 14);
            if (minBreakMs == 0L) {
                maxPerSec = Math.max(22, maxPerSec + 8);
            }
            if (rate > maxPerSec) {
                if (manager.flag(p, d, CheckType.FASTBREAK, "Break rate=" + rate + "/s (max=" + maxPerSec + ")", 1.0)) {
                    if (sFast.isCancel()) event.setCancelled(true);
                    return;
                }
            }

            if (minBreakMs == Long.MAX_VALUE) {
                if (manager.flag(p, d, CheckType.FASTBREAK, "Phá khối bất tử " + mat, 3.0)) {
                    event.setCancelled(true);
                    return;
                }
            }

            if (minBreakMs >= 180L) {
                long key = AcUtil.blockKey(b);
                if (key == d.digBlockKey && d.digStart > 0) {
                    long digTime = now - d.digStart;
                    long allowedMin = (long) (minBreakMs * 0.65);
                    if (digTime < allowedMin) {
                        d.buffer[CheckType.FASTBREAK.ordinal()] += 1.0;
                        if (sFast.isCancel()) event.setCancelled(true);
                        if (d.buffer[CheckType.FASTBREAK.ordinal()] >= 1.5) {
                            manager.flag(p, d, CheckType.FASTBREAK,
                                    String.format(Locale.ROOT, "FastBreak %s trong %dms (min=%dms)", mat, digTime, allowedMin), 1.0);
                            d.buffer[CheckType.FASTBREAK.ordinal()] = 0.5;
                            return;
                        }
                    } else {
                        d.buffer[CheckType.FASTBREAK.ordinal()] = Math.max(0, d.buffer[CheckType.FASTBREAK.ordinal()] - 0.25);
                    }
                } else if (minBreakMs >= 400L && !AcUtil.isBedrockPlayer(p)) {
                    d.buffer[CheckType.FASTBREAK.ordinal()] += 1.0;
                    if (sFast.isCancel()) event.setCancelled(true);
                    if (d.buffer[CheckType.FASTBREAK.ordinal()] >= 2.0) {
                        manager.flag(p, d, CheckType.FASTBREAK, "Packet break không đào " + mat, 1.0);
                        d.buffer[CheckType.FASTBREAK.ordinal()] = 0.5;
                        return;
                    }
                }
            }
        }

        if (manager.isEnabled(CheckType.BARITONE) && !AcUtil.isBedrockPlayer(p)) {
            float norm = ((p.getLocation().getYaw() % 360.0f) + 360.0f) % 360.0f;
            float yawMod = norm % 45.0f;
            if (yawMod < 0.02f || (45.0f - yawMod) < 0.02f) {
                d.buffer[CheckType.BARITONE.ordinal()] += 1.0;
                if (d.buffer[CheckType.BARITONE.ordinal()] >= 6.0) {
                    manager.flag(p, d, CheckType.BARITONE, "Góc đào khối snap toán học (yaw=" + String.format(Locale.ROOT, "%.3f", p.getLocation().getYaw()) + ")", 1.0);
                    d.buffer[CheckType.BARITONE.ordinal()] = 1.0;
                }
            } else {
                d.buffer[CheckType.BARITONE.ordinal()] = Math.max(0, d.buffer[CheckType.BARITONE.ordinal()] - 0.3);
            }

            Location bLoc = b.getLocation();
            if (d.lastMinedBlockLoc != null && d.lastMinedBlockLoc.getWorld() == bLoc.getWorld()) {
                double dist = d.lastMinedBlockLoc.distance(bLoc);
                float curYaw = p.getLocation().getYaw();
                if (dist >= 0.8 && dist <= 2.2 && AcUtil.angleDiff(curYaw, d.lastMinedYaw) < 0.001f && d.pitchDelta == 0.0f) {
                    d.baritoneMineStreak++;
                    if (d.baritoneMineStreak >= 20) {
                        manager.flag(p, d, CheckType.BARITONE, "Đào hầm Baritone thẳng tắp tự động (streak=" + d.baritoneMineStreak + ")", 1.0);
                        d.baritoneMineStreak = 0;
                    }
                } else if (dist > 3.0 || AcUtil.angleDiff(curYaw, d.lastMinedYaw) > 8.0f) {
                    d.baritoneMineStreak = Math.max(0, d.baritoneMineStreak - 2);
                }
            }
            d.lastMinedBlockLoc = bLoc;
            d.lastMinedYaw = p.getLocation().getYaw();
        }
    }

    private boolean isFullySealedBySolidBlocks(Block center) {
        World w = center.getWorld();
        for (BlockFace face : SIX_FACES) {
            int nx = center.getX() + face.getModX();
            int ny = center.getY() + face.getModY();
            int nz = center.getZ() + face.getModZ();
            if (!AcUtil.loaded(w, nx, nz)) return false;
            Block adj = w.getBlockAt(nx, ny, nz);
            if (adj.isPassable() || !adj.getType().isOccluding()) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlockOccludedFromEye(Location eye, Block target, BoundingBox box) {
        World w = eye.getWorld();
        if (w == null) return false;
        Vector[] points = new Vector[]{
                AcUtil.nearestPoint(eye, box),
                box.getCenter(),
                new Vector(box.getCenterX(), box.getMaxY() - 0.1, box.getCenterZ())
        };
        Vector eyeVec = eye.toVector();
        for (Vector pt : points) {
            Vector dir = pt.clone().subtract(eyeVec);
            double len = dir.length();
            if (len < 0.5) return false;
            dir.multiply(1.0 / len);
            RayTraceResult res = w.rayTraceBlocks(eye, dir, len - 0.15, FluidCollisionMode.NEVER, true);
            if (res == null || res.getHitBlock() == null || res.getHitBlock().equals(target)
                    || !res.getHitBlock().getType().isOccluding()) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    public void processBlockPlace(Player p, PlayerData d, BlockPlaceEvent event) {
        if (manager.isBypassed(p, d)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (manager.isLagging()) return;

        Block b = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        Location eye = p.getEyeLocation();
        long now = System.currentTimeMillis();

        if (manager.isEnabled(CheckType.MULTIACTIONS)) {
            if (d.invState == PlayerData.InvState.OPEN && (now - d.lastContainerClickTime < 900L)) {
                if (manager.flag(p, d, CheckType.MULTIACTIONS, "Đặt khối khi đang mở rương/túi đồ", 1.0)) {
                    if (manager.settings(CheckType.MULTIACTIONS).isCancel()) {
                        revertGhostBlock(p, b, event);
                    }
                    return;
                }
            }
        }

        if (manager.isEnabled(CheckType.AIRPLACE)) {
            Material placedType = b.getType();
            boolean waterSurfaceBlock = placedType == Material.LILY_PAD || placedType.name().contains("FROGSPAWN");
            if (!waterSurfaceBlock && against.getType().isAir() && !hasAnySolidNeighbor(b)) {
                d.buffer[CheckType.AIRPLACE.ordinal()] += 1.0;
                if (manager.settings(CheckType.AIRPLACE).isCancel()) {
                    revertGhostBlock(p, b, event);
                }
                if (d.buffer[CheckType.AIRPLACE.ordinal()] >= 1.5) {
                    manager.flag(p, d, CheckType.AIRPLACE, "Placed against AIR", 1.0);
                    d.buffer[CheckType.AIRPLACE.ordinal()] = 0.5;
                }
                return;
            } else {
                d.buffer[CheckType.AIRPLACE.ordinal()] = Math.max(0, d.buffer[CheckType.AIRPLACE.ordinal()] - 0.25);
            }
        }

        BoundingBox placedBox = new BoundingBox(b.getX(), b.getY(), b.getZ(), b.getX() + 1.0, b.getY() + 1.0, b.getZ() + 1.0);
        double distToPlaced = AcUtil.distanceToBox(eye, placedBox);

        if (manager.isEnabled(CheckType.BLOCKREACH)) {
            CheckSettings sReach = manager.settings(CheckType.BLOCKREACH);
            double attrMax = AcUtil.blockReach(p) + 0.95 + (AcUtil.isBedrockPlayer(p) ? 0.65 : 0.0);
            double maxDist = Math.max(sReach.d("max-distance", 5.4), attrMax);
            if (distToPlaced > maxDist) {
                if (manager.flag(p, d, CheckType.BLOCKREACH, String.format(Locale.ROOT, "Place dist=%.2f max=%.2f", distToPlaced, maxDist), 1.0)) {
                    if (sReach.isCancel()) {
                        revertGhostBlock(p, b, event);
                    }
                    return;
                }
            }
        }

        d.places.addLast(now);
        while (!d.places.isEmpty() && now - d.places.peekFirst() > 1000L) {
            d.places.pollFirst();
        }

        int rate = d.places.size();
        CheckSettings sFast = manager.settings(CheckType.FASTPLACE);
        if (manager.isEnabled(CheckType.FASTPLACE) && rate > sFast.i("max-per-second", 13)) {
            if (manager.flag(p, d, CheckType.FASTPLACE, "Place rate=" + rate + "/s", 1.0)) {
                if (sFast.isCancel()) {
                    revertGhostBlock(p, b, event);
                }
                return;
            }
        }

        if (manager.isEnabled(CheckType.SCAFFOLD)) {
            Location pLoc = p.getLocation();
            int bX = b.getX(), bY = b.getY(), bZ = b.getZ();
            int aX = against.getX(), aY = against.getY(), aZ = against.getZ();
            int pFootY = (int) Math.floor(pLoc.getY() + 0.05);

            int offX = bX - aX;
            int offY = bY - aY;
            int offZ = bZ - aZ;

            double distH = Math.hypot(bX + 0.5 - pLoc.getX(), bZ + 0.5 - pLoc.getZ());
            boolean atFeetOrBelow = (bY <= pFootY);
            boolean nearPlayer = distH <= 3.2;
            boolean airUnderPlaced = b.getWorld().getBlockAt(bX, bY - 1, bZ).isPassable()
                    && b.getWorld().getBlockAt(bX, bY - 2, bZ).isPassable();
            boolean bedrock = AcUtil.isBedrockPlayer(p);

            while (!d.recentScaffoldBlocks.isEmpty() && now - d.recentScaffoldBlocks.peekFirst().time() > 4500L) {
                d.recentScaffoldBlocks.pollFirst();
            }

            boolean suspicious = false;
            boolean blatant = false;
            String reason = null;

            if (against.getType().isOccluding()) {
                double eyeX = eye.getX(), eyeY = eye.getY(), eyeZ = eye.getZ();
                if (offY == -1 && eyeY > aY + 0.25) {
                    suspicious = true;
                    blatant = true;
                    reason = "Impossible down face (offY=-1 while above block)";
                } else if (offY == 1 && eyeY < aY + 0.80) {
                    suspicious = true;
                    blatant = true;
                    reason = "Impossible top face (offY=+1 while below block)";
                } else if (eyeY >= aY + 1.02 && atFeetOrBelow) {
                    if ((offX == 1 && eyeX < aX + 0.86) ||
                            (offX == -1 && eyeX > aX + 0.14) ||
                            (offZ == 1 && eyeZ < aZ + 0.86) ||
                            (offZ == -1 && eyeZ > aZ + 0.14)) {
                        suspicious = true;
                        blatant = true;
                        reason = String.format(Locale.ROOT, "Impossible bridge face normal (off=[%d,%d,%d])", offX, offY, offZ);
                    }
                }
            }

            BoundingBox againstBox = resolveBlockBoundingBox(against);

            if (!suspicious && atFeetOrBelow && nearPlayer && airUnderPlaced) {
                Vector lookDir = eye.getDirection();
                double hitDist = AcUtil.rayIntersectsBox(eye, lookDir, againstBox.clone().expand(0.18), 6.0);
                if (hitDist < 0) {
                    suspicious = true;
                    blatant = !bedrock;
                    reason = "No line of sight with bridge block (silent rotation)";
                }
            }

            if (!suspicious && offY >= 1 && bX == pLoc.getBlockX() && bZ == pLoc.getBlockZ() && bY <= pFootY) {
                if (d.lastDy > 0.14 && pLoc.getPitch() < 52.0f) {
                    suspicious = true;
                    reason = String.format(Locale.ROOT, "Tower scaffold (pitch=%.1f < 52.0, dy=%.2f)", pLoc.getPitch(), d.lastDy);
                }
            }

            if (!suspicious && !bedrock && atFeetOrBelow && nearPlayer && airUnderPlaced && offY == 0 && !p.isSneaking()) {
                boolean activelyMoving = (now - d.lastPosMoveMs < 110L) && d.lastH > 0.15;
                if (activelyMoving) {
                    Vector moveDir = new Vector(d.lastDx, 0, d.lastDz).normalize();
                    Vector faceNormal = new Vector(offX, 0, offZ);
                    if (faceNormal.lengthSquared() > 0.5) {
                        faceNormal.normalize();
                        double dotFaceMove = moveDir.dot(faceNormal);
                        if (dotFaceMove > 0.55) {
                            Vector lookH = eye.getDirection().setY(0);
                            double dotLookMove = lookH.lengthSquared() > 0.001 ? lookH.normalize().dot(moveDir) : 0.0;
                            float pitch = pLoc.getPitch();
                            if (dotLookMove > -0.15 || pitch < 70.0f || p.isSprinting() || d.yawDelta > 45.0f) {
                                suspicious = true;
                                blatant = (dotLookMove > 0.20 || p.isSprinting());
                                reason = String.format(Locale.ROOT, "Sprint/Forward Scaffold (h=%.2f, faceDot=%.2f, pitch=%.1f)",
                                        d.lastH, dotFaceMove, pitch);
                            }
                        }
                    }
                }
            }

            if (!suspicious && !bedrock && atFeetOrBelow && nearPlayer && airUnderPlaced && !p.isSneaking()) {
                if ((d.yawDelta > 60.0f || d.pitchDelta > 38.0f) && (now - d.lastPosMoveMs < 110L) && d.lastH > 0.18) {
                    suspicious = true;
                    reason = String.format(Locale.ROOT, "Snap/Telly Scaffold (dYaw=%.1f°, dPitch=%.1f°, h=%.2f)",
                            d.yawDelta, d.pitchDelta, d.lastH);
                }
            }

            if (!suspicious && atFeetOrBelow && airUnderPlaced && (now - d.lastPosMoveMs < 110L) && d.lastH > 0.12) {
                Vector lookH = eye.getDirection().setY(0);
                if (lookH.lengthSquared() > 0.001) {
                    lookH.normalize();
                    Vector toAgainst = new Vector(aX + 0.5 - pLoc.getX(), 0, aZ + 0.5 - pLoc.getZ());
                    if (toAgainst.lengthSquared() > 0.04) {
                        toAgainst.normalize();
                        double dotLook = lookH.dot(toAgainst);
                        if (dotLook < -0.35) {
                            suspicious = true;
                            blatant = true;
                            reason = String.format(Locale.ROOT, "Behind-back Scaffold placement (dot=%.2f)", dotLook);
                        }
                    }
                }
            }

            if (suspicious) {
                CheckSettings sScaf = manager.settings(CheckType.SCAFFOLD);
                d.buffer[CheckType.SCAFFOLD.ordinal()] += blatant ? 1.5 : 1.0;

                if (sScaf.isCancel() && (blatant || d.buffer[CheckType.SCAFFOLD.ordinal()] >= 1.5)) {
                    revertGhostBlock(p, b, event);
                } else if (atFeetOrBelow && airUnderPlaced) {
                    recordScaffoldBlock(d, b, now);
                }

                if (d.buffer[CheckType.SCAFFOLD.ordinal()] >= 2.0) {
                    if (sScaf.isCancel()) {
                        if (!event.isCancelled()) {
                            revertGhostBlock(p, b, event);
                        }
                        rollbackScaffoldBridge(p, d);
                    }
                    manager.flag(p, d, CheckType.SCAFFOLD, reason, 1.0);
                    d.buffer[CheckType.SCAFFOLD.ordinal()] = 1.0;
                }
            } else {
                if (atFeetOrBelow && airUnderPlaced && !p.isSneaking()) {
                    recordScaffoldBlock(d, b, now);
                }
                d.buffer[CheckType.SCAFFOLD.ordinal()] = Math.max(0, d.buffer[CheckType.SCAFFOLD.ordinal()] - 0.20);
            }
        }
    }

    private void recordScaffoldBlock(PlayerData d, Block b, long now) {
        d.recentScaffoldBlocks.addLast(new PlayerData.ScaffoldBlockRecord(
                b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), b.getType(), now));
        while (d.recentScaffoldBlocks.size() > 10) {
            d.recentScaffoldBlocks.pollFirst();
        }
    }

    @SuppressWarnings("deprecation")
    private void rollbackScaffoldBridge(Player p, PlayerData d) {
        if (d.recentScaffoldBlocks.isEmpty()) return;
        while (!d.recentScaffoldBlocks.isEmpty()) {
            PlayerData.ScaffoldBlockRecord rec = d.recentScaffoldBlocks.pollFirst();
            World w = Bukkit.getWorld(rec.world());
            if (w == null || !AcUtil.loaded(w, rec.x(), rec.z())) continue;
            Location loc = new Location(w, rec.x(), rec.y(), rec.z());
            PlatformUtil.runAtLocation(manager.getPlugin(), loc, () -> {
                if (!AcUtil.loaded(w, rec.x(), rec.z())) return;
                Block placed = w.getBlockAt(rec.x(), rec.y(), rec.z());
                if (placed.getType() == rec.type() && !placed.getType().isAir()) {
                    placed.setType(Material.AIR, true);
                    if (p.isOnline()) {
                        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                            p.getInventory().addItem(new ItemStack(rec.type(), 1));
                        }
                        try {
                            p.sendBlockChange(loc, placed.getBlockData());
                        } catch (Throwable ignored) {}
                    }
                }
            });
        }
        PlatformUtil.runAtEntity(manager.getPlugin(), p, () -> {
            if (p.isOnline()) {
                try {
                    p.updateInventory();
                } catch (Throwable ignored) {}
            }
        });
    }

    private BoundingBox resolveBlockBoundingBox(Block b) {
        int bx = b.getX(), by = b.getY(), bz = b.getZ();
        if (b.getType().isOccluding()) {
            return new BoundingBox(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0);
        }
        try {
            Collection<BoundingBox> boxes = b.getCollisionShape().getBoundingBoxes();
            if (!boxes.isEmpty()) {
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
                for (BoundingBox bb : boxes) {
                    minX = Math.min(minX, bx + bb.getMinX());
                    minY = Math.min(minY, by + bb.getMinY());
                    minZ = Math.min(minZ, bz + bb.getMinZ());
                    maxX = Math.max(maxX, bx + bb.getMaxX());
                    maxY = Math.max(maxY, by + bb.getMaxY());
                    maxZ = Math.max(maxZ, bz + bb.getMaxZ());
                }
                return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
            }
        } catch (Throwable ignored) {}
        return new BoundingBox(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0);
    }

    private boolean hasAnySolidNeighbor(Block b) {
        World w = b.getWorld();
        for (BlockFace face : SIX_FACES) {
            int nx = b.getX() + face.getModX();
            int ny = b.getY() + face.getModY();
            int nz = b.getZ() + face.getModZ();
            if (!AcUtil.loaded(w, nx, nz)) return true;
            if (!w.getBlockAt(nx, ny, nz).getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    public void processInventoryClick(Player p, PlayerData d, InventoryClickEvent event) {
        if (manager.isBypassed(p, d)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;

        long now = System.currentTimeMillis();
        boolean activelyMoving = (now - d.lastPosMoveMs < 95L)
                && !manager.isInVelocityGrace(d)
                && (d.lastEnv == null || (!d.lastEnv.ice && !d.lastEnv.liquid && !d.lastEnv.bounce))
                && !p.isGliding()
                && !p.isInsideVehicle();

        if (manager.isEnabled(CheckType.INVENTORY)) {
            if (activelyMoving && d.lastH > 0.23 && d.groundTicks > 4) {
                d.buffer[CheckType.INVENTORY.ordinal()] += 1.0;
                if (d.buffer[CheckType.INVENTORY.ordinal()] >= 2.0) {
                    if (manager.flag(p, d, CheckType.INVENTORY, "Click inv while moving h=" + String.format(Locale.ROOT, "%.2f", d.lastH), 1.0)) {
                        if (manager.settings(CheckType.INVENTORY).isCancel()) {
                            event.setCancelled(true);
                        }
                    }
                    d.buffer[CheckType.INVENTORY.ordinal()] = 0.5;
                }
            } else {
                d.buffer[CheckType.INVENTORY.ordinal()] = Math.max(0, d.buffer[CheckType.INVENTORY.ordinal()] - 0.25);
            }
        }

        if (manager.isEnabled(CheckType.CHESTSTEALER)
                && event.getClickedInventory() != null
                && event.getClickedInventory() != p.getInventory()) {
            d.chestClicks.addLast(now);
            while (!d.chestClicks.isEmpty() && now - d.chestClicks.peekFirst() > 1000L) {
                d.chestClicks.pollFirst();
            }

            int clicksInSec = d.chestClicks.size();
            CheckSettings sSteal = manager.settings(CheckType.CHESTSTEALER);
            if (clicksInSec > sSteal.i("max-clicks-per-sec", 15)) {
                if (manager.flag(p, d, CheckType.CHESTSTEALER, "Stealer clicks=" + clicksInSec + "/s", 1.0)) {
                    if (sSteal.isCancel()) event.setCancelled(true);
                }
            }
        }

        if (manager.isEnabled(CheckType.AUTOARMOR) && event.getSlotType() == InventoryType.SlotType.ARMOR) {
            long equipDiff = d.lastArmorEquip > 0 ? (now - d.lastArmorEquip) : 9999L;
            d.lastArmorEquip = now;

            boolean instantMultiPiece = equipDiff < 55L && event.getSlot() != d.lastClickedSlot;
            boolean runningEquip = activelyMoving && d.lastH > 0.24 && d.groundTicks > 3;

            if (instantMultiPiece || runningEquip) {
                d.buffer[CheckType.AUTOARMOR.ordinal()] += 1.0;
                if (d.buffer[CheckType.AUTOARMOR.ordinal()] >= 2.0) {
                    String detail = instantMultiPiece
                            ? ("AutoArmor mặc nhiều món trong " + equipDiff + "ms")
                            : ("Mặc/thay giáp khi đang chạy h=" + String.format(Locale.ROOT, "%.2f", d.lastH));
                    if (manager.flag(p, d, CheckType.AUTOARMOR, detail, 1.0)) {
                        if (manager.settings(CheckType.AUTOARMOR).isCancel()) {
                            event.setCancelled(true);
                        }
                    }
                    d.buffer[CheckType.AUTOARMOR.ordinal()] = 0.5;
                }
            } else {
                d.buffer[CheckType.AUTOARMOR.ordinal()] = Math.max(0, d.buffer[CheckType.AUTOARMOR.ordinal()] - 0.25);
            }
        }
    }

    public void processSlotChange(Player p, PlayerData d, Cancellable event) {
        if (manager.isBypassed(p, d) || !manager.isEnabled(CheckType.BADPACKETS)) return;
        long now = System.currentTimeMillis();
        d.slotChanges.addLast(now);
        while (!d.slotChanges.isEmpty() && now - d.slotChanges.peekFirst() > 1000L) {
            d.slotChanges.pollFirst();
        }

        int count = d.slotChanges.size();
        if (count > 18) {
            if (manager.flag(p, d, CheckType.BADPACKETS, "Slot spam rate=" + count + "/s", 1.0)) {
                if (manager.settings(CheckType.BADPACKETS).isCancel()) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void revertGhostBlock(Player p, Block b, BlockPlaceEvent event) {
        event.setCancelled(true);
        event.setBuild(false);
        try {
            Location loc = b.getLocation();
            p.sendBlockChange(loc, event.getBlockReplacedState().getBlockData());
            int bx = b.getX(), by = b.getY(), bz = b.getZ();
            PlatformUtil.runAtLocation(manager.getPlugin(), loc, () -> {
                if (p.isOnline() && AcUtil.loaded(b.getWorld(), bx, bz)) {
                    try {
                        p.sendBlockChange(loc, b.getWorld().getBlockAt(bx, by, bz).getBlockData());
                        p.updateInventory();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }
}
