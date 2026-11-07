package com.javastudio.anticheat;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;

public final class InventoryFsmCheck {

    private final AntiCheatManager manager;

    public InventoryFsmCheck(AntiCheatManager manager) {
        this.manager = manager;
    }

    public void processInventoryOpen(Player p, PlayerData d, InventoryOpenEvent event) {
        if (!manager.isActive()) return;
        d.invState = PlayerData.InvState.OPEN;
        d.lastInvOpen = System.currentTimeMillis();
        d.lastInvStateChange = d.lastInvOpen;
    }

    public void processInventoryClose(Player p, PlayerData d, InventoryCloseEvent event) {
        if (!manager.isActive()) return;
        long now = System.currentTimeMillis();

        Inventory top = event.getView().getTopInventory();
        boolean externalContainer = top.getType() != InventoryType.CRAFTING && top.getType() != InventoryType.CREATIVE;

        if (externalContainer && manager.isEnabled(CheckType.DUPE) && !manager.isBypassed(p, d)) {
            if (d.lastDropPacketTime > 0 && now - d.lastDropPacketTime < 15L && now - d.lastContainerClickTime < 30L) {
                d.buffer[CheckType.DUPE.ordinal()] += 1.0;
                if (d.buffer[CheckType.DUPE.ordinal()] >= 2.0) {
                    manager.flag(p, d, CheckType.DUPE, "Instant Drop+Close container dupe (diff=" + (now - d.lastDropPacketTime) + "ms)", 2.0);
                    d.buffer[CheckType.DUPE.ordinal()] = 0;
                }
            }
        }

        d.invState = PlayerData.InvState.CLOSED;
        d.lastInvClose = now;
        d.lastInvStateChange = now;
        d.lastClickedSlot = -1;
    }

    public void processInventoryClick(Player p, PlayerData d, InventoryClickEvent event) {
        if (!manager.isActive() || manager.isBypassed(p, d)) return;
        long now = System.currentTimeMillis();

        if (manager.isEnabled(CheckType.BADPACKETS)) {
            int rawSlot = event.getRawSlot();
            int slot = event.getSlot();
            if (slot < -999 || rawSlot > 250 || rawSlot < -999) {
                event.setCancelled(true);
                manager.flag(p, d, CheckType.BADPACKETS, "Out-of-bounds slot packet (slot=" + slot + ", raw=" + rawSlot + ")", 2.0);
                return;
            }
        }

        Inventory top = event.getView().getTopInventory();
        InventoryType topType = top.getType();
        boolean isPersonalView = topType == InventoryType.CRAFTING || topType == InventoryType.CREATIVE;

        if (!isPersonalView && manager.isEnabled(CheckType.DUPE)) {
            Inventory clicked = event.getClickedInventory();
            if (clicked != null && clicked == top && d.invState == PlayerData.InvState.CLOSED) {
                event.setCancelled(true);
                p.closeInventory();
                manager.flag(p, d, CheckType.DUPE, "Ghost container click khi trạng thái FSM là CLOSED (" + topType + ")", 2.0);
                return;
            }
        }

        if (!isPersonalView) {
            d.lastContainerClickTime = now;
        }
        d.lastClickedSlot = event.getSlot();
    }

    public void processDropItem(Player p, PlayerData d, PlayerDropItemEvent event) {
        if (!manager.isActive()) return;
        long now = System.currentTimeMillis();
        d.lastDropPacketTime = now;

        if (manager.isEnabled(CheckType.DUPE) && !manager.isBypassed(p, d)) {
            if (d.invState == PlayerData.InvState.OPEN && d.lastContainerClickTime > 0 && (now - d.lastContainerClickTime < 8L)) {
                d.buffer[CheckType.DUPE.ordinal()] += 0.5;
                if (d.buffer[CheckType.DUPE.ordinal()] >= 2.5) {
                    manager.flag(p, d, CheckType.DUPE, "Rapid Click-Drop packet dupe", 1.0);
                    d.buffer[CheckType.DUPE.ordinal()] = 0.5;
                }
            }
        }
    }
}
