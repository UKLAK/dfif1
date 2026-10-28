package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

/**
 * Lắng nghe tất cả sự kiện Bukkit/Paper liên quan để kích hoạt các tầng kiểm tra Anti-Cheat.
 * Tương thích Folia (xử lý trên đúng region thread của người chơi).
 */
public final class AntiCheatListener implements Listener {

    private final AntiCheatManager manager;
    private final MovementCheck movementCheck;
    private final CombatCheck combatCheck;
    private final WorldCheck worldCheck;
    private final PlayerCheck playerCheck;

    public AntiCheatListener(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.manager = manager;
        this.movementCheck = new MovementCheck(manager);
        this.combatCheck = new CombatCheck(manager);
        this.worldCheck = new WorldCheck(manager);
        this.playerCheck = new PlayerCheck(plugin, manager);
    }

    // ============================================================
    //  VÒNG ĐỜI NGƯỜI CHƠI & CHUYỂN TRẠNG THÁI
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        d.setback = p.getLocation().clone();
        d.lastLoc = p.getLocation().clone();
        d.lastSafeGround = p.getLocation().clone();

        double pending = manager.consumePendingFall(p.getUniqueId());
        if (pending > 3.0) {
            d.realFallDist = pending;
            p.setFallDistance((float) pending);
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        PlayerData d = manager.peek(p.getUniqueId());
        if (d != null) {
            // Chống exploit ClickTP / Phase relog: ép vị trí lưu về lastSafeGround trước khi Server ghi file dữ liệu
            if (d.lastSafeGround != null && d.lastSafeGround.getWorld() == p.getWorld()) {
                if (d.lastSetback > 0 || !d.serverGround) {
                    try {
                        p.teleport(d.lastSafeGround);
                    } catch (Throwable ignored) {}
                }
            }
            // Chống NoFall / CombatLog né sát thương rơi: lưu lại độ cao rơi để phạt khi vào lại
            if (d.realFallDist > 3.0 && !d.serverGround) {
                manager.savePendingFall(p.getUniqueId(), d.realFallDist);
            }
            if (d.isFrozen) {
                manager.flag(p, d, CheckType.INVALIDMOVE, "Tẩu thoát khi đang bị Freeze bởi " + d.frozenBy, 10.0);
                org.bukkit.Bukkit.broadcast(MessageUtil.colorize("&c[AntiCheat] &f" + p.getName() + " &7đã ngắt kết nối khi đang bị Staff (" + d.frozenBy + ") Freeze kiểm tra!"), "javastudio.ac.alerts");
            }
        }
        if (manager.getAuraBotManager() != null) {
            manager.getAuraBotManager().despawn(p.getUniqueId());
        }
        if (manager.getXrayManager() != null) {
            manager.getXrayManager().cleanupPlayer(p.getUniqueId());
        }
        manager.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleMove(org.bukkit.event.vehicle.VehicleMoveEvent event) {
        if (!manager.isActive()) return;
        if (manager.getVehicleCheck() != null) {
            manager.getVehicleCheck().processVehicleMove(event.getVehicle(), event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        long now = System.currentTimeMillis();
        // Nếu là teleport từ Setback của chính Anti-Cheat
        if (now - d.lastSetback < 150) {
            return;
        }
        d.lastTeleport = now;
        d.resetMovement();
        if (event.getTo() != null) {
            d.setback = event.getTo().clone();
            d.lastLoc = event.getTo().clone();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData d = manager.data(event.getPlayer());
        d.lastRespawn = System.currentTimeMillis();
        d.resetMovement();
        d.setback = event.getRespawnLocation().clone();
        d.lastLoc = event.getRespawnLocation().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData d = manager.data(event.getPlayer());
        d.resetMovement();
        d.setback = event.getPlayer().getLocation().clone();
        d.lastLoc = event.getPlayer().getLocation().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        PlayerData d = manager.data(event.getPlayer());
        d.lastGameModeChange = System.currentTimeMillis();
        d.resetMovement();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onToggleGlide(org.bukkit.event.entity.EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player p) {
            PlayerData d = manager.data(p);
            long now = System.currentTimeMillis();
            if (!event.isGliding()) {
                d.lastGlideStop = now;
            } else {
                d.lastGlideStart = now;
            }

            // Phát hiện ElytraFly Packet Spam (liên tục bật/tắt cánh cứng trong thời gian siêu ngắn)
            d.glideToggles.addLast(now);
            while (!d.glideToggles.isEmpty() && now - d.glideToggles.peekFirst() > 1200L) {
                d.glideToggles.pollFirst();
            }
            if (d.glideToggles.size() > 6) {
                manager.flag(p, d, CheckType.ELYTRA, "Elytra packet toggle spam (toggles=" + d.glideToggles.size() + "/1.2s)", 1.0);
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onElytraBoost(com.destroystokyo.paper.event.player.PlayerElytraBoostEvent event) {
        if (!manager.isActive()) return;
        PlayerData d = manager.data(event.getPlayer());
        d.lastFirework = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRiptide(org.bukkit.event.player.PlayerRiptideEvent event) {
        if (!manager.isActive()) return;
        PlayerData d = manager.data(event.getPlayer());
        d.lastRiptide = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            PlayerData d = manager.data(p);
            long now = System.currentTimeMillis();
            d.lastDamage = now;
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                d.lastFallDamage = now;
            }
        }
    }

    // ============================================================
    //  DI CHUYỂN
    // ============================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        if (manager.isBypassed(p, d)) return;

        // Chỉ xử lý nếu có sự thay đổi tọa độ hoặc góc nhìn
        if (event.getFrom().getX() == event.getTo().getX() &&
                event.getFrom().getY() == event.getTo().getY() &&
                event.getFrom().getZ() == event.getTo().getZ() &&
                event.getFrom().getYaw() == event.getTo().getYaw() &&
                event.getFrom().getPitch() == event.getTo().getPitch()) {
            return;
        }

        float yawDelta = Math.abs(event.getTo().getYaw() - event.getFrom().getYaw());
        float pitchDelta = Math.abs(event.getTo().getPitch() - event.getFrom().getPitch());
        combatCheck.processRotation(p, d, yawDelta, pitchDelta);

        if (manager.getReplayManager() != null) {
            double dx = event.getTo().getX() - event.getFrom().getX();
            double dz = event.getTo().getZ() - event.getFrom().getZ();
            double spd = Math.sqrt(dx * dx + dz * dz);
            manager.getReplayManager().recordFrame(p, "MOVE", d.clicks.size(), spd);
        }

        if (manager.getVehicleCheck() != null) {
            manager.getVehicleCheck().processDisabler(p, d);
        }

        if (manager.getClusterEngine() != null) {
            manager.getClusterEngine().evaluatePlayer(p, d);
        }

        movementCheck.processMove(p, d, event);
    }

    // ============================================================
    //  CHIẾN ĐẤU
    // ============================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!manager.isActive()) return;

        // Bẫy AuraBot tàng hình (Hướng 1)
        if (manager.getAuraBotManager() != null && manager.getAuraBotManager().isAuraBot(event.getEntity())) {
            if (event.getDamager() instanceof Player attacker) {
                manager.getAuraBotManager().handleBotAttacked(attacker, event.getEntity(), event);
            } else {
                event.setCancelled(true);
            }
            return;
        }

        if (event.getDamager() instanceof Player attacker) {
            PlayerData d = manager.data(attacker);
            if (manager.getReplayManager() != null) {
                manager.getReplayManager().recordFrame(attacker, "ATTACK", d.clicks.size(), d.lastH);
            }
            if (manager.getClusterEngine() != null) {
                manager.getClusterEngine().evaluatePlayer(attacker, d);
            }
            combatCheck.processAttack(attacker, d, event.getEntity(), event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmSwing(PlayerAnimationEvent event) {
        if (!manager.isActive()) return;
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            Player p = event.getPlayer();
            PlayerData d = manager.data(p);
            if (manager.getReplayManager() != null) {
                manager.getReplayManager().recordFrame(p, "SWING", d.clicks.size(), d.lastH);
            }
            combatCheck.processArmSwing(p, d, event);
        }
    }

    // ============================================================
    //  THẾ GIỚI & KHỐI
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        worldCheck.processBlockDamage(p, d, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);

        // Bẫy quặng Honeypot & tỷ lệ quặng (Hướng 2)
        if (manager.getXrayManager() != null) {
            manager.getXrayManager().processBlockBreak(p, d, event.getBlock(), event);
        }

        worldCheck.processBlockBreak(p, d, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        worldCheck.processBlockPlace(p, d, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!manager.isActive()) return;
        if (event.getWhoClicked() instanceof Player p) {
            PlayerData d = manager.data(p);

            // AutoTotem qua Inventory Click / Shift-click / Offhand slot (Hướng 4)
            if (manager.isEnabled(CheckType.AUTOTOTEM)) {
                boolean isOffhandSlot = event.getSlot() == 40 || event.getRawSlot() == 45
                        || event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND;
                boolean isTotem = (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.TOTEM_OF_UNDYING)
                        || (event.getCursor() != null && event.getCursor().getType() == Material.TOTEM_OF_UNDYING);
                if (isOffhandSlot && isTotem) {
                    long now = System.currentTimeMillis();
                    long diff = now - d.lastTotemPop;
                    if (diff < 50L && d.lastTotemPop > 0) {
                        if (manager.flag(p, d, CheckType.AUTOTOTEM, "AutoTotem inventory click trong " + diff + "ms", 1.0)) {
                            if (manager.settings(CheckType.AUTOTOTEM).isCancel()) {
                                event.setCancelled(true);
                            }
                        }
                    }
                }
            }

            // Máy trạng thái túi đồ & Chống Dupe (Hướng 4)
            if (manager.getInventoryFsmCheck() != null) {
                manager.getInventoryFsmCheck().processInventoryClick(p, d, event);
            }

            worldCheck.processInventoryClick(p, d, event);
        }
    }

    // ============================================================
    //  HÀNH ĐỘNG NGƯỜI CHƠI
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        playerCheck.processVelocity(p, d, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        playerCheck.processConsume(p, d, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!manager.isActive()) return;
        if (event.getEntity() instanceof Player p) {
            PlayerData d = manager.data(p);
            playerCheck.processShootBow(p, d, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleSprint(PlayerToggleSprintEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        playerCheck.processToggleSprint(p, d, event);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);

        if (p.isInsideVehicle()) {
            worldCheck.processVehicleInteract(p, d, event);
        }

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = event.getItem();
            long now = System.currentTimeMillis();

            // Kiểm tra pháo hoa khi lướt cánh cứng (hỗ trợ cả tay chính, tay phụ hoặc nhảy bắn pháo hoa)
            ItemStack main = p.getInventory().getItemInMainHand();
            ItemStack off = p.getInventory().getItemInOffHand();
            boolean usingFirework = (item != null && item.getType() == Material.FIREWORK_ROCKET)
                    || (main != null && main.getType() == Material.FIREWORK_ROCKET)
                    || (off != null && off.getType() == Material.FIREWORK_ROCKET);

            if (usingFirework) {
                ItemStack chest = p.getInventory().getChestplate();
                boolean hasElytra = chest != null && chest.getType() == Material.ELYTRA;
                if (p.isGliding() || (hasElytra && !p.isOnGround())) {
                    d.lastFirework = now;
                }
            }

            if (item != null) {
                Material m = item.getType();
                // Bắt đầu ăn hoặc giương cung
                if (m.isEdible() || m == Material.BOW || m == Material.CROSSBOW || m == Material.POTION) {
                    d.useStart = now;
                    d.useItem = m;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player p) {
            PlayerData d = manager.data(p);
            d.lastTotemPop = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        ItemStack off = event.getMainHandItem();
        if (off != null && off.getType() == Material.TOTEM_OF_UNDYING) {
            combatCheck.processTotemEquip(p, d, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        worldCheck.processSlotChange(p, d, event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player p) {
            PlayerData d = manager.data(p);
            d.lastInvOpen = System.currentTimeMillis();
            if (manager.getInventoryFsmCheck() != null) {
                manager.getInventoryFsmCheck().processInventoryOpen(p, d, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player p) {
            PlayerData d = manager.data(p);
            d.lastInvClose = System.currentTimeMillis();
            if (manager.getInventoryFsmCheck() != null) {
                manager.getInventoryFsmCheck().processInventoryClose(p, d, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDropItem(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        if (manager.getInventoryFsmCheck() != null) {
            manager.getInventoryFsmCheck().processDropItem(p, d, event);
        }
    }
}
