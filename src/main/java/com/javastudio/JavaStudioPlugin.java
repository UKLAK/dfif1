package com.javastudio;

import com.javastudio.commands.JExecCommand;
import com.javastudio.commands.JPluginCommand;
import com.javastudio.commands.JScriptCommand;
import com.javastudio.commands.JavaStudioCommand;
import com.javastudio.api.gui.SmartGuiListener;
import com.javastudio.security.exploit.ExploitListener;
import com.javastudio.compiler.RuntimeCompiler;
import com.javastudio.manager.DynamicCommandManager;
import com.javastudio.manager.DynamicPluginManager;
import com.javastudio.manager.ScriptManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/**
 * Main Plugin Class của JavaStudio.
 * Cho phép tạo plugin, biên dịch và chạy các lệnh bằng ngôn ngữ Java trực tiếp trong Minecraft Server.
 * Tương thích Paper, Purpur, Spigot từ phiên bản 1.20.5 đến 26.3+.
 */
public class JavaStudioPlugin extends JavaPlugin {

    private static JavaStudioPlugin instance;

    private RuntimeCompiler compiler;
    private DynamicCommandManager commandManager;
    private DynamicPluginManager pluginManager;
    private ScriptManager scriptManager;
    private com.javastudio.manager.DependencyResolver dependencyResolver;
    private com.javastudio.security.SecuritySandbox securitySandbox;
    private com.javastudio.manager.PluginExporter exporter;
    private com.javastudio.discord.DiscordWebhook discordWebhook;
    private com.javastudio.watcher.FileWatcherService fileWatcher;
    private com.javastudio.control.PluginController pluginController;
    private com.javastudio.control.ServerController serverController;
    private com.javastudio.manager.SettingsManager settingsManager;
    private com.javastudio.display.DisplayEngine displayEngine;
    private com.javastudio.security.SecurityScanner securityScanner;
    private com.javastudio.control.BytecodeInspector bytecodeInspector;
    private com.javastudio.manager.MarketplaceManager marketplaceManager;
    private com.javastudio.commands.JReplSession replSession;
    private com.javastudio.network.NetworkSyncManager networkSyncManager;
    private com.javastudio.profiler.DynamicPluginProfiler profiler;
    private com.javastudio.network.RedisSyncManager redisSyncManager;
    private com.javastudio.network.ProxyBridgeManager proxyBridgeManager;
    private com.javastudio.control.ConsoleLogBuffer consoleLogBuffer;
    private com.javastudio.security.exploit.ExploitGuard exploitGuard;
    private com.javastudio.anticheat.AntiCheatManager antiCheatManager;
    private com.javastudio.analytics.DebugHudManager debugHudManager;
    private com.javastudio.control.EventSpyManager eventSpyManager;
    private com.javastudio.api.ability.ItemAbilityManager itemAbilityManager;
    private com.javastudio.security.SecuritySentinel securitySentinel;
    private com.javastudio.network.ClusterNetworkHub clusterHub;
    private com.javastudio.analytics.TelemetryNexus telemetryNexus;

    // v2.0 - Hệ thống mới
    private com.javastudio.pipeline.EventPipeline eventPipeline;
    private com.javastudio.analytics.HealthMonitor healthMonitor;
    private com.javastudio.analytics.MetricsCollector metricsCollector;
    private com.javastudio.graph.DependencyGraph dependencyGraph;
    private com.javastudio.permissions.RoleManager roleManager;
    private com.javastudio.bridge.PlaceholderApiBridge placeholderApiBridge;

    // v3.0 - Tầng thấp Netty, AI & Autonomous Ops
    private com.javastudio.analytics.AutoMitigationEngine autoMitigationEngine;
    private com.javastudio.ai.AiAssistant aiAssistant;
    private com.javastudio.network.packet.PacketSnifferManager packetSniffer;
    private com.javastudio.control.BytecodePatchManager bytecodePatchManager;

    // v3.0 - Resource Pack & Licensing
    private com.javastudio.pack.ResourcePackHost resourcePackHost;
    private com.javastudio.export.LicenseManager licenseManager;

    // v3.0 Extended - Fake Players, Live Heap, 2FA, Remote Bridge, Netty Engine
    private com.javastudio.network.NettyPacketEngine nettyPacketEngine;
    private com.javastudio.bot.FakePlayerManager fakePlayerManager;
    private com.javastudio.inspector.HeapInspector heapInspector;
    private com.javastudio.security.TotpManager totpManager;
    private com.javastudio.bridge.RemoteBridgeManager remoteBridgeManager;

    // v3.0 Circuit Breaker, FastBlock & Netty Fake Player Engine
    private com.javastudio.pipeline.CircuitBreakerEngine circuitBreakerEngine;
    private com.javastudio.pipeline.FastBlockEngine fastBlockEngine;
    private com.javastudio.pipeline.JBotEngine botEngine;

    // Panel & Wings CVE Protection Suite (Crafty Controller & Pterodactyl)
    private com.javastudio.security.cve.PanelCveProtectionSuite panelCveProtection;

    @Override
    public void onEnable() {
        instance = this;

        // Lưu cấu hình mặc định
        saveDefaultConfig();

        // Khởi tạo các thư mục làm việc
        ensureDirectories();

        getLogger().info("╔══════════════════════════════════════════════════╗");
        getLogger().info("║    JavaStudio v2.0 - Dynamic Java Plugin Engine  ║");
        getLogger().info("║  Nền tảng: " + com.javastudio.utils.PlatformUtil.getPlatformName() + " (Minecraft " + com.javastudio.utils.PlatformUtil.getMinecraftVersion() + ")");
        getLogger().info("║  Môi trường: Java " + com.javastudio.utils.PlatformUtil.getJavaVersion() + " (" + com.javastudio.utils.PlatformUtil.getJavaVersionString() + ")");
        if (com.javastudio.utils.PlatformUtil.isVirtualThreadsSupported()) {
            getLogger().info("║  Virtual Threads (Project Loom): Đã kích hoạt    ║");
        }
        getLogger().info("║  Hỗ trợ toàn diện Java 21+ (Paper/Purpur/Spigot) ║");
        getLogger().info("╚══════════════════════════════════════════════════╝");

        // Khởi tạo bộ đệm log console server
        this.consoleLogBuffer = new com.javastudio.control.ConsoleLogBuffer();

        // Khởi tạo trình quản lý cài đặt module
        this.settingsManager = new com.javastudio.manager.SettingsManager(this);

        // Khởi tạo các module cốt lõi
        this.compiler = new RuntimeCompiler(this);
        this.commandManager = new DynamicCommandManager(this);
        this.dependencyResolver = new com.javastudio.manager.DependencyResolver(this);
        this.securitySandbox = new com.javastudio.security.SecuritySandbox(this);
        this.discordWebhook = new com.javastudio.discord.DiscordWebhook(this);
        this.pluginManager = new DynamicPluginManager(this);
        this.scriptManager = new ScriptManager(this);
        this.exporter = new com.javastudio.manager.PluginExporter(this);
        this.pluginController = new com.javastudio.control.PluginController(this);
        this.serverController = new com.javastudio.control.ServerController(this);

        // Khởi tạo các hệ thống tính năng mở rộng
        this.displayEngine = new com.javastudio.display.DisplayEngine(this);
        this.securityScanner = new com.javastudio.security.SecurityScanner(this);
        this.bytecodeInspector = new com.javastudio.control.BytecodeInspector(this);
        this.marketplaceManager = new com.javastudio.manager.MarketplaceManager(this);
        this.networkSyncManager = new com.javastudio.network.NetworkSyncManager(this);

        // Khởi tạo Profiler và Redis Sync
        this.profiler = new com.javastudio.profiler.DynamicPluginProfiler(this);
        this.redisSyncManager = new com.javastudio.network.RedisSyncManager(this);
        this.proxyBridgeManager = new com.javastudio.network.ProxyBridgeManager(this);

        // v2.0 - Khởi tạo 6 hệ thống mới
        this.eventPipeline = new com.javastudio.pipeline.EventPipeline(getLogger());
        this.metricsCollector = new com.javastudio.analytics.MetricsCollector(this);
        this.healthMonitor = new com.javastudio.analytics.HealthMonitor(this);
        this.dependencyGraph = new com.javastudio.graph.DependencyGraph(getLogger());
        this.roleManager = new com.javastudio.permissions.RoleManager(this);

        // Bắt đầu giám sát sức khỏe hệ thống
        this.healthMonitor.start();

        // Đăng ký SmartGui Listener
        getServer().getPluginManager().registerEvents(new SmartGuiListener(), this);

        // Kết nối Redis nếu được cấu hình
        if (getConfig().getBoolean("redis.enabled", false)) {
            String rHost = getConfig().getString("redis.host", "127.0.0.1");
            int rPort = getConfig().getInt("redis.port", 6379);
            String rPass = getConfig().getString("redis.password", "");
            this.redisSyncManager.connect(rHost, rPort, rPass);
        }

        if (settingsManager.isInteractiveReplEnabled()) {
            this.replSession = new com.javastudio.commands.JReplSession(this);
        }

        // Khởi tạo ExploitFixer Protection Guard
        this.exploitGuard = new com.javastudio.security.exploit.ExploitGuard(this);
        if (settingsManager.isExploitFixerEnabled()) {
            getServer().getPluginManager().registerEvents(new ExploitListener(this, exploitGuard), this);
            getLogger().info("[ExploitFixer] Đã kích hoạt hệ thống bảo vệ máy chủ chống exploit!");
        }

        // Khởi tạo Anti-Cheat Engine (Vulcan/Grim/Matrix-style)
        this.antiCheatManager = new com.javastudio.anticheat.AntiCheatManager(this);
        if (settingsManager.isAntiCheatEnabled()) {
            this.antiCheatManager.start();
            getServer().getPluginManager().registerEvents(new com.javastudio.anticheat.AntiCheatListener(this, antiCheatManager), this);
            getLogger().info("[AntiCheat] Đã kích hoạt hệ thống chống gian lận (Vulcan/Grim/Matrix-style)!");
        }

        // Khởi tạo Debug HUD, EventSpy và Item Ability Framework
        this.debugHudManager = new com.javastudio.analytics.DebugHudManager(this);
        this.eventSpyManager = new com.javastudio.control.EventSpyManager(this);
        this.itemAbilityManager = new com.javastudio.api.ability.ItemAbilityManager(this);

        // Khởi tạo các trung tâm hợp nhất (Security Sentinel, Cluster Hub, Telemetry Nexus)
        this.securitySentinel = new com.javastudio.security.SecuritySentinel(this, securitySandbox, securityScanner, exploitGuard, bytecodeInspector);
        this.clusterHub = new com.javastudio.network.ClusterNetworkHub(this, redisSyncManager, proxyBridgeManager, networkSyncManager);
        this.telemetryNexus = new com.javastudio.analytics.TelemetryNexus(this, serverController, healthMonitor, metricsCollector, profiler, debugHudManager);

        // Khởi tạo PlaceholderAPI Bridge
        this.placeholderApiBridge = new com.javastudio.bridge.PlaceholderApiBridge(this);
        this.placeholderApiBridge.init();

        // v3.0 - Khởi tạo AutoMitigation, AI Copilot, Packet Sniffer, Bytecode Patch
        this.autoMitigationEngine = new com.javastudio.analytics.AutoMitigationEngine(this, serverController);
        if (settingsManager.isAutoMitigationEnabled()) {
            this.autoMitigationEngine.start();
        }
        this.aiAssistant = new com.javastudio.ai.AiAssistant(this);
        this.packetSniffer = new com.javastudio.network.packet.PacketSnifferManager(this);
        if (settingsManager.isPacketSnifferEnabled()) {
            this.packetSniffer.start();
        }
        this.bytecodePatchManager = new com.javastudio.control.BytecodePatchManager(this);

        // v3.0 - Resource Pack & Licensing
        this.licenseManager = new com.javastudio.export.LicenseManager(this);
        this.resourcePackHost = new com.javastudio.pack.ResourcePackHost(this);

        // v3.0 Extended - Fake Players, Live Heap, 2FA, Remote Bridge, Netty Engine
        this.nettyPacketEngine = new com.javastudio.network.NettyPacketEngine(this);
        if (settingsManager.isPacketSnifferEnabled()) {
            this.nettyPacketEngine.start();
        }
        this.fakePlayerManager = new com.javastudio.bot.FakePlayerManager(this);
        this.heapInspector = new com.javastudio.inspector.HeapInspector(this);
        this.totpManager = new com.javastudio.security.TotpManager(this);
        this.remoteBridgeManager = new com.javastudio.bridge.RemoteBridgeManager(this);

        // Khởi tạo CircuitBreakerEngine, FastBlockEngine và JBotEngine
        this.circuitBreakerEngine = new com.javastudio.pipeline.CircuitBreakerEngine();
        this.fastBlockEngine = new com.javastudio.pipeline.FastBlockEngine(this);
        this.botEngine = new com.javastudio.pipeline.JBotEngine(this);

        // Tự động gắn cầu chì bảo vệ cho toàn bộ Listener trên server sau khi khởi động hoàn tất nếu module được bật
        if (settingsManager.isCircuitBreakerEnabled()) {
            getServer().getScheduler().runTask(this, () -> {
                int hooked = this.circuitBreakerEngine.instrumentPlugin(null);
                getLogger().info("[CircuitBreaker] Đã bảo vệ " + hooked + " Event Listeners trên toàn máy chủ.");
            });
        }

        // Khởi tạo Panel & Wings CVE Defense Suite (Crafty Controller & Pterodactyl Panel/Wings)
        this.panelCveProtection = new com.javastudio.security.cve.PanelCveProtectionSuite(this);
        if (settingsManager.isPanelCveDefenseEnabled()) {
            this.panelCveProtection.init();
        }

        // Khởi chạy Hot-Reload File Watcher nếu module được bật
        this.fileWatcher = new com.javastudio.watcher.FileWatcherService(this);
        if (settingsManager.isFileWatcherEnabled()) {
            this.fileWatcher.start();
        } else {
            getLogger().info("[FileWatcher] Hot-Reload Watcher đang tắt trong cài đặt (modules.file-watcher: false)");
        }

        // Đăng ký các lệnh
        registerCommands();

        // Tự động nạp các plugin nếu module dynamic-plugins được bật
        if (settingsManager.isDynamicPluginsEnabled()) {
            autoLoadPlugins();
        }

        getLogger().info("[JavaStudio] Plugin v2.0 đã được kích hoạt thành công!");
        getLogger().info("[JavaStudio] Modules: EventPipeline, HealthMonitor, MetricsCollector, DependencyGraph, RoleManager");
    }

    @Override
    public void onDisable() {
        getLogger().info("[JavaStudio] Đang tắt các dynamic plugins và giải phóng tài nguyên...");

        // Dừng Hot-Reload File Watcher
        if (fileWatcher != null) {
            fileWatcher.stop();
        }

        // Tắt Sandbox Executor
        if (securitySandbox != null) {
            securitySandbox.shutdown();
        }

        // Tắt toàn bộ dynamic plugins đang hoạt động
        if (pluginManager != null) {
            pluginManager.unloadAll();
        }

        // Dọn dẹp Display Engine (Holograms, BossBars)
        if (displayEngine != null) {
            displayEngine.cleanupAll();
        }

        // Tắt Network Sync
        if (networkSyncManager != null) {
            networkSyncManager.stop();
        }

        // Tắt Redis Sync
        if (redisSyncManager != null) {
            redisSyncManager.close();
        }

        // Tắt Proxy Bridge
        if (proxyBridgeManager != null) {
            proxyBridgeManager.unregister();
        }

        // Tắt Exploit Guard, Debug HUD, EventSpy và Item Ability
        if (panelCveProtection != null) {
            panelCveProtection.shutdown();
        }
        if (exploitGuard != null) {
            exploitGuard.shutdown();
        }
        if (antiCheatManager != null) {
            antiCheatManager.shutdown();
        }
        if (debugHudManager != null) {
            debugHudManager.cleanupAll();
        }
        if (eventSpyManager != null) {
            eventSpyManager.cleanup();
        }
        if (itemAbilityManager != null) {
            itemAbilityManager.cleanup();
        }

        // Tắt PlaceholderAPI Bridge
        if (placeholderApiBridge != null) {
            placeholderApiBridge.unregister();
        }

        // Tắt v3.0 managers
        if (autoMitigationEngine != null) {
            autoMitigationEngine.stop();
        }
        if (packetSniffer != null) {
            packetSniffer.stop();
        }
        if (fakePlayerManager != null) {
            fakePlayerManager.shutdown();
        }
        if (botEngine != null) {
            botEngine.shutdown();
        }
        if (nettyPacketEngine != null) {
            nettyPacketEngine.stop();
        }

        // Tách logger buffer
        if (consoleLogBuffer != null) {
            consoleLogBuffer.detach();
        }

        // Gỡ bỏ toàn bộ lệnh động
        if (commandManager != null) {
            commandManager.unregisterAll();
        }

        // Đóng Virtual Threads executor
        com.javastudio.utils.PlatformUtil.shutdown();

        getLogger().info("[JavaStudio] Đã tắt thành công!");
        instance = null;
    }

    private void ensureDirectories() {
        File dataDir = getDataFolder();
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        new File(dataDir, "projects").mkdirs();
        new File(dataDir, "scripts").mkdirs();
        new File(dataDir, "logs").mkdirs();
        new File(dataDir, "exports").mkdirs();
        new File(dataDir, ".cache" + File.separator + "libraries").mkdirs();
    }

    private void registerCommands() {
        // Lệnh /jexec và /java
        JExecCommand jExec = new JExecCommand(this);
        registerCmd("jexec", jExec, jExec);
        registerCmd("java", jExec, jExec);

        // Lệnh /jplugin
        JPluginCommand jPlugin = new JPluginCommand(this);
        registerCmd("jplugin", jPlugin, jPlugin);

        // Lệnh /jscript
        JScriptCommand jScript = new JScriptCommand(this);
        registerCmd("jscript", jScript, jScript);

        // Lệnh /javastudio
        JavaStudioCommand jStudio = new JavaStudioCommand(this);
        registerCmd("javastudio", jStudio, jStudio);

        // Lệnh /jcontrol, /jpm, /jserver (PlugManX & Software Control)
        com.javastudio.commands.JControlCommand jControl = new com.javastudio.commands.JControlCommand(this, pluginController, serverController);
        registerCmd("jcontrol", jControl, jControl);
        registerCmd("jpm", jControl, jControl);
        registerCmd("jserver", jControl, jControl);

        // Lệnh /jnetwork (Multi-Server & Redis Sync)
        com.javastudio.commands.JNetworkCommand jNetwork = new com.javastudio.commands.JNetworkCommand(this);
        registerCmd("jnetwork", jNetwork, jNetwork);

        // Lệnh /jexploit (ExploitFixer Protection)
        com.javastudio.commands.JExploitCommand jExploit = new com.javastudio.commands.JExploitCommand(this, exploitGuard);
        registerCmd("jexploit", jExploit, jExploit);

        // Lệnh /jac và /anticheat (Anti-Cheat Engine Control)
        com.javastudio.commands.JAntiCheatCommand jAc = new com.javastudio.commands.JAntiCheatCommand(this, antiCheatManager);
        registerCmd("jac", jAc, jAc);
        registerCmd("anticheat", jAc, jAc);
        registerCmd("jstudioac", jAc, jAc);

        // Lệnh /jhud (Realtime Performance Debug HUD)
        com.javastudio.commands.JHudCommand jHud = new com.javastudio.commands.JHudCommand(this, debugHudManager);
        registerCmd("jhud", jHud, jHud);

        // Lệnh /jspy (Interactive In-Game Event Spy)
        com.javastudio.commands.JSpyCommand jSpy = new com.javastudio.commands.JSpyCommand(this, eventSpyManager);
        registerCmd("jspy", jSpy, jSpy);

        // Lệnh /jsecurity (Unified Security Sentinel)
        com.javastudio.commands.JSecurityCommand jSec = new com.javastudio.commands.JSecurityCommand(this, securitySentinel);
        registerCmd("jsecurity", jSec, jSec);

        // Lệnh /jdiag (Unified Telemetry & Diagnostics)
        com.javastudio.commands.JDiagCommand jDiag = new com.javastudio.commands.JDiagCommand(this, telemetryNexus);
        registerCmd("jdiag", jDiag, jDiag);

        // Lệnh /jai (AI Code Copilot)
        com.javastudio.commands.JAiCommand jAi = new com.javastudio.commands.JAiCommand(this, aiAssistant);
        registerCmd("jai", jAi, jAi);

        // Lệnh /jpacket (Netty Packet Sniffer)
        com.javastudio.commands.JPacketCommand jPacket = new com.javastudio.commands.JPacketCommand(this, packetSniffer);
        registerCmd("jpacket", jPacket, jPacket);

        // Lệnh /jpack (Resource Pack & Custom Models/Audio/GUI)
        com.javastudio.commands.JPackCommand jPack = new com.javastudio.commands.JPackCommand(this, resourcePackHost);
        registerCmd("jpack", jPack, jPack);

        // Lệnh /jlicense (Cryptographic Licensing & Anti-Leak Watermark)
        com.javastudio.commands.JLicenseCommand jLic = new com.javastudio.commands.JLicenseCommand(this, licenseManager);
        registerCmd("jlicense", jLic, jLic);

        // Lệnh /jtrace (Live Event Tracer & Circuit Breaker)
        com.javastudio.commands.JTraceCommand jTrace = new com.javastudio.commands.JTraceCommand(this.circuitBreakerEngine);
        registerCmd("jtrace", jTrace, jTrace);
        registerCmd("jt", jTrace, jTrace);
        registerCmd("jbreaker", jTrace, jTrace);

        // Lệnh /jchunk (FastBlockEngine & RAM Arena Snapshots)
        com.javastudio.commands.JChunkCommand jChunk = new com.javastudio.commands.JChunkCommand(this.fastBlockEngine);
        registerCmd("jchunk", jChunk, jChunk);
        registerCmd("jc", jChunk, jChunk);
        registerCmd("jarena", jChunk, jChunk);

        // Lệnh /jbot và /jb (Fake Player Netty Engine & Stress Testing)
        com.javastudio.commands.JBotCommand jBot = new com.javastudio.commands.JBotCommand(this, this.botEngine, this.fakePlayerManager);
        registerCmd("jbot", jBot, jBot);
        registerCmd("jb", jBot, jBot);

        // Lệnh /jheap và /jmem (Live JVM Heap Inspector)
        com.javastudio.commands.JHeapCommand jHeap = new com.javastudio.commands.JHeapCommand(this, heapInspector);
        registerCmd("jheap", jHeap, jHeap);
        registerCmd("jmem", jHeap, jHeap);

        // Lệnh /jbridge (Remote Discord Bridge & Rescue Ops)
        com.javastudio.commands.JBridgeCommand jBridge = new com.javastudio.commands.JBridgeCommand(this, remoteBridgeManager);
        registerCmd("jbridge", jBridge, jBridge);

        // Lệnh /j2fa (Google Authenticator TOTP)
        registerCmd("j2fa", jSec, jSec);

        // Lệnh /jpkt (Netty Packet Engine alias)
        registerCmd("jpkt", jPacket, jPacket);

        // Lệnh /report (Player Reporting & Auto Investigation)
        com.javastudio.commands.JReportCommand jRep = new com.javastudio.commands.JReportCommand(this, antiCheatManager);
        registerCmd("report", jRep, jRep);
    }

    private void registerCmd(String name, org.bukkit.command.CommandExecutor executor, org.bukkit.command.TabCompleter completer) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (completer != null) {
                cmd.setTabCompleter(completer);
            }
        } else {
            getLogger().warning("Không thể đăng ký lệnh /" + name + " từ plugin.yml");
        }
    }

    private void autoLoadPlugins() {
        List<String> autoLoadList = getConfig().getStringList("auto-load-plugins");
        for (String pName : autoLoadList) {
            StringBuilder err = new StringBuilder();
            boolean success = pluginManager.loadPlugin(pName, err);
            if (success) {
                getLogger().info("[Auto-Load] Đã nạp thành công dynamic plugin: " + pName);
            } else {
                getLogger().warning("[Auto-Load] Không thể nạp dynamic plugin " + pName + ": " + err);
            }
        }
    }

    public static JavaStudioPlugin getInstance() {
        return instance;
    }

    public RuntimeCompiler getCompiler() {
        return compiler;
    }

    public DynamicCommandManager getCommandManager() {
        return commandManager;
    }

    public DynamicPluginManager getPluginManager() {
        return pluginManager;
    }

    public ScriptManager getScriptManager() {
        return scriptManager;
    }

    public com.javastudio.manager.DependencyResolver getDependencyResolver() {
        return dependencyResolver;
    }

    public com.javastudio.security.SecuritySandbox getSecuritySandbox() {
        return securitySandbox;
    }

    public com.javastudio.manager.PluginExporter getExporter() {
        return exporter;
    }

    public com.javastudio.discord.DiscordWebhook getDiscordWebhook() {
        return discordWebhook;
    }

    public com.javastudio.watcher.FileWatcherService getFileWatcher() {
        return fileWatcher;
    }

    public com.javastudio.control.PluginController getPluginController() {
        return pluginController;
    }

    public com.javastudio.control.ServerController getServerController() {
        return serverController;
    }

    public com.javastudio.manager.AutoRollbackManager getRollbackManager() {
        return pluginManager != null ? pluginManager.getRollbackManager() : null;
    }

    public com.javastudio.manager.SettingsManager getSettingsManager() {
        return settingsManager;
    }

    public com.javastudio.display.DisplayEngine getDisplayEngine() {
        return displayEngine;
    }

    public com.javastudio.security.SecurityScanner getSecurityScanner() {
        return securityScanner;
    }

    public com.javastudio.control.BytecodeInspector getBytecodeInspector() {
        return bytecodeInspector;
    }

    public com.javastudio.manager.MarketplaceManager getMarketplaceManager() {
        return marketplaceManager;
    }

    public com.javastudio.commands.JReplSession getReplSession() {
        return replSession;
    }

    public com.javastudio.network.NetworkSyncManager getNetworkSyncManager() {
        return networkSyncManager;
    }

    public com.javastudio.profiler.DynamicPluginProfiler getProfiler() {
        return profiler;
    }

    public com.javastudio.network.RedisSyncManager getRedisSyncManager() {
        return redisSyncManager;
    }

    public com.javastudio.control.ConsoleLogBuffer getConsoleLogBuffer() {
        return consoleLogBuffer;
    }

    // v2.0 getters
    public com.javastudio.pipeline.EventPipeline getEventPipeline() {
        return eventPipeline;
    }

    public com.javastudio.analytics.HealthMonitor getHealthMonitor() {
        return healthMonitor;
    }

    public com.javastudio.analytics.MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public com.javastudio.graph.DependencyGraph getDependencyGraph() {
        return dependencyGraph;
    }

    public com.javastudio.permissions.RoleManager getRoleManager() {
        return roleManager;
    }

    public com.javastudio.network.ProxyBridgeManager getProxyBridge() {
        return proxyBridgeManager;
    }

    public com.javastudio.security.exploit.ExploitGuard getExploitGuard() {
        return exploitGuard;
    }

    public com.javastudio.anticheat.AntiCheatManager getAntiCheatManager() {
        return antiCheatManager;
    }

    public com.javastudio.analytics.DebugHudManager getDebugHudManager() {
        return debugHudManager;
    }

    public com.javastudio.control.EventSpyManager getEventSpyManager() {
        return eventSpyManager;
    }

    public com.javastudio.api.ability.ItemAbilityManager getItemAbilityManager() {
        return itemAbilityManager;
    }

    public com.javastudio.security.SecuritySentinel getSecuritySentinel() {
        return securitySentinel;
    }

    public com.javastudio.security.cve.PanelCveProtectionSuite getPanelCveProtection() {
        return panelCveProtection;
    }

    public com.javastudio.network.ClusterNetworkHub getClusterHub() {
        return clusterHub;
    }

    public com.javastudio.analytics.TelemetryNexus getTelemetryNexus() {
        return telemetryNexus;
    }

    public com.javastudio.bridge.PlaceholderApiBridge getPlaceholderApiBridge() {
        return placeholderApiBridge;
    }

    public com.javastudio.analytics.AutoMitigationEngine getAutoMitigationEngine() {
        return autoMitigationEngine;
    }

    public com.javastudio.ai.AiAssistant getAiAssistant() {
        return aiAssistant;
    }

    public com.javastudio.network.packet.PacketSnifferManager getPacketSniffer() {
        return packetSniffer;
    }

    public com.javastudio.control.BytecodePatchManager getBytecodePatchManager() {
        return bytecodePatchManager;
    }

    public com.javastudio.pack.ResourcePackHost getResourcePackHost() {
        return resourcePackHost;
    }

    public com.javastudio.export.LicenseManager getLicenseManager() {
        return licenseManager;
    }

    public com.javastudio.network.NettyPacketEngine getNettyPacketEngine() {
        return nettyPacketEngine;
    }

    public com.javastudio.bot.FakePlayerManager getFakePlayerManager() {
        return fakePlayerManager;
    }

    public com.javastudio.inspector.HeapInspector getHeapInspector() {
        return heapInspector;
    }

    public com.javastudio.security.TotpManager getTotpManager() {
        return totpManager;
    }

    public com.javastudio.bridge.RemoteBridgeManager getRemoteBridgeManager() {
        return remoteBridgeManager;
    }

    public com.javastudio.pipeline.CircuitBreakerEngine getCircuitBreakerEngine() {
        return circuitBreakerEngine;
    }

    public com.javastudio.pipeline.FastBlockEngine getFastBlock() {
        return fastBlockEngine;
    }

    public com.javastudio.pipeline.JBotEngine getBotEngine() {
        return botEngine;
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        if (settingsManager != null) {
            settingsManager.loadSettings();
        }
        if (autoMitigationEngine != null) {
            autoMitigationEngine.loadConfig();
        }
        if (exploitGuard != null) {
            exploitGuard.loadConfig();
        }
        if (antiCheatManager != null) {
            antiCheatManager.reload();
        }
    }
}

