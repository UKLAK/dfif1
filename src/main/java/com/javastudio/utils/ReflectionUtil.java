package com.javastudio.utils;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Tiện ích Reflection an toàn để truy cập và can thiệp vào CommandMap
 * tương thích 100% trên Paper, Purpur và Spigot từ 1.20.5 đến 26.3+.
 */
public class ReflectionUtil {

    private static CommandMap commandMap;
    private static Map<String, Command> knownCommands;

    /**
     * Lấy Bukkit CommandMap hiện tại của Server
     */
    public static synchronized CommandMap getCommandMap() {
        if (commandMap != null) {
            return commandMap;
        }

        // 1. Thử gọi Bukkit.getCommandMap() (Hỗ trợ Paper và Spigot hiện đại)
        try {
            Method getCommandMapMethod = Bukkit.class.getMethod("getCommandMap");
            commandMap = (CommandMap) getCommandMapMethod.invoke(null);
            if (commandMap != null) {
                return commandMap;
            }
        } catch (Throwable ignored) {}

        // 2. Thử gọi CraftServer.getCommandMap()
        try {
            Method getCommandMapMethod = Bukkit.getServer().getClass().getMethod("getCommandMap");
            commandMap = (CommandMap) getCommandMapMethod.invoke(Bukkit.getServer());
            if (commandMap != null) {
                return commandMap;
            }
        } catch (Throwable ignored) {}

        // 3. Thử lấy từ SimplePluginManager.commandMap
        try {
            PluginManager pm = Bukkit.getPluginManager();
            Field commandMapField = pm.getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            commandMap = (CommandMap) commandMapField.get(pm);
            if (commandMap != null) {
                return commandMap;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Lấy bảng Map các lệnh đã đăng ký trên server (knownCommands)
     */
    @SuppressWarnings("unchecked")
    public static synchronized Map<String, Command> getKnownCommands() {
        if (knownCommands != null) {
            return knownCommands;
        }

        CommandMap map = getCommandMap();
        if (map == null) return null;

        // Thử getKnownCommands() của SimpleCommandMap
        if (map instanceof SimpleCommandMap scm) {
            try {
                knownCommands = scm.getKnownCommands();
                return knownCommands;
            } catch (Throwable ignored) {}
        }

        // Reflection vào field knownCommands
        try {
            Field field = SimpleCommandMap.class.getDeclaredField("knownCommands");
            field.setAccessible(true);
            knownCommands = (Map<String, Command>) field.get(map);
            return knownCommands;
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Hủy đăng ký an toàn một lệnh khỏi server, đảm bảo KHÔNG làm ảnh hưởng
     * đến lệnh của các plugin khác hoặc lệnh mặc định của Minecraft.
     */
    public static boolean unregisterCommand(String commandName) {
        return unregisterCommand(commandName, null);
    }

    public static boolean unregisterCommand(String commandName, Command targetCommand) {
        CommandMap map = getCommandMap();
        Map<String, Command> commands = getKnownCommands();
        if (map == null || commands == null) return false;

        String name = commandName.toLowerCase();
        Command target = commands.get(name);
        if (target != null && (targetCommand == null || target.equals(targetCommand))) {
            target.unregister(map);
            commands.remove(name);
        }

        // Xóa dạng namespace nhưng CHỈ khi đối tượng lệnh trùng khớp với targetCommand
        commands.entrySet().removeIf(entry -> {
            if (entry.getKey().endsWith(":" + name)) {
                if (targetCommand == null || entry.getValue().equals(targetCommand)) {
                    entry.getValue().unregister(map);
                    return true;
                }
            }
            return false;
        });

        return true;
    }

    /**
     * Đồng bộ lại cây lệnh (Brigadier command tree) tới toàn bộ người chơi đang online.
     * Cần thiết trên Paper, Purpur và Spigot hiện đại (1.20.5 - 26.3+) để cập nhật Tab Completion ngay lập tức.
     */
    public static void syncCommands() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("syncCommands");
            method.invoke(Bukkit.getServer());
        } catch (Throwable ignored) {}
    }
}
