package com.javastudio.api;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import com.javastudio.JavaStudioPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import com.javastudio.api.annotations.JCommand;
import com.javastudio.api.annotations.JEvent;
import com.javastudio.api.annotations.JTask;
import com.javastudio.api.annotations.JRegionTask;
import com.javastudio.api.annotations.JPlaceholder;

public abstract class DynamicPlugin {

    private String name;
    private String version;
    private String author;
    private String description;

    private JavaStudioPlugin hostPlugin;
    private File dataFolder;
    private File configFile;
    private FileConfiguration config;
    private Logger logger;
    private boolean enabled = false;

    private final List<Listener> registeredListeners = new ArrayList<>();
    private final List<String> registeredCommands = new ArrayList<>();
    private final List<String> registeredPlaceholders = new ArrayList<>();
    private final List<BukkitTask> activeTasks = new ArrayList<>();
    private final List<com.javastudio.utils.PlatformUtil.TaskHandle> activeTaskHandles = new ArrayList<>();

    private JavaPlugin standalonePlugin;


    public final void init(JavaStudioPlugin host, String name, String version, String author, String description, File dataFolder) {
        this.hostPlugin = host;
        this.name = name;
        this.version = version;
        this.author = author;
        this.description = description;
        this.dataFolder = dataFolder;
        this.configFile = new File(dataFolder, "config.yml");
        this.logger = Logger.getLogger("JavaStudio[" + name + "]");
    }

    public final void initStandalone(JavaPlugin standalone, String name, String version, String author, String description, File dataFolder) {
        this.standalonePlugin = standalone;
        this.name = name;
        this.version = version;
        this.author = author;
        this.description = description;
        this.dataFolder = dataFolder;
        this.configFile = new File(dataFolder, "config.yml");
        this.logger = standalone.getLogger();
    }

    private JavaPlugin getOwningPlugin() {
        return hostPlugin != null ? hostPlugin : standalonePlugin;
    }

    public abstract void onEnable();

    public abstract void onDisable();

    public final void registerListener(Listener listener) {
        if (listener == null) return;
        getServer().getPluginManager().registerEvents(listener, getOwningPlugin());
        registeredListeners.add(listener);
        logger.info("Đã đăng ký Event Listener: " + listener.getClass().getSimpleName());
    }

    public final void registerCommand(String commandName, DynamicCommand command) {
        registerCommand(commandName, "Lệnh tạo bởi dynamic plugin " + name, "/" + commandName, Collections.emptyList(), command);
    }

    public final void registerCommand(String commandName, String desc, String usage, List<String> aliases, DynamicCommand command) {
        if (hostPlugin != null) {
            hostPlugin.getCommandManager().registerDynamicCommand(this, commandName, desc, usage, aliases, command);
        } else {
            org.bukkit.command.CommandMap map = com.javastudio.utils.ReflectionUtil.getCommandMap();
            if (map != null) {
                map.register(getName().toLowerCase(), new org.bukkit.command.Command(commandName, desc, usage, aliases != null ? aliases : Collections.emptyList()) {
                    @Override
                    public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                        return command.execute(sender, args);
                    }
                });
            }
        }
        registeredCommands.add(commandName.toLowerCase());
        logger.info("Đã đăng ký lệnh: /" + commandName);
    }

    public Logger getLogger() {
        return logger;
    }

    public Server getServer() {
        if (hostPlugin != null) return hostPlugin.getServer();
        if (standalonePlugin != null) return standalonePlugin.getServer();
        return org.bukkit.Bukkit.getServer();
    }

    public JavaStudioPlugin getHostPlugin() {
        return hostPlugin;
    }

    public File getDataFolder() {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        return dataFolder;
    }

    public FileConfiguration getConfig() {
        if (config == null) {
            reloadConfig();
        }
        return config;
    }

    public void reloadConfig() {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    public void saveConfig() {
        if (config != null) {
            try {
                config.save(configFile);
            } catch (IOException e) {
                logger.severe("Không thể lưu file config.yml: " + e.getMessage());
            }
        }
    }

    public void saveDefaultConfig() {
        if (!configFile.exists()) {
            try {
                if (!dataFolder.exists()) {
                    dataFolder.mkdirs();
                }
                configFile.createNewFile();
                config = YamlConfiguration.loadConfiguration(configFile);
                config.set("enabled", true);
                config.set("example-message", "&aXin chào từ dynamic plugin " + name + "!");
                saveConfig();
            } catch (IOException e) {
                logger.severe("Lỗi khi tạo config.yml mặc định: " + e.getMessage());
            }
        }
    }

    public BukkitTask runTask(Runnable runnable) {
        if (com.javastudio.utils.PlatformUtil.isFolia()) {
            activeTaskHandles.add(com.javastudio.utils.PlatformUtil.runTask(getOwningPlugin(), runnable));
            return null;
        }
        BukkitTask task = getServer().getScheduler().runTask(getOwningPlugin(), runnable);
        activeTasks.add(task);
        return task;
    }

    public BukkitTask runTaskAsync(Runnable runnable) {
        if (com.javastudio.utils.PlatformUtil.isFolia()) {
            activeTaskHandles.add(com.javastudio.utils.PlatformUtil.runTaskAsync(getOwningPlugin(), runnable));
            return null;
        }
        BukkitTask task = getServer().getScheduler().runTaskAsynchronously(getOwningPlugin(), runnable);
        activeTasks.add(task);
        return task;
    }

    public BukkitTask runTaskLater(Runnable runnable, long delayTicks) {
        if (com.javastudio.utils.PlatformUtil.isFolia()) {
            activeTaskHandles.add(com.javastudio.utils.PlatformUtil.runTaskLater(getOwningPlugin(), runnable, delayTicks));
            return null;
        }
        BukkitTask task = getServer().getScheduler().runTaskLater(getOwningPlugin(), runnable, delayTicks);
        activeTasks.add(task);
        return task;
    }

    public BukkitTask runTaskTimer(Runnable runnable, long delayTicks, long periodTicks) {
        if (com.javastudio.utils.PlatformUtil.isFolia()) {
            activeTaskHandles.add(com.javastudio.utils.PlatformUtil.runTaskTimer(getOwningPlugin(), runnable, delayTicks, periodTicks));
            return null;
        }
        BukkitTask task = getServer().getScheduler().runTaskTimer(getOwningPlugin(), runnable, delayTicks, periodTicks);
        activeTasks.add(task);
        return task;
    }

    public com.javastudio.utils.PlatformUtil.TaskHandle runUniversalTask(Runnable runnable) {
        com.javastudio.utils.PlatformUtil.TaskHandle handle = com.javastudio.utils.PlatformUtil.runTask(getOwningPlugin(), runnable);
        activeTaskHandles.add(handle);
        return handle;
    }

    public com.javastudio.utils.PlatformUtil.TaskHandle runUniversalTaskAsync(Runnable runnable) {
        com.javastudio.utils.PlatformUtil.TaskHandle handle = com.javastudio.utils.PlatformUtil.runTaskAsync(getOwningPlugin(), runnable);
        activeTaskHandles.add(handle);
        return handle;
    }

    public com.javastudio.utils.PlatformUtil.TaskHandle runUniversalTaskLater(Runnable runnable, long delayTicks) {
        com.javastudio.utils.PlatformUtil.TaskHandle handle = com.javastudio.utils.PlatformUtil.runTaskLater(getOwningPlugin(), runnable, delayTicks);
        activeTaskHandles.add(handle);
        return handle;
    }

    public com.javastudio.utils.PlatformUtil.TaskHandle runUniversalTaskTimer(Runnable runnable, long delayTicks, long periodTicks) {
        com.javastudio.utils.PlatformUtil.TaskHandle handle = com.javastudio.utils.PlatformUtil.runTaskTimer(getOwningPlugin(), runnable, delayTicks, periodTicks);
        activeTaskHandles.add(handle);
        return handle;
    }

    public com.javastudio.utils.PlatformUtil.TaskHandle runTaskAtLocation(Location location, Runnable runnable) {
        com.javastudio.utils.PlatformUtil.TaskHandle handle = com.javastudio.utils.PlatformUtil.runAtLocation(getOwningPlugin(), location, runnable);
        activeTaskHandles.add(handle);
        return handle;
    }

    public com.javastudio.utils.PlatformUtil.TaskHandle runTaskAtLocationLater(Location location, Runnable runnable, long delayTicks) {
        com.javastudio.utils.PlatformUtil.TaskHandle handle = com.javastudio.utils.PlatformUtil.runAtLocationLater(getOwningPlugin(), location, runnable, delayTicks);
        activeTaskHandles.add(handle);
        return handle;
    }

    public com.javastudio.utils.PlatformUtil.TaskHandle runTaskAtEntity(Entity entity, Runnable runnable) {
        com.javastudio.utils.PlatformUtil.TaskHandle handle = com.javastudio.utils.PlatformUtil.runAtEntity(getOwningPlugin(), entity, runnable);
        activeTaskHandles.add(handle);
        return handle;
    }

    public com.javastudio.utils.PlatformUtil.TaskHandle runTaskAtEntityLater(Entity entity, Runnable runnable, long delayTicks) {
        com.javastudio.utils.PlatformUtil.TaskHandle handle = com.javastudio.utils.PlatformUtil.runAtEntityLater(getOwningPlugin(), entity, runnable, delayTicks);
        activeTaskHandles.add(handle);
        return handle;
    }

    public com.javastudio.utils.PlatformUtil.TaskHandle runAt(Location location, Runnable runnable) {
        return runTaskAtLocation(location, runnable);
    }

    public com.javastudio.utils.PlatformUtil.TaskHandle runFor(Entity entity, Runnable runnable) {
        return runTaskAtEntity(entity, runnable);
    }

    public java.util.concurrent.CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
        return com.javastudio.utils.PlatformUtil.teleportAsync(entity, location);
    }

    public final void cleanup() {

        for (BukkitTask task : activeTasks) {
            try {
                task.cancel();
            } catch (Exception ignored) {}
        }
        activeTasks.clear();

        for (com.javastudio.utils.PlatformUtil.TaskHandle handle : activeTaskHandles) {
            try {
                handle.cancel();
            } catch (Exception ignored) {}
        }
        activeTaskHandles.clear();

        for (Listener listener : registeredListeners) {
            HandlerList.unregisterAll(listener);
        }
        registeredListeners.clear();

        for (String cmdName : registeredCommands) {
            if (hostPlugin != null) {
                hostPlugin.getCommandManager().unregisterDynamicCommand(cmdName);
            } else {
                com.javastudio.utils.ReflectionUtil.unregisterCommand(cmdName);
            }
        }
        registeredCommands.clear();

        for (String pKey : registeredPlaceholders) {
            com.javastudio.bridge.PlaceholderApiBridge.unregisterCustomPlaceholder(pKey);
        }
        registeredPlaceholders.clear();

        if (getName() != null) {
            com.javastudio.api.ServiceBroker.getInstance().unregisterAllByPlugin(getName());
        }

        if (dataStore != null) {
            dataStore.close();
            dataStore = null;
        }

        this.enabled = false;
    }

    public void autoRegisterAnnotations() {
        Class<?> clazz = this.getClass();
        while (clazz != null && clazz != DynamicPlugin.class && clazz != Object.class) {
            for (Method method : clazz.getDeclaredMethods()) {
                method.setAccessible(true);

                if (method.isAnnotationPresent(JCommand.class)) {
                    JCommand cmd = method.getAnnotation(JCommand.class);
                    String cmdName = !cmd.value().isEmpty() ? cmd.value() : (!cmd.name().isEmpty() ? cmd.name() : method.getName());
                    String desc = cmd.description();
                    String usage = cmd.usage().isEmpty() ? "/" + cmdName : cmd.usage();
                    List<String> aliases = Arrays.asList(cmd.aliases());
                    String permission = cmd.permission();
                    String permMsg = cmd.permissionMessage();

                    registerCommand(cmdName, desc, usage, aliases, (sender, args) -> {
                        if (!permission.isEmpty() && !sender.hasPermission(permission)) {
                            sender.sendMessage(permMsg);
                            return true;
                        }
                        try {
                            Class<?>[] pTypes = method.getParameterTypes();
                            Object result = null;
                            if (pTypes.length == 2 && CommandSender.class.isAssignableFrom(pTypes[0]) && pTypes[1] == String[].class) {
                                result = method.invoke(this, sender, args);
                            } else if (pTypes.length == 2 && Player.class.isAssignableFrom(pTypes[0]) && pTypes[1] == String[].class) {
                                if (!(sender instanceof Player p)) {
                                    sender.sendMessage("§cChỉ có người chơi mới có thể thực hiện lệnh này!");
                                    return true;
                                }
                                result = method.invoke(this, p, args);
                            } else if (pTypes.length == 1 && CommandSender.class.isAssignableFrom(pTypes[0])) {
                                result = method.invoke(this, sender);
                            } else if (pTypes.length == 1 && Player.class.isAssignableFrom(pTypes[0])) {
                                if (!(sender instanceof Player p)) {
                                    sender.sendMessage("§cChỉ có người chơi mới có thể thực hiện lệnh này!");
                                    return true;
                                }
                                result = method.invoke(this, p);
                            } else if (pTypes.length == 0) {
                                result = method.invoke(this);
                            } else {
                                sender.sendMessage("§cLỗi: Tham số của phương thức lệnh không hợp lệ!");
                                return true;
                            }
                            if (result instanceof Boolean b) return b;
                            return true;
                        } catch (Throwable t) {
                            sender.sendMessage("§cLỗi thực thi lệnh /" + cmdName + ": " + t.getMessage());
                            logger.severe("Lỗi thực thi @JCommand /" + cmdName + ": " + t.getMessage());
                            return true;
                        }
                    });
                }

                if (method.isAnnotationPresent(JEvent.class)) {
                    JEvent eventAnno = method.getAnnotation(JEvent.class);
                    Class<?>[] pTypes = method.getParameterTypes();
                    if (pTypes.length == 1 && Event.class.isAssignableFrom(pTypes[0])) {
                        @SuppressWarnings("unchecked")
                        Class<? extends Event> eventClass = (Class<? extends Event>) pTypes[0];
                        Listener dummyListener = new Listener() {};
                        try {
                            getServer().getPluginManager().registerEvent(
                                    eventClass,
                                    dummyListener,
                                    eventAnno.priority(),
                                    (l, event) -> {
                                        if (eventClass.isInstance(event)) {
                                            try {
                                                method.invoke(this, event);
                                            } catch (Throwable t) {
                                                logger.warning("Lỗi trong @JEvent listener " + method.getName() + ": " + t.getMessage());
                                            }
                                        }
                                    },
                                    getOwningPlugin(),
                                    eventAnno.ignoreCancelled()
                            );
                            registeredListeners.add(dummyListener);
                            logger.info("Đã tự động hook @JEvent: " + method.getName() + " -> " + eventClass.getSimpleName());
                        } catch (Throwable t) {
                            logger.warning("Không thể đăng ký @JEvent " + method.getName() + ": " + t.getMessage());
                        }
                    }
                }

                if (method.isAnnotationPresent(JTask.class)) {
                    JTask taskAnno = method.getAnnotation(JTask.class);
                    Runnable r = () -> {
                        try {
                            if (method.getParameterCount() == 0) {
                                method.invoke(this);
                            }
                        } catch (Throwable t) {
                            logger.warning("Lỗi trong @JTask " + method.getName() + ": " + t.getMessage());
                        }
                    };

                    long delay = Math.max(0L, taskAnno.delay());
                    long period = taskAnno.period();
                    boolean async = taskAnno.async();

                    if (period > 0) {
                        if (async) {
                            runUniversalTaskTimer(r, delay, period);
                        } else {
                            runUniversalTaskTimer(r, delay, period);
                        }
                        logger.info("Đã lên lịch @JTask định kỳ (" + period + " ticks): " + method.getName());
                    } else {
                        if (async) {
                            runUniversalTaskLater(r, delay);
                        } else {
                            runUniversalTaskLater(r, delay);
                        }
                        logger.info("Đã lên lịch @JTask chạy sau (" + delay + " ticks): " + method.getName());
                    }
                }

                if (method.isAnnotationPresent(JRegionTask.class)) {
                    JRegionTask rAnno = method.getAnnotation(JRegionTask.class);
                    Runnable r = () -> {
                        try {
                            if (method.getParameterCount() == 0) {
                                method.invoke(this);
                            }
                        } catch (Throwable t) {
                            logger.warning("Lỗi trong @JRegionTask " + method.getName() + ": " + t.getMessage());
                        }
                    };
                    long delay = Math.max(0L, rAnno.delay());
                    long period = rAnno.period();
                    if (period > 0) {
                        runUniversalTaskTimer(r, delay, period);
                    } else {
                        runUniversalTaskLater(r, delay);
                    }
                    logger.info("Đã lên lịch @JRegionTask: " + method.getName());
                }

                if (method.isAnnotationPresent(JPlaceholder.class)) {
                    JPlaceholder pAnno = method.getAnnotation(JPlaceholder.class);
                    String key = !pAnno.value().isEmpty() ? pAnno.value() : (!pAnno.name().isEmpty() ? pAnno.name() : method.getName());
                    registeredPlaceholders.add(key.toLowerCase());

                    com.javastudio.bridge.PlaceholderApiBridge.registerCustomPlaceholder(key, player -> {
                        try {
                            Class<?>[] pTypes = method.getParameterTypes();
                            Object val = null;
                            if (pTypes.length == 1 && Player.class.isAssignableFrom(pTypes[0])) {
                                val = method.invoke(this, player);
                            } else if (pTypes.length == 0) {
                                val = method.invoke(this);
                            }
                            return val != null ? val.toString() : "";
                        } catch (Throwable t) {
                            return "Error: " + t.getMessage();
                        }
                    });
                    logger.info("Đã tự động hook @JPlaceholder: %javastudio_" + key.toLowerCase() + "%");
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private com.javastudio.storage.DataStore dataStore;


    public synchronized com.javastudio.storage.DataStore getDataStore() {
        if (dataStore == null) {
            dataStore = new com.javastudio.storage.DataStore(getDataFolder());
        }
        return dataStore;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getAuthor() {
        return author;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Listener> getRegisteredListeners() {
        return Collections.unmodifiableList(registeredListeners);
    }

    public List<String> getRegisteredCommands() {
        return Collections.unmodifiableList(registeredCommands);
    }

    public com.javastudio.api.gui.SmartGui createGui(String title, int rows) {
        return com.javastudio.api.gui.SmartGui.of(title, rows);
    }

    public com.javastudio.api.item.ItemBuilder buildItem(org.bukkit.Material material) {
        return com.javastudio.api.item.ItemBuilder.of(material);
    }

    public com.javastudio.api.item.ItemBuilder buildItem(org.bukkit.inventory.ItemStack item) {
        return com.javastudio.api.item.ItemBuilder.of(item);
    }


    public boolean deposit(org.bukkit.entity.Player player, double amount) {
        return com.javastudio.utils.VaultBridge.deposit(player, amount);
    }

    public boolean withdraw(org.bukkit.entity.Player player, double amount) {
        return com.javastudio.utils.VaultBridge.withdraw(player, amount);
    }

    public double getBalance(org.bukkit.entity.Player player) {
        return com.javastudio.utils.VaultBridge.getBalance(player);
    }

    public boolean hasPermission(org.bukkit.entity.Player player, String perm) {
        return com.javastudio.utils.VaultBridge.hasPermission(player, perm);
    }

    public boolean hasMoney(org.bukkit.entity.Player player, double amount) {
        return com.javastudio.utils.VaultBridge.has(player, amount);
    }

    public String getPrimaryGroup(org.bukkit.entity.Player player) {
        return com.javastudio.utils.VaultBridge.getPrimaryGroup(player);
    }


    public com.javastudio.pipeline.EventPipeline getEventPipeline() {
        return hostPlugin != null ? hostPlugin.getEventPipeline() : null;
    }

    public void emitEvent(String eventName, java.util.Map<String, Object> data) {
        var pipeline = getEventPipeline();
        if (pipeline != null) {
            pipeline.emit(eventName, data);
        }
    }

    public void onEvent(String eventName, java.util.function.Consumer<com.javastudio.pipeline.EventContext> handler) {
        var pipeline = getEventPipeline();
        if (pipeline != null) {
            pipeline.on(eventName, handler);
        }
    }


    public com.javastudio.analytics.MetricsCollector getMetrics() {
        return hostPlugin != null ? hostPlugin.getMetricsCollector() : null;
    }

    public void countMetric(String metricName) {
        var mc = getMetrics();
        if (mc != null) mc.count(getName(), metricName);
    }

    public void timeMetric(String metricName, long nanos) {
        var mc = getMetrics();
        if (mc != null) mc.time(getName(), metricName, nanos);
    }


    public com.javastudio.database.MigrationEngine getMigrator() {
        return new com.javastudio.database.MigrationEngine(getDataStore().getConnection(), getLogger());
    }


    public com.javastudio.permissions.RoleManager getRoleManager() {
        return hostPlugin != null ? hostPlugin.getRoleManager() : null;
    }

    public boolean checkPermission(org.bukkit.entity.Player player, String permission) {
        var rm = getRoleManager();
        if (rm != null) {
            return rm.hasPermission(player, permission);
        }
        return player.hasPermission(permission) || player.isOp();
    }


    public final void registerScheduledMethods() {
        Class<?> current = this.getClass();
        while (current != null && current != DynamicPlugin.class && current != Object.class) {
            for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                Schedule schedule = method.getAnnotation(Schedule.class);
                if (schedule != null) {
                    method.setAccessible(true);
                    Runnable task = () -> {
                        try {
                            method.invoke(this);
                        } catch (Throwable t) {
                            getLogger().warning("Lỗi khi chạy @Schedule '" + method.getName() + "': " + t.getMessage());
                        }
                    };

                    if (schedule.period() > 0) {
                        runTaskTimer(task, schedule.delay(), schedule.period());
                    } else {
                        runTaskLater(task, schedule.delay());
                    }

                    String desc = schedule.description().isEmpty() ? method.getName() : schedule.description();
                    logger.info("Đã đăng ký @Schedule: " + desc + " (delay=" + schedule.delay() + ", period=" + schedule.period() + ", async=" + schedule.async() + ")");
                }
            }
            current = current.getSuperclass();
        }
    }

    public com.javastudio.network.ProxyBridgeManager getProxyBridge() {
        return hostPlugin != null ? hostPlugin.getProxyBridge() : null;
    }

    public com.javastudio.security.exploit.ExploitGuard getExploitGuard() {
        return hostPlugin != null ? hostPlugin.getExploitGuard() : null;
    }

    public boolean isExploitSuspicious(org.bukkit.entity.Player player) {
        if (hostPlugin == null || hostPlugin.getExploitGuard() == null || player == null) return false;
        return hostPlugin.getExploitGuard().getViolationLevel(player) > 0;
    }

    public boolean sanitizeItem(org.bukkit.inventory.ItemStack item) {
        if (hostPlugin == null || hostPlugin.getExploitGuard() == null) return false;
        return hostPlugin.getExploitGuard().sanitizeItem(item);
    }

    public com.javastudio.api.ability.ItemAbilityManager getItemAbilityManager() {
        return hostPlugin != null ? hostPlugin.getItemAbilityManager() : null;
    }

    public void registerAbility(com.javastudio.api.ability.ItemAbility ability) {
        if (hostPlugin != null && hostPlugin.getItemAbilityManager() != null) {
            hostPlugin.getItemAbilityManager().registerAbility(ability);
        }
    }

    public org.bukkit.inventory.ItemStack applyAbility(org.bukkit.inventory.ItemStack item, String abilityId) {
        if (hostPlugin != null && hostPlugin.getItemAbilityManager() != null) {
            return hostPlugin.getItemAbilityManager().applyAbility(item, abilityId);
        }
        return item;
    }

    public com.javastudio.network.ClusterNetworkHub getCluster() {
        return hostPlugin != null ? hostPlugin.getClusterHub() : null;
    }

    public com.javastudio.security.SecuritySentinel getSecurity() {
        return hostPlugin != null ? hostPlugin.getSecuritySentinel() : null;
    }

    public com.javastudio.analytics.TelemetryNexus getTelemetry() {
        return hostPlugin != null ? hostPlugin.getTelemetryNexus() : null;
    }

    public com.javastudio.security.cve.PanelCveProtectionSuite getCveProtection() {
        return hostPlugin != null ? hostPlugin.getPanelCveProtection() : null;
    }

    public com.javastudio.pipeline.FastBlockEngine getFastBlock() {
        return hostPlugin != null ? hostPlugin.getFastBlock() : null;
    }

    public com.javastudio.network.NettyPacketEngine getNetty() {
        return hostPlugin != null ? hostPlugin.getNettyPacketEngine() : null;
    }

    public com.javastudio.ai.AiAssistant getAi() {
        return hostPlugin != null ? hostPlugin.getAiAssistant() : null;
    }

    public com.javastudio.anticheat.AntiCheatManager getAntiCheat() {
        return hostPlugin != null ? hostPlugin.getAntiCheatManager() : null;
    }

    public com.javastudio.bot.FakePlayerManager getBots() {
        return hostPlugin != null ? hostPlugin.getFakePlayerManager() : null;
    }

    public com.javastudio.pipeline.CircuitBreakerEngine getCircuitBreaker() {
        return hostPlugin != null ? hostPlugin.getCircuitBreakerEngine() : null;
    }
}


