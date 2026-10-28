package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trung tâm điều phối Anti-Cheat: nạp cấu hình, quản lý dữ liệu người chơi, ghi nhận vi phạm (flag),
 * cảnh báo staff, trừng phạt, setback, ghi log và các cơ chế chống false-positive (lag, ping, grace).
 */
public final class AntiCheatManager {

    private final JavaStudioPlugin plugin;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final CheckSettings[] settings = new CheckSettings[CheckType.values().length];
    private final AtomicLong[] flagCounters = new AtomicLong[CheckType.values().length];
    private final AtomicLong totalFlags = new AtomicLong();
    private final AtomicLong totalPunishments = new AtomicLong();
    private final Map<UUID, Double> pendingFall = new ConcurrentHashMap<>();
    private final ArrayDeque<String> recentLog = new ArrayDeque<>();
    private final Object fileLock = new Object();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private volatile boolean moduleEnabled = true;
    private volatile boolean configEnabled = true;

    // Cấu hình chung
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

    // Giám sát lag toàn cục
    private volatile long lagUntil;
    private volatile double tps = 20.0;
    private long lastTickNano;
    private int tickCounter;
    private PlatformUtil.TaskHandle monitorTask;
    private PlatformUtil.TaskHandle decayTask;
    private final ReplayManager replayManager;
    private final AuraBotManager auraBotManager;
    private final AcWebDashboard webDashboard;
    private final VehicleCheck vehicleCheck;
    private final CombatAnomalyEngine anomalyEngine;
    private final XrayHoneypotManager xrayManager;
    private final BanWaveManager banWaveManager;
    private final InventoryFsmCheck inventoryFsmCheck;
    private final PhysicsSimulationCheck physicsCheck;
    private final BehaviorClusterEngine clusterEngine;
    private final ReportManager reportManager;

    public AntiCheatManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.replayManager = new ReplayManager(plugin);
        this.auraBotManager = new AuraBotManager(plugin, this);
        this.webDashboard = new AcWebDashboard(plugin, this);
        this.vehicleCheck = new VehicleCheck(this);
        this.anomalyEngine = new CombatAnomalyEngine(this);
        this.xrayManager = new XrayHoneypotManager(plugin, this);
        this.banWaveManager = new BanWaveManager(plugin, this);
        this.inventoryFsmCheck = new InventoryFsmCheck(this);
        this.physicsCheck = new PhysicsSimulationCheck(this);
        this.clusterEngine = new BehaviorClusterEngine(this);
        this.reportManager = new ReportManager(plugin, this);
        for (int i = 0; i < flagCounters.length; i++) {
            flagCounters[i] = new AtomicLong();
        }
        this.moduleEnabled = plugin.getSettingsManager().isAntiCheatEnabled();
        loadConfig();
    }

    public ReplayManager getReplayManager() {
        return replayManager;
    }

    public AuraBotManager getAuraBotManager() {
        return auraBotManager;
    }

    public AcWebDashboard getWebDashboard() {
        return webDashboard;
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

    // ============================================================
    //  CẤU HÌNH
    // ============================================================

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
            webDashboard.loadConfig();
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
            webDashboard.start();
        }
        if (banWaveManager != null) {
            banWaveManager.start();
        }
    }

    public void stop() {
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
            webDashboard.stop();
        }
        if (banWaveManager != null) {
            banWaveManager.stop();
        }
        if (xrayManager != null) {
            xrayManager.cleanupAll();
        }
    }

    public void shutdown() {
        stop();
        players.clear();
    }

    /** Nạp lại config và khởi động lại tác vụ nền (decay interval có thể đổi). */
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

    // ============================================================
    //  DỮ LIỆU NGƯỜI CHƠI
    // ============================================================

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

    // ============================================================
    //  MIỄN TRỪ / CHỐNG FALSE POSITIVE
    // ============================================================

    /** Người chơi bị bỏ qua hoàn toàn (bypass perm, OP, world tắt, exempt tạm thời). */
    public boolean isBypassed(Player p, PlayerData d) {
        if (!isActive()) return true;
        if (d.forceCheck) return false; // Chế độ Test Mode: Quét cả Admin/OP để tự kiểm thử
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

    /** Điều kiện chung cho các check di chuyển (gamemode, vehicle, lag, ping, grace). */
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
        return System.currentTimeMillis() - d.lastVelocity < velocityGraceMs;
    }

    public boolean isLagging() {
        return System.currentTimeMillis() < lagUntil || tps < minTps;
    }

    public double getTps() {
        return tps;
    }

    public int safePing(Player p) {
        try {
            return p.getPing();
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

    /** Đánh dấu vị trí setback để Listener bỏ qua sự kiện teleport do chính Anti-Cheat tạo. */
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
        if (d.lastSafeGround != null && from != null && d.lastSafeGround.getWorld() == from.getWorld()) {
            return d.lastSafeGround.clone();
        }
        Location s = d.setback;
        if (s != null && from != null && s.getWorld() == from.getWorld()) {
            return s.clone();
        }
        return from.clone();
    }

    // ============================================================
    //  FLAG / CẢNH BÁO / TRỪNG PHẠT
    // ============================================================

    /**
     * Ghi nhận một vi phạm. Trả về true nếu check đang bật và vi phạm đã được ghi nhận.
     */
    public boolean flag(Player p, PlayerData d, CheckType type, String detail, double add) {
        CheckSettings s = settings(type);
        if (!isActive() || !s.isEnabled()) return false;

        int o = type.ordinal();
        if (d.investigationBoostUntil > System.currentTimeMillis()) {
            add *= 1.5; // Tăng 50% độ nhạy khi đang trong trạng thái bị tố cáo điều tra
        }
        d.vl[o] += add;
        d.totalFlags++;
        flagCounters[o].incrementAndGet();
        totalFlags.incrementAndGet();

        double vl = d.vl[o];
        long now = System.currentTimeMillis();
        String line = String.format(Locale.ROOT, "%s | %s | %s | %s | VL %.1f/%.0f | ping %dms | tps %.1f",
                dateFormat.format(new Date(now)), p.getName(), type.getDisplayName(), detail, vl, s.getMaxVl(),
                safePing(p), tps);
        addLog(line);

        if (alertsEnabled && vl >= alertMinVl && now - d.lastAlert[o] >= alertThrottleMs) {
            d.lastAlert[o] = now;
            sendAlert(p, type, detail, vl, s.getMaxVl());
            replayManager.captureIncident(p, type.name(), vl);
            if (alertDiscord && vl >= discordMinVl && plugin.getSettingsManager().isDiscordWebhookEnabled()
                    && plugin.getDiscordWebhook() != null) {
                try {
                    plugin.getDiscordWebhook().logExploit(p.getName(),
                            "[AntiCheat] " + type.getDisplayName() + " (" + detail + ") VL " + String.format(Locale.ROOT, "%.1f", vl),
                            "Flag");
                } catch (Throwable ignored) {
                    // Discord lỗi không được làm hỏng luồng anti-cheat
                }
            }
        }

        if (vl >= s.getMaxVl() && now - d.lastPunish >= punishCooldownMs) {
            d.lastPunish = now;
            punish(p, d, s, vl);
        }
        return true;
    }

    private void sendAlert(Player p, CheckType type, String detail, double vl, double max) {
        String msg = MessageUtil.colorize(alertFormat
                .replace("%player%", p.getName())
                .replace("%check%", type.getDisplayName())
                .replace("%detail%", detail)
                .replace("%vl%", String.format(Locale.ROOT, "%.1f", vl))
                .replace("%max%", String.format(Locale.ROOT, "%.0f", max))
                .replace("%ping%", String.valueOf(safePing(p))));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission(alertPermission) && !staff.isOp()) continue;
            PlayerData sd = players.get(staff.getUniqueId());
            if (sd != null && !sd.alerts) continue;
            staff.sendMessage(msg);
            try {
                staff.playSound(staff.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.8f);
            } catch (Throwable ignored) {}
        }
        if (alertConsole) {
            plugin.getLogger().info(msg.replaceAll("(?i)§[0-9A-FK-ORX]", ""));
        }
    }

    @SuppressWarnings("deprecation")
    private void punish(Player p, PlayerData d, CheckSettings s, double vl) {
        totalPunishments.incrementAndGet();
        // Lập tức giật lùi người chơi về vị trí mặt đất an toàn trước khi lệnh kick được thực thi!
        // Chống triệt để exploit ClickTP/Phase giữ chuột để bị kick rồi relog xuất hiện ở vị trí hack!
        Location safe = pickSetback(d, p.getLocation(), true);
        if (safe != null && safe.getWorld() == p.getWorld()) {
            try {
                p.teleport(safe);
            } catch (Throwable ignored) {}
            d.lastLoc = safe.clone();
            d.setback = safe.clone();
        }

        List<String> cmds = s.getCommands().isEmpty() ? defaultCommands : s.getCommands();
        String check = s.getType().getDisplayName();
        addLog(dateFormat.format(new Date()) + " | PUNISH | " + p.getName() + " | " + check + " | VL " + String.format(Locale.ROOT, "%.1f", vl));
        if (resetOnPunish) {
            d.vl[s.getType().ordinal()] = 0;
            d.buffer[s.getType().ordinal()] = 0;
        }
        if (cmds.isEmpty()) {
            final String msg = MessageUtil.colorize(kickMessage.replace("%check%", check));
            PlatformUtil.runAtEntity(plugin, p, () -> p.kickPlayer(msg));
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

    // ============================================================
    //  LOG
    // ============================================================

    private void addLog(String line) {
        synchronized (recentLog) {
            recentLog.addLast(line);
            while (recentLog.size() > logMemoryLines) {
                recentLog.pollFirst();
            }
        }
        if (logToFile) {
            final String out = line + System.lineSeparator();
            PlatformUtil.runTaskAsync(plugin, () -> {
                synchronized (fileLock) {
                    try {
                        Path path = plugin.getDataFolder().toPath().resolve("logs").resolve("anticheat.log");
                        Files.createDirectories(path.getParent());
                        Files.writeString(path, out, StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (IOException ignored) {
                        // Không để lỗi I/O ảnh hưởng server
                    }
                }
            });
        }
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

    // ============================================================
    //  TÁC VỤ NỀN
    // ============================================================

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

    // ============================================================
    //  THỐNG KÊ
    // ============================================================

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

    // ============================================================
    //  STAFF FREEZE & TOP VIOLATORS
    // ============================================================

    @SuppressWarnings("deprecation")
    public boolean freezePlayer(org.bukkit.command.CommandSender staff, Player target) {
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
        org.bukkit.Bukkit.broadcast(MessageUtil.colorize(notify), alertPermission);
        return true;
    }

    @SuppressWarnings("deprecation")
    public boolean unfreezePlayer(org.bukkit.command.CommandSender staff, Player target) {
        PlayerData d = data(target);
        if (!d.isFrozen) return false;
        d.isFrozen = false;
        d.frozenLocation = null;
        d.frozenBy = null;

        target.sendTitle("§a§lĐÃ ĐƯỢC MỞ KHÓA!", "§fBạn có thể tiếp tục chơi game bình thường.", 10, 60, 20);
        MessageUtil.sendSuccess(target, "Bạn đã được Staff mở khóa đóng băng! Cảm ơn bạn đã hợp tác.");

        String notify = "&8[&cAC-Freeze&8] &fStaff &e" + staff.getName() + " &7đã mở đóng băng cho &a" + target.getName();
        org.bukkit.Bukkit.broadcast(MessageUtil.colorize(notify), alertPermission);
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
}
