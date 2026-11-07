package com.javastudio.utils;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public class ReflectionUtil {

    private static CommandMap commandMap;
    private static Map<String, Command> knownCommands;

    public static synchronized CommandMap getCommandMap() {
        if (commandMap != null) {
            return commandMap;
        }

        try {
            Method getCommandMapMethod = Bukkit.class.getMethod("getCommandMap");
            commandMap = (CommandMap) getCommandMapMethod.invoke(null);
            if (commandMap != null) {
                return commandMap;
            }
        } catch (Throwable ignored) {}

        try {
            Method getCommandMapMethod = Bukkit.getServer().getClass().getMethod("getCommandMap");
            commandMap = (CommandMap) getCommandMapMethod.invoke(Bukkit.getServer());
            if (commandMap != null) {
                return commandMap;
            }
        } catch (Throwable ignored) {}

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

    @SuppressWarnings("unchecked")
    public static synchronized Map<String, Command> getKnownCommands() {
        if (knownCommands != null) {
            return knownCommands;
        }

        CommandMap map = getCommandMap();
        if (map == null) return null;

        if (map instanceof SimpleCommandMap scm) {
            try {
                knownCommands = scm.getKnownCommands();
                return knownCommands;
            } catch (Throwable ignored) {}
        }

        try {
            Field field = SimpleCommandMap.class.getDeclaredField("knownCommands");
            field.setAccessible(true);
            knownCommands = (Map<String, Command>) field.get(map);
            return knownCommands;
        } catch (Throwable ignored) {}

        return null;
    }

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

    public static void syncCommands() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("syncCommands");
            method.invoke(Bukkit.getServer());
        } catch (Throwable ignored) {}
    }
}
