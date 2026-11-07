package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SettingsManager {

    private final JavaStudioPlugin plugin;

    public enum ModuleKey {
        DIRECT_EXEC("direct-exec", "Thực thi Java trực tiếp (/jexec, /java)", "Trình biên dịch & chạy mã Java tức thì", true),
        DYNAMIC_PLUGINS("dynamic-plugins", "Dynamic Plugins Engine (/jplugin)", "Nạp, quản lý & cách ly dynamic plugin", true),
        SCRIPT_MANAGER("script-manager", "Script Manager (/jscript)", "Quản lý & chạy các file Java script độc lập", true),
        PLUGIN_CONTROLLER("plugin-controller", "Plugin Controller (/jpm)", "Kiểm soát, nạp/gỡ plugin bên ngoài như PlugManX", true),
        SERVER_MONITOR("server-monitor", "Server Monitor (/jserver)", "Theo dõi TPS, MSPT, RAM, CPU & dọn dẹp thực thể", true),
        FILE_WATCHER("file-watcher", "Hot-Reload File Watcher", "Theo dõi thay đổi file trên đĩa để tự động nạp", true),
        PLUGIN_EXPORTER("plugin-exporter", "Plugin Exporter (Export JAR)", "Biên dịch & xuất plugin ra file JAR độc lập", true),
        DISCORD_WEBHOOK("discord-webhook", "Discord Webhook Logging", "Gửi cảnh báo và nhật ký thực thi tới Discord", false),
        SECURITY_SANDBOX("security-sandbox", "Security Sandbox", "Hộp cát kiểm soát mã độc và chống vòng lặp treo server", true),
        MARKETPLACE("marketplace", "Chợ Plugin & Package Manager", "Kho lưu trữ và cài đặt dynamic plugin 1-click", true),
        SECURITY_SCANNER("security-scanner", "Máy quét mã độc (Security Scanner)", "Phát hiện backdoor, Force OP và RCE trong plugin", true),
        BYTECODE_INSPECTOR("bytecode-inspector", "Trình dịch ngược & mổ xẻ Bytecode", "Xem cấu trúc class và dịch ngược mã nguồn plugin khác", true),
        DISPLAY_ENGINE("display-engine", "Display Engine (Hologram, Scoreboard, BossBar)", "Hiển thị chữ nổi 3D, bảng điểm & thanh boss", true),
        INTERACTIVE_REPL("interactive-repl", "Chat REPL Interactive Mode", "Chế độ lập trình tương tác trực tiếp qua chat", true),
        NETWORK_SYNC("network-sync", "Đồng bộ cụm máy chủ (Network Sync)", "Kết nối và đồng bộ nhiều server Minecraft", false),
        PLUGIN_PROFILER("plugin-profiler", "Plugin Profiler & Lag Monitor", "Đo thời gian thực thi event và phát hiện lag spikes", true),
        REDIS_SYNC("redis-sync", "Đồng Bộ Redis Phân Tán", "Đồng bộ cụm máy chủ và chia sẻ dữ liệu qua Redis", false),
        EXPLOIT_FIXER("exploit-fixer", "Bảo Vệ Chống Exploit (ExploitFixer)", "Chặn packet crash, NBT quá tải, book crash, tọa độ vô cực & click lag", true),
        ANTI_CHEAT("anti-cheat", "Anti-Cheat (Vulcan/Grim/Matrix-style)", "Phát hiện Speed, Fly, KillAura, Reach, Scaffold, Timer, AutoClicker... có cấu hình chi tiết", true),
        AUTO_MITIGATION("auto-mitigation", "Ứng Cứu Quá Tải Tự Động (Auto-Mitigation)", "Tự động hạ nhiệt CPU, dọn thực thể và phục hồi khi server lag", false),
        PANEL_CVE_DEFENSE("panel-cve-defense", "Panel & Wings CVE Defense (Crafty & Pterodactyl)", "Phòng thủ chuyên sâu chống 10+ CVEs khai thác Crafty Controller & Pterodactyl Panel / Wings", true),
        AI_ASSISTANT("ai-assistant", "Trợ Lý Lập Trình AI Copilot (/jai)", "Tự động sinh mã nguồn dynamic plugin bằng AI & sửa lỗi", true),
        PACKET_SNIFFER("packet-sniffer", "Netty Packet Sniffer (/jpacket, /jpkt)", "Bắt, giám sát và can thiệp gói tin mạng cấp thấp", true),
        RESOURCE_PACK("resource-pack", "Quản Lý Resource Pack (/jpack)", "Máy chủ HTTP cấp phát Resource Pack & Custom Items/Audio", true),
        LICENSE_MANAGER("license-manager", "Bản Quyền & Thủy Vân (/jlicense)", "Quản lý giấy phép bản quyền HMAC-SHA256 & chống leak JAR", true),
        FAKE_PLAYERS("fake-players", "Giả Lập Người Chơi Ảo (/jbot)", "Bot mạng Netty/NMS kiểm thử tải & stress test server", true),
        HEAP_INSPECTOR("heap-inspector", "Live Heap Inspector (/jheap)", "Thanh tra cây đối tượng & sửa đổi RAM thời gian thực", true),
        REMOTE_BRIDGE("remote-bridge", "Cầu Nối Điều Hành Từ Xa (/jbridge)", "Cầu nối cứu hộ khẩn cấp & gửi cảnh báo tới Discord", true),
        TWO_FACTOR_AUTH("two-factor-auth", "Xác Thực 2 Bước TOTP (/j2fa)", "Khóa bảo mật 2FA Google Authenticator bằng bản đồ in-game", true),
        CIRCUIT_BREAKER("circuit-breaker", "Cầu Chì Circuit Breaker (/jtrace)", "Tự ngắt EventListener lỗi & Hộp đen ngoại lệ RAM", true),
        FAST_BLOCK("fast-block", "FastBlock & RAM Arena (/jchunk)", "Thao tác khối siêu tốc & nén snapshot đấu trường vào RAM", true),
        DEBUG_HUD("debug-hud", "Debug HUD Thời Gian Thực (/jhud)", "Thanh HUD theo dõi TPS, MSPT, RAM, Ping trên ActionBar/BossBar", true),
        EVENT_SPY("event-spy", "Interactive Event Spy (/jspy)", "Lắng nghe và gỡ lỗi Bukkit Event trực tiếp trong chat", true),
        TELEMETRY_NEXUS("telemetry-nexus", "Chẩn Đoán Telemetry Nexus (/jdiag)", "Hệ thống chẩn đoán hiệu năng tổng hợp & rò rỉ bộ nhớ", true),
        CLUSTER_MESH("cluster-mesh", "Cluster Mesh P2P Engine (/jmesh)", "Mạng cụm phân tán P2P & đồng thuận Raft thuần Java", true),
        DEADLOCK_RESOLVER("deadlock-resolver", "Autonomous Deadlock Resolver", "Phát hiện và tự động giải tỏa bế tắc luồng", true),
        CHUNK_PREFETCHER("chunk-prefetcher", "Async Chunk Prefetcher (/jprefetch)", "Nạp trước chunk bất đồng bộ theo vector di chuyển", true),
        GUI_DESIGNER("gui-designer", "Visual In-Game GUI Designer (/jgui)", "Thiết kế menu kéo thả trực quan và sinh mã Java", true),
        VISUAL_FLOW("visual-flow", "Visual Flow Engine (/jflow)", "Sơ đồ khối logic trực quan và nạp nóng", true),
        SNAPSHOT_ROLLBACK("snapshot-rollback", "Time-Travel Snapshots & Rollback (/jgit)", "Quản lý phiên bản mã nguồn và hoàn tác", true),
        MOCK_EVENTS("mock-events", "Mock Event Dispatcher (/jmock)", "Giả lập bắn sự kiện Bukkit in-game", true),
        DATABASE_QUERY("database-query", "Database Inspector & SQL Query (/jdb)", "Truy vấn và thanh tra SQLite DataStore", true),
        UNIVERSAL_COMMAND("universal-command", "Universal Command (/js)", "Giao diện dòng lệnh tập trung", true),
        WEB_IDE("webide", "Monaco Web IDE (:8085)", "Trình soạn thảo mã nguồn VS Code trên nền Web", true),
        GAS_METER("gas-meter", "Bytecode Gas Metering", "Ngắt vòng lặp vô tận và đệ quy sâu an toàn", true),
        HOTSWAP_ENGINE("hotswap-engine", "HotSwap Bytecode Patcher", "Vá bytecode phương thức trực tiếp trong RAM", true),
        INVENTORY_SYNC("inventory-sync", "Đồng Bộ Kho Đồ Cụm (/jmesh sync)", "Đồng bộ túi đồ, giáp, rương không dupe đồ giữa các cụm server", true),
        SERVER_BENCHMARK("server-benchmark", "Kiểm Thử Sức Mạnh Phần Cứng (/jbench)", "Đo lường năng lực phần cứng, CPU, RAM, NVMe và chấm điểm server", true);

        private final String key;
        private final String displayName;
        private final String description;
        private final boolean defaultEnabled;

        ModuleKey(String key, String displayName, String description, boolean defaultEnabled) {
            this.key = key;
            this.displayName = displayName;
            this.description = description;
            this.defaultEnabled = defaultEnabled;
        }

        public String getKey() {
            return key;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public boolean isDefaultEnabled() {
            return defaultEnabled;
        }

        public static ModuleKey fromKey(String text) {
            if (text == null) return null;
            String clean = text.trim().toLowerCase().replace("_", "-");
            if (clean.equals("ai-copilot") || clean.equals("copilot")) return AI_ASSISTANT;
            if (clean.equals("exploitguard") || clean.equals("exploit-guard")) return EXPLOIT_FIXER;
            String noHyphen = clean.replace("-", "");
            for (ModuleKey m : values()) {
                if (m.key.equalsIgnoreCase(clean)
                        || m.name().equalsIgnoreCase(clean.replace("-", "_"))
                        || m.key.replace("-", "").equalsIgnoreCase(noHyphen)) {
                    return m;
                }
            }
            return null;
        }
    }

    private final Map<ModuleKey, Boolean> moduleStates = new ConcurrentHashMap<>();

    public SettingsManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        loadSettings();
    }

    public final synchronized void loadSettings() {
        FileConfiguration config = plugin.getConfig();
        moduleStates.clear();

        for (ModuleKey mod : ModuleKey.values()) {
            boolean enabled = config.getBoolean("modules." + mod.getKey(), mod.isDefaultEnabled());

            if (mod == ModuleKey.AUTO_MITIGATION && config.contains("auto-mitigation.enabled")) {
                enabled = config.getBoolean("auto-mitigation.enabled", false);
            } else if (mod == ModuleKey.FILE_WATCHER && config.contains("watcher.enabled")) {
                enabled = enabled && config.getBoolean("watcher.enabled", true);
            } else if (mod == ModuleKey.SECURITY_SANDBOX && config.contains("security.sandbox.enabled")) {
                enabled = enabled && config.getBoolean("security.sandbox.enabled", true);
            } else if (mod == ModuleKey.DISCORD_WEBHOOK && config.contains("discord.webhook-url")) {
                String url = config.getString("discord.webhook-url", "");
                if (url == null || url.trim().isEmpty()) {
                    enabled = false;
                }
            } else if (mod == ModuleKey.AI_ASSISTANT && config.contains("ai-assistant.enabled")) {
                enabled = enabled && config.getBoolean("ai-assistant.enabled", true);
            } else if (mod == ModuleKey.PACKET_SNIFFER && config.contains("packet-sniffer.enabled")) {
                enabled = enabled && config.getBoolean("packet-sniffer.enabled", true);
            } else if (mod == ModuleKey.RESOURCE_PACK && config.contains("resource-pack.enabled")) {
                enabled = enabled && config.getBoolean("resource-pack.enabled", true);
            } else if (mod == ModuleKey.LICENSE_MANAGER && config.contains("licensing.enabled")) {
                enabled = enabled && config.getBoolean("licensing.enabled", true);
            } else if (mod == ModuleKey.FAKE_PLAYERS && config.contains("fake-players.enabled")) {
                enabled = enabled && config.getBoolean("fake-players.enabled", true);
            } else if (mod == ModuleKey.HEAP_INSPECTOR && config.contains("heap-inspector.enabled")) {
                enabled = enabled && config.getBoolean("heap-inspector.enabled", true);
            } else if (mod == ModuleKey.REMOTE_BRIDGE && config.contains("remote-bridge.enabled")) {
                enabled = enabled && config.getBoolean("remote-bridge.enabled", true);
            } else if (mod == ModuleKey.TWO_FACTOR_AUTH && config.contains("two-factor-auth.enabled")) {
                enabled = enabled && config.getBoolean("two-factor-auth.enabled", true);
            } else if (mod == ModuleKey.CIRCUIT_BREAKER && config.contains("circuit-breaker.enabled")) {
                enabled = enabled && config.getBoolean("circuit-breaker.enabled", true);
            } else if (mod == ModuleKey.FAST_BLOCK && config.contains("fast-block.enabled")) {
                enabled = enabled && config.getBoolean("fast-block.enabled", true);
            } else if (mod == ModuleKey.DEBUG_HUD && config.contains("debug-hud.enabled")) {
                enabled = enabled && config.getBoolean("debug-hud.enabled", true);
            } else if (mod == ModuleKey.EVENT_SPY && config.contains("event-spy.enabled")) {
                enabled = enabled && config.getBoolean("event-spy.enabled", true);
            } else if (mod == ModuleKey.TELEMETRY_NEXUS && config.contains("telemetry-nexus.enabled")) {
                enabled = enabled && config.getBoolean("telemetry-nexus.enabled", true);
            }

            moduleStates.put(mod, enabled);
        }
    }

    public boolean isEnabled(ModuleKey module) {
        return moduleStates.getOrDefault(module, module.isDefaultEnabled());
    }

    public boolean isEnabled(String moduleKeyStr) {
        ModuleKey mod = ModuleKey.fromKey(moduleKeyStr);
        return mod == null || isEnabled(mod);
    }

    public boolean isDirectExecEnabled() { return isEnabled(ModuleKey.DIRECT_EXEC); }
    public boolean isDynamicPluginsEnabled() { return isEnabled(ModuleKey.DYNAMIC_PLUGINS); }
    public boolean isScriptManagerEnabled() { return isEnabled(ModuleKey.SCRIPT_MANAGER); }
    public boolean isPluginControllerEnabled() { return isEnabled(ModuleKey.PLUGIN_CONTROLLER); }
    public boolean isServerMonitorEnabled() { return isEnabled(ModuleKey.SERVER_MONITOR); }
    public boolean isFileWatcherEnabled() { return isEnabled(ModuleKey.FILE_WATCHER); }
    public boolean isPluginExporterEnabled() { return isEnabled(ModuleKey.PLUGIN_EXPORTER); }
    public boolean isDiscordWebhookEnabled() { return isEnabled(ModuleKey.DISCORD_WEBHOOK); }
    public boolean isSecuritySandboxEnabled() { return isEnabled(ModuleKey.SECURITY_SANDBOX); }
    public boolean isMarketplaceEnabled() { return isEnabled(ModuleKey.MARKETPLACE); }
    public boolean isSecurityScannerEnabled() { return isEnabled(ModuleKey.SECURITY_SCANNER); }
    public boolean isBytecodeInspectorEnabled() { return isEnabled(ModuleKey.BYTECODE_INSPECTOR); }
    public boolean isDisplayEngineEnabled() { return isEnabled(ModuleKey.DISPLAY_ENGINE); }
    public boolean isInteractiveReplEnabled() { return isEnabled(ModuleKey.INTERACTIVE_REPL); }
    public boolean isNetworkSyncEnabled() { return isEnabled(ModuleKey.NETWORK_SYNC); }
    public boolean isExploitFixerEnabled() { return isEnabled(ModuleKey.EXPLOIT_FIXER); }
    public boolean isExploitGuardEnabled() { return isEnabled(ModuleKey.EXPLOIT_FIXER); }
    public boolean isAntiCheatEnabled() { return isEnabled(ModuleKey.ANTI_CHEAT); }
    public boolean isPanelCveDefenseEnabled() { return isEnabled(ModuleKey.PANEL_CVE_DEFENSE); }
    public boolean isAutoMitigationEnabled() { return isEnabled(ModuleKey.AUTO_MITIGATION); }

    public boolean isAiAssistantEnabled() { return isEnabled(ModuleKey.AI_ASSISTANT); }
    public boolean isPacketSnifferEnabled() { return isEnabled(ModuleKey.PACKET_SNIFFER); }
    public boolean isResourcePackEnabled() { return isEnabled(ModuleKey.RESOURCE_PACK); }
    public boolean isLicenseManagerEnabled() { return isEnabled(ModuleKey.LICENSE_MANAGER); }
    public boolean isFakePlayersEnabled() { return isEnabled(ModuleKey.FAKE_PLAYERS); }
    public boolean isHeapInspectorEnabled() { return isEnabled(ModuleKey.HEAP_INSPECTOR); }
    public boolean isRemoteBridgeEnabled() { return isEnabled(ModuleKey.REMOTE_BRIDGE); }
    public boolean isTwoFactorAuthEnabled() { return isEnabled(ModuleKey.TWO_FACTOR_AUTH); }
    public boolean isCircuitBreakerEnabled() { return isEnabled(ModuleKey.CIRCUIT_BREAKER); }
    public boolean isFastBlockEnabled() { return isEnabled(ModuleKey.FAST_BLOCK); }
    public boolean isDebugHudEnabled() { return isEnabled(ModuleKey.DEBUG_HUD); }
    public boolean isEventSpyEnabled() { return isEnabled(ModuleKey.EVENT_SPY); }
    public boolean isTelemetryNexusEnabled() { return isEnabled(ModuleKey.TELEMETRY_NEXUS); }
    public boolean isClusterMeshEnabled() { return isEnabled(ModuleKey.CLUSTER_MESH); }
    public boolean isDeadlockResolverEnabled() { return isEnabled(ModuleKey.DEADLOCK_RESOLVER); }
    public boolean isChunkPrefetcherEnabled() { return isEnabled(ModuleKey.CHUNK_PREFETCHER); }
    public boolean isGuiDesignerEnabled() { return isEnabled(ModuleKey.GUI_DESIGNER); }
    public boolean isVisualFlowEnabled() { return isEnabled(ModuleKey.VISUAL_FLOW); }
    public boolean isSnapshotRollbackEnabled() { return isEnabled(ModuleKey.SNAPSHOT_ROLLBACK); }
    public boolean isMockEventsEnabled() { return isEnabled(ModuleKey.MOCK_EVENTS); }
    public boolean isDatabaseQueryEnabled() { return isEnabled(ModuleKey.DATABASE_QUERY); }
    public boolean isUniversalCommandEnabled() { return isEnabled(ModuleKey.UNIVERSAL_COMMAND); }
    public boolean isWebIdeEnabled() { return isEnabled(ModuleKey.WEB_IDE); }
    public boolean isGasMeterEnabled() { return isEnabled(ModuleKey.GAS_METER); }
    public boolean isHotSwapEngineEnabled() { return isEnabled(ModuleKey.HOTSWAP_ENGINE); }
    public boolean isInventorySyncEnabled() { return isEnabled(ModuleKey.INVENTORY_SYNC); }
    public boolean isServerBenchmarkEnabled() { return isEnabled(ModuleKey.SERVER_BENCHMARK); }

    public synchronized boolean setModuleEnabled(ModuleKey module, boolean enable) {
        moduleStates.put(module, enable);
        FileConfiguration config = plugin.getConfig();
        config.set("modules." + module.getKey(), enable);

        if (module == ModuleKey.AUTO_MITIGATION) {
            config.set("auto-mitigation.enabled", enable);
            if (plugin.getAutoMitigationEngine() != null) {
                plugin.getAutoMitigationEngine().setEnabled(enable);
            }
        } else if (module == ModuleKey.FILE_WATCHER) {
            config.set("watcher.enabled", enable);
            if (enable) {
                plugin.getFileWatcher().start();
            } else {
                plugin.getFileWatcher().stop();
            }
        } else if (module == ModuleKey.DYNAMIC_PLUGINS && !enable) {
            plugin.getPluginManager().unloadAll();
        } else if (module == ModuleKey.DISPLAY_ENGINE && !enable) {
            if (plugin.getDisplayEngine() != null) {
                plugin.getDisplayEngine().cleanupAll();
            }
        } else if (module == ModuleKey.NETWORK_SYNC && !enable) {
            if (plugin.getNetworkSyncManager() != null) {
                plugin.getNetworkSyncManager().stop();
            }
        } else if (module == ModuleKey.EXPLOIT_FIXER) {
            if (plugin.getExploitGuard() != null) {
                plugin.getExploitGuard().setEnabled(enable);
            }
        } else if (module == ModuleKey.ANTI_CHEAT) {
            if (plugin.getAntiCheatManager() != null) {
                plugin.getAntiCheatManager().setModuleEnabled(enable);
            }
        } else if (module == ModuleKey.PACKET_SNIFFER) {
            config.set("packet-sniffer.enabled", enable);
            if (!enable) {
                if (plugin.getPacketSniffer() != null) plugin.getPacketSniffer().stop();
                if (plugin.getNettyPacketEngine() != null) plugin.getNettyPacketEngine().stop();
            } else {
                if (plugin.getPacketSniffer() != null) plugin.getPacketSniffer().start();
                if (plugin.getNettyPacketEngine() != null) plugin.getNettyPacketEngine().start();
            }
        } else if (module == ModuleKey.DEBUG_HUD) {
            config.set("debug-hud.enabled", enable);
            if (!enable && plugin.getDebugHudManager() != null) {
                plugin.getDebugHudManager().cleanupAll();
            }
        } else if (module == ModuleKey.EVENT_SPY) {
            config.set("event-spy.enabled", enable);
            if (!enable && plugin.getEventSpyManager() != null) {
                plugin.getEventSpyManager().cleanup();
            }
        } else if (module == ModuleKey.FAKE_PLAYERS) {
            config.set("fake-players.enabled", enable);
            if (!enable) {
                if (plugin.getBotEngine() != null) plugin.getBotEngine().shutdown();
                if (plugin.getFakePlayerManager() != null) plugin.getFakePlayerManager().shutdown();
            }
        } else if (module == ModuleKey.CIRCUIT_BREAKER) {
            config.set("circuit-breaker.enabled", enable);
        } else if (module == ModuleKey.PANEL_CVE_DEFENSE) {
            config.set("cve-defense.enabled", enable);
            if (!enable && plugin.getPanelCveProtection() != null) {
                plugin.getPanelCveProtection().shutdown();
            } else if (enable && plugin.getPanelCveProtection() != null) {
                plugin.getPanelCveProtection().init();
            }
        } else if (module == ModuleKey.CLUSTER_MESH) {
            if (!enable && plugin.getClusterMeshEngine() != null) {
                plugin.getClusterMeshEngine().stop();
            } else if (enable && plugin.getClusterMeshEngine() != null) {
                plugin.getClusterMeshEngine().start();
            }
        } else if (module == ModuleKey.DEADLOCK_RESOLVER) {
            if (!enable && plugin.getDeadlockResolverEngine() != null) {
                plugin.getDeadlockResolverEngine().stop();
            } else if (enable && plugin.getDeadlockResolverEngine() != null) {
                plugin.getDeadlockResolverEngine().start();
            }
        } else if (module == ModuleKey.CHUNK_PREFETCHER) {
            if (!enable && plugin.getChunkPrefetcher() != null) {
                plugin.getChunkPrefetcher().stop();
            } else if (enable && plugin.getChunkPrefetcher() != null) {
                plugin.getChunkPrefetcher().start();
            }
        } else if (module == ModuleKey.RESOURCE_PACK) {
            config.set("resource-pack.enabled", enable);
            if (!enable && plugin.getResourcePackHost() != null) {
                plugin.getResourcePackHost().stop();
            } else if (enable && plugin.getResourcePackHost() != null) {
                plugin.getResourcePackHost().start();
            }
        } else if (module == ModuleKey.GAS_METER) {
            com.javastudio.security.LoopWatchdog.setEnabled(enable);
            com.javastudio.pipeline.LoopGuardTransformer.setEnabled(enable);
        } else if (module == ModuleKey.WEB_IDE) {
            if (plugin.getAntiCheatManager() != null && plugin.getAntiCheatManager().getWebDashboard() != null) {
                try {
                    if (!enable) {
                        plugin.getAntiCheatManager().getWebDashboard().stop();
                    } else {
                        plugin.getAntiCheatManager().getWebDashboard().start();
                    }
                } catch (Throwable ignored) {}
            }
        }

        plugin.saveConfig();
        return enable;
    }

    public synchronized boolean toggleModule(ModuleKey module) {
        boolean current = isEnabled(module);
        return setModuleEnabled(module, !current);
    }

    public Map<ModuleKey, Boolean> getAllModules() {
        return Collections.unmodifiableMap(moduleStates);
    }

    public String getResourceSavings(ModuleKey module) {
        return switch (module) {
            case AUTO_MITIGATION -> "Tắt giám sát quá tải tự động; không hạ tầm nhìn thế giới hay dọn thực thể khi lag.";
            case FILE_WATCHER -> "Tắt thread WatchService chạy nền, giảm 100% I/O ổ đĩa khi lập trình.";
            case SERVER_MONITOR -> "Không lấy mẫu TPS/MSPT định kỳ, giải phóng tác vụ tính toán Main Thread.";
            case PLUGIN_CONTROLLER -> "Không can thiệp vào ClassLoader hoặc danh sách plugin ngoài của server.";
            case DIRECT_EXEC -> "Chặn gọi trình biên dịch mã Java trực tiếp qua lệnh in-game.";
            case DYNAMIC_PLUGINS -> "Không khởi tạo DynamicClassLoader, tiết kiệm bộ nhớ Metaspace/RAM.";
            case SCRIPT_MANAGER -> "Không quét và nạp file script trong thư mục scripts/.";
            case PLUGIN_EXPORTER -> "Không nạp công cụ đóng gói JAR độc lập.";
            case DISCORD_WEBHOOK -> "Không gửi HTTP Request ra ngoài Internet, tiết kiệm băng thông.";
            case SECURITY_SANDBOX -> "Bỏ qua luồng Executor kiểm soát thời gian để chạy trực tiếp.";
            case MARKETPLACE -> "Không tải hoặc phân tích danh mục package từ kho marketplace.";
            case SECURITY_SCANNER -> "Không nạp công cụ phân tích bytecode rà soát mã độc.";
            case BYTECODE_INSPECTOR -> "Không nạp bộ dịch ngược class bytecode khi phân tích plugin ngoài.";
            case DISPLAY_ENGINE -> "Xóa toàn bộ Hologram và giải phóng bộ nhớ Scoreboard/BossBar.";
            case INTERACTIVE_REPL -> "Không lắng nghe sự kiện chat để thông dịch dòng lệnh REPL.";
            case NETWORK_SYNC -> "Đóng toàn bộ cổng Socket kết nối liên cụm server, tiết kiệm mạng.";
            case PLUGIN_PROFILER -> "Dừng theo dõi latency và không tính toán thời gian nanosecond trên event.";
            case REDIS_SYNC -> "Đóng kết nối socket Redis và dừng luồng lắng nghe Subscriber ngầm.";
            case EXPLOIT_FIXER -> "Không kích hoạt bộ lọc gói tin và không kiểm tra NBT/Tọa độ di chuyển.";
            case ANTI_CHEAT -> "Bỏ qua toàn bộ kiểm tra di chuyển/chiến đấu/xây dựng, giảm tải CPU Main Thread ở sự kiện PlayerMove.";
            case PANEL_CVE_DEFENSE -> "Không lọc gói tin MOTD ping, không chạy bẫy Honeypot LFI và không quét Session Zombie.";
            case AI_ASSISTANT -> "Không gọi API OpenAI/Claude/Ollama ra Internet, tiết kiệm tài nguyên mạng.";
            case PACKET_SNIFFER -> "Gỡ bỏ Netty Pipeline Handler, giải phóng 100% chi phí phân tích gói tin mạng.";
            case RESOURCE_PACK -> "Không mở HTTP Server cổng 8088 và không giữ file ZIP bộ nhớ đệm trong RAM.";
            case LICENSE_MANAGER -> "Không chạy thuật toán băm HMAC-SHA256 kiểm tra giấy phép bản quyền.";
            case FAKE_PLAYERS -> "Xóa bỏ toàn bộ bot ảo và dừng các tác vụ giả lập mạng/di chuyển bot.";
            case HEAP_INSPECTOR -> "Không duyệt cây đối tượng RAM và khóa tính năng can thiệp biến live.";
            case REMOTE_BRIDGE -> "Dừng các tác vụ chuyển tiếp cảnh báo sự cố từ xa và webhook Discord.";
            case TWO_FACTOR_AUTH -> "Không yêu cầu xác thực 2FA Google Authenticator cho các thao tác nhạy cảm.";
            case CIRCUIT_BREAKER -> "Không bọc EventExecutor của Bukkit, tắt cơ chế cúp cầu chì và hộp đen ngoại lệ.";
            case FAST_BLOCK -> "Giải phóng toàn bộ bộ nhớ RAM lưu trữ snapshot đấu trường và không nén khối.";
            case DEBUG_HUD -> "Dừng tác vụ cập nhật định kỳ trên ActionBar và BossBar của người chơi.";
            case EVENT_SPY -> "Hủy toàn bộ Listener lắng nghe sự kiện Bukkit đang gỡ lỗi trong game.";
            case TELEMETRY_NEXUS -> "Dừng tác vụ lấy mẫu thống kê TPS, MSPT, rò rỉ bộ nhớ Metaspace định kỳ.";
            case CLUSTER_MESH -> "Đóng toàn bộ cổng UDP multicast và socket TCP peer-to-peer trong mạng cục bộ.";
            case DEADLOCK_RESOLVER -> "Dừng tác vụ quét chu trình khóa ThreadMXBean định kỳ trên JVM.";
            case CHUNK_PREFETCHER -> "Dừng theo dõi vector vận tốc người chơi và không gọi async chunk loading.";
            case GUI_DESIGNER -> "Tắt trình thiết kế menu rương và hủy sự kiện click kéo thả in-game.";
            case VISUAL_FLOW -> "Không nạp hoặc biên dịch các kịch bản sơ đồ khối logic từ thư mục flows/.";
            case SNAPSHOT_ROLLBACK -> "Không tự động tạo bản sao lưu snapshot mã nguồn.";
            case MOCK_EVENTS -> "Vô hiệu hóa bộ tạo và bắn sự kiện Bukkit giả lập.";
            case DATABASE_QUERY -> "Khóa tính năng thực thi câu lệnh SQL trực tiếp trên database của plugin.";
            case UNIVERSAL_COMMAND -> "Vô hiệu hóa lệnh gom tập trung /js.";
            case WEB_IDE -> "Đóng máy chủ HTTP cổng 8085, không phục vụ trình soạn thảo Web Monaco.";
            case GAS_METER -> "Bỏ qua bước tiêm lệnh đếm nhịp gas vào mã nguồn khi biên dịch.";
            case HOTSWAP_ENGINE -> "Tắt cơ chế vá bytecode nóng trong RAM qua JVM Instrumentation.";
            case INVENTORY_SYNC -> "Không đồng bộ kho đồ người chơi qua mạng P2P, chỉ lưu cục bộ trên máy chủ.";
            case SERVER_BENCHMARK -> "Không chạy các bài đo lường năng lực phần cứng và stress test máy chủ.";
        };
    }
}
