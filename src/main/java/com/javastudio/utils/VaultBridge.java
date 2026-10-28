package com.javastudio.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Cầu nối tích hợp tự động với Vault Economy & Permissions (Vault Bridge).
 * Cung cấp các hàm nạp/rút tiền, kiểm tra số dư và phân quyền an toàn,
 * tự động kiểm tra và không gây lỗi nếu server không cài đặt Vault.
 */
public class VaultBridge {

    private static Object economyProvider = null;
    private static Method depositMethod = null;
    private static Method withdrawMethod = null;
    private static Method getBalanceMethod = null;
    private static Method hasMethod = null;

    private static Object permissionProvider = null;
    private static Method playerHasMethod = null;
    private static Method getPrimaryGroupMethod = null;

    private static boolean initialized = false;

    private static synchronized void checkInit() {
        if (initialized) return;
        initialized = true;

        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }

        try {
            Class<?> ecoClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(ecoClass);
            if (rsp != null) {
                economyProvider = rsp.getProvider();
                if (economyProvider != null) {
                    depositMethod = ecoClass.getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class);
                    withdrawMethod = ecoClass.getMethod("withdrawPlayer", org.bukkit.OfflinePlayer.class, double.class);
                    getBalanceMethod = ecoClass.getMethod("getBalance", org.bukkit.OfflinePlayer.class);
                    hasMethod = ecoClass.getMethod("has", org.bukkit.OfflinePlayer.class, double.class);
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> permClass = Class.forName("net.milkbowl.vault.permission.Permission");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(permClass);
            if (rsp != null) {
                permissionProvider = rsp.getProvider();
                if (permissionProvider != null) {
                    playerHasMethod = permClass.getMethod("playerHas", Player.class, String.class);
                    getPrimaryGroupMethod = permClass.getMethod("getPrimaryGroup", Player.class);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean hasEconomy() {
        checkInit();
        return economyProvider != null;
    }

    public static double getBalance(Player player) {
        checkInit();
        if (economyProvider == null || player == null || getBalanceMethod == null) return 0.0;
        try {
            Object res = getBalanceMethod.invoke(economyProvider, player);
            return res instanceof Number n ? n.doubleValue() : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    public static boolean deposit(Player player, double amount) {
        checkInit();
        if (economyProvider == null || player == null || depositMethod == null) return false;
        try {
            Object response = depositMethod.invoke(economyProvider, player, amount);
            if (response != null) {
                Method transSuccess = response.getClass().getMethod("transactionSuccess");
                return Boolean.TRUE.equals(transSuccess.invoke(response));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean withdraw(Player player, double amount) {
        checkInit();
        if (economyProvider == null || player == null || withdrawMethod == null) return false;
        try {
            Object response = withdrawMethod.invoke(economyProvider, player, amount);
            if (response != null) {
                Method transSuccess = response.getClass().getMethod("transactionSuccess");
                return Boolean.TRUE.equals(transSuccess.invoke(response));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean has(Player player, double amount) {
        checkInit();
        if (economyProvider == null || player == null || hasMethod == null) return false;
        try {
            Object res = hasMethod.invoke(economyProvider, player, amount);
            return Boolean.TRUE.equals(res);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission(Player player, String permission) {
        if (player == null || permission == null) return false;
        checkInit();
        if (permissionProvider != null && playerHasMethod != null) {
            try {
                Object res = playerHasMethod.invoke(permissionProvider, player, permission);
                return Boolean.TRUE.equals(res);
            } catch (Throwable ignored) {}
        }
        return player.hasPermission(permission);
    }

    public static String getPrimaryGroup(Player player) {
        checkInit();
        if (permissionProvider == null || player == null || getPrimaryGroupMethod == null) return "default";
        try {
            Object res = getPrimaryGroupMethod.invoke(permissionProvider, player);
            return res != null ? res.toString() : "default";
        } catch (Throwable t) {
            return "default";
        }
    }
}
