package com.javastudio.api.gui;

import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class SmartGui implements InventoryHolder {

    private final String title;
    private final int rows;
    private final int size;
    private final Map<Integer, GuiButton> buttons = new ConcurrentHashMap<>();
    private boolean cancelClicks = true;
    private Consumer<Player> closeHandler;

    private static final Map<UUID, SmartGui> openGuis = new ConcurrentHashMap<>();

    public SmartGui(String title, int rows) {
        this.title = MessageUtil.color(title);
        this.rows = Math.max(1, Math.min(6, rows));
        this.size = this.rows * 9;
    }

    public static SmartGui of(String title, int rows) {
        return new SmartGui(title, rows);
    }

    public SmartGui setButton(int slot, GuiButton button) {
        if (slot >= 0 && slot < size) {
            buttons.put(slot, button);
        }
        return this;
    }

    public SmartGui setButton(int slot, ItemStack item, BiConsumer<Player, InventoryClickEvent> handler) {
        return setButton(slot, GuiButton.of(item).onClick(handler));
    }

    public SmartGui fillBorder(ItemStack borderItem) {
        GuiButton btn = GuiButton.of(borderItem);
        for (int c = 0; c < 9; c++) {
            setButton(c, btn);
            if (rows > 1) {
                setButton((rows - 1) * 9 + c, btn);
            }
        }
        for (int r = 1; r < rows - 1; r++) {
            setButton(r * 9, btn);
            setButton(r * 9 + 8, btn);
        }
        return this;
    }

    public SmartGui fillAll(ItemStack fillItem) {
        GuiButton btn = GuiButton.of(fillItem);
        for (int i = 0; i < size; i++) {
            if (!buttons.containsKey(i)) {
                setButton(i, btn);
            }
        }
        return this;
    }

    public SmartGui onClose(Consumer<Player> handler) {
        this.closeHandler = handler;
        return this;
    }

    public SmartGui setCancelClicks(boolean cancel) {
        this.cancelClicks = cancel;
        return this;
    }

    @SuppressWarnings("deprecation")
    public void open(Player player) {
        if (player == null || !player.isOnline()) return;

        Inventory inv = Bukkit.createInventory(this, size, title);
        for (Map.Entry<Integer, GuiButton> entry : buttons.entrySet()) {
            if (entry.getKey() < size && entry.getValue() != null) {
                inv.setItem(entry.getKey(), entry.getValue().getItem());
            }
        }

        openGuis.put(player.getUniqueId(), this);
        player.openInventory(inv);
    }

    public void handleClick(Player player, InventoryClickEvent event) {
        if (cancelClicks) {
            event.setCancelled(true);
        }

        int slot = event.getRawSlot();
        if (slot >= 0 && slot < size) {
            GuiButton button = buttons.get(slot);
            if (button != null) {
                if (button.getRequiredPermission() != null && !button.getRequiredPermission().isBlank()) {
                    if (!player.hasPermission(button.getRequiredPermission()) && !player.isOp()) {
                        MessageUtil.sendError(player, "Bạn không có quyền thực hiện thao tác này!");
                        return;
                    }
                }

                if (button.getClickSound() != null) {
                    player.playSound(player.getLocation(), button.getClickSound(), 1.0f, 1.0f);
                }

                if (button.getClickHandler() != null) {
                    try {
                        button.getClickHandler().accept(player, event);
                    } catch (Throwable t) {
                        MessageUtil.sendError(player, "Lỗi khi xử lý click GUI: " + t.getMessage());
                        t.printStackTrace();
                    }
                }

                if (button.isCloseOnClick()) {
                    player.closeInventory();
                }
            }
        }
    }

    public void handleClose(Player player) {
        openGuis.remove(player.getUniqueId());
        if (closeHandler != null) {
            try {
                closeHandler.accept(player);
            } catch (Throwable ignored) {}
        }
    }

    public static SmartGui getActiveGui(Player player) {
        if (player == null) return null;
        return openGuis.get(player.getUniqueId());
    }

    public static void unregisterPlayer(Player player) {
        if (player != null) {
            openGuis.remove(player.getUniqueId());
        }
    }

    @Override
    public Inventory getInventory() {
        return null;
    }

    public String getTitle() {
        return title;
    }

    public int getRows() {
        return rows;
    }

    public int getSize() {
        return size;
    }
}
