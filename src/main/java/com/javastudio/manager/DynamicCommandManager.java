package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.DynamicCommand;
import com.javastudio.api.DynamicPlugin;
import com.javastudio.utils.ReflectionUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;

import java.util.*;

public class DynamicCommandManager {

    private final JavaStudioPlugin plugin;
    private final Map<String, DynamicCommandWrapper> registeredWrappers = new HashMap<>();
    private final BrigadierCommandEngine brigadierEngine;

    public DynamicCommandManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.brigadierEngine = new BrigadierCommandEngine(plugin);
    }

    public BrigadierCommandEngine getBrigadierEngine() {
        return brigadierEngine;
    }

    public synchronized boolean registerDynamicCommand(DynamicPlugin owner, String name, String description, String usage, List<String> aliases, DynamicCommand command) {
        CommandMap map = ReflectionUtil.getCommandMap();
        if (map == null) {
            plugin.getLogger().severe("Không thể tìm thấy CommandMap của Server để đăng ký lệnh /" + name);
            return false;
        }

        String fallbackPrefix = owner != null ? owner.getName().toLowerCase() : "javastudio";
        DynamicCommandWrapper wrapper = new DynamicCommandWrapper(name, description, usage, aliases, command, fallbackPrefix);

        unregisterDynamicCommand(name);

        boolean registered = map.register(fallbackPrefix, wrapper);
        if (registered) {
            registeredWrappers.put(name.toLowerCase(), wrapper);
            brigadierEngine.injectBrigadierCommand(name, aliases, description);
            brigadierEngine.syncAllPlayers();
        }
        return registered;
    }

    public synchronized boolean unregisterDynamicCommand(String name) {
        String lower = name.toLowerCase();
        DynamicCommandWrapper wrapper = registeredWrappers.remove(lower);
        if (wrapper != null) {
            boolean success = ReflectionUtil.unregisterCommand(lower, wrapper);
            brigadierEngine.syncAllPlayers();
            return success;
        }
        return false;
    }

    public synchronized void unregisterAll() {
        for (String name : new ArrayList<>(registeredWrappers.keySet())) {
            unregisterDynamicCommand(name);
        }
        registeredWrappers.clear();
        brigadierEngine.syncAllPlayers();
    }

    public static class DynamicCommandWrapper extends Command {

        private final DynamicCommand handler;

        public DynamicCommandWrapper(String name, String description, String usageMessage, List<String> aliases, DynamicCommand handler, String fallbackPrefix) {
            super(name, description, usageMessage, aliases != null ? aliases : Collections.emptyList());
            this.handler = handler;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            try {
                return handler.execute(sender, args);
            } catch (Throwable t) {
                sender.sendMessage("§cĐã xảy ra lỗi khi thực thi lệnh /" + commandLabel + ": §f" + t.getMessage());
                t.printStackTrace();
                return false;
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            try {
                List<String> list = handler.tabComplete(sender, args);
                return list != null ? list : Collections.emptyList();
            } catch (Throwable t) {
                return Collections.emptyList();
            }
        }
    }
}
