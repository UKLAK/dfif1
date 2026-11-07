package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        Location loc = p.getLocation();
        d.pendingKick = false;
        d.setback = loc.clone();
        d.lastLoc = loc.clone();
        d.lastSafeGround = loc.clone();
        d.lastYaw = loc.getYaw();
        d.lastPitch = loc.getPitch();
        d.hasRotationSample = true;

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
        UUID uuid = p.getUniqueId();
        PlayerData d = manager.peek(uuid);
        if (d != null) {
            if (d.lastSafeGround != null && d.lastSafeGround.getWorld() == p.getWorld()) {
                if (d.lastSetback > 0 || !d.serverGround) {
                    try {
                        p.teleport(d.lastSafeGround);
                    } catch (Throwable ignored) {}
                }
            }
            if (d.realFallDist > 3.0 && !d.serverGround) {
                manager.savePendingFall(uuid, d.realFallDist);
            }
            if (d.isFrozen) {
                manager.flag(p, d, CheckType.INVALIDMOVE, "Tẩu thoát khi đang bị Freeze bởi " + d.frozenBy, 10.0);
                Bukkit.broadcast(MessageUtil.colorize("&c[AntiCheat] &f" + p.getName() + " &7đã ngắt kết nối khi đang bị Staff (" + d.frozenBy + ") Freeze kiểm tra!"), manager.getAlertPermission());
            }
        }
        if (manager.getAuraBotManager() != null) {
            manager.getAuraBotManager().despawn(uuid);
        }
        if (manager.getXrayManager() != null) {
            manager.getXrayManager().cleanupPlayer(uuid);
        }
        if (manager.getReplayManager() != null) {
            manager.getReplayManager().cleanupPlayer(uuid);
        }
        if (manager.getBacktrackEngine() != null) {
            manager.getBacktrackEngine().removeEntity(uuid);
        }
        manager.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!manager.isActive()) return;
        if (manager.getVehicleCheck() != null) {
            manager.getVehicleCheck().processVehicleMove(event.getVehicle(), event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player p) {
            PlayerData d = manager.data(p);
            d.lastTeleport = System.currentTimeMillis();
            d.resetMovement();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player p) {
            PlayerData d = manager.data(p);
            d.lastTeleport = System.currentTimeMillis();
            d.resetMovement();
            d.setback = p.getLocation().clone();
            d.lastSafeGround = p.getLocation().clone();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        long now = System.currentTimeMillis();
        if (now - d.lastSetback < 150) {
            return;
        }
        d.lastTeleport = now;
        d.resetMovement();
        if (event.getTo() != null) {
            d.setback = event.getTo().clone();
            d.lastLoc = event.getTo().clone();
            d.lastYaw = event.getTo().getYaw();
            d.lastPitch = event.getTo().getPitch();
            d.hasRotationSample = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData d = manager.data(event.getPlayer());
        d.lastRespawn = System.currentTimeMillis();
        d.resetMovement();
        d.realFallDist = 0;
        Location resp = event.getRespawnLocation();
        d.setback = resp.clone();
        d.lastLoc = resp.clone();
        d.lastSafeGround = resp.clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        d.lastTeleport = System.currentTimeMillis();
        d.resetMovement();
        d.realFallDist = 0;
        d.lastVictimLoc = null;
        Location loc = p.getLocation();
        d.setback = loc.clone();
        d.lastLoc = loc.clone();
        d.lastSafeGround = loc.clone();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        PlayerData d = manager.data(event.getPlayer());
        d.lastGameModeChange = System.currentTimeMillis();
        d.resetMovement();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!manager.isActive()) return;
        if (event.getEntity() instanceof Player p) {
            PlayerData d = manager.data(p);
            if (manager.isBypassed(p, d)) return;
            long now = System.currentTimeMillis();
            if (!event.isGliding()) {
                d.lastGlideStop = now;
            } else {
                d.lastGlideStart = now;
            }

            d.glideToggles.addLast(now);
            while (!d.glideToggles.isEmpty() && now - d.glideToggles.peekFirst() > 1200L) {
                d.glideToggles.pollFirst();
            }
            if (d.glideToggles.size() > 7) {
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
    public void onRiptide(PlayerRiptideEvent event) {
        if (!manager.isActive()) return;
        PlayerData d = manager.data(event.getPlayer());
        d.lastRiptide = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            PlayerData d = manager.data(p);
            long now = System.currentTimeMillis();
            d.lastDamage = now;
            EntityDamageEvent.DamageCause cause = event.getCause();
            if (cause == EntityDamageEvent.DamageCause.FALL) {
                d.lastFallDamage = now;
            } else if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                    || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                    || cause.name().contains("SONIC_BOOM")) {
                d.lastVelocity = now;
                d.lastExplosionBoost = now;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!manager.isActive()) return;
        Projectile proj = event.getEntity();
        String typeName = proj.getType().name();
        boolean isWindCharge = typeName.contains("WIND_CHARGE");
        boolean isBlastProj = isWindCharge || proj instanceof Fireball || proj instanceof ShulkerBullet
                || typeName.contains("WITHER_SKULL");
        if (!isBlastProj) return;

        Location hitLoc = proj.getLocation();
        if (hitLoc.getWorld() == null) return;
        long now = System.currentTimeMillis();
        for (Player p : hitLoc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(hitLoc) <= 36.0) {
                PlayerData d = manager.data(p);
                d.lastVelocity = now;
                d.lastExplosionBoost = now;
                if (isWindCharge) {
                    d.lastWindCharge = now;
                    d.realFallDist = 0;
                    d.fallDist = 0;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!manager.isActive()) return;
        grantExplosionGrace(event.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!manager.isActive()) return;
        grantExplosionGrace(event.getBlock().getLocation());
    }

    private void grantExplosionGrace(Location center) {
        if (center == null || center.getWorld() == null) return;
        long now = System.currentTimeMillis();
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= 81.0) {
                PlayerData d = manager.data(p);
                d.lastVelocity = now;
                d.lastExplosionBoost = now;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!manager.isActive()) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;

        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        if (manager.isBypassed(p, d)) return;

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        boolean posChanged = (dx * dx + dy * dy + dz * dz) > 1.0e-9;

        float yawDelta = AcUtil.angleDiff(to.getYaw(), from.getYaw());
        float pitchDelta = Math.abs(to.getPitch() - from.getPitch());
        boolean rotChanged = yawDelta > 1.0e-4f || pitchDelta > 1.0e-4f;

        if (!posChanged && !rotChanged) {
            return;
        }

        long now = System.currentTimeMillis();

        if (rotChanged) {
            float prevYawDelta = d.yawDelta;
            d.yawDelta = yawDelta;
            d.pitchDelta = pitchDelta;
            if (yawDelta > 35.0f) {
                d.lastBigFlick = now;
            }
            combatCheck.processRotation(p, d, yawDelta, pitchDelta, prevYawDelta);
            d.lastYaw = to.getYaw();
            d.lastPitch = to.getPitch();
            d.hasRotationSample = true;
        }

        if (!posChanged && (now - d.lastMovePacketTime < 42L || d.serverGround)) {
            return;
        }

        if (posChanged) {
            d.prevPosMoveMs = d.lastPosMoveMs;
            d.lastPosMoveMs = now;
        }

        if (manager.getReplayManager() != null) {
            double spd = Math.hypot(dx, dz);
            manager.getReplayManager().recordFrame(p, "MOVE", d.clicks.size(), spd);
        }

        if (manager.getVehicleCheck() != null) {
            manager.getVehicleCheck().processDisabler(p, d, now);
        }

        if (manager.getClusterEngine() != null) {
            manager.getClusterEngine().evaluatePlayer(p, d);
        }

        movementCheck.processMove(p, d, event, now);
        d.lastMovePacketTime = now;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!manager.isActive()) return;

        Entity victim = event.getEntity();
        if (manager.getAuraBotManager() != null && manager.getAuraBotManager().isAuraBot(victim)) {
            if (event.getDamager() instanceof Player attacker) {
                manager.getAuraBotManager().handleBotAttacked(attacker, victim, event);
            } else {
                event.setCancelled(true);
            }
            return;
        }

        if (event.getDamager() instanceof Player attacker) {
            PlayerData d = manager.data(attacker);
            long now = System.currentTimeMillis();
            if (manager.getReplayManager() != null) {
                manager.getReplayManager().recordFrame(attacker, "ATTACK", d.clicks.size(), d.lastH);
            }
            if (manager.getClusterEngine() != null) {
                manager.getClusterEngine().evaluatePlayer(attacker, d);
            }
            combatCheck.processAttack(attacker, d, victim, event);
            if (!event.isCancelled()) {
                if (victim instanceof LivingEntity le && !(victim instanceof Player) && manager.getBacktrackEngine() != null) {
                    manager.getBacktrackEngine().recordEntity(le, now);
                }
                ItemStack mainHand = attacker.getInventory().getItemInMainHand();
                if (mainHand != null && "MACE".equals(mainHand.getType().name()) && d.realFallDist > 1.5) {
                    d.lastMaceSmash = now;
                    d.realFallDist = 0;
                    d.fallDist = 0;
                    if (AcUtil.enchantLevel(mainHand, "WIND_BURST") > 0) {
                        d.lastVelocity = now;
                        d.lastWindCharge = now;
                    }
                }
            }
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        worldCheck.processBlockDamage(p, d, event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamageAbort(BlockDamageAbortEvent event) {
        if (!manager.isActive()) return;
        PlayerData d = manager.peek(event.getPlayer().getUniqueId());
        if (d != null) {
            d.lastAbortDigTime = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);

        worldCheck.processBlockBreak(p, d, event);
        if (!event.isCancelled() && manager.getXrayManager() != null) {
            manager.getXrayManager().processBlockBreak(p, d, event.getBlock(), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        d.lastBlockPlace = System.currentTimeMillis();
        worldCheck.processBlockPlace(p, d, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!manager.isActive()) return;
        if (event.getWhoClicked() instanceof Player p) {
            PlayerData d = manager.data(p);

            if (manager.isEnabled(CheckType.AUTOTOTEM) && !manager.isBypassed(p, d)) {
                boolean isOffhandSlot = event.getSlot() == 40 || event.getRawSlot() == 45
                        || event.getClick() == ClickType.SWAP_OFFHAND;
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

            if (manager.getInventoryFsmCheck() != null) {
                manager.getInventoryFsmCheck().processInventoryClick(p, d, event);
            }

            worldCheck.processInventoryClick(p, d, event);
        }
    }

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
        long now = System.currentTimeMillis();
        Action action = event.getAction();

        if (action == Action.LEFT_CLICK_BLOCK) {
            d.lastInteractBlockTime = now;
            return;
        }

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = event.getItem();
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
                if (m.isEdible() || m == Material.BOW || m == Material.POTION || m == Material.MILK_BUCKET || m == Material.HONEY_BOTTLE) {
                    if (!p.isHandRaised() || d.useStart == 0 || d.useItem != m || (now - d.useStart > 5000L)) {
                        d.useStart = now;
                        d.useItem = m;
                    }
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
        ItemStack off = event.getOffHandItem();
        ItemStack main = event.getMainHandItem();
        if ((off != null && off.getType() == Material.TOTEM_OF_UNDYING)
                || (main != null && main.getType() == Material.TOTEM_OF_UNDYING)) {
            combatCheck.processTotemEquip(p, d, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        d.useStart = 0;
        d.useItem = null;
        worldCheck.processSlotChange(p, d, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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
    public void onDropItem(PlayerDropItemEvent event) {
        if (!manager.isActive()) return;
        Player p = event.getPlayer();
        PlayerData d = manager.data(p);
        if (manager.getInventoryFsmCheck() != null) {
            manager.getInventoryFsmCheck().processDropItem(p, d, event);
        }
    }
}
