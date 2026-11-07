package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.*;

public class BrigadierCommandEngine {

    private final JavaStudioPlugin plugin;
    private Object nmsDispatcher = null;

    public BrigadierCommandEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        initDispatcher();
    }

    public JavaStudioPlugin getPlugin() {
        return plugin;
    }

    private void initDispatcher() {
        try {
            Object server = Bukkit.getServer();
            Method getServerMethod = server.getClass().getMethod("getServer");
            Object console = getServerMethod.invoke(server);

            for (Method m : console.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().toLowerCase().contains("command")) {
                    Object commandsObj = m.invoke(console);
                    if (commandsObj != null) {
                        for (Method dm : commandsObj.getClass().getMethods()) {
                            if (CommandDispatcher.class.isAssignableFrom(dm.getReturnType()) && dm.getParameterCount() == 0) {
                                this.nmsDispatcher = dm.invoke(commandsObj);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean injectBrigadierCommand(String commandName, List<String> aliases, String description) {
        if (nmsDispatcher == null) {
            initDispatcher();
        }
        if (nmsDispatcher instanceof CommandDispatcher dispatcher) {
            try {
                LiteralArgumentBuilder builder = LiteralArgumentBuilder.literal(commandName.toLowerCase());
                LiteralCommandNode node = dispatcher.register(builder);

                if (aliases != null) {
                    for (String alias : aliases) {
                        dispatcher.register(LiteralArgumentBuilder.literal(alias.toLowerCase()).redirect(node));
                    }
                }
                return true;
            } catch (Throwable t) {
                plugin.getLogger().warning("[Brigadier] Không thể tiêm node /" + commandName + ": " + t.getMessage());
            }
        }
        return false;
    }

    public void syncAllPlayers() {
        try {
            Method m = Bukkit.getServer().getClass().getMethod("syncCommands");
            m.invoke(Bukkit.getServer());
        } catch (Throwable ignored) {}

        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.updateCommands();
            } catch (Throwable ignored) {}
        }
    }

    public boolean isBrigadierAvailable() {
        return nmsDispatcher != null;
    }
}
