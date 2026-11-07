package com.javastudio.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.Locale;

public final class VehicleCheck {

    private final AntiCheatManager manager;

    public VehicleCheck(AntiCheatManager manager) {
        this.manager = manager;
    }

    public void processVehicleMove(Vehicle vehicle, VehicleMoveEvent event) {
        if (!manager.isActive() || !manager.isEnabled(CheckType.VEHICLE)) return;
        if (vehicle.getPassengers().isEmpty()) return;

        if (!(vehicle.getPassengers().get(0) instanceof Player p)) return;
        PlayerData d = manager.data(p);
        if (manager.isBypassed(p, d) || manager.isLagging()) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;

        World world = to.getWorld();
        if (world == null) return;

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double hDist = Math.hypot(dx, dz);

        long now = System.currentTimeMillis();
        d.lastVehicleMove = now;
        d.vehicleLastDy = dy;

        boolean hasWaterOrIce = false;
        boolean hasBounce = false;
        boolean hasGround = false;

        int bx = to.getBlockX();
        int by = to.getBlockY();
        int bz = to.getBlockZ();

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (!AcUtil.loaded(world, bx + ox, bz + oz)) continue;
                for (int oy = -1; oy <= 0; oy++) {
                    Block b = world.getBlockAt(bx + ox, by + oy, bz + oz);
                    Material mat = b.getType();
                    if (mat.isAir()) continue;
                    if (mat == Material.WATER || mat == Material.BUBBLE_COLUMN
                            || mat == Material.ICE || mat == Material.PACKED_ICE
                            || mat == Material.BLUE_ICE || mat == Material.FROSTED_ICE) {
                        hasWaterOrIce = true;
                    } else if (b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) {
                        hasWaterOrIce = true;
                    }
                    if (mat == Material.SLIME_BLOCK || mat == Material.HONEY_BLOCK) {
                        hasBounce = true;
                        d.lastBounce = now;
                    }
                    if (b.isSolid() || mat.name().contains("SLAB") || mat.name().contains("STAIRS") || mat == Material.LILY_PAD) {
                        hasGround = true;
                    }
                }
            }
        }

        if (vehicle instanceof Boat) {
            if (!hasWaterOrIce && !hasGround && !hasBounce) {
                d.vehicleAirTicks++;
            } else {
                d.vehicleAirTicks = 0;
            }

            boolean recentBounce = hasBounce || (now - d.lastBounce < 2200L);
            if (d.vehicleAirTicks > 14 && !recentBounce) {
                if (dy > -0.04) {
                    d.buffer[CheckType.VEHICLE.ordinal()] += 1.5;
                    if (d.buffer[CheckType.VEHICLE.ordinal()] >= 2.5) {
                        String detail = String.format(Locale.ROOT, "BoatFly dy=%.2f H=%.2f airTicks=%d", dy, hDist, d.vehicleAirTicks);
                        if (manager.flag(p, d, CheckType.VEHICLE, detail, 1.0)) {
                            vehicle.eject();
                            Location safe = manager.pickSetback(d, from, true);
                            if (safe != null) {
                                p.teleport(safe);
                            }
                            d.buffer[CheckType.VEHICLE.ordinal()] = 0;
                            return;
                        }
                    }
                } else {
                    d.buffer[CheckType.VEHICLE.ordinal()] = Math.max(0, d.buffer[CheckType.VEHICLE.ordinal()] - 0.25);
                }
            }

            if (!hasWaterOrIce && hasGround && d.vehicleAirTicks == 0) {
                if (hDist > 0.55 && (now - d.lastIce > 1800L)) {
                    d.buffer[CheckType.VEHICLE.ordinal()] += 1.0;
                    if (d.buffer[CheckType.VEHICLE.ordinal()] >= 3.0) {
                        String detail = String.format(Locale.ROOT, "BoatSpeed on land H=%.2f (Max=0.55)", hDist);
                        if (manager.flag(p, d, CheckType.VEHICLE, detail, 1.0)) {
                            vehicle.eject();
                            Location safe = manager.pickSetback(d, from, true);
                            if (safe != null) p.teleport(safe);
                            d.buffer[CheckType.VEHICLE.ordinal()] = 0;
                            return;
                        }
                    }
                } else {
                    d.buffer[CheckType.VEHICLE.ordinal()] = Math.max(0, d.buffer[CheckType.VEHICLE.ordinal()] - 0.2);
                }
            } else if (hasWaterOrIce) {
                d.lastIce = now;
                if (hDist > 2.45) {
                    d.buffer[CheckType.VEHICLE.ordinal()] += 1.0;
                    if (d.buffer[CheckType.VEHICLE.ordinal()] >= 3.0) {
                        String detail = String.format(Locale.ROOT, "BoatSpeed on ice/water H=%.2f (Max=2.45)", hDist);
                        manager.flag(p, d, CheckType.VEHICLE, detail, 1.0);
                        d.buffer[CheckType.VEHICLE.ordinal()] = 0;
                    }
                } else {
                    d.buffer[CheckType.VEHICLE.ordinal()] = Math.max(0, d.buffer[CheckType.VEHICLE.ordinal()] - 0.2);
                }
            }
        }

        if (vehicle instanceof Minecart minecart) {
            double maxCart = Math.max(1.05, minecart.getMaxSpeed() * 2.2);
            if (hDist > maxCart) {
                d.buffer[CheckType.VEHICLE.ordinal()] += 1.0;
                if (d.buffer[CheckType.VEHICLE.ordinal()] >= 3.0) {
                    String detail = String.format(Locale.ROOT, "Minecart speed H=%.2f (Max=%.2f)", hDist, maxCart);
                    manager.flag(p, d, CheckType.VEHICLE, detail, 1.0);
                    d.buffer[CheckType.VEHICLE.ordinal()] = 0;
                }
            } else {
                d.buffer[CheckType.VEHICLE.ordinal()] = Math.max(0, d.buffer[CheckType.VEHICLE.ordinal()] - 0.2);
            }
        }

        if (AcUtil.loaded(world, bx, bz) && hDist > 0.12) {
            Block inside = to.getBlock();
            Material inType = inside.getType();
            if (inType.isSolid() && inType.isOccluding() && !inside.isPassable()) {
                if (inType == Material.BEDROCK || inType == Material.BARRIER || inType == Material.OBSIDIAN) {
                    manager.flag(p, d, CheckType.PHASE, "VehiclePhase xuyên khối " + inType.name(), 2.0);
                    vehicle.eject();
                    Location safe = manager.pickSetback(d, from, true);
                    if (safe != null) p.teleport(safe);
                }
            }
        }
    }

    public void processDisabler(Player p, PlayerData d) {
        processDisabler(p, d, System.currentTimeMillis());
    }

    public void processDisabler(Player p, PlayerData d, long now) {
        if (!manager.isActive() || !manager.isEnabled(CheckType.DISABLER)) return;
        if (manager.isBypassed(p, d)) return;

        if (d.lastMovePacketTime > 0) {
            long packetInterval = now - d.lastMovePacketTime;
            int ping = manager.safePing(p);

            if (packetInterval >= 45 && packetInterval <= 55 && ping > 850) {
                d.pingSpoofBuffer++;
                if (d.pingSpoofBuffer >= 45) {
                    String detail = String.format(Locale.ROOT, "Disabler PingSpoof (Ping=%dms, MoveInterval=%dms)", ping, packetInterval);
                    manager.flag(p, d, CheckType.DISABLER, detail, 1.0);
                    d.pingSpoofBuffer = 0;
                }
            } else {
                d.pingSpoofBuffer = Math.max(0, d.pingSpoofBuffer - 1);
            }
        }
    }
}
