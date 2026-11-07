package com.javastudio.api.item;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this(material, 1);
    }

    public ItemBuilder(Material material, int amount) {
        this.item = new ItemStack(material != null ? material : Material.STONE, Math.max(1, amount));
        this.meta = item.getItemMeta();
    }

    public ItemBuilder(ItemStack item) {
        this.item = item != null ? item.clone() : new ItemStack(Material.STONE);
        this.meta = this.item.getItemMeta();
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material);
    }

    public static ItemBuilder of(Material material, int amount) {
        return new ItemBuilder(material, amount);
    }

    public static ItemBuilder of(ItemStack item) {
        return new ItemBuilder(item);
    }

    @SuppressWarnings("deprecation")
    public ItemBuilder name(String displayName) {
        if (meta != null && displayName != null) {
            meta.setDisplayName(MessageUtil.color(displayName));
        }
        return this;
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(Math.max(1, amount));
        return this;
    }

    public ItemBuilder lore(String... lines) {
        if (lines != null) {
            lore(Arrays.asList(lines));
        }
        return this;
    }

    @SuppressWarnings("deprecation")
    public ItemBuilder lore(List<String> lines) {
        if (meta != null && lines != null) {
            List<String> colored = new ArrayList<>();
            for (String l : lines) {
                colored.add(MessageUtil.color(l));
            }
            meta.setLore(colored);
        }
        return this;
    }

    @SuppressWarnings("deprecation")
    public ItemBuilder addLore(String... lines) {
        if (meta != null && lines != null) {
            List<String> current = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            for (String l : lines) {
                current.add(MessageUtil.color(l));
            }
            meta.setLore(current);
        }
        return this;
    }

    public ItemBuilder glow() {
        return glow(true);
    }

    public ItemBuilder glow(boolean glow) {
        if (meta != null) {
            if (glow) {
                try {
                    meta.addEnchant(Enchantment.LURE, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                } catch (Throwable ignored) {}
            }
        }
        return this;
    }

    public ItemBuilder unbreakable() {
        return unbreakable(true);
    }

    public ItemBuilder unbreakable(boolean unb) {
        if (meta != null) {
            try {
                meta.setUnbreakable(unb);
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            } catch (Throwable ignored) {}
        }
        return this;
    }

    public ItemBuilder customModelData(int data) {
        if (meta != null) {
            try {
                meta.setCustomModelData(data);
            } catch (Throwable ignored) {}
        }
        return this;
    }

    public ItemBuilder maxStackSize(int maxStackSize) {
        if (meta != null) {
            ComponentItemHelper.setMaxStackSize(meta, maxStackSize);
        }
        return this;
    }

    public ItemBuilder rarity(String rarityName) {
        if (meta != null) {
            ComponentItemHelper.setRarity(meta, rarityName);
        }
        return this;
    }

    public ItemBuilder fireResistant(boolean fireResistant) {
        if (meta != null) {
            ComponentItemHelper.setFireResistant(meta, fireResistant);
        }
        return this;
    }

    public ItemBuilder hideTooltip(boolean hideTooltip) {
        if (meta != null) {
            ComponentItemHelper.setHideTooltip(meta, hideTooltip);
        }
        return this;
    }

    public ItemBuilder glintOverride(Boolean glint) {
        if (meta != null) {
            ComponentItemHelper.setGlintOverride(meta, glint);
        }
        return this;
    }

    public ItemBuilder baseItemName(String baseName) {
        if (meta != null) {
            ComponentItemHelper.setItemName(meta, baseName);
        }
        return this;
    }

    public ItemBuilder food(int nutrition, float saturation, boolean canAlwaysEat) {
        if (meta != null) {
            ComponentItemHelper.setFood(meta, nutrition, saturation, canAlwaysEat);
        }
        return this;
    }

    public ItemBuilder enchant(Enchantment ench, int level) {
        if (meta != null && ench != null) {
            meta.addEnchant(ench, level, true);
        }
        return this;
    }

    public ItemBuilder flags(ItemFlag... flags) {
        if (meta != null && flags != null) {
            meta.addItemFlags(flags);
        }
        return this;
    }

    public ItemBuilder hideAllFlags() {
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
        }
        return this;
    }


    private NamespacedKey getKey(String key) {
        JavaStudioPlugin plugin = JavaStudioPlugin.getInstance();
        return plugin != null ? new NamespacedKey(plugin, key.toLowerCase()) : NamespacedKey.minecraft(key.toLowerCase());
    }

    public ItemBuilder setPdcString(String key, String value) {
        if (meta != null && key != null && value != null) {
            try {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(getKey(key), PersistentDataType.STRING, value);
            } catch (Throwable ignored) {}
        }
        return this;
    }

    public ItemBuilder setPdcInt(String key, int value) {
        if (meta != null && key != null) {
            try {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(getKey(key), PersistentDataType.INTEGER, value);
            } catch (Throwable ignored) {}
        }
        return this;
    }

    public ItemBuilder setPdcDouble(String key, double value) {
        if (meta != null && key != null) {
            try {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(getKey(key), PersistentDataType.DOUBLE, value);
            } catch (Throwable ignored) {}
        }
        return this;
    }

    public ItemBuilder ability(String abilityId) {
        if (meta != null && abilityId != null && !abilityId.trim().isEmpty()) {
            try {
                JavaStudioPlugin plugin = JavaStudioPlugin.getInstance();
                if (plugin != null) {
                    NamespacedKey abilityKey = new NamespacedKey(plugin, "item_abilities");
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    String current = pdc.getOrDefault(abilityKey, PersistentDataType.STRING, "");
                    Set<String> set = new LinkedHashSet<>(Arrays.asList(current.split(";")));
                    set.remove("");
                    set.add(abilityId.toLowerCase());
                    pdc.set(abilityKey, PersistentDataType.STRING, String.join(";", set));
                }
            } catch (Throwable ignored) {}
        }
        return this;
    }

    public ItemBuilder ability(com.javastudio.api.ability.ItemAbility ability) {
        if (ability != null) {
            ability(ability.getId());
        }
        return this;
    }

    public static String getPdcString(ItemStack item, String key) {
        if (item == null || !item.hasItemMeta() || key == null) return null;
        try {
            JavaStudioPlugin plugin = JavaStudioPlugin.getInstance();
            NamespacedKey nsKey = plugin != null ? new NamespacedKey(plugin, key.toLowerCase()) : NamespacedKey.minecraft(key.toLowerCase());
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            return pdc.get(nsKey, PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int getPdcInt(ItemStack item, String key, int def) {
        if (item == null || !item.hasItemMeta() || key == null) return def;
        try {
            JavaStudioPlugin plugin = JavaStudioPlugin.getInstance();
            NamespacedKey nsKey = plugin != null ? new NamespacedKey(plugin, key.toLowerCase()) : NamespacedKey.minecraft(key.toLowerCase());
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            Integer val = pdc.get(nsKey, PersistentDataType.INTEGER);
            return val != null ? val : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static boolean hasPdc(ItemStack item, String key) {
        if (item == null || !item.hasItemMeta() || key == null) return false;
        try {
            JavaStudioPlugin plugin = JavaStudioPlugin.getInstance();
            NamespacedKey nsKey = plugin != null ? new NamespacedKey(plugin, key.toLowerCase()) : NamespacedKey.minecraft(key.toLowerCase());
            return item.getItemMeta().getPersistentDataContainer().has(nsKey, PersistentDataType.STRING) ||
                   item.getItemMeta().getPersistentDataContainer().has(nsKey, PersistentDataType.INTEGER);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public ItemStack build() {
        if (meta != null) {
            item.setItemMeta(meta);
        }
        return item;
    }
}
