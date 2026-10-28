package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.Locale;

/**
 * Hướng 3: Kiểm tra gian lận xe cộ và di chuyển phức tạp (Vehicle Checks):
 * BoatFly, Vehicle Speed (thuyền trên cạn / đường băng), Vehicle Phase (xuyên tường bằng thuyền/xe mỏ),
 * và Disabler / Ping Spoof chống bẫy lag compensation.
 */
public final class VehicleCheck {

    private final AntiCheatManager manager;

    public VehicleCheck(AntiCheatManager manager) {
        this.manager = manager;
    }

    /**
     * Xử lý di chuyển phương tiện (VehicleMoveEvent).
     */
    public void processVehicleMove(Vehicle vehicle, VehicleMoveEvent event) {
        if (!manager.isActive() || !manager.isEnabled(CheckType.VEHICLE)) return;
        if (vehicle.getPassengers().isEmpty()) return;

        // Chỉ kiểm tra khi hành khách điều khiển là người chơi
        if (!(vehicle.getPassengers().get(0) instanceof Player p)) return;
        PlayerData d = manager.data(p);
        if (manager.isBypassed(p, d)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()) return;

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        long now = System.currentTimeMillis();
        d.lastVehicleMove = now;
        d.vehicleLastDy = dy;

        // 1. Kiểm tra môi trường xung quanh phương tiện
        boolean hasWaterOrIce = false;
        boolean hasGround = false;
        Location vLoc = to.clone();

        int bx = vLoc.getBlockX();
        int by = vLoc.getBlockY();
        int bz = vLoc.getBlockZ();

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int oy = -1; oy <= 0; oy++) {
                    Block b = to.getWorld().getBlockAt(bx + ox, by + oy, bz + oz);
                    Material mat = b.getType();
                    if (mat == Material.WATER || mat == Material.ICE || mat == Material.PACKED_ICE || mat == Material.BLUE_ICE) {
                        hasWaterOrIce = true;
                    }
                    if (b.isSolid() || mat.name().contains("SLAB") || mat.name().contains("STAIRS") || mat == Material.LILY_PAD) {
                        hasGround = true;
                    }
                }
            }
        }

        // 2. BoatFly Check (Bay thuyền lơ lửng hoặc bay lên trên không trung)
        if (vehicle instanceof Boat) {
            if (!hasWaterOrIce && !hasGround) {
                d.vehicleAirTicks++;
            } else {
                d.vehicleAirTicks = 0;
            }

            // Thuyền rơi tự do trong không khí sau 10 ticks bắt buộc phải có gia tốc rơi dy <= -0.05
            if (d.vehicleAirTicks > 12) {
                // Gian lận BoatFly: Thuyền bay lơ lửng (dy >= -0.04) hoặc bay thẳng lên trời (dy > 0)
                if (dy >= -0.04 || hDist > 0.45) {
                    d.buffer[CheckType.VEHICLE.ordinal()] += 1.5;
                    if (d.buffer[CheckType.VEHICLE.ordinal()] >= 2.0) {
                        String detail = String.format(Locale.ROOT, "BoatFly dy=%.2f H=%.2f airTicks=%d", dy, hDist, d.vehicleAirTicks);
                        if (manager.flag(p, d, CheckType.VEHICLE, detail, 1.0)) {
                            // Hạ xe và giật lùi người chơi về mặt đất an toàn
                            vehicle.eject();
                            Location safe = manager.pickSetback(d, from, true);
                            if (safe != null) {
                                p.teleport(safe);
                            }
                            d.buffer[CheckType.VEHICLE.ordinal()] = 0;
                            return;
                        }
                    }
                }
            }

            // 3. Vehicle Speed Check (Tốc độ thuyền bất thường)
            if (!hasWaterOrIce && hasGround) {
                // Thuyền đi trên cạn: Vanilla giới hạn tối đa ~0.15 - 0.20 b/t
                if (hDist > 0.42) {
                    d.buffer[CheckType.VEHICLE.ordinal()] += 1.0;
                    if (d.buffer[CheckType.VEHICLE.ordinal()] >= 2.0) {
                        String detail = String.format(Locale.ROOT, "BoatSpeed on land H=%.2f (Max=0.20)", hDist);
                        if (manager.flag(p, d, CheckType.VEHICLE, detail, 1.0)) {
                            vehicle.eject();
                            Location safe = manager.pickSetback(d, from, true);
                            if (safe != null) p.teleport(safe);
                            d.buffer[CheckType.VEHICLE.ordinal()] = 0;
                            return;
                        }
                    }
                }
            } else if (hasWaterOrIce) {
                // Thuyền trên băng: Max vanilla ~1.5 - 1.8 b/t
                if (hDist > 2.30) {
                    d.buffer[CheckType.VEHICLE.ordinal()] += 1.0;
                    if (d.buffer[CheckType.VEHICLE.ordinal()] >= 2.0) {
                        String detail = String.format(Locale.ROOT, "BoatSpeed on ice H=%.2f (Max=1.8)", hDist);
                        manager.flag(p, d, CheckType.VEHICLE, detail, 1.0);
                        d.buffer[CheckType.VEHICLE.ordinal()] = 0;
                    }
                }
            }
        }

        // 4. Minecart Speed Check
        if (vehicle instanceof Minecart) {
            if (hDist > 0.95) {
                d.buffer[CheckType.VEHICLE.ordinal()] += 1.0;
                if (d.buffer[CheckType.VEHICLE.ordinal()] >= 2.0) {
                    String detail = String.format(Locale.ROOT, "Minecart speed H=%.2f (Max=0.8)", hDist);
                    manager.flag(p, d, CheckType.VEHICLE, detail, 1.0);
                    d.buffer[CheckType.VEHICLE.ordinal()] = 0;
                }
            }
        }

        // 5. Vehicle Phase Check (Xuyên khối / Xuyên tường / Xuyên Bedrock bằng phương tiện)
        Block inside = to.getBlock();
        if (inside.getType().isSolid() && inside.getType().isOccluding() && inside.getType() != Material.AIR) {
            if (inside.getType() == Material.BEDROCK || inside.getType() == Material.BARRIER || inside.getType().name().contains("DOOR")) {
                manager.flag(p, d, CheckType.PHASE, "VehiclePhase xuyên khối " + inside.getType().name(), 2.0);
                vehicle.eject();
                Location safe = manager.pickSetback(d, from, true);
                if (safe != null) p.teleport(safe);
            }
        }
    }

    /**
     * Kiểm tra Disabler & Ping Spoof.
     * So sánh tần suất gửi gói tin di chuyển (50ms đều đặn) với Ping báo cáo giả lập.
     */
    public void processDisabler(Player p, PlayerData d) {
        if (!manager.isActive() || !manager.isEnabled(CheckType.DISABLER)) return;
        if (manager.isBypassed(p, d)) return;

        long now = System.currentTimeMillis();
        if (d.lastMovePacketTime > 0) {
            long packetInterval = now - d.lastMovePacketTime;
            int ping = manager.safePing(p);

            // Nếu người chơi gửi gói di chuyển cực kỳ mượt (45 - 55ms) chứng tỏ kết nối trực tiếp không lag,
            // nhưng ping báo cáo giả lập đột biến lên tới > 800ms nhằm vô hiệu hóa Reach / Velocity checks
            if (packetInterval >= 45 && packetInterval <= 55 && ping > 800) {
                d.pingSpoofBuffer++;
                if (d.pingSpoofBuffer >= 40) { // 2 giây liên tục
                    String detail = String.format(Locale.ROOT, "Disabler PingSpoof (Ping=%dms, MoveInterval=%dms)", ping, packetInterval);
                    manager.flag(p, d, CheckType.DISABLER, detail, 1.0);
                    d.pingSpoofBuffer = 0;
                }
            } else {
                d.pingSpoofBuffer = Math.max(0, d.pingSpoofBuffer - 1);
            }
        }
        d.lastMovePacketTime = now;
    }
}
