package com.javastudio.api.gui;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.function.BiConsumer;

public class GuiButton {

    private final ItemStack item;
    private BiConsumer<Player, InventoryClickEvent> onClick;
    private Sound clickSound;
    private String requiredPermission;
    private boolean closeOnClick = false;

    public GuiButton(ItemStack item) {
        this.item = item;
    }

    public static GuiButton of(ItemStack item) {
        return new GuiButton(item);
    }

    public GuiButton onClick(BiConsumer<Player, InventoryClickEvent> handler) {
        this.onClick = handler;
        return this;
    }

    public GuiButton sound(Sound sound) {
        this.clickSound = sound;
        return this;
    }

    public GuiButton permission(String perm) {
        this.requiredPermission = perm;
        return this;
    }

    public GuiButton closeOnClick() {
        this.closeOnClick = true;
        return this;
    }

    public ItemStack getItem() {
        return item;
    }

    public BiConsumer<Player, InventoryClickEvent> getClickHandler() {
        return onClick;
    }

    public Sound getClickSound() {
        return clickSound;
    }

    public String getRequiredPermission() {
        return requiredPermission;
    }

    public boolean isCloseOnClick() {
        return closeOnClick;
    }
}
