package com.javastudio.api;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.javastudio.JavaStudioPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Context đối tượng được truyền vào khi thực thi mã Java trực tiếp hoặc script.
 * Cung cấp các biến môi trường tiện ích: player, sender, server, world, location,...
 */
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

    /**
     * Alias ngắn gọn cho getPlayer()
     */
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

    /**
     * In tin nhắn về cho người thực thi mã
     */
    public void print(Object message) {
        String msg = String.valueOf(message);
        outputs.add(msg);
        sender.sendMessage(msg);
    }

    /**
     * Phát sóng tin nhắn cho toàn server
     */
    @SuppressWarnings("deprecation")
    public void broadcast(Object message) {
        server.broadcastMessage(String.valueOf(message));
    }

    /**
     * Chạy 1 task đồng bộ trên main thread của Bukkit
     */
    public void runTask(Runnable runnable) {
        server.getScheduler().runTask(plugin, runnable);
    }

    /**
     * Chạy 1 task bất đồng bộ
     */
    public void runTaskAsync(Runnable runnable) {
        server.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    /**
     * Chạy 1 task sau N ticks
     */
    public void runTaskLater(Runnable runnable, long delayTicks) {
        server.getScheduler().runTaskLater(plugin, runnable, delayTicks);
    }

    /**
     * Chạy 1 task lặp lại định kỳ
     */
    public void runTaskTimer(Runnable runnable, long delayTicks, long periodTicks) {
        server.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
    }

    public List<String> getOutputs() {
        return outputs;
    }

    /**
     * Lấy bộ điều khiển Plugin toàn quyền (tương tự PlugManX)
     */
    public com.javastudio.control.PluginController getPluginControl() {
        return plugin.getPluginController();
    }

    /**
     * Lấy bộ điều khiển Toàn bộ Máy chủ (Server & Software Controller)
     */
    public com.javastudio.control.ServerController getServerControl() {
        return plugin.getServerController();
    }
}
