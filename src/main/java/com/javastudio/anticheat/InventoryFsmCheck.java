package com.javastudio.anticheat;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * Hướng 4: Máy Trạng Thái Túi Đồ & Chống Dupe Đồ / Crash Packets (Inventory FSM Shield).
 * Áp dụng mô hình Finite State Machine (FSM) kiểm soát chặt chẽ chu trình đóng/mở/tương tác túi đồ.
 * Chặn đứng toàn bộ các exploit:
 * 1. Ghost Container Click: Gửi gói tin click rương/thùng khi rương chưa mở hoặc đã đóng.
 * 2. Instant Drop + Close Dupe: Gửi gói vứt đồ đồng thời cùng một tick với đóng túi đồ hoặc logout.
 * 3. Out-of-bounds Slot Crash: Gửi index ô đồ âm hoặc vượt quá 250 để làm sập server.
 */
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

        // Chống exploit Instant Drop+Close Dupe (Combat-log / Shulker dupe)
        if (manager.isEnabled(CheckType.DUPE) && !manager.isBypassed(p, d)) {
            if (now - d.lastDropPacketTime < 25L && d.lastDropPacketTime > 0) {
                manager.flag(p, d, CheckType.DUPE, "Instant Drop+Close container dupe (diff=" + (now - d.lastDropPacketTime) + "ms)", 2.0);
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

        // 1. Chống gói tin Crash bằng Slot ngoài phạm vi (Out-of-Bounds Slot Crash)
        if (manager.isEnabled(CheckType.BADPACKETS)) {
            int rawSlot = event.getRawSlot();
            int slot = event.getSlot();
            if (slot < -1 || rawSlot > 250 || rawSlot < -999) {
                event.setCancelled(true);
                manager.flag(p, d, CheckType.BADPACKETS, "Out-of-bounds slot packet (slot=" + slot + ", raw=" + rawSlot + ")", 2.0);
                return;
            }
        }

        // 2. Chống Ghost Container Click (Tương tác với rương/container khi trạng thái FSM là CLOSED)
        if (manager.isEnabled(CheckType.DUPE)) {
            boolean isTopContainer = event.getClickedInventory() != null && event.getClickedInventory() != p.getInventory();
            if (isTopContainer && d.invState == PlayerData.InvState.CLOSED) {
                event.setCancelled(true);
                p.closeInventory();
                manager.flag(p, d, CheckType.DUPE, "Ghost container click khi trạng thái FSM là CLOSED", 2.0);
                return;
            }
        }

        d.lastContainerClickTime = now;
        d.lastClickedSlot = event.getSlot();
    }

    public void processDropItem(Player p, PlayerData d, PlayerDropItemEvent event) {
        if (!manager.isActive()) return;
        long now = System.currentTimeMillis();
        d.lastDropPacketTime = now;

        // Vừa click container vừa vứt đồ trong vòng 10ms
        if (manager.isEnabled(CheckType.DUPE) && !manager.isBypassed(p, d)) {
            if (d.invState == PlayerData.InvState.OPEN && (now - d.lastContainerClickTime < 10L)) {
                d.buffer[CheckType.DUPE.ordinal()] += 0.5;
                if (d.buffer[CheckType.DUPE.ordinal()] >= 2.0) {
                    manager.flag(p, d, CheckType.DUPE, "Rapid Click-Drop packet dupe", 1.0);
                    d.buffer[CheckType.DUPE.ordinal()] = 0;
                }
            }
        }
    }
}
