package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
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
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * ReplayManager: Hệ thống ghi hình và phát lại hành vi vi phạm Anti-Cheat (/jac replay).
 * Duy trì Rolling Buffer 400 ticks (20 giây) vị trí, góc quay và hành vi của người chơi.
 * Khi phát hiện vi phạm nghiêm trọng, hệ thống lưu clip và cho phép Staff chiếu lại hiện trường
 * bằng Ghost Mannequin / Display Entity để kiểm tra chống Ban nhầm.
 */
@SuppressWarnings("deprecation")
public class ReplayManager {

    private final JavaStudioPlugin plugin;
    private final File replayFolder;

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
            this.frames = frames;
            this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }

    private static final int BUFFER_CAPACITY = 400; // 20 giây @ 20 ticks/sec
    private final Map<UUID, Deque<ReplayFrame>> playerBuffers = new ConcurrentHashMap<>();
    private final Map<String, ReplayIncident> incidentCache = new ConcurrentHashMap<>();

    private BukkitTask activePlaybackTask;
    private ArmorStand activeGhost;
    private TextDisplay activeLabel;

    public ReplayManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.replayFolder = new File(plugin.getDataFolder(), "replays");
        if (!replayFolder.exists()) replayFolder.mkdirs();
        loadSavedClips();
    }

    private void loadSavedClips() {
        File[] files = replayFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            try {
                String content = java.nio.file.Files.readString(f.toPath(), StandardCharsets.UTF_8);
                // Parse clip metadata nhanh
                String id = extractJsonString(content, "id");
                String name = extractJsonString(content, "playerName");
                String check = extractJsonString(content, "checkName");
                double vl = extractJsonDouble(content, "vl");
                String world = extractJsonString(content, "worldName");
                if (id != null && name != null) {
                    ReplayIncident inc = new ReplayIncident(id, name, check != null ? check : "UNKNOWN", vl, world != null ? world : "world", new ArrayList<>());
                    incidentCache.put(id, inc);
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Ghi nhận 1 frame chuyển động của người chơi vào Circular Buffer.
     */
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

    /**
     * Bắt và lưu lại clip vi phạm của người chơi khi bị cờ cấm (Flag).
     */
    public String captureIncident(Player player, String checkName, double vl) {
        Deque<ReplayFrame> buffer = playerBuffers.get(player.getUniqueId());
        if (buffer == null || buffer.isEmpty()) return null;

        String id = "clip_" + (System.currentTimeMillis() % 1_000_000);
        List<ReplayFrame> snapshot = new ArrayList<>(buffer);
        ReplayIncident incident = new ReplayIncident(id, player.getName(), checkName, vl, player.getWorld().getName(), snapshot);

        incidentCache.put(id, incident);

        // Lưu ra file JSON trên ổ đĩa bất đồng bộ
        PlatformUtil.runTaskAsync(plugin, () -> {
            try {
                File file = new File(replayFolder, id + ".json");
                StringBuilder sb = new StringBuilder();
                sb.append("{\n");
                sb.append("  \"id\": \"").append(incident.id).append("\",\n");
                sb.append("  \"playerName\": \"").append(incident.playerName).append("\",\n");
                sb.append("  \"checkName\": \"").append(incident.checkName).append("\",\n");
                sb.append("  \"vl\": ").append(incident.vl).append(",\n");
                sb.append("  \"worldName\": \"").append(incident.worldName).append("\",\n");
                sb.append("  \"timestamp\": \"").append(incident.timestamp).append("\",\n");
                sb.append("  \"frameCount\": ").append(snapshot.size()).append("\n");
                sb.append("}");
                java.nio.file.Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            } catch (Throwable ignored) {}
        });

        return id;
    }

    /**
     * Chiếu lại (Playback) hiện trường vi phạm bằng Ghost ArmorStand và TextDisplay.
     */
    public boolean playReplay(Player staff, String clipId) {
        ReplayIncident inc = incidentCache.get(clipId);
        if (inc == null || inc.frames.isEmpty()) return false;

        stopPlayback();

        World rawWorld = Bukkit.getWorld(inc.worldName);
        final World playWorld = rawWorld != null ? rawWorld : staff.getWorld();

        List<ReplayFrame> frames = inc.frames;
        ReplayFrame first = frames.get(0);
        Location startLoc = new Location(playWorld, first.x(), first.y(), first.z(), first.yaw(), first.pitch());

        staff.teleport(startLoc);

        // Tạo Ghost ArmorStand với trang bị trực quan
        activeGhost = playWorld.spawn(startLoc, ArmorStand.class, as -> {
            as.setVisible(true);
            as.setArms(true);
            as.setBasePlate(false);
            as.setGravity(false);
            as.setCustomName("§c[GHOST] " + inc.playerName);
            as.setCustomNameVisible(true);
            if (as.getEquipment() != null) {
                as.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
                as.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                as.getEquipment().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
            }
        });

        // Tạo TextDisplay thông số phía trên
        activeLabel = playWorld.spawn(startLoc.clone().add(0, 2.2, 0), TextDisplay.class, td -> {
            td.setBillboard(Display.Billboard.CENTER);
            td.setText(MessageUtil.colorize("&c&l[REPLAY] &e" + inc.playerName + " &7- &c" + inc.checkName + " &8(VL: " + inc.vl + ")"));
            td.setShadowed(true);
        });

        final int totalFrames = frames.size();
        final int[] frameIdx = {0};

        activePlaybackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (frameIdx[0] >= totalFrames || activeGhost == null || !activeGhost.isValid()) {
                stopPlayback();
                staff.sendMessage("§a[Replay] Đã chiếu lại xong clip §e" + clipId + "§a!");
                return;
            }

            ReplayFrame f = frames.get(frameIdx[0]++);
            Location fLoc = new Location(playWorld, f.x(), f.y(), f.z(), f.yaw(), f.pitch());
            activeGhost.teleport(fLoc);

            if ("ATTACK".equals(f.action())) {
                playWorld.spawnParticle(Particle.CRIT, fLoc.clone().add(0, 1.2, 0), 4, 0.15, 0.15, 0.15, 0.05);
            }

            if (activeLabel != null && activeLabel.isValid()) {
                activeLabel.teleport(fLoc.clone().add(0, 2.2, 0));
                activeLabel.setText(MessageUtil.colorize(String.format(
                        "&c&l[REPLAY] &e%s &7(&c%s&7)\n&fCPS: &a%d &7| Spd: &e%.2f &7| Action: &b%s &7| Tick: &6%d/%d",
                        inc.playerName, inc.checkName, f.cps(), f.speed(), f.action(), frameIdx[0], totalFrames
                )));
            }
        }, 1L, 1L);

        return true;
    }

    public void stopPlayback() {
        if (activePlaybackTask != null) {
            activePlaybackTask.cancel();
            activePlaybackTask = null;
        }
        if (activeGhost != null && activeGhost.isValid()) {
            activeGhost.remove();
            activeGhost = null;
        }
        if (activeLabel != null && activeLabel.isValid()) {
            activeLabel.remove();
            activeLabel = null;
        }
    }

    public List<ReplayIncident> getIncidents() {
        return new ArrayList<>(incidentCache.values());
    }

    public boolean deleteIncident(String id) {
        incidentCache.remove(id);
        File file = new File(replayFolder, id + ".json");
        if (file.exists()) file.delete();
        return true;
    }

    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\\s*\"([^\"]+)\"";
        var m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private double extractJsonDouble(String json, String key) {
        String pattern = "\"" + key + "\":\\s*([0-9.]+)";
        var m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (Throwable ignored) {}
        }
        return 0.0;
    }
}
