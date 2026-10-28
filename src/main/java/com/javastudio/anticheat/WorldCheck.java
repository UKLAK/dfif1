package com.javastudio.anticheat;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * Kiểm tra các gian lận tương tác thế giới và khối (World Checks):
 * FastBreak, Nuker, FastPlace, Scaffold, AirPlace, BlockReach, ChestStealer.
 */
public final class WorldCheck {

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
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (manager.isLagging()) return;

        Block b = event.getBlock();
        Location eye = p.getEyeLocation();
        long now = System.currentTimeMillis();

        // 1. BlockReach Check (Đập khối quá xa)
        if (manager.isEnabled(CheckType.BLOCKREACH)) {
            Location bLoc = b.getLocation().add(0.5, 0.5, 0.5);
            double dist = eye.distance(bLoc);
            CheckSettings sReach = manager.settings(CheckType.BLOCKREACH);
            double maxDist = sReach.d("max-distance", 5.4);
            if (dist > maxDist) {
                if (manager.flag(p, d, CheckType.BLOCKREACH, "Break dist=" + String.format("%.2f", dist) + " max=" + maxDist, 1.0)) {
                    if (sReach.isCancel()) event.setCancelled(true);
                    return;
                }
            }
        }

        // 2. Nuker / FastBreak Rate Check
        d.breaks.addLast(now);
        while (!d.breaks.isEmpty() && now - d.breaks.peekFirst() > 1000) {
            d.breaks.pollFirst();
        }

        int rate = d.breaks.size();
        if (manager.isEnabled(CheckType.FASTBREAK) && rate > manager.settings(CheckType.FASTBREAK).i("max-per-second", 14)) {
            if (manager.flag(p, d, CheckType.FASTBREAK, "Break rate=" + rate + "/s", 1.0)) {
                if (manager.settings(CheckType.FASTBREAK).isCancel()) event.setCancelled(true);
                return;
            }
        }

        // 3. FastBreak Instant Check (Khối cứng đập trong chớp mắt)
        if (manager.isEnabled(CheckType.FASTBREAK) && b.getType().getHardness() > 0.0f) {
            long key = AcUtil.blockKey(b);
            if (key == d.digBlockKey && d.digStart > 0) {
                long digTime = now - d.digStart;
                // Khối cứng như Obsidian / Netherite / Log mà đào dưới 50ms mà không phải creative
                if (digTime < 45 && b.getType().getHardness() >= 1.5f) {
                    if (manager.flag(p, d, CheckType.FASTBREAK, "Instant break " + b.getType() + " in " + digTime + "ms", 1.0)) {
                        if (manager.settings(CheckType.FASTBREAK).isCancel()) event.setCancelled(true);
                        return;
                    }
                }
            }
        }

        // 4. Baritone / Bot Digging Pattern Check (Hướng 4)
        if (manager.isEnabled(CheckType.BARITONE)) {
            // A. Bot thường snap góc quay chính xác 90 hoặc 45 độ khi chuyển khối đào tiếp theo
            float yawMod = Math.abs(p.getLocation().getYaw() % 45.0f);
            if (yawMod < 0.05f || Math.abs(yawMod - 45.0f) < 0.05f) {
                d.buffer[CheckType.BARITONE.ordinal()] += 1.0;
                if (d.buffer[CheckType.BARITONE.ordinal()] >= 4.0) {
                    manager.flag(p, d, CheckType.BARITONE, "Góc đào khối snap toán học (yaw=" + String.format("%.2f", p.getLocation().getYaw()) + ")", 1.0);
                    d.buffer[CheckType.BARITONE.ordinal()] = 0;
                }
            } else {
                d.buffer[CheckType.BARITONE.ordinal()] = Math.max(0, d.buffer[CheckType.BARITONE.ordinal()] - 0.2);
            }

            // B. Baritone Automated Straight Tunneling (Đào hầm thẳng tắp 1x2 với yaw bất biến)
            Location bLoc = b.getLocation();
            if (d.lastMinedBlockLoc != null && d.lastMinedBlockLoc.getWorld() == bLoc.getWorld()) {
                double dist = d.lastMinedBlockLoc.distance(bLoc);
                float curYaw = p.getLocation().getYaw();
                if (dist >= 0.8 && dist <= 2.2 && Math.abs(curYaw - d.lastMinedYaw) < 0.001f) {
                    d.baritoneMineStreak++;
                    if (d.baritoneMineStreak >= 16) {
                        manager.flag(p, d, CheckType.BARITONE, "Đào hầm Baritone thẳng tắp tự động (streak=" + d.baritoneMineStreak + ")", 1.0);
                        d.baritoneMineStreak = 0;
                    }
                } else if (dist > 3.0 || Math.abs(curYaw - d.lastMinedYaw) > 10.0f) {
                    d.baritoneMineStreak = Math.max(0, d.baritoneMineStreak - 2);
                }
            }
            d.lastMinedBlockLoc = bLoc;
            d.lastMinedYaw = p.getLocation().getYaw();
        }
    }

    @SuppressWarnings("deprecation")
    public void processBlockPlace(Player p, PlayerData d, BlockPlaceEvent event) {
        if (manager.isBypassed(p, d)) return;
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (manager.isLagging()) return;

        Block b = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        Location eye = p.getEyeLocation();
        long now = System.currentTimeMillis();

        // 1. AirPlace Check (Đặt khối vào không khí mà không có điểm tựa)
        if (manager.isEnabled(CheckType.AIRPLACE)) {
            if (against.getType().isAir()) {
                if (manager.flag(p, d, CheckType.AIRPLACE, "Placed against AIR", 1.0)) {
                    if (manager.settings(CheckType.AIRPLACE).isCancel()) event.setCancelled(true);
                    return;
                }
            }
        }

        // 2. BlockReach Check khi đặt khối
        if (manager.isEnabled(CheckType.BLOCKREACH)) {
            Location bLoc = b.getLocation().add(0.5, 0.5, 0.5);
            double dist = eye.distance(bLoc);
            CheckSettings sReach = manager.settings(CheckType.BLOCKREACH);
            double maxDist = sReach.d("max-distance", 5.4);
            if (dist > maxDist) {
                if (manager.flag(p, d, CheckType.BLOCKREACH, "Place dist=" + String.format("%.2f", dist) + " max=" + maxDist, 1.0)) {
                    if (sReach.isCancel()) event.setCancelled(true);
                    return;
                }
            }
        }

        // 3. FastPlace Check (Tốc độ đặt khối / giây)
        d.places.addLast(now);
        while (!d.places.isEmpty() && now - d.places.peekFirst() > 1000) {
            d.places.pollFirst();
        }

        int rate = d.places.size();
        CheckSettings sFast = manager.settings(CheckType.FASTPLACE);
        if (manager.isEnabled(CheckType.FASTPLACE) && rate > sFast.i("max-per-second", 12)) {
            if (manager.flag(p, d, CheckType.FASTPLACE, "Place rate=" + rate + "/s", 1.0)) {
                if (sFast.isCancel()) event.setCancelled(true);
                return;
            }
        }

        // 4. Scaffold Check V2 (Bắt cầu tự động, Silent Rotation, Sprint Bridging, Impossible Face)
        if (manager.isEnabled(CheckType.SCAFFOLD)) {
            Location pLoc = p.getLocation();
            int bX = b.getX(), bY = b.getY(), bZ = b.getZ();
            int aX = against.getX(), aY = against.getY(), aZ = against.getZ();
            int pFootY = pLoc.getBlockY();

            // Độ lệch mặt đặt khối (Face normal): b - against
            int offX = bX - aX;
            int offY = bY - aY;
            int offZ = bZ - aZ;

            // Khoảng cách ngang từ người chơi đến khối đặt
            double distH = Math.hypot(bX + 0.5 - pLoc.getX(), bZ + 0.5 - pLoc.getZ());
            boolean atFeetOrBelow = (bY <= pFootY && bY >= pFootY - 1);
            boolean nearPlayer = distH <= 2.8;
            boolean isHorizontalBridge = (offY == 0 && (offX != 0 || offZ != 0));
            boolean airUnderPlaced = b.getWorld().getBlockAt(bX, bY - 1, bZ).getType().isAir();

            boolean suspicious = false;
            String reason = null;

            // Subcheck 4.1: Impossible Down Face (Đặt vào mặt đáy của khối khi đang đứng trên khối đó)
            if (offY == -1 && pLoc.getY() >= aY + 0.25 && against.getType().isSolid()) {
                suspicious = true;
                reason = "Impossible down face (offY=-1 while above block)";
            }

            // Subcheck 4.2: Raycast / Line of Sight Check (Silent Rotation & No-Rotation Scaffold)
            if (!suspicious && atFeetOrBelow && nearPlayer) {
                Vector lookDir = eye.getDirection();
                BoundingBox againstBox = new BoundingBox(aX, aY, aZ, aX + 1.0, aY + 1.0, aZ + 1.0).expand(0.42);
                double hitDist = AcUtil.rayIntersectsBox(eye, lookDir, againstBox, 6.0);
                if (hitDist < 0) {
                    if (d.lastH > 0.08 || !p.isOnGround()) {
                        suspicious = true;
                        reason = "No line of sight with block against (silent rotation)";
                    }
                }
            }

            // Subcheck 4.3: Sprint Scaffold Check (Chạy nước rút qua vực trống và đặt khối dưới chân hướng tới)
            if (!suspicious && p.isSprinting() && !p.isSneaking() && atFeetOrBelow && nearPlayer) {
                if (isHorizontalBridge && airUnderPlaced && d.lastH > 0.18) {
                    Vector moveDir = eye.getDirection().setY(0);
                    if (moveDir.lengthSquared() > 0.001) {
                        moveDir.normalize();
                        Vector toPlaced = new Vector(bX + 0.5 - pLoc.getX(), 0, bZ + 0.5 - pLoc.getZ());
                        if (toPlaced.lengthSquared() > 0.001) {
                            toPlaced.normalize();
                            double dot = moveDir.dot(toPlaced);
                            if (dot > 0.35) {
                                suspicious = true;
                                reason = String.format(Locale.ROOT, "Sprint scaffold bridging (h=%.2f, dot=%.2f)", d.lastH, dot);
                            }
                        }
                    }
                }
            }

            // Subcheck 4.4: Pitch Anomaly (Đặt khối đáy chân nhưng ngẩng đầu hoặc nhìn ngang)
            if (!suspicious && bY == pFootY - 1 && distH < 1.4 && d.lastH > 0.10) {
                float pitch = pLoc.getPitch();
                if (pitch < 48.0f) {
                    suspicious = true;
                    reason = String.format(Locale.ROOT, "Pitch anomaly under feet (pitch=%.1f < 48.0)", pitch);
                }
            }

            // Subcheck 4.5: Behind-Back Placement (Đặt khối sau lưng mà không quay đầu lại nhìn)
            if (!suspicious && atFeetOrBelow && d.lastH > 0.10) {
                Vector lookH = eye.getDirection().setY(0);
                if (lookH.lengthSquared() > 0.001) {
                    lookH.normalize();
                    Vector toAgainst = new Vector(aX + 0.5 - pLoc.getX(), 0, aZ + 0.5 - pLoc.getZ());
                    if (toAgainst.lengthSquared() > 0.001) {
                        toAgainst.normalize();
                        double dotLook = lookH.dot(toAgainst);
                        if (dotLook < -0.30) {
                            suspicious = true;
                            reason = String.format(Locale.ROOT, "Behind-back placement (dot=%.2f)", dotLook);
                        }
                    }
                }
            }

            // Xử lý kết quả kiểm tra Scaffold
            if (suspicious) {
                d.buffer[CheckType.SCAFFOLD.ordinal()] += 1.0;
                // Nếu cancel-event được bật, lập tức hủy đặt khối để hack rơi xuống vực!
                if (manager.settings(CheckType.SCAFFOLD).isCancel()) {
                    event.setCancelled(true);
                }
                if (d.buffer[CheckType.SCAFFOLD.ordinal()] >= 2.0) {
                    manager.flag(p, d, CheckType.SCAFFOLD, reason, 1.0);
                    d.buffer[CheckType.SCAFFOLD.ordinal()] = 1.0; // Duy trì buffer ở mức cảnh giác cao
                }
            } else {
                d.buffer[CheckType.SCAFFOLD.ordinal()] = Math.max(0, d.buffer[CheckType.SCAFFOLD.ordinal()] - 0.15);
            }
        }
    }

    public void processInventoryClick(Player p, PlayerData d, InventoryClickEvent event) {
        if (manager.isBypassed(p, d)) return;
        if (p.getGameMode() == GameMode.CREATIVE) return;

        long now = System.currentTimeMillis();

        // 1. InventoryMove (Di chuyển khi đang mở và click túi đồ)
        if (manager.isEnabled(CheckType.INVENTORY)) {
            if (d.lastH > 0.25 && d.groundTicks > 3) {
                if (manager.flag(p, d, CheckType.INVENTORY, "Click inv while moving h=" + String.format("%.2f", d.lastH), 1.0)) {
                    if (manager.settings(CheckType.INVENTORY).isCancel()) {
                        event.setCancelled(true);
                    }
                }
            }
        }

        // 2. ChestStealer (Hút đồ từ rương quá nhanh bằng shift-click)
        if (manager.isEnabled(CheckType.CHESTSTEALER)) {
            d.chestClicks.addLast(now);
            while (!d.chestClicks.isEmpty() && now - d.chestClicks.peekFirst() > 1000) {
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

        // 3. AutoArmor Check (Tự động thay giáp khi đang di chuyển nhanh)
        if (manager.isEnabled(CheckType.AUTOARMOR) && event.getSlotType() == InventoryType.SlotType.ARMOR) {
            d.lastArmorEquip = now;
            if (d.lastH > 0.26 && d.groundTicks > 2) {
                if (manager.flag(p, d, CheckType.AUTOARMOR, "Mặc/thay giáp khi đang chạy h=" + String.format("%.2f", d.lastH), 1.0)) {
                    if (manager.settings(CheckType.AUTOARMOR).isCancel()) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    /**
     * Kiểm tra BadPackets: Spam đổi slot hotbar liên tục (Slot Spam / Hand Flapping Exploit).
     */
    public void processSlotChange(Player p, PlayerData d, org.bukkit.event.Cancellable event) {
        if (manager.isBypassed(p, d) || !manager.isEnabled(CheckType.BADPACKETS)) return;
        long now = System.currentTimeMillis();
        d.slotChanges.addLast(now);
        while (!d.slotChanges.isEmpty() && now - d.slotChanges.peekFirst() > 1000) {
            d.slotChanges.pollFirst();
        }

        int count = d.slotChanges.size();
        if (count > 16) {
            if (manager.flag(p, d, CheckType.BADPACKETS, "Slot spam rate=" + count + "/s", 1.0)) {
                if (manager.settings(CheckType.BADPACKETS).isCancel()) {
                    event.setCancelled(true);
                }
            }
        }
    }

    /**
     * Kiểm tra BadPackets: Tương tác bất hợp lệ khi đang ngồi phương tiện (Vehicle).
     */
    public void processVehicleInteract(Player p, PlayerData d, org.bukkit.event.Cancellable event) {
        if (manager.isBypassed(p, d) || !manager.isEnabled(CheckType.BADPACKETS)) return;
        if (p.isInsideVehicle()) {
            // Trong Minecraft, người chơi không thể với tay đặt/đập khối tự do ở góc khuất khi ngồi xe
            if (manager.flag(p, d, CheckType.BADPACKETS, "Illegal vehicle interaction", 1.0)) {
                if (manager.settings(CheckType.BADPACKETS).isCancel()) {
                    event.setCancelled(true);
                }
            }
        }
    }
}
