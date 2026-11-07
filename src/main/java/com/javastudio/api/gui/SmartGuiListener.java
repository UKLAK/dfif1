package com.javastudio.api.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class SmartGuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getInventory().getHolder() instanceof SmartGui smartGui) {
            smartGui.handleClick(player, event);
        } else {
            SmartGui tracked = SmartGui.getActiveGui(player);
            if (tracked != null) {
                tracked.handleClick(player, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        if (event.getInventory().getHolder() instanceof SmartGui smartGui) {
            smartGui.handleClose(player);
        } else {
            SmartGui tracked = SmartGui.getActiveGui(player);
            if (tracked != null) {
                tracked.handleClose(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        SmartGui.unregisterPlayer(event.getPlayer());
    }
}
