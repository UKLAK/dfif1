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

    private com.javastudio.pipeline.EventPipeline eventPipeline;
    private com.javastudio.analytics.HealthMonitor healthMonitor;
    private com.javastudio.analytics.MetricsCollector metricsCollector;
    private com.javastudio.graph.DependencyGraph dependencyGraph;
    private com.javastudio.permissions.RoleManager roleManager;
    private com.javastudio.bridge.PlaceholderApiBridge placeholderApiBridge;

    private com.javastudio.analytics.AutoMitigationEngine autoMitigationEngine;
    private com.javastudio.ai.AiAssistant aiAssistant;
    private com.javastudio.ai.AutonomousWatchdogAgent autonomousWatchdog;
    private com.javastudio.network.packet.PacketSnifferManager packetSniffer;
    private com.javastudio.control.BytecodePatchManager bytecodePatchManager;

    private com.javastudio.pack.ResourcePackHost resourcePackHost;
    private com.javastudio.export.LicenseManager licenseManager;

    private com.javastudio.network.NettyPacketEngine nettyPacketEngine;
    private com.javastudio.network.SinglePortMuxEngine singlePortMuxEngine;
    private com.javastudio.bot.FakePlayerManager fakePlayerManager;
    private com.javastudio.inspector.HeapInspector heapInspector;
    private com.javastudio.security.TotpManager totpManager;
    private com.javastudio.bridge.RemoteBridgeManager remoteBridgeManager;

    private com.javastudio.pipeline.CircuitBreakerEngine circuitBreakerEngine;
    private com.javastudio.pipeline.FastBlockEngine fastBlockEngine;
    private com.javastudio.pipeline.JBotEngine botEngine;
    private com.javastudio.pipeline.VisualFlowEngine visualFlowEngine;
    private com.javastudio.network.ClusterMeshEngine clusterMeshEngine;
    private com.javastudio.network.ClusterInventorySync clusterInventorySync;
    private com.javastudio.manager.HotSwapEngine hotSwapEngine;
    private com.javastudio.ai.DeadlockResolverEngine deadlockResolverEngine;
    private com.javastudio.pipeline.AsyncChunkPrefetcher chunkPrefetcher;
    private com.javastudio.security.ClassTaintEngine classTaintEngine;
    private com.javastudio.api.gui.GuiDesignerEngine guiDesignerEngine;
    private com.javastudio.control.KernelModuleManager kernelModuleManager;
    private com.javastudio.manager.SnapshotEngine snapshotEngine;
    private com.javastudio.pipeline.MockEventEngine mockEventEngine;

    private com.javastudio.security.cve.PanelCveProtectionSuite panelCveProtection;
    private com.javastudio.analytics.ServerBenchmarkEngine serverBenchmarkEngine;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

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

        this.consoleLogBuffer = new com.javastudio.control.ConsoleLogBuffer();

        this.kernelModuleManager = new com.javastudio.control.KernelModuleManager(this);
        this.snapshotEngine = new com.javastudio.manager.SnapshotEngine(this);
        this.mockEventEngine = new com.javastudio.pipeline.MockEventEngine(this);

        this.settingsManager = new com.javastudio.manager.SettingsManager(this);

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

        this.displayEngine = new com.javastudio.display.DisplayEngine(this);
        this.securityScanner = new com.javastudio.security.SecurityScanner(this);
        this.bytecodeInspector = new com.javastudio.control.BytecodeInspector(this);
        this.marketplaceManager = new com.javastudio.manager.MarketplaceManager(this);
        this.networkSyncManager = new com.javastudio.network.NetworkSyncManager(this);

        this.profiler = new com.javastudio.profiler.DynamicPluginProfiler(this);
        this.redisSyncManager = new com.javastudio.network.RedisSyncManager(this);
        this.proxyBridgeManager = new com.javastudio.network.ProxyBridgeManager(this);

        this.eventPipeline = new com.javastudio.pipeline.EventPipeline(getLogger());
        this.metricsCollector = new com.javastudio.analytics.MetricsCollector(this);
        this.healthMonitor = new com.javastudio.analytics.HealthMonitor(this);
        this.dependencyGraph = new com.javastudio.graph.DependencyGraph(getLogger());
        this.roleManager = new com.javastudio.permissions.RoleManager(this);

        this.healthMonitor.start();

        getServer().getPluginManager().registerEvents(new SmartGuiListener(), this);

        if (getConfig().getBoolean("redis.enabled", false)) {
            String rHost = getConfig().getString("redis.host", "127.0.0.1");
            int rPort = getConfig().getInt("redis.port", 6379);
            String rPass = getConfig().getString("redis.password", "");
            this.redisSyncManager.connect(rHost, rPort, rPass);
        }

        if (settingsManager.isInteractiveReplEnabled()) {
            this.replSession = new com.javastudio.commands.JReplSession(this);
        }

        this.exploitGuard = new com.javastudio.security.exploit.ExploitGuard(this);
        if (settingsManager.isExploitFixerEnabled()) {
            getServer().getPluginManager().registerEvents(new ExploitListener(this, exploitGuard), this);
            getLogger().info("[ExploitFixer] Đã kích hoạt hệ thống bảo vệ máy chủ chống exploit!");
        }

        this.antiCheatManager = new com.javastudio.anticheat.AntiCheatManager(this);
        getServer().getPluginManager().registerEvents(new com.javastudio.anticheat.AntiCheatListener(this, antiCheatManager), this);
        if (settingsManager.isAntiCheatEnabled() && kernelModuleManager.isModuleEnabled("anti-cheat")) {
            this.antiCheatManager.start();
            getLogger().info("[AntiCheat] Đã kích hoạt hệ thống chống gian lận (Vulcan/Grim/Matrix-style)!");
        }

        this.debugHudManager = new com.javastudio.analytics.DebugHudManager(this);
        this.eventSpyManager = new com.javastudio.control.EventSpyManager(this);
        this.itemAbilityManager = new com.javastudio.api.ability.ItemAbilityManager(this);

        this.securitySentinel = new com.javastudio.security.SecuritySentinel(this, securitySandbox, securityScanner, exploitGuard, bytecodeInspector);
        this.clusterHub = new com.javastudio.network.ClusterNetworkHub(this, redisSyncManager, proxyBridgeManager, networkSyncManager);
        this.telemetryNexus = new com.javastudio.analytics.TelemetryNexus(this, serverController, healthMonitor, metricsCollector, profiler, debugHudManager);

        this.placeholderApiBridge = new com.javastudio.bridge.PlaceholderApiBridge(this);
        this.placeholderApiBridge.init();

        this.autoMitigationEngine = new com.javastudio.analytics.AutoMitigationEngine(this, serverController);
        if (settingsManager.isAutoMitigationEnabled()) {
            this.autoMitigationEngine.start();
        }
        this.aiAssistant = new com.javastudio.ai.AiAssistant(this);
        this.autonomousWatchdog = new com.javastudio.ai.AutonomousWatchdogAgent(this, aiAssistant);
        if (kernelModuleManager.isModuleEnabled("ai-assistant")) {
            this.autonomousWatchdog.start();
        }
        this.packetSniffer = new com.javastudio.network.packet.PacketSnifferManager(this);
        if (settingsManager.isPacketSnifferEnabled() && kernelModuleManager.isModuleEnabled("packet-sniffer")) {
            this.packetSniffer.start();
        }
        this.bytecodePatchManager = new com.javastudio.control.BytecodePatchManager(this);

        this.licenseManager = new com.javastudio.export.LicenseManager(this);
        this.singlePortMuxEngine = new com.javastudio.network.SinglePortMuxEngine(this);
        if (this.singlePortMuxEngine.isConfiguredEnabled()) {
            this.singlePortMuxEngine.start();
        }
        this.resourcePackHost = new com.javastudio.pack.ResourcePackHost(this);
        if (settingsManager.isResourcePackEnabled() && kernelModuleManager.isModuleEnabled("resource-pack")) {
            this.resourcePackHost.start();
        }

        this.nettyPacketEngine = new com.javastudio.network.NettyPacketEngine(this);
        if ((settingsManager.isPacketSnifferEnabled() && kernelModuleManager.isModuleEnabled("packet-sniffer"))
                || (settingsManager.isAntiCheatEnabled() && kernelModuleManager.isModuleEnabled("anti-cheat"))) {
            this.nettyPacketEngine.start();
        }
        if (this.antiCheatManager != null && this.antiCheatManager.isActive()) {
            this.antiCheatManager.attachNettyGuard();
        }
        this.fakePlayerManager = new com.javastudio.bot.FakePlayerManager(this);
        this.heapInspector = new com.javastudio.inspector.HeapInspector(this);
        this.totpManager = new com.javastudio.security.TotpManager(this);
        this.remoteBridgeManager = new com.javastudio.bridge.RemoteBridgeManager(this);

        this.circuitBreakerEngine = new com.javastudio.pipeline.CircuitBreakerEngine();
        this.fastBlockEngine = new com.javastudio.pipeline.FastBlockEngine(this);
        this.botEngine = new com.javastudio.pipeline.JBotEngine(this);
        if (settingsManager.isVisualFlowEnabled()) {
            this.visualFlowEngine = new com.javastudio.pipeline.VisualFlowEngine(this);
        }
        this.hotSwapEngine = new com.javastudio.manager.HotSwapEngine(this);
        this.clusterMeshEngine = new com.javastudio.network.ClusterMeshEngine(this);
        if (settingsManager.isClusterMeshEnabled() && kernelModuleManager.isModuleEnabled("cluster-mesh")) {
            this.clusterMeshEngine.start();
        }
        this.clusterInventorySync = new com.javastudio.network.ClusterInventorySync(this);
        if (settingsManager.isInventorySyncEnabled() && kernelModuleManager.isModuleEnabled("cluster-mesh")) {
            getServer().getPluginManager().registerEvents(this.clusterInventorySync, this);
        }

        this.classTaintEngine = new com.javastudio.security.ClassTaintEngine(this);
        this.deadlockResolverEngine = new com.javastudio.ai.DeadlockResolverEngine(this);
        if (settingsManager.isDeadlockResolverEnabled() && kernelModuleManager.isModuleEnabled("deadlock-resolver")) {
            this.deadlockResolverEngine.start();
        }
        this.chunkPrefetcher = new com.javastudio.pipeline.AsyncChunkPrefetcher(this);
        if (settingsManager.isChunkPrefetcherEnabled() && kernelModuleManager.isModuleEnabled("chunk-prefetcher")) {
            this.chunkPrefetcher.start();
        }
        this.guiDesignerEngine = new com.javastudio.api.gui.GuiDesignerEngine(this);
        if (settingsManager.isGuiDesignerEnabled()) {
            getServer().getPluginManager().registerEvents(this.guiDesignerEngine, this);
        }

        if (settingsManager.isCircuitBreakerEnabled()) {
            com.javastudio.utils.PlatformUtil.runTask(this, () -> {
                int hooked = this.circuitBreakerEngine.instrumentPlugin(null);
                getLogger().info("[CircuitBreaker] Đã bảo vệ " + hooked + " Event Listeners trên toàn máy chủ.");
            });
        }

        this.panelCveProtection = new com.javastudio.security.cve.PanelCveProtectionSuite(this);
        if (settingsManager.isPanelCveDefenseEnabled()) {
            this.panelCveProtection.init();
        }

        this.fileWatcher = new com.javastudio.watcher.FileWatcherService(this);
        if (settingsManager.isFileWatcherEnabled()) {
            this.fileWatcher.start();
        } else {
            getLogger().info("[FileWatcher] Hot-Reload Watcher đang tắt trong cài đặt (modules.file-watcher: false)");
        }

        this.serverBenchmarkEngine = new com.javastudio.analytics.ServerBenchmarkEngine(this);

        registerCommands();

        if (settingsManager.isDynamicPluginsEnabled()) {
            autoLoadPlugins();
        }

        getLogger().info("[JavaStudio] Plugin v2.0 đã được kích hoạt thành công!");
        getLogger().info("[JavaStudio] Modules: EventPipeline, HealthMonitor, MetricsCollector, DependencyGraph, RoleManager");
    }

    @Override
    public void onDisable() {
        getLogger().info("[JavaStudio] Đang tắt các dynamic plugins và giải phóng tài nguyên...");

        if (fileWatcher != null) {
            fileWatcher.stop();
        }

        if (securitySandbox != null) {
            securitySandbox.shutdown();
        }

        if (pluginManager != null) {
            pluginManager.unloadAll();
        }

        if (displayEngine != null) {
            displayEngine.cleanupAll();
        }

        if (networkSyncManager != null) {
            networkSyncManager.stop();
        }

        if (redisSyncManager != null) {
            redisSyncManager.close();
        }

        if (proxyBridgeManager != null) {
            proxyBridgeManager.unregister();
        }

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

        if (placeholderApiBridge != null) {
            placeholderApiBridge.unregister();
        }

        if (autoMitigationEngine != null) {
            autoMitigationEngine.stop();
        }
        if (autonomousWatchdog != null) {
            autonomousWatchdog.stop();
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
        if (singlePortMuxEngine != null) {
            singlePortMuxEngine.stop();
        }
        if (nettyPacketEngine != null) {
            nettyPacketEngine.stop();
        }
        if (clusterMeshEngine != null) {
            clusterMeshEngine.stop();
        }
        if (deadlockResolverEngine != null) {
            deadlockResolverEngine.stop();
        }
        if (chunkPrefetcher != null) {
            chunkPrefetcher.stop();
        }
        if (resourcePackHost != null) {
            resourcePackHost.stop();
        }
        if (fastBlockEngine != null) {
            fastBlockEngine.shutdown();
        }
        if (healthMonitor != null) {
            healthMonitor.stop();
        }

        if (consoleLogBuffer != null) {
            consoleLogBuffer.detach();
        }

        if (commandManager != null) {
            commandManager.unregisterAll();
        }

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
        File modulesConfigFile = new File(dataDir, "modules.yml");
        if (!modulesConfigFile.exists()) {
            saveResource("modules.yml", false);
        }
    }

    private void registerCommands() {
        JExecCommand jExec = new JExecCommand(this);
        registerCmd("jexec", jExec, jExec);

        JPluginCommand jPlugin = new JPluginCommand(this);
        registerCmd("jplugin", jPlugin, jPlugin);

        JScriptCommand jScript = new JScriptCommand(this);
        registerCmd("jscript", jScript, jScript);

        JavaStudioCommand jStudio = new JavaStudioCommand(this);
        registerCmd("javastudio", jStudio, jStudio);

        com.javastudio.commands.JControlCommand jControl = new com.javastudio.commands.JControlCommand(this, pluginController, serverController);
        registerCmd("jcontrol", jControl, jControl);

        com.javastudio.commands.JNetworkCommand jNetwork = new com.javastudio.commands.JNetworkCommand(this);
        registerCmd("jnetwork", jNetwork, jNetwork);

        com.javastudio.commands.JExploitCommand jExploit = new com.javastudio.commands.JExploitCommand(this, exploitGuard);
        registerCmd("jexploit", jExploit, jExploit);

        com.javastudio.commands.JAntiCheatCommand jAc = new com.javastudio.commands.JAntiCheatCommand(this, antiCheatManager);
        registerCmd("jac", jAc, jAc);

        com.javastudio.commands.JHudCommand jHud = new com.javastudio.commands.JHudCommand(this, debugHudManager);
        registerCmd("jhud", jHud, jHud);

        com.javastudio.commands.JSpyCommand jSpy = new com.javastudio.commands.JSpyCommand(this, eventSpyManager);
        registerCmd("jspy", jSpy, jSpy);

        com.javastudio.commands.JSecurityCommand jSec = new com.javastudio.commands.JSecurityCommand(this, securitySentinel);
        registerCmd("jsecurity", jSec, jSec);

        com.javastudio.commands.JDiagCommand jDiag = new com.javastudio.commands.JDiagCommand(this, telemetryNexus);
        registerCmd("jdiag", jDiag, jDiag);

        com.javastudio.commands.JAiCommand jAi = new com.javastudio.commands.JAiCommand(this, aiAssistant);
        registerCmd("jai", jAi, jAi);

        com.javastudio.commands.JPacketCommand jPacket = new com.javastudio.commands.JPacketCommand(this, packetSniffer);
        registerCmd("jpacket", jPacket, jPacket);

        com.javastudio.commands.JPackCommand jPack = new com.javastudio.commands.JPackCommand(this, resourcePackHost);
        registerCmd("jpack", jPack, jPack);

        com.javastudio.commands.JLicenseCommand jLic = new com.javastudio.commands.JLicenseCommand(this, licenseManager);
        registerCmd("jlicense", jLic, jLic);

        com.javastudio.commands.JTraceCommand jTrace = new com.javastudio.commands.JTraceCommand(this.circuitBreakerEngine);
        registerCmd("jtrace", jTrace, jTrace);

        com.javastudio.commands.JChunkCommand jChunk = new com.javastudio.commands.JChunkCommand(this.fastBlockEngine);
        registerCmd("jchunk", jChunk, jChunk);

        com.javastudio.commands.JBotCommand jBot = new com.javastudio.commands.JBotCommand(this, this.botEngine, this.fakePlayerManager);
        registerCmd("jbot", jBot, jBot);

        com.javastudio.commands.JHeapCommand jHeap = new com.javastudio.commands.JHeapCommand(this, heapInspector);
        registerCmd("jheap", jHeap, jHeap);

        com.javastudio.commands.JBridgeCommand jBridge = new com.javastudio.commands.JBridgeCommand(this, remoteBridgeManager);
        registerCmd("jbridge", jBridge, jBridge);

        registerCmd("j2fa", jSec, jSec);

        registerCmd("jpkt", jPacket, jPacket);

        com.javastudio.commands.JReportCommand jRep = new com.javastudio.commands.JReportCommand(this, antiCheatManager);
        registerCmd("report", jRep, jRep);

        com.javastudio.commands.JMeshCommand jMesh = new com.javastudio.commands.JMeshCommand(this);
        registerCmd("jmesh", jMesh, jMesh);

        com.javastudio.commands.JFlowCommand jFlow = new com.javastudio.commands.JFlowCommand(this);
        registerCmd("jflow", jFlow, jFlow);

        com.javastudio.commands.JPrefetchCommand jPrefetch = new com.javastudio.commands.JPrefetchCommand(this, chunkPrefetcher);
        registerCmd("jprefetch", jPrefetch, jPrefetch);

        com.javastudio.commands.JGuiCommand jGui = new com.javastudio.commands.JGuiCommand(this, guiDesignerEngine);
        registerCmd("jgui", jGui, jGui);

        com.javastudio.commands.JGitCommand jGit = new com.javastudio.commands.JGitCommand(this, snapshotEngine);
        registerCmd("jgit", jGit, jGit);

        com.javastudio.commands.JMockCommand jMock = new com.javastudio.commands.JMockCommand(this, mockEventEngine);
        registerCmd("jmock", jMock, jMock);

        com.javastudio.commands.JDbCommand jDb = new com.javastudio.commands.JDbCommand(this);
        registerCmd("jdb", jDb, jDb);

        com.javastudio.commands.JBenchCommand jBench = new com.javastudio.commands.JBenchCommand(this, serverBenchmarkEngine);
        registerCmd("jbench", jBench, jBench);

        com.javastudio.commands.JStudioUniversalCommand jStudioUni = new com.javastudio.commands.JStudioUniversalCommand(this);
        registerCmd("js", jStudioUni, jStudioUni);
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

    public com.javastudio.ai.AutonomousWatchdogAgent getAutonomousWatchdog() {
        return autonomousWatchdog;
    }

    public com.javastudio.pipeline.VisualFlowEngine getVisualFlowEngine() {
        return visualFlowEngine;
    }

    public com.javastudio.network.ClusterMeshEngine getClusterMeshEngine() {
        return clusterMeshEngine;
    }

    public com.javastudio.manager.HotSwapEngine getHotSwapEngine() {
        return hotSwapEngine;
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

    public com.javastudio.network.SinglePortMuxEngine getSinglePortMuxEngine() {
        return singlePortMuxEngine;
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

    public com.javastudio.ai.DeadlockResolverEngine getDeadlockResolverEngine() {
        return deadlockResolverEngine;
    }

    public com.javastudio.pipeline.AsyncChunkPrefetcher getChunkPrefetcher() {
        return chunkPrefetcher;
    }

    public com.javastudio.security.ClassTaintEngine getClassTaintEngine() {
        return classTaintEngine;
    }

    public com.javastudio.api.gui.GuiDesignerEngine getGuiDesignerEngine() {
        return guiDesignerEngine;
    }

    public com.javastudio.control.KernelModuleManager getKernelModuleManager() {
        return kernelModuleManager;
    }

    public com.javastudio.manager.SnapshotEngine getSnapshotEngine() {
        return snapshotEngine;
    }

    public com.javastudio.pipeline.MockEventEngine getMockEventEngine() {
        return mockEventEngine;
    }

    public com.javastudio.network.ClusterInventorySync getClusterInventorySync() {
        return clusterInventorySync;
    }

    public com.javastudio.analytics.ServerBenchmarkEngine getServerBenchmarkEngine() {
        return serverBenchmarkEngine;
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

