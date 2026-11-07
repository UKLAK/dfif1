package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class AntiCheatManager {

    private static final String NETTY_GUARD_ID = "jac_packet_shield";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static final class MovePacketFields {
        final Field[] doubles;
        final Field[] floats;

        MovePacketFields(Field[] doubles, Field[] floats) {
            this.doubles = doubles;
            this.floats = floats;
        }
    }

    private final JavaStudioPlugin plugin;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final Map<Class<?>, MovePacketFields> movePacketFieldCache = new ConcurrentHashMap<>();
    private final CheckSettings[] settings = new CheckSettings[CheckType.values().length];
    private final AtomicLong[] flagCounters = new AtomicLong[CheckType.values().length];
    private final AtomicLong totalFlags = new AtomicLong();
    private final AtomicLong totalPunishments = new AtomicLong();
    private final AtomicLong nettyDroppedPackets = new AtomicLong();
    private final Map<UUID, Double> pendingFall = new ConcurrentHashMap<>();
    private final ArrayDeque<String> recentLog = new ArrayDeque<>();
    private final Object fileLock = new Object();
    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean moduleEnabled = true;
    private volatile boolean configEnabled = true;

    private String bypassPermission = "javastudio.ac.bypass";
    private String alertPermission = "javastudio.ac.alerts";
    private boolean exemptOps = true;
    private Set<String> disabledWorlds = new HashSet<>();
    private boolean alertsEnabled = true;
    private double alertMinVl = 1.0;
    private long alertThrottleMs = 1500;
    private String alertFormat = "&8[&c&lAC-V2&8] &c[!] &f%player% &7vượt mốc Pro: &e%check% &8(&f%detail%&8) &cVL %vl%/%max% &8[&b%ping%ms&8]";
    private boolean alertConsole = true;
    private boolean alertDiscord = false;
    private double discordMinVl = 5.0;
    private long decayIntervalTicks = 600;
    private double decayAmount = 1.0;
    private boolean resetOnPunish = true;
    private List<String> defaultCommands = new ArrayList<>();
    private String kickMessage = "&c[AntiCheat] Bạn bị kick do nghi ngờ gian lận (%check%)";
    private long punishCooldownMs = 5000;
    private double minTps = 18.0;
    private int maxPing = 700;
    private long joinGraceMs = 3000;
    private long teleportGraceMs = 1200;
    private long velocityGraceMs = 1800;
    private long lagSpikeMs = 150;
    private long lagGraceMs = 2000;
    private int logMemoryLines = 300;
    private boolean logToFile = true;
    private boolean debugConsole = false;

    private volatile long lagUntil;
    private volatile long lastGlobalSoundMs;
    private volatile double tps = 20.0;
    private long lastTickNano;
    private int tickCounter;
    private PlatformUtil.TaskHandle monitorTask;
    private PlatformUtil.TaskHandle decayTask;
    private final ReplayManager replayManager;
    private final AuraBotManager auraBotManager;
    private volatile WebDashboardService webDashboard;
    private final VehicleCheck vehicleCheck;
    private final CombatAnomalyEngine anomalyEngine;
    private final XrayHoneypotManager xrayManager;
    private final BanWaveManager banWaveManager;
    private final InventoryFsmCheck inventoryFsmCheck;
    private final PhysicsSimulationCheck physicsCheck;
    private final BehaviorClusterEngine clusterEngine;
    private final ReportManager reportManager;
    private final BacktrackEngine backtrackEngine;
    private final NeuralHeuristicEngine neuralHeuristicEngine;

    public AntiCheatManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.replayManager = new ReplayManager(plugin);
        this.auraBotManager = new AuraBotManager(plugin, this);
        this.vehicleCheck = new VehicleCheck(this);
        this.anomalyEngine = new CombatAnomalyEngine(this);
        this.xrayManager = new XrayHoneypotManager(plugin, this);
        this.banWaveManager = new BanWaveManager(plugin, this);
        this.inventoryFsmCheck = new InventoryFsmCheck(this);
        this.physicsCheck = new PhysicsSimulationCheck(this);
        this.clusterEngine = new BehaviorClusterEngine(this);
        this.reportManager = new ReportManager(plugin, this);
        this.backtrackEngine = new BacktrackEngine(plugin, this);
        this.neuralHeuristicEngine = new NeuralHeuristicEngine(this);
        for (int i = 0; i < flagCounters.length; i++) {
            flagCounters[i] = new AtomicLong();
        }
        this.moduleEnabled = plugin.getSettingsManager().isAntiCheatEnabled();
        loadConfig();
    }

    public JavaStudioPlugin getPlugin() {
        return plugin;
    }

    public ReplayManager getReplayManager() {
        return replayManager;
    }

    public AuraBotManager getAuraBotManager() {
        return auraBotManager;
    }

    public WebDashboardService getWebDashboard() {
        return webDashboard;
    }

    public void setWebDashboard(WebDashboardService service) {
        WebDashboardService prev = this.webDashboard;
        if (prev != null && prev != service) {
            try {
                prev.stop();
            } catch (Throwable ignored) {}
        }
        this.webDashboard = service;
    }

    public VehicleCheck getVehicleCheck() {
        return vehicleCheck;
    }

    public CombatAnomalyEngine getAnomalyEngine() {
        return anomalyEngine;
    }

    public XrayHoneypotManager getXrayManager() {
        return xrayManager;
    }

    public BanWaveManager getBanWaveManager() {
        return banWaveManager;
    }

    public InventoryFsmCheck getInventoryFsmCheck() {
        return inventoryFsmCheck;
    }

    public PhysicsSimulationCheck getPhysicsCheck() {
        return physicsCheck;
    }

    public BehaviorClusterEngine getClusterEngine() {
        return clusterEngine;
    }

    public ReportManager getReportManager() {
        return reportManager;
    }

    public BacktrackEngine getBacktrackEngine() {
        return backtrackEngine;
    }

    public NeuralHeuristicEngine getNeuralHeuristicEngine() {
        return neuralHeuristicEngine;
    }

    public long getNettyDroppedPackets() {
        return nettyDroppedPackets.get();
    }

    public synchronized void loadConfig() {
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection root = cfg.getConfigurationSection("anticheat");

        configEnabled = cfg.getBoolean("anticheat.enabled", true);
        bypassPermission = cfg.getString("anticheat.bypass-permission", "javastudio.ac.bypass");
        exemptOps = cfg.getBoolean("anticheat.exempt-ops", true);
        disabledWorlds = new HashSet<>();
        for (String w : cfg.getStringList("anticheat.disabled-worlds")) {
            disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        }

        alertsEnabled = cfg.getBoolean("anticheat.alerts.enabled", true);
        alertPermission = cfg.getString("anticheat.alerts.permission", "javastudio.ac.alerts");
        alertMinVl = cfg.getDouble("anticheat.alerts.min-vl", 1.0);
        alertThrottleMs = cfg.getLong("anticheat.alerts.throttle-ms", 1500L);
        alertFormat = cfg.getString("anticheat.alerts.format", alertFormat);
        alertConsole = cfg.getBoolean("anticheat.alerts.console", true);
        alertDiscord = cfg.getBoolean("anticheat.alerts.discord", false);
        discordMinVl = cfg.getDouble("anticheat.alerts.discord-min-vl", 5.0);

        long decaySec = Math.max(5L, cfg.getLong("anticheat.violations.decay-seconds", 30L));
        decayIntervalTicks = decaySec * 20L;
        decayAmount = cfg.getDouble("anticheat.violations.decay-amount", 1.0);
        resetOnPunish = cfg.getBoolean("anticheat.violations.reset-on-punish", true);

        defaultCommands = new ArrayList<>(cfg.getStringList("anticheat.punishments.default-commands"));
        kickMessage = cfg.getString("anticheat.punishments.kick-message", kickMessage);
        punishCooldownMs = cfg.getLong("anticheat.punishments.cooldown-ms", 5000L);

        minTps = cfg.getDouble("anticheat.exemptions.min-tps", 18.0);
        maxPing = cfg.getInt("anticheat.exemptions.max-ping", 700);
        joinGraceMs = cfg.getLong("anticheat.exemptions.join-grace-ms", 3000L);
        teleportGraceMs = cfg.getLong("anticheat.exemptions.teleport-grace-ms", 1200L);
        velocityGraceMs = cfg.getLong("anticheat.exemptions.velocity-grace-ms", 1800L);
        lagSpikeMs = cfg.getLong("anticheat.exemptions.lag-spike-ms", 150L);
        lagGraceMs = cfg.getLong("anticheat.exemptions.lag-grace-ms", 2000L);

        logMemoryLines = Math.max(20, cfg.getInt("anticheat.logging.memory-lines", 300));
        logToFile = cfg.getBoolean("anticheat.logging.file", true);
        debugConsole = cfg.getBoolean("anticheat.logging.debug-console", false);

        for (CheckType t : CheckType.values()) {
            ConfigurationSection sec = root == null ? null : root.getConfigurationSection("checks." + t.getKey());
            settings[t.ordinal()] = new CheckSettings(t, sec);
        }

        if (auraBotManager != null) {
            auraBotManager.loadConfig();
        }
        if (webDashboard != null) {
            try {
                webDashboard.loadConfig();
            } catch (Throwable ignored) {}
        }
        if (xrayManager != null) {
            xrayManager.loadConfig();
        }
        if (banWaveManager != null) {
            banWaveManager.loadConfig();
        }
    }

    public void start() {
        stop();
        lastTickNano = System.nanoTime();
        monitorTask = PlatformUtil.runTaskTimer(plugin, this::monitorTick, 1L, 1L);
        decayTask = PlatformUtil.runTaskTimer(plugin, this::decayTick, decayIntervalTicks, decayIntervalTicks);
        if (webDashboard != null) {
            try {
                webDashboard.start();
            } catch (Throwable ignored) {}
        }
        if (banWaveManager != null) {
            banWaveManager.start();
        }
        if (backtrackEngine != null) {
            backtrackEngine.start();
        }
        attachNettyGuard();
    }

    public void stop() {
        detachNettyGuard();
        if (backtrackEngine != null) {
            backtrackEngine.stop();
        }
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
        if (auraBotManager != null) {
            auraBotManager.despawnAll();
        }
        if (webDashboard != null) {
            try {
                webDashboard.stop();
            } catch (Throwable ignored) {}
        }
        if (banWaveManager != null) {
            banWaveManager.stop();
        }
        if (xrayManager != null) {
            xrayManager.cleanupAll();
        }
        if (replayManager != null) {
            replayManager.stopPlayback();
        }
        flushLogsAsync();
    }

    public void shutdown() {
        stop();
        if (replayManager != null) {
            replayManager.shutdown();
        }
        players.clear();
    }

    public void attachNettyGuard() {
        var netty = plugin.getNettyPacketEngine();
        if (netty == null) return;
        if (!netty.isStarted()) {
            netty.start();
        }
        netty.registerInboundFilter(NETTY_GUARD_ID, this::inspectInboundPacket);
    }

    public void detachNettyGuard() {
        var netty = plugin.getNettyPacketEngine();
        if (netty != null) {
            netty.unregisterInboundFilter(NETTY_GUARD_ID);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean inspectInboundPacket(Player player, Object packet) {
        if (!isActive() || player == null || packet == null) return true;
        PlayerData d = players.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (d != null && d.pendingKick && now - d.pendingKickAt < 4000L) {
            nettyDroppedPackets.incrementAndGet();
            return false;
        }

        if (d != null) {
            long sec = now / 1000L;
            int rate;
            if (d.packetWindowSec != sec) {
                d.packetWindowSec = sec;
                d.inboundPacketRate.set(1);
                rate = 1;
            } else {
                rate = d.inboundPacketRate.incrementAndGet();
            }

            if (rate > 380) {
                nettyDroppedPackets.incrementAndGet();
                if (rate > 700 && !d.pendingKick) {
                    d.pendingKick = true;
                    d.pendingKickAt = now;
                    PlatformUtil.runAtEntity(plugin, player, () -> {
                        if (player.isOnline()) {
                            player.kickPlayer(MessageUtil.colorize("&c[AntiCheat] Ngắt kết nối do Packet Flood quá tải (" + rate + " pkt/s)"));
                        }
                    });
                }
                return false;
            }
        }

        String simpleName = packet.getClass().getSimpleName();
        if (simpleName.contains("MovePlayer") || simpleName.contains("Flying")) {
            MovePacketFields fields = movePacketFieldCache.computeIfAbsent(packet.getClass(), this::resolveMovePacketFields);
            try {
                for (Field df : fields.doubles) {
                    double v = df.getDouble(packet);
                    if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 3.00001e7) {
                        nettyDroppedPackets.incrementAndGet();
                        scheduleNettyCrashKick(player, d, "Invalid Move Coordinate (NaN/Infinity)");
                        return false;
                    }
                }
                for (Field ff : fields.floats) {
                    float fv = ff.getFloat(packet);
                    if (Float.isNaN(fv) || Float.isInfinite(fv)) {
                        nettyDroppedPackets.incrementAndGet();
                        scheduleNettyCrashKick(player, d, "Invalid Move Rotation (NaN/Infinity)");
                        return false;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private void scheduleNettyCrashKick(Player player, PlayerData d, String reason) {
        long now = System.currentTimeMillis();
        if (d != null) {
            if (d.pendingKick && now - d.pendingKickAt < 4000L) return;
            if (d.nettyCrashStrikes.incrementAndGet() >= 2) {
                d.pendingKick = true;
                d.pendingKickAt = now;
                PlatformUtil.runAtEntity(plugin, player, () -> {
                    if (player.isOnline()) {
                        player.kickPlayer(MessageUtil.colorize("&c[AntiCheat] Chặn gói tin Crash (" + reason + ")"));
                    }
                });
            }
        }
    }

    private MovePacketFields resolveMovePacketFields(Class<?> pktClass) {
        List<Field> dList = new ArrayList<>();
        List<Field> fList = new ArrayList<>();
        for (Class<?> cls = pktClass; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == double.class) {
                    f.setAccessible(true);
                    dList.add(f);
                } else if (f.getType() == float.class) {
                    f.setAccessible(true);
                    fList.add(f);
                }
            }
        }
        return new MovePacketFields(dList.toArray(new Field[0]), fList.toArray(new Field[0]));
    }

    public void reload() {
        loadConfig();
        if (isActive()) {
            start();
        } else {
            stop();
        }
    }

    public boolean isActive() {
        return moduleEnabled && configEnabled;
    }

    public void setModuleEnabled(boolean enable) {
        this.moduleEnabled = enable;
        if (isActive()) {
            start();
        } else {
            stop();
        }
    }

    public CheckSettings settings(CheckType type) {
        return settings[type.ordinal()];
    }

    public boolean isEnabled(CheckType type) {
        return isActive() && settings[type.ordinal()].isEnabled();
    }

    public PlayerData data(Player p) {
        return players.computeIfAbsent(p.getUniqueId(), id -> new PlayerData(id, p.getName()));
    }

    public PlayerData peek(UUID id) {
        return players.get(id);
    }

    public PlayerData peekByName(String name) {
        for (PlayerData d : players.values()) {
            if (d.name.equalsIgnoreCase(name)) return d;
        }
        return null;
    }

    public void remove(UUID id) {
        players.remove(id);
    }

    public Map<UUID, PlayerData> getPlayers() {
        return players;
    }

    public void resetAll() {
        for (PlayerData d : players.values()) {
            d.resetViolations();
        }
    }

    public boolean isBypassed(Player p, PlayerData d) {
        if (!isActive()) return true;
        if (d.forceCheck) return false;
        long now = System.currentTimeMillis();
        if (d.exemptUntil > now) return true;
        if (!disabledWorlds.isEmpty() && disabledWorlds.contains(p.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (now - d.bypassCheckedAt > 5000L) {
            d.bypass = p.hasPermission(bypassPermission) || (exemptOps && p.isOp());
            d.bypassCheckedAt = now;
        }
        return d.bypass;
    }

    public boolean isExemptOps() {
        return exemptOps;
    }

    public void setExemptOps(boolean exemptOps) {
        this.exemptOps = exemptOps;
        for (PlayerData d : players.values()) {
            d.bypassCheckedAt = 0;
        }
    }

    public boolean isMovementExempt(Player p, PlayerData d) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.getAllowFlight() || p.isFlying() || p.isInsideVehicle() || p.isDead() || p.isSleeping()) return true;
        long now = System.currentTimeMillis();
        if (now - d.joinTime < joinGraceMs) return true;
        if (now - d.lastTeleport < teleportGraceMs) return true;
        if (now - d.lastGameModeChange < teleportGraceMs) return true;
        if (now - d.lastRespawn < teleportGraceMs) return true;
        if (isLagging()) return true;
        return maxPing > 0 && safePing(p) > maxPing;
    }

    public boolean isInVelocityGrace(PlayerData d) {
        long now = System.currentTimeMillis();
        return (now - d.lastVelocity < velocityGraceMs)
                || (now - d.lastExplosionBoost < velocityGraceMs + 400L)
                || (now - d.lastWindCharge < 2600L);
    }

    public boolean isLagging() {
        return System.currentTimeMillis() < lagUntil || tps < minTps;
    }

    public double getTps() {
        return tps;
    }

    public int safePing(Player p) {
        try {
            return Math.max(0, p.getPing());
        } catch (Throwable t) {
            return 0;
        }
    }

    public long getTeleportGraceMs() {
        return teleportGraceMs;
    }

    public long getJoinGraceMs() {
        return joinGraceMs;
    }

    public boolean isDebugConsole() {
        return debugConsole;
    }

    public void markSetback(PlayerData d) {
        d.lastSetback = System.currentTimeMillis();
        d.lastLoc = null;
    }

    public void savePendingFall(UUID id, double dist) {
        pendingFall.put(id, dist);
    }

    public double consumePendingFall(UUID id) {
        Double d = pendingFall.remove(id);
        return d == null ? 0.0 : d;
    }

    public Location pickSetback(PlayerData d, Location from, boolean preferGround) {
        if (preferGround && d.lastSafeGround != null && from != null && d.lastSafeGround.getWorld() == from.getWorld()) {
            return d.lastSafeGround.clone();
        }
        Location s = d.setback;
        if (s != null && from != null && s.getWorld() == from.getWorld()) {
            return s.clone();
        }
        if (d.lastSafeGround != null && from != null && d.lastSafeGround.getWorld() == from.getWorld()) {
            return d.lastSafeGround.clone();
        }
        return from != null ? from.clone() : null;
    }

    public boolean flag(Player p, PlayerData d, CheckType type, String detail, double add) {
        CheckSettings s = settings(type);
        if (!isActive() || !s.isEnabled()) return false;

        long now = System.currentTimeMillis();
        if (d.pendingKick) {
            if (now - d.pendingKickAt < 4000L) {
                return true;
            }
            d.pendingKick = false;
        }

        int o = type.ordinal();
        if (d.investigationBoostUntil > now) {
            add *= 1.5;
        }
        d.vl[o] += add;
        d.totalFlags++;
        flagCounters[o].incrementAndGet();
        totalFlags.incrementAndGet();

        double vl = d.vl[o];
        String line = String.format(Locale.ROOT, "%s | %s | %s | %s | VL %.1f/%.0f | ping %dms | tps %.1f",
                DATE_FORMAT.format(Instant.ofEpochMilli(now)), p.getName(), type.getDisplayName(), detail, vl, s.getMaxVl(),
                safePing(p), tps);
        addLog(line);

        if (alertsEnabled && vl >= alertMinVl && now - d.lastAlert[o] >= alertThrottleMs) {
            d.lastAlert[o] = now;
            sendAlert(p, type, detail, vl, s.getMaxVl(), now);
            replayManager.captureIncident(p, type.name(), vl);
            if (alertDiscord && vl >= discordMinVl && plugin.getSettingsManager().isDiscordWebhookEnabled()
                    && plugin.getDiscordWebhook() != null) {
                try {
                    plugin.getDiscordWebhook().logExploit(p.getName(),
                            "[AntiCheat] " + type.getDisplayName() + " (" + detail + ") VL " + String.format(Locale.ROOT, "%.1f", vl),
                            "Flag");
                } catch (Throwable ignored) {
                }
            }
        }

        if (vl >= s.getMaxVl() && now - d.lastPunish >= punishCooldownMs) {
            d.lastPunish = now;
            punish(p, d, s, detail, vl, now);
        }
        return true;
    }

    private void sendAlert(Player p, CheckType type, String detail, double vl, double max, long now) {
        String msg = MessageUtil.colorize(alertFormat
                .replace("%player%", p.getName())
                .replace("%check%", type.getDisplayName())
                .replace("%detail%", detail)
                .replace("%vl%", String.format(Locale.ROOT, "%.1f", vl))
                .replace("%max%", String.format(Locale.ROOT, "%.0f", max))
                .replace("%ping%", String.valueOf(safePing(p))));
        boolean playSound = (now - lastGlobalSoundMs) >= 250L;
        if (playSound) {
            lastGlobalSoundMs = now;
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission(alertPermission) && !staff.isOp()) continue;
            PlayerData sd = players.get(staff.getUniqueId());
            if (sd != null && !sd.alerts) continue;
            staff.sendMessage(msg);
            if (playSound) {
                try {
                    staff.playSound(staff.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.8f);
                } catch (Throwable ignored) {}
            }
        }
        if (alertConsole) {
            plugin.getLogger().info(msg.replaceAll("(?i)§[0-9A-FK-ORX]", ""));
        }
    }

    @SuppressWarnings("deprecation")
    private void punish(Player p, PlayerData d, CheckSettings s, String detail, double vl, long now) {
        totalPunishments.incrementAndGet();
        Location safe = pickSetback(d, p.getLocation(), true);
        if (safe != null && safe.getWorld() == p.getWorld()) {
            try {
                p.teleport(safe);
            } catch (Throwable ignored) {}
            d.lastLoc = safe.clone();
            d.setback = safe.clone();
        }

        String check = s.getType().getDisplayName();
        addLog(DATE_FORMAT.format(Instant.ofEpochMilli(now)) + " | PUNISH | " + p.getName() + " | " + check + " | VL " + String.format(Locale.ROOT, "%.1f", vl));
        if (resetOnPunish) {
            d.vl[s.getType().ordinal()] = 0;
            d.buffer[s.getType().ordinal()] = 0;
        }

        if (banWaveManager != null && banWaveManager.isEnabled() && s.b("ban-wave", false)) {
            banWaveManager.queuePlayer(p, s.getType(), detail, vl);
            return;
        }

        List<String> cmds = s.getCommands().isEmpty() ? defaultCommands : s.getCommands();
        d.pendingKick = true;
        d.pendingKickAt = now;
        if (cmds.isEmpty()) {
            final String msg = MessageUtil.colorize(kickMessage.replace("%check%", check));
            PlatformUtil.runAtEntity(plugin, p, () -> {
                if (p.isOnline()) {
                    p.kickPlayer(msg);
                }
            });
            return;
        }
        final List<String> resolved = new ArrayList<>();
        for (String c : cmds) {
            resolved.add(c.replace("%player%", p.getName())
                    .replace("%uuid%", p.getUniqueId().toString())
                    .replace("%check%", check)
                    .replace("%vl%", String.format(Locale.ROOT, "%.0f", vl)));
        }
        PlatformUtil.runTask(plugin, () -> {
            for (String c : resolved) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.startsWith("/") ? c.substring(1) : c);
                } catch (Throwable t) {
                    plugin.getLogger().warning("[AntiCheat] Lệnh trừng phạt lỗi '" + c + "': " + t.getMessage());
                }
            }
        });
    }

    private void addLog(String line) {
        synchronized (recentLog) {
            recentLog.addLast(line);
            while (recentLog.size() > logMemoryLines) {
                recentLog.pollFirst();
            }
        }
        if (logToFile && logQueue.size() < 5000) {
            logQueue.add(line);
        }
    }

    private void flushLogsAsync() {
        if (logQueue.isEmpty()) return;
        List<String> lines = new ArrayList<>();
        String s;
        while (lines.size() < 250 && (s = logQueue.poll()) != null) {
            lines.add(s);
        }
        if (lines.isEmpty()) return;
        PlatformUtil.runTaskAsync(plugin, () -> {
            synchronized (fileLock) {
                try {
                    Path path = plugin.getDataFolder().toPath().resolve("logs").resolve("anticheat.log");
                    Files.createDirectories(path.getParent());
                    Files.write(path, lines, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {}
            }
        });
    }

    public List<String> getRecentLog(int max) {
        synchronized (recentLog) {
            List<String> all = new ArrayList<>(recentLog);
            int from = Math.max(0, all.size() - Math.max(1, max));
            return all.subList(from, all.size());
        }
    }

    public void debug(Player p, PlayerData d, String msg) {
        if (d.debug) {
            p.sendMessage(MessageUtil.colorize("&8[&bACDebug&8] &7" + msg));
        }
        if (debugConsole) {
            plugin.getLogger().info("[AC-Debug] " + p.getName() + ": " + msg);
        }
    }

    private void monitorTick() {
        long now = System.nanoTime();
        long gapMs = (now - lastTickNano) / 1_000_000L;
        lastTickNano = now;
        if (gapMs > lagSpikeMs) {
            lagUntil = System.currentTimeMillis() + lagGraceMs;
        }
        if (++tickCounter >= 20) {
            tickCounter = 0;
            try {
                double[] t = plugin.getServerController().getTPS();
                if (t != null && t.length > 0) {
                    tps = Math.min(20.0, t[0]);
                }
            } catch (Throwable ignored) {
                tps = 20.0;
            }
            flushLogsAsync();
        }
    }

    private void decayTick() {
        if (decayAmount <= 0) return;
        for (PlayerData d : players.values()) {
            for (int i = 0; i < d.vl.length; i++) {
                if (d.vl[i] > 0) {
                    d.vl[i] = Math.max(0, d.vl[i] - decayAmount);
                }
            }
        }
    }

    public long getTotalFlags() {
        return totalFlags.get();
    }

    public long getTotalPunishments() {
        return totalPunishments.get();
    }

    public long getFlagCount(CheckType t) {
        return flagCounters[t.ordinal()].get();
    }

    public String getAlertPermission() {
        return alertPermission;
    }

    public String getBypassPermission() {
        return bypassPermission;
    }

    @SuppressWarnings("deprecation")
    public boolean freezePlayer(CommandSender staff, Player target) {
        PlayerData d = data(target);
        if (d.isFrozen) return false;
        d.isFrozen = true;
        d.frozenLocation = target.getLocation().clone();
        d.frozenBy = staff.getName();
        d.frozenAt = System.currentTimeMillis();

        target.sendTitle("§c§lBẠN ĐÃ BỊ ĐÓNG BĂNG!", "§eStaff đang tiến hành kiểm tra! Không được thoát!", 10, 100, 20);
        MessageUtil.sendError(target, "--------------------------------------------------");
        MessageUtil.sendError(target, " BẠN ĐANG BỊ STAFF (" + staff.getName() + ") ĐÓNG BĂNG ĐỂ KIỂM TRA!");
        MessageUtil.sendWarning(target, " Hãy mở Discord / chuẩn bị theo yêu cầu của Staff!");
        MessageUtil.sendError(target, " CẢNH BÁO: Nếu bạn ngắt kết nối sẽ bị BAN vĩnh viễn!");
        MessageUtil.sendError(target, "--------------------------------------------------");

        String notify = "&8[&cAC-Freeze&8] &fStaff &e" + staff.getName() + " &7đã đóng băng người chơi &c" + target.getName();
        Bukkit.broadcast(MessageUtil.colorize(notify), alertPermission);
        return true;
    }

    @SuppressWarnings("deprecation")
    public boolean unfreezePlayer(CommandSender staff, Player target) {
        PlayerData d = data(target);
        if (!d.isFrozen) return false;
        d.isFrozen = false;
        d.frozenLocation = null;
        d.frozenBy = null;

        target.sendTitle("§a§lĐÃ ĐƯỢC MỞ KHÓA!", "§fBạn có thể tiếp tục chơi game bình thường.", 10, 60, 20);
        MessageUtil.sendSuccess(target, "Bạn đã được Staff mở khóa đóng băng! Cảm ơn bạn đã hợp tác.");

        String notify = "&8[&cAC-Freeze&8] &fStaff &e" + staff.getName() + " &7đã mở đóng băng cho &a" + target.getName();
        Bukkit.broadcast(MessageUtil.colorize(notify), alertPermission);
        return true;
    }

    public List<PlayerData> getTopViolators(int limit) {
        List<PlayerData> list = new ArrayList<>(players.values());
        list.sort((a, b) -> Double.compare(b.getTotalVl(), a.getTotalVl()));
        if (list.size() > limit) {
            return list.subList(0, limit);
        }
        return list;
    }

    public void runSelfTest(CommandSender sender) {
        long startNs = System.nanoTime();
        int passed = 0;
        int total = 0;

        sender.sendMessage("§b╔══════════════════════════════════════════════════════╗");
        sender.sendMessage("§b║   §e§lKIỂM ĐỊNH TOÀN DIỆN HỆ THỐNG ANTI-CHEAT (SELFTEST) §b║");
        sender.sendMessage("§b╚══════════════════════════════════════════════════════╝");

        total++;
        int enabledCount = 0;
        for (CheckType ct : CheckType.values()) {
            if (settings(ct) != null && settings(ct).isEnabled()) enabledCount++;
        }
        if (settings.length == CheckType.values().length && enabledCount > 0) {
            passed++;
            sender.sendMessage(" §a✔ §fCheck Registry: §aPASS §8(" + enabledCount + "/" + CheckType.values().length + " checks đang bật)");
        } else {
            sender.sendMessage(" §c✖ §fCheck Registry: §cFAIL");
        }

        total++;
        float wrapDiff = AcUtil.angleDiff(179.0f, -179.0f);
        double humanGcd = AcUtil.gcd(0.45, 0.30);
        double botGcd = AcUtil.gcd(0.413719, 0.289137);
        if (Math.abs(wrapDiff - 2.0f) < 0.01f && Math.abs(humanGcd - 0.15) < 0.002 && botGcd < 0.002) {
            passed++;
            sender.sendMessage(String.format(Locale.ROOT, " §a✔ §fToán học Góc & GCD: §aPASS §8(Wrap=%.2f°, HumanGCD=%.4f, AimbotGCD=%.6f)", wrapDiff, humanGcd, botGcd));
        } else {
            sender.sendMessage(String.format(Locale.ROOT, " §c✖ §fToán học Góc & GCD: §cFAIL §8(Wrap=%.2f°, HumanGCD=%.4f, AimbotGCD=%.6f)", wrapDiff, humanGcd, botGcd));
        }

        total++;
        BoundingBox testBox = new BoundingBox(0.0, 64.0, 3.0, 0.6, 65.8, 3.6);
        boolean rayHit = BacktrackEngine.rayIntersectsBox(new Vector(0.3, 65.62, 0.0), new Vector(0.0, -0.05, 1.0).normalize(), testBox, 6.0);
        boolean rayMiss = BacktrackEngine.rayIntersectsBox(new Vector(0.3, 65.62, 0.0), new Vector(1.0, 0.0, 0.0), testBox, 6.0);
        BacktrackEngine.EntityHistory hist = new BacktrackEngine.EntityHistory(10);
        hist.addFrame(new BacktrackEngine.HistoricalFrame(1, 1000L, new BoundingBox(0, 64, 0, 1, 66, 1), null));
        hist.addFrame(new BacktrackEngine.HistoricalFrame(2, 1050L, new BoundingBox(2, 64, 0, 3, 66, 1), null));
        var interp = hist.interpolateBox(1025L);
        if (rayHit && !rayMiss && interp != null && Math.abs(interp.box().getMinX() - 1.0) < 0.01) {
            passed++;
            sender.sendMessage(" §a✔ §fBacktrack 3D & Raycast Slab: §aPASS §8(Nội suy 50% X=" + String.format(Locale.ROOT, "%.2f", interp.box().getMinX()) + ")");
        } else {
            sender.sendMessage(" §c✖ §fBacktrack 3D & Raycast Slab: §cFAIL");
        }

        total++;
        double legitScore = neuralHeuristicEngine != null ? neuralHeuristicEngine.selfTestSample(false) : -1;
        double cheatScore = neuralHeuristicEngine != null ? neuralHeuristicEngine.selfTestSample(true) : -1;
        if (legitScore >= 0.0 && legitScore < 0.45 && cheatScore >= 0.72) {
            passed++;
            sender.sendMessage(String.format(Locale.ROOT, " §a✔ §fNeural Heuristic MLP: §aPASS §8(Legit=%.2f < 0.45, KillAura=%.2f >= 0.72)", legitScore, cheatScore));
        } else {
            sender.sendMessage(String.format(Locale.ROOT, " §c✖ §fNeural Heuristic MLP: §cFAIL §8(Legit=%.2f, KillAura=%.2f)", legitScore, cheatScore));
        }

        total++;
        var netty = plugin.getNettyPacketEngine();
        boolean nettyOk = netty != null && netty.isStarted();
        if (nettyOk) {
            passed++;
            sender.sendMessage(" §a✔ §fNetty Packet Shield: §aACTIVE §8(Hooked=" + netty.getHookedCount() + " kênh, Đã chặn=" + nettyDroppedPackets.get() + " gói rác)");
        } else {
            sender.sendMessage(" §e! §fNetty Packet Shield: §eSTANDBY");
        }

        total++;
        long benchT0 = System.nanoTime();
        double sink = 0;
        for (int i = 0; i < 10_000; i++) {
            sink += AcUtil.gcd(0.45 + (i % 5) * 0.15, 0.30);
            sink += AcUtil.angleDiff(170.0f + (i % 20), -175.0f);
            if (BacktrackEngine.rayIntersectsBox(new Vector(0.3, 65.6, 0.0), new Vector(0.0, 0.0, 1.0), testBox, 4.0)) {
                sink += 1.0;
            }
        }
        long benchUs = (System.nanoTime() - benchT0) / 1_000L;
        double usPerOp = benchUs / 10_000.0;
        if (sink > 0 && benchUs < 150_000L) {
            passed++;
            sender.sendMessage(String.format(Locale.ROOT, " §a✔ §fHiệu năng Chống Sập (10,000 phép quét): §a%.2f ms §8(%.3f µs/lượt - Chịu tải >200 hacker cùng lúc)", benchUs / 1000.0, usPerOp));
        } else {
            sender.sendMessage(String.format(Locale.ROOT, " §e! §fHiệu năng Chống Sập: §e%.2f ms", benchUs / 1000.0));
        }

        long totalMs = (System.nanoTime() - startNs) / 1_000_000L;
        sender.sendMessage("§b════════════════════════════════════════════════════════");
        sender.sendMessage(String.format(Locale.ROOT, " §fKết quả tổng thể: %s%d/%d HẠNG MỤC ĐẠT CHUẨN §8(Hoàn tất trong %d ms | TPS %.2f)",
                passed == total ? "§a§l" : "§e§l", passed, total, totalMs, tps));
    }
}
