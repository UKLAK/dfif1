package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import com.javastudio.utils.SmartScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public class ReplayManager {

    private static final int BUFFER_CAPACITY = 400;
    private static final int MAX_CACHED_CLIPS = 100;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaStudioPlugin plugin;
    private final File replayFolder;
    private final AtomicLong clipSeq = new AtomicLong();

    public record ReplayFrame(
            double x, double y, double z,
            float yaw, float pitch,
            boolean onGround, boolean sneaking,
            String action, int cps, double speed,
            long time
    ) {}

    public static class ReplayIncident {
        public String id;
        public String playerName;
        public String checkName;
        public double vl;
        public String timestamp;
        public String worldName;
        public List<ReplayFrame> frames;

        public ReplayIncident(String id, String playerName, String checkName, double vl, String worldName, List<ReplayFrame> frames) {
            this.id = id;
            this.playerName = playerName;
            this.checkName = checkName;
            this.vl = vl;
            this.worldName = worldName;
            this.frames = frames != null ? frames : new ArrayList<>();
            this.timestamp = LocalDateTime.now().format(TS_FMT);
        }
    }

    private final Map<UUID, Deque<ReplayFrame>> playerBuffers = new ConcurrentHashMap<>();
    private final Map<String, ReplayIncident> incidentCache = new ConcurrentHashMap<>();
    private final Deque<String> incidentOrder = new ConcurrentLinkedDeque<>();

    private PlatformUtil.TaskHandle activePlaybackTask;
    private ArmorStand activeGhost;
    private TextDisplay activeLabel;
    private volatile boolean isPaused = false;
    private volatile String activeClipId = null;
    private volatile double activeSpeedMultiplier = 1.0;
    private int currentFrameIndex = 0;
    private int totalFramesCount = 0;
    private final Map<UUID, Long> lastCaptureTimes = new ConcurrentHashMap<>();

    public ReplayManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.replayFolder = new File(plugin.getDataFolder(), "replays");
        if (!replayFolder.exists()) replayFolder.mkdirs();
        loadSavedClips();
    }

    public void cleanupPlayer(UUID uuid) {
        if (uuid != null) {
            playerBuffers.remove(uuid);
            lastCaptureTimes.remove(uuid);
        }
    }

    public void shutdown() {
        stopPlayback();
        playerBuffers.clear();
        lastCaptureTimes.clear();
    }

    private void loadSavedClips() {
        File[] files = replayFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int start = Math.max(0, files.length - MAX_CACHED_CLIPS);
        for (int idx = start; idx < files.length; idx++) {
            File f = files[idx];
            try {
                String content = java.nio.file.Files.readString(f.toPath(), StandardCharsets.UTF_8);
                String id = extractJsonString(content, "id");
                String name = extractJsonString(content, "playerName");
                String check = extractJsonString(content, "checkName");
                double vl = extractJsonDouble(content, "vl");
                String world = extractJsonString(content, "worldName");
                String time = extractJsonString(content, "timestamp");

                if (id != null && name != null) {
                    ReplayIncident inc = new ReplayIncident(id, name, check != null ? check : "UNKNOWN", vl, world != null ? world : "world", new ArrayList<>());
                    if (time != null) inc.timestamp = time;
                    incidentCache.put(id, inc);
                    incidentOrder.addLast(id);
                }
            } catch (Throwable ignored) {}
        }
    }

    public void recordFrame(Player player, String action, int cps, double speed) {
        Location loc = player.getLocation();
        ReplayFrame frame = new ReplayFrame(
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(),
                player.isOnGround(), player.isSneaking(),
                action, cps, speed, System.currentTimeMillis()
        );

        Deque<ReplayFrame> buffer = playerBuffers.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentLinkedDeque<>());
        buffer.addLast(frame);
        while (buffer.size() > BUFFER_CAPACITY) {
            buffer.pollFirst();
        }
    }

    public String captureIncident(Player player, String checkName, double vl) {
        long now = System.currentTimeMillis();
        boolean manual = checkName != null && checkName.startsWith("MANUAL_");
        if (!manual) {
            Long last = lastCaptureTimes.get(player.getUniqueId());
            if (last != null && now - last < 15000L) return null;
            lastCaptureTimes.put(player.getUniqueId(), now);
        }

        Deque<ReplayFrame> buffer = playerBuffers.get(player.getUniqueId());
        if (buffer == null || buffer.isEmpty()) return null;

        String id = "clip_" + (now % 1_000_000L) + "_" + clipSeq.incrementAndGet();
        List<ReplayFrame> snapshot = new ArrayList<>(buffer);
        ReplayIncident incident = new ReplayIncident(id, player.getName(), checkName, vl, player.getWorld().getName(), snapshot);

        incidentCache.put(id, incident);
        incidentOrder.addLast(id);
        while (incidentOrder.size() > MAX_CACHED_CLIPS) {
            String oldest = incidentOrder.pollFirst();
            if (oldest != null) {
                incidentCache.remove(oldest);
            }
        }

        PlatformUtil.runTaskAsync(plugin, () -> {
            try {
                File file = new File(replayFolder, id + ".json");
                StringBuilder sb = new StringBuilder();
                sb.append("{\n");
                sb.append("  \"id\": \"").append(incident.id).append("\",\n");
                sb.append("  \"playerName\": \"").append(escapeJson(incident.playerName)).append("\",\n");
                sb.append("  \"checkName\": \"").append(escapeJson(incident.checkName)).append("\",\n");
                sb.append("  \"vl\": ").append(String.format(Locale.ROOT, "%.1f", incident.vl)).append(",\n");
                sb.append("  \"worldName\": \"").append(escapeJson(incident.worldName)).append("\",\n");
                sb.append("  \"timestamp\": \"").append(escapeJson(incident.timestamp)).append("\",\n");
                sb.append("  \"frameCount\": ").append(snapshot.size()).append(",\n");
                sb.append("  \"frames\": [\n");

                for (int i = 0; i < snapshot.size(); i++) {
                    ReplayFrame f = snapshot.get(i);
                    sb.append(String.format(Locale.ROOT,
                            "    [%.3f, %.3f, %.3f, %.2f, %.2f, %b, %b, \"%s\", %d, %.3f, %d]%s\n",
                            f.x(), f.y(), f.z(), f.yaw(), f.pitch(),
                            f.onGround(), f.sneaking(), escapeJson(f.action()),
                            f.cps(), f.speed(), f.time(),
                            (i < snapshot.size() - 1 ? "," : "")
                    ));
                }
                sb.append("  ]\n");
                sb.append("}");
                java.nio.file.Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            } catch (Throwable ignored) {}
        });

        return id;
    }

    public boolean playReplay(Player staff, String clipId) {
        return playReplay(staff, clipId, 1.0);
    }

    public boolean playReplay(Player staff, String clipId, double speedMultiplier) {
        String safeId = clipId == null ? "" : clipId.replaceAll("[^a-zA-Z0-9_-]", "");
        ReplayIncident inc = getIncident(safeId);
        if (inc == null || inc.frames.isEmpty()) return false;

        stopPlayback();

        World rawWorld = Bukkit.getWorld(inc.worldName);
        final World playWorld = rawWorld != null ? rawWorld : staff.getWorld();

        List<ReplayFrame> frames = inc.frames;
        ReplayFrame first = frames.get(0);
        Location startLoc = new Location(playWorld, first.x(), first.y(), first.z(), first.yaw(), first.pitch());

        SmartScheduler.teleport(staff, startLoc);

        activeGhost = playWorld.spawn(startLoc, ArmorStand.class, as -> {
            as.setVisible(true);
            as.setArms(true);
            as.setBasePlate(false);
            as.setGravity(false);
            as.setPersistent(false);
            as.setInvulnerable(true);
            as.setCustomName("§c[GHOST] " + inc.playerName);
            as.setCustomNameVisible(true);
            if (as.getEquipment() != null) {
                as.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
                as.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                as.getEquipment().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
                as.getEquipment().setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
                as.getEquipment().setBoots(new ItemStack(Material.LEATHER_BOOTS));
            }
        });

        activeLabel = playWorld.spawn(startLoc.clone().add(0, 2.3, 0), TextDisplay.class, td -> {
            td.setBillboard(Display.Billboard.CENTER);
            td.setPersistent(false);
            td.setText(MessageUtil.colorize("&c&l[REPLAY] &e" + inc.playerName + " &7- &c" + inc.checkName + " &8(VL: " + inc.vl + ")"));
            td.setShadowed(true);
        });

        this.activeClipId = safeId;
        this.activeSpeedMultiplier = Math.max(0.25, Math.min(4.0, speedMultiplier));
        this.isPaused = false;
        this.totalFramesCount = frames.size();
        this.currentFrameIndex = 0;

        final double[] subFrameCounter = {0.0};

        activePlaybackTask = PlatformUtil.runTaskTimer(plugin, () -> {
            if (isPaused) return;

            if (currentFrameIndex >= totalFramesCount || activeGhost == null || !activeGhost.isValid()) {
                stopPlayback();
                if (staff.isOnline()) {
                    staff.sendMessage("§a[Replay] Đã chiếu lại xong clip §e" + safeId + "§a!");
                }
                return;
            }

            subFrameCounter[0] += activeSpeedMultiplier;
            int step = (int) Math.floor(subFrameCounter[0]);
            if (step < 1) return;
            subFrameCounter[0] -= step;

            currentFrameIndex = Math.min(totalFramesCount - 1, currentFrameIndex + step);
            ReplayFrame f = frames.get(currentFrameIndex);
            Location fLoc = new Location(playWorld, f.x(), f.y(), f.z(), f.yaw(), f.pitch());
            SmartScheduler.teleport(activeGhost, fLoc);

            try {
                playWorld.spawnParticle(Particle.WAX_OFF, fLoc.clone().add(0, 0.15, 0), 1, 0.05, 0.05, 0.05, 0.0);
                if ("ATTACK".equals(f.action())) {
                    playWorld.spawnParticle(Particle.CRIT, fLoc.clone().add(0, 1.2, 0), 6, 0.2, 0.2, 0.2, 0.1);
                    playWorld.spawnParticle(Particle.SWEEP_ATTACK, fLoc.clone().add(fLoc.getDirection().multiply(0.8)).add(0, 1.0, 0), 1, 0, 0, 0, 0);
                }
            } catch (Throwable ignored) {}

            if (activeLabel != null && activeLabel.isValid()) {
                SmartScheduler.teleport(activeLabel, fLoc.clone().add(0, 2.3, 0));
                activeLabel.setText(MessageUtil.colorize(String.format(Locale.ROOT,
                        "&c&l[REPLAY: %.1fx] &e%s &7(&c%s&7)\n&fCPS: &a%d &7| Spd: &e%.2f &7| Action: &b%s\n&7Tick: &6%d/%d &8| OnGround: &f%b",
                        activeSpeedMultiplier, inc.playerName, inc.checkName, f.cps(), f.speed(), f.action(),
                        currentFrameIndex + 1, totalFramesCount, f.onGround()
                )));
            }

            if (currentFrameIndex >= totalFramesCount - 1) {
                stopPlayback();
                if (staff.isOnline()) {
                    staff.sendMessage("§a[Replay] Đã chiếu lại xong clip §e" + safeId + "§a!");
                }
            }
        }, 1L, 1L);

        return true;
    }

    public boolean pausePlayback() {
        if (activePlaybackTask != null && !isPaused) {
            isPaused = true;
            return true;
        }
        return false;
    }

    public boolean resumePlayback() {
        if (activePlaybackTask != null && isPaused) {
            isPaused = false;
            return true;
        }
        return false;
    }

    public boolean setSpeed(double speed) {
        if (activePlaybackTask != null) {
            this.activeSpeedMultiplier = Math.max(0.25, Math.min(4.0, speed));
            return true;
        }
        return false;
    }

    public boolean isPlaying() {
        return activePlaybackTask != null;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public String getActiveClipId() {
        return activeClipId;
    }

    public double getActiveSpeed() {
        return activeSpeedMultiplier;
    }

    public void stopPlayback() {
        if (activePlaybackTask != null) {
            activePlaybackTask.cancel();
            activePlaybackTask = null;
        }
        if (activeGhost != null) {
            ArmorStand ghost = activeGhost;
            activeGhost = null;
            PlatformUtil.runAtEntity(plugin, ghost, () -> {
                try {
                    if (ghost.isValid()) ghost.remove();
                } catch (Throwable ignored) {}
            });
        }
        if (activeLabel != null) {
            TextDisplay label = activeLabel;
            activeLabel = null;
            PlatformUtil.runAtEntity(plugin, label, () -> {
                try {
                    if (label.isValid()) label.remove();
                } catch (Throwable ignored) {}
            });
        }
        this.isPaused = false;
        this.activeClipId = null;
    }

    public List<ReplayIncident> getIncidents() {
        return new ArrayList<>(incidentCache.values());
    }

    public ReplayIncident getIncident(String id) {
        if (id == null) return null;
        String safeId = id.replaceAll("[^a-zA-Z0-9_-]", "");
        ReplayIncident inc = incidentCache.get(safeId);
        if (inc != null && inc.frames.isEmpty()) {
            File f = new File(replayFolder, safeId + ".json");
            if (f.exists()) {
                try {
                    String content = java.nio.file.Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    inc.frames = parseFramesFromJson(content);
                } catch (Throwable ignored) {}
            }
        }
        return inc;
    }

    public String getIncidentJson(String id) {
        if (id == null) return null;
        String safeId = id.replaceAll("[^a-zA-Z0-9_-]", "");
        File file = new File(replayFolder, safeId + ".json");
        if (file.exists()) {
            try {
                return java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (Throwable ignored) {}
        }
        ReplayIncident inc = incidentCache.get(safeId);
        if (inc == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"id\": \"").append(inc.id).append("\",\n");
        sb.append("  \"playerName\": \"").append(escapeJson(inc.playerName)).append("\",\n");
        sb.append("  \"checkName\": \"").append(escapeJson(inc.checkName)).append("\",\n");
        sb.append("  \"vl\": ").append(String.format(Locale.ROOT, "%.1f", inc.vl)).append(",\n");
        sb.append("  \"worldName\": \"").append(escapeJson(inc.worldName)).append("\",\n");
        sb.append("  \"timestamp\": \"").append(escapeJson(inc.timestamp)).append("\",\n");
        sb.append("  \"frameCount\": ").append(inc.frames.size()).append(",\n");
        sb.append("  \"frames\": [\n");
        for (int i = 0; i < inc.frames.size(); i++) {
            ReplayFrame f = inc.frames.get(i);
            sb.append(String.format(Locale.ROOT,
                    "    [%.3f, %.3f, %.3f, %.2f, %.2f, %b, %b, \"%s\", %d, %.3f, %d]%s\n",
                    f.x(), f.y(), f.z(), f.yaw(), f.pitch(),
                    f.onGround(), f.sneaking(), escapeJson(f.action()),
                    f.cps(), f.speed(), f.time(),
                    (i < inc.frames.size() - 1 ? "," : "")
            ));
        }
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    public boolean deleteIncident(String id) {
        if (id == null) return false;
        String safeId = id.replaceAll("[^a-zA-Z0-9_-]", "");
        boolean removed = incidentCache.remove(safeId) != null;
        incidentOrder.remove(safeId);
        File file = new File(replayFolder, safeId + ".json");
        if (file.exists() && file.delete()) {
            removed = true;
        }
        return removed;
    }

    public void clearAllIncidents() {
        stopPlayback();
        incidentCache.clear();
        incidentOrder.clear();
        File[] files = replayFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    private static final Pattern FRAME_PATTERN = Pattern.compile(
            "\\[\\s*([\\d.-]+)\\s*,\\s*([\\d.-]+)\\s*,\\s*([\\d.-]+)\\s*,\\s*([\\d.-]+)\\s*,\\s*([\\d.-]+)\\s*,\\s*([a-zA-Z0-9]+)\\s*,\\s*([a-zA-Z0-9]+)\\s*,\\s*\"([^\"]*)\"\\s*,\\s*(\\d+)\\s*,\\s*([\\d.-]+)\\s*,\\s*(\\d+)\\s*\\]"
    );

    private List<ReplayFrame> parseFramesFromJson(String json) {
        List<ReplayFrame> list = new ArrayList<>();
        int framesIdx = json.indexOf("\"frames\":");
        if (framesIdx == -1) return list;

        Matcher m = FRAME_PATTERN.matcher(json.substring(framesIdx));
        while (m.find()) {
            try {
                double x = Double.parseDouble(m.group(1));
                double y = Double.parseDouble(m.group(2));
                double z = Double.parseDouble(m.group(3));
                float yaw = Float.parseFloat(m.group(4));
                float pitch = Float.parseFloat(m.group(5));
                boolean onGround = "true".equalsIgnoreCase(m.group(6)) || "1".equals(m.group(6));
                boolean sneaking = "true".equalsIgnoreCase(m.group(7)) || "1".equals(m.group(7));
                String action = m.group(8);
                int cps = Integer.parseInt(m.group(9));
                double speed = Double.parseDouble(m.group(10));
                long time = Long.parseLong(m.group(11));

                list.add(new ReplayFrame(x, y, z, yaw, pitch, onGround, sneaking, action, cps, speed, time));
            } catch (Throwable ignored) {}
        }
        return list;
    }

    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\\s*\"([^\"]+)\"";
        Matcher m = Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private double extractJsonDouble(String json, String key) {
        String pattern = "\"" + key + "\":\\s*([0-9.]+)";
        Matcher m = Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (Throwable ignored) {}
        }
        return 0.0;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
