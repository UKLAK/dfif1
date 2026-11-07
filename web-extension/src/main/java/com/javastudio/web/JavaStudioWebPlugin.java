package com.javastudio.web;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;

public final class JavaStudioWebPlugin extends JavaPlugin implements Listener {

    private static final String REQUIRED_CORE_NAME = "JavaStudio";
    private static final String REQUIRED_CORE_MAIN = "com.javastudio.JavaStudioPlugin";
    private static final String BOOTSTRAP_CLASS = "com.javastudio.web.WebExtensionBootstrap";

    private volatile WebExtensionController controller;

    @Override
    public void onEnable() {
        Plugin core = getServer().getPluginManager().getPlugin(REQUIRED_CORE_NAME);
        if (core == null || !core.isEnabled() || !isCompatibleCore(core)) {
            getLogger().warning("Khong tim thay plugin mac dinh 'JavaStudio' dang hoat dong tren may chu!");
            getLogger().warning("JavaStudio-Web la plugin mo rong va se tu dong ngung kich hoat an toan (0 loi).");
            disableSelfSafely();
            return;
        }

        try {
            saveDefaultConfig();
            Class<?> bootClass = Class.forName(BOOTSTRAP_CLASS, true, getClassLoader());
            WebExtensionController instance = (WebExtensionController) bootClass
                    .getDeclaredConstructor(JavaPlugin.class, Plugin.class)
                    .newInstance(this, core);
            instance.enable();
            this.controller = instance;
            getServer().getPluginManager().registerEvents(this, this);
            getLogger().info("Da ket noi thanh cong voi plugin mac dinh JavaStudio v" + core.getDescription().getVersion() + "!");
        } catch (Throwable t) {
            this.controller = null;
            getLogger().warning("Khong the khoi tao phan mo rong JavaStudio-Web (" + t.getClass().getSimpleName() + "): " + t.getMessage());
            disableSelfSafely();
        }
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((Listener) this);
        shutdownController();
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != null && REQUIRED_CORE_NAME.equals(event.getPlugin().getName())) {
            getLogger().info("Plugin mac dinh JavaStudio vua tat -> Dong an toan JavaStudio-Web.");
            shutdownController();
            if (isEnabled()) {
                disableSelfSafely();
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        WebExtensionController active = this.controller;
        if (active == null) {
            sender.sendMessage("§c[JavaStudio-Web] Tiện ích mở rộng hiện không hoạt động do thiếu plugin mặc định JavaStudio!");
            return true;
        }
        return active.handleCommand(sender, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        WebExtensionController active = this.controller;
        if (active == null) {
            return Collections.emptyList();
        }
        return active.handleTabComplete(sender, args);
    }

    private boolean isCompatibleCore(Plugin core) {
        try {
            Class<?> coreClass = Class.forName(REQUIRED_CORE_MAIN, false, core.getClass().getClassLoader());
            return coreClass.isInstance(core);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void shutdownController() {
        WebExtensionController active = this.controller;
        this.controller = null;
        if (active != null) {
            try {
                active.disable();
            } catch (Throwable ignored) {}
        }
    }

    private void disableSelfSafely() {
        try {
            getServer().getPluginManager().disablePlugin(this);
        } catch (Throwable ignored) {}
    }
}

