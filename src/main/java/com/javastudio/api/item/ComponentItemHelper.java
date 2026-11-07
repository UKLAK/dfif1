package com.javastudio.api.item;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.*;

@SuppressWarnings("deprecation")
public final class ComponentItemHelper {

    private static final boolean COMPONENTS_SUPPORTED;
    private static MethodHandle SET_MAX_STACK_SIZE;
    private static MethodHandle GET_MAX_STACK_SIZE;
    private static MethodHandle HAS_MAX_STACK_SIZE;

    private static MethodHandle SET_RARITY;
    private static MethodHandle GET_RARITY;
    private static MethodHandle HAS_RARITY;

    private static MethodHandle SET_FIRE_RESISTANT;
    private static MethodHandle IS_FIRE_RESISTANT;

    private static MethodHandle SET_HIDE_TOOLTIP;
    private static MethodHandle IS_HIDE_TOOLTIP;

    private static MethodHandle SET_GLINT_OVERRIDE;
    private static MethodHandle GET_GLINT_OVERRIDE;

    private static MethodHandle SET_ITEM_NAME;
    private static MethodHandle GET_ITEM_NAME;

    private static MethodHandle GET_AS_COMPONENT_STRING;

    static {
        boolean supported = false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            Method mSetMaxStack = findMethod(ItemMeta.class, "setMaxStackSize", Integer.class);
            Method mGetMaxStack = findMethod(ItemMeta.class, "getMaxStackSize");
            Method mHasMaxStack = findMethod(ItemMeta.class, "hasMaxStackSize");
            if (mSetMaxStack != null) {
                SET_MAX_STACK_SIZE = lookup.unreflect(mSetMaxStack);
                GET_MAX_STACK_SIZE = mGetMaxStack != null ? lookup.unreflect(mGetMaxStack) : null;
                HAS_MAX_STACK_SIZE = mHasMaxStack != null ? lookup.unreflect(mHasMaxStack) : null;
                supported = true;
            }

            Method mSetRarity = findMethod(ItemMeta.class, "setRarity", ItemRarity.class);
            Method mGetRarity = findMethod(ItemMeta.class, "getRarity");
            Method mHasRarity = findMethod(ItemMeta.class, "hasRarity");
            if (mSetRarity != null) {
                SET_RARITY = lookup.unreflect(mSetRarity);
                GET_RARITY = mGetRarity != null ? lookup.unreflect(mGetRarity) : null;
                HAS_RARITY = mHasRarity != null ? lookup.unreflect(mHasRarity) : null;
            }

            Method mSetFire = findMethod(ItemMeta.class, "setFireResistant", boolean.class);
            Method mIsFire = findMethod(ItemMeta.class, "isFireResistant");
            if (mSetFire != null) {
                SET_FIRE_RESISTANT = lookup.unreflect(mSetFire);
                IS_FIRE_RESISTANT = mIsFire != null ? lookup.unreflect(mIsFire) : null;
            }

            Method mSetHide = findMethod(ItemMeta.class, "setHideTooltip", boolean.class);
            Method mIsHide = findMethod(ItemMeta.class, "isHideTooltip");
            if (mSetHide != null) {
                SET_HIDE_TOOLTIP = lookup.unreflect(mSetHide);
                IS_HIDE_TOOLTIP = mIsHide != null ? lookup.unreflect(mIsHide) : null;
            }

            Method mSetGlint = findMethod(ItemMeta.class, "setEnchantmentGlintOverride", Boolean.class);
            Method mGetGlint = findMethod(ItemMeta.class, "getEnchantmentGlintOverride");
            if (mSetGlint != null) {
                SET_GLINT_OVERRIDE = lookup.unreflect(mSetGlint);
                GET_GLINT_OVERRIDE = mGetGlint != null ? lookup.unreflect(mGetGlint) : null;
            }

            Method mSetItemName = findMethod(ItemMeta.class, "setItemName", String.class);
            Method mGetItemName = findMethod(ItemMeta.class, "getItemName");
            if (mSetItemName != null) {
                SET_ITEM_NAME = lookup.unreflect(mSetItemName);
                GET_ITEM_NAME = mGetItemName != null ? lookup.unreflect(mGetItemName) : null;
            }

            Method mCompStr = findMethod(ItemMeta.class, "getAsComponentString");
            if (mCompStr != null) {
                GET_AS_COMPONENT_STRING = lookup.unreflect(mCompStr);
            }
        } catch (Throwable ignored) {}
        COMPONENTS_SUPPORTED = supported;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private ComponentItemHelper() {}

    public static boolean isComponentApiSupported() {
        return COMPONENTS_SUPPORTED;
    }


    public static boolean setMaxStackSize(ItemStack stack, int maxStackSize) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        boolean ok = setMaxStackSize(meta, maxStackSize);
        if (ok) stack.setItemMeta(meta);
        return ok;
    }

    public static boolean setMaxStackSize(ItemMeta meta, int maxStackSize) {
        if (meta == null || SET_MAX_STACK_SIZE == null) return false;
        try {
            int clamped = Math.max(1, Math.min(99, maxStackSize));
            SET_MAX_STACK_SIZE.invoke(meta, Integer.valueOf(clamped));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int getMaxStackSize(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return 0;
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && GET_MAX_STACK_SIZE != null && HAS_MAX_STACK_SIZE != null) {
            try {
                boolean has = (boolean) HAS_MAX_STACK_SIZE.invoke(meta);
                if (has) {
                    return (int) GET_MAX_STACK_SIZE.invoke(meta);
                }
            } catch (Throwable ignored) {}
        }
        return stack.getMaxStackSize();
    }


    public static boolean setRarity(ItemStack stack, String rarityName) {
        if (stack == null || stack.getType() == Material.AIR || rarityName == null) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        boolean ok = setRarity(meta, rarityName);
        if (ok) stack.setItemMeta(meta);
        return ok;
    }

    public static boolean setRarity(ItemMeta meta, String rarityName) {
        if (meta == null || SET_RARITY == null || rarityName == null) return false;
        try {
            ItemRarity rarity = ItemRarity.valueOf(rarityName.trim().toUpperCase(Locale.ROOT));
            SET_RARITY.invoke(meta, rarity);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String getRarity(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return "COMMON";
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && GET_RARITY != null) {
            try {
                Object r = GET_RARITY.invoke(meta);
                if (r instanceof ItemRarity ir) return ir.name();
            } catch (Throwable ignored) {}
        }
        return "COMMON";
    }


    public static boolean setFireResistant(ItemStack stack, boolean fireResistant) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        boolean ok = setFireResistant(meta, fireResistant);
        if (ok) stack.setItemMeta(meta);
        return ok;
    }

    public static boolean setFireResistant(ItemMeta meta, boolean fireResistant) {
        if (meta == null || SET_FIRE_RESISTANT == null) return false;
        try {
            SET_FIRE_RESISTANT.invoke(meta, fireResistant);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isFireResistant(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && IS_FIRE_RESISTANT != null) {
            try {
                return (boolean) IS_FIRE_RESISTANT.invoke(meta);
            } catch (Throwable ignored) {}
        }
        return false;
    }


    public static boolean setHideTooltip(ItemStack stack, boolean hideTooltip) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        boolean ok = setHideTooltip(meta, hideTooltip);
        if (ok) stack.setItemMeta(meta);
        return ok;
    }

    public static boolean setHideTooltip(ItemMeta meta, boolean hideTooltip) {
        if (meta == null || SET_HIDE_TOOLTIP == null) return false;
        try {
            SET_HIDE_TOOLTIP.invoke(meta, hideTooltip);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isHideTooltip(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && IS_HIDE_TOOLTIP != null) {
            try {
                return (boolean) IS_HIDE_TOOLTIP.invoke(meta);
            } catch (Throwable ignored) {}
        }
        return false;
    }


    public static boolean setGlintOverride(ItemStack stack, Boolean glint) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        boolean ok = setGlintOverride(meta, glint);
        if (ok) stack.setItemMeta(meta);
        return ok;
    }

    public static boolean setGlintOverride(ItemMeta meta, Boolean glint) {
        if (meta == null || SET_GLINT_OVERRIDE == null) return false;
        try {
            SET_GLINT_OVERRIDE.invoke(meta, glint);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Boolean getGlintOverride(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && GET_GLINT_OVERRIDE != null) {
            try {
                return (Boolean) GET_GLINT_OVERRIDE.invoke(meta);
            } catch (Throwable ignored) {}
        }
        return null;
    }


    public static boolean setItemName(ItemStack stack, String itemName) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        boolean ok = setItemName(meta, itemName);
        if (ok) stack.setItemMeta(meta);
        return ok;
    }

    public static boolean setItemName(ItemMeta meta, String itemName) {
        if (meta == null || itemName == null) return false;
        if (SET_ITEM_NAME != null) {
            try {
                SET_ITEM_NAME.invoke(meta, MessageUtil.color(itemName));
                return true;
            } catch (Throwable ignored) {}
        }
        meta.setDisplayName(MessageUtil.color(itemName));
        return true;
    }

    public static String getItemName(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return "";
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (GET_ITEM_NAME != null) {
                try {
                    String name = (String) GET_ITEM_NAME.invoke(meta);
                    if (name != null) return name;
                } catch (Throwable ignored) {}
            }
            if (meta.hasDisplayName()) return meta.getDisplayName();
        }
        return stack.getType().name();
    }


    public static boolean setFood(ItemStack stack, int nutrition, float saturation, boolean canAlwaysEat) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        boolean ok = setFood(meta, nutrition, saturation, canAlwaysEat);
        if (ok) stack.setItemMeta(meta);
        return ok;
    }

    public static boolean setFood(ItemMeta meta, int nutrition, float saturation, boolean canAlwaysEat) {
        if (meta == null) return false;
        try {
            if (meta.hasFood()) {
                FoodComponent food = meta.getFood();
                if (food != null) {
                    food.setNutrition(nutrition);
                    food.setSaturation(saturation);
                    food.setCanAlwaysEat(canAlwaysEat);
                    meta.setFood(food);
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }


    public static boolean setCustomModelData(ItemStack stack, int customModelData) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        meta.setCustomModelData(customModelData);
        stack.setItemMeta(meta);
        return true;
    }

    public static int getCustomModelData(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) return 0;
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : 0;
    }


    public static String getComponentString(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) return "[]";
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && GET_AS_COMPONENT_STRING != null) {
            try {
                Object res = GET_AS_COMPONENT_STRING.invoke(meta);
                if (res != null) return res.toString();
            } catch (Throwable ignored) {}
        }
        return "[]";
    }


    private static NamespacedKey resolveKey(String key) {
        JavaStudioPlugin plugin = JavaStudioPlugin.getInstance();
        return plugin != null ? new NamespacedKey(plugin, key.toLowerCase(Locale.ROOT)) : NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT));
    }

    public static boolean setPdcValue(ItemStack stack, String key, Object value) {
        if (stack == null || !stack.hasItemMeta() || key == null || value == null) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        NamespacedKey nsKey = resolveKey(key);

        if (value instanceof String s) {
            pdc.set(nsKey, PersistentDataType.STRING, s);
        } else if (value instanceof Integer i) {
            pdc.set(nsKey, PersistentDataType.INTEGER, i);
        } else if (value instanceof Double d) {
            pdc.set(nsKey, PersistentDataType.DOUBLE, d);
        } else if (value instanceof Long l) {
            pdc.set(nsKey, PersistentDataType.LONG, l);
        } else if (value instanceof Boolean b) {
            pdc.set(nsKey, PersistentDataType.BYTE, (byte) (b ? 1 : 0));
        } else if (value instanceof byte[] bytes) {
            pdc.set(nsKey, PersistentDataType.BYTE_ARRAY, bytes);
        } else {
            pdc.set(nsKey, PersistentDataType.STRING, String.valueOf(value));
        }
        stack.setItemMeta(meta);
        return true;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getPdcValue(ItemStack stack, String key, Class<T> type) {
        if (stack == null || !stack.hasItemMeta() || key == null || type == null) return null;
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        NamespacedKey nsKey = resolveKey(key);

        try {
            if (type == String.class) {
                return (T) pdc.get(nsKey, PersistentDataType.STRING);
            } else if (type == Integer.class || type == int.class) {
                return (T) pdc.get(nsKey, PersistentDataType.INTEGER);
            } else if (type == Double.class || type == double.class) {
                return (T) pdc.get(nsKey, PersistentDataType.DOUBLE);
            } else if (type == Long.class || type == long.class) {
                return (T) pdc.get(nsKey, PersistentDataType.LONG);
            } else if (type == Boolean.class || type == boolean.class) {
                Byte b = pdc.get(nsKey, PersistentDataType.BYTE);
                return b != null ? (T) Boolean.valueOf(b == 1) : null;
            } else if (type == byte[].class) {
                return (T) pdc.get(nsKey, PersistentDataType.BYTE_ARRAY);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Map<String, Object> exportComponentSummary(ItemStack stack) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (stack == null || stack.getType() == Material.AIR) {
            map.put("material", "AIR");
            map.put("amount", 0);
            return map;
        }

        map.put("material", stack.getType().name());
        map.put("amount", stack.getAmount());
        map.put("maxStackSize", getMaxStackSize(stack));

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) map.put("displayName", meta.getDisplayName());
            if (meta.hasLore()) map.put("lore", meta.getLore());
            if (meta.hasCustomModelData()) map.put("customModelData", meta.getCustomModelData());
            map.put("rarity", getRarity(stack));
            map.put("fireResistant", isFireResistant(stack));
            map.put("hideTooltip", isHideTooltip(stack));
            Boolean glint = getGlintOverride(stack);
            if (glint != null) map.put("glintOverride", glint);
            if (!meta.getEnchants().isEmpty()) {
                Map<String, Integer> enchs = new HashMap<>();
                meta.getEnchants().forEach((e, lvl) -> enchs.put(e.getKey().getKey(), lvl));
                map.put("enchantments", enchs);
            }
            map.put("componentString", getComponentString(stack));
        }
        return map;
    }
}

