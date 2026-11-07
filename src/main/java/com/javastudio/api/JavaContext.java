package com.javastudio.api;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.javastudio.JavaStudioPlugin;

import java.util.ArrayList;
import java.util.List;

public class JavaContext {

    private final CommandSender sender;
    private final Player player;
    private final Server server;
    private final JavaStudioPlugin plugin;
    private final String[] args;
    private final List<String> outputs = new ArrayList<>();

    public JavaContext(CommandSender sender, JavaStudioPlugin plugin, String[] args) {
        this.sender = sender;
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.args = args != null ? args : new String[0];
        if (sender instanceof Player p) {
            this.player = p;
        } else {
            this.player = null;
        }
    }

    public CommandSender getSender() {
        return sender;
    }

    public Player getPlayer() {
        return player;
    }

    public Player p() {
        return player;
    }

    public Server getServer() {
        return server;
    }

    public JavaStudioPlugin getPlugin() {
        return plugin;
    }

    public String[] getArgs() {
        return args;
    }

    public World getWorld() {
        if (player != null) {
            return player.getWorld();
        }
        return server.getWorlds().isEmpty() ? null : server.getWorlds().get(0);
    }

    public Location getLocation() {
        return player != null ? player.getLocation() : null;
    }

    public void print(Object message) {
        String msg = String.valueOf(message);
        outputs.add(msg);
        sender.sendMessage(msg);
    }

    @SuppressWarnings("deprecation")
    public void broadcast(Object message) {
        server.broadcastMessage(String.valueOf(message));
    }

    public void runTask(Runnable runnable) {
        server.getScheduler().runTask(plugin, runnable);
    }

    public void runTaskAsync(Runnable runnable) {
        server.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    public void runTaskLater(Runnable runnable, long delayTicks) {
        server.getScheduler().runTaskLater(plugin, runnable, delayTicks);
    }

    public void runTaskTimer(Runnable runnable, long delayTicks, long periodTicks) {
        server.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
    }

    public List<String> getOutputs() {
        return outputs;
    }

    public com.javastudio.control.PluginController getPluginControl() {
        return plugin.getPluginController();
    }

    public com.javastudio.control.ServerController getServerControl() {
        return plugin.getServerController();
    }
}
