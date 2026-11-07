package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ClusterInventorySync implements Listener {

    private final JavaStudioPlugin plugin;
    private final File backupDir;
    private final Set<UUID> syncingPlayers = ConcurrentHashMap.newKeySet();

    public ClusterInventorySync(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.backupDir = new File(plugin.getDataFolder(), "inv_backups");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!plugin.getSettingsManager().isInventorySyncEnabled()) return;
        Player player = event.getPlayer();
        syncPlayerToCluster(player, false, null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getSettingsManager().isInventorySyncEnabled()) return;
        Player player = event.getPlayer();
        restorePlayerFromCluster(player, false, null);
    }

    public void syncPlayerToCluster(Player player, boolean isManual, org.bukkit.command.CommandSender feedback) {
        if (player == null || !player.isOnline()) return;
        ClusterMeshEngine mesh = plugin.getClusterMeshEngine();
        if (mesh == null || !mesh.isRunning()) {
            if (feedback != null) feedback.sendMessage("§cCluster Mesh Engine chưa chạy!");
            return;
        }

        UUID uuid = player.getUniqueId();
        syncingPlayers.add(uuid);

        PlatformUtil.runTaskAsync(plugin, () -> {
            String lockKey = "invlock:" + uuid;
            boolean locked = mesh.tryAcquireLock(lockKey, mesh.getNodeId(), 15_000L);
            if (!locked) {
                plugin.getLogger().warning("[ClusterMesh] Không thể khóa invlock cho " + player.getName() + " (Đang bị node khác giữ)");
                syncingPlayers.remove(uuid);
                if (feedback != null) feedback.sendMessage("§cKhông thể chiếm khóa đồng bộ phân tán cho người chơi này!");
                return;
            }

            try {
                String payload = serializePlayerData(player, mesh.getNodeId());
                saveLocalBackup(uuid, payload);
                mesh.putClusterState("inv_data:" + uuid, payload);
                if (feedback != null) {
                    feedback.sendMessage("§a✔ Đã đồng bộ dữ liệu người chơi §e" + player.getName() + " §avào cụm phân tán thành công!");
                }
            } catch (Throwable t) {
                plugin.getLogger().severe("[ClusterMesh] Lỗi đồng bộ kho đồ: " + t.getMessage());
                if (feedback != null) feedback.sendMessage("§cLỗi đồng bộ kho đồ: " + t.getMessage());
            } finally {
                syncingPlayers.remove(uuid);
                if (isManual) {
                    mesh.releaseLock(lockKey, mesh.getNodeId());
                }
            }
        });
    }

    public void restorePlayerFromCluster(Player player, boolean isManual, org.bukkit.command.CommandSender feedback) {
        if (player == null) return;
        ClusterMeshEngine mesh = plugin.getClusterMeshEngine();
        if (mesh == null || !mesh.isRunning()) {
            if (feedback != null) feedback.sendMessage("§cCluster Mesh Engine chưa chạy!");
            return;
        }

        UUID uuid = player.getUniqueId();
        PlatformUtil.runTaskAsync(plugin, () -> {
            String kvKey = "inv_data:" + uuid;
            String data = mesh.getClusterState(kvKey);
            if (data == null || data.isBlank()) {
                if (feedback != null) feedback.sendMessage("§7Không tìm thấy bản ghi kho đồ cụm cho người chơi " + player.getName());
                return;
            }

            String lockKey = "invlock:" + uuid;
            boolean locked = mesh.tryAcquireLock(lockKey, mesh.getNodeId(), 10_000L);
            if (!locked) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {}
                data = mesh.getClusterState(kvKey);
                if (data == null || data.isBlank()) return;
            }

            final String payload = data;
            PlatformUtil.runTask(plugin, () -> {
                try {
                    boolean success = applyPlayerData(player, payload);
                    if (success) {
                        PlatformUtil.runTaskAsync(plugin, () -> {
                            mesh.removeClusterState(kvKey);
                            mesh.releaseLock(lockKey, mesh.getNodeId());
                        });
                        player.sendMessage("§8[§6ClusterMesh§8] §aĐã đồng bộ kho đồ từ cụm máy chủ thành công (Zero-Dupe Verified).");
                        if (feedback != null) feedback.sendMessage("§a✔ Đã khôi phục kho đồ cụm cho §e" + player.getName() + " §athành công!");
                    } else {
                        player.sendMessage("§8[§6ClusterMesh§8] §cPhát hiện lỗi checksum dữ liệu cụm. Giữ nguyên kho đồ hiện tại để bảo toàn.");
                        if (feedback != null) feedback.sendMessage("§c✖ Lỗi xác thực checksum dữ liệu kho đồ cụm!");
                    }
                } catch (Throwable t) {
                    plugin.getLogger().severe("[ClusterMesh] Lỗi nạp kho đồ: " + t.getMessage());
                    if (feedback != null) feedback.sendMessage("§cLỗi khôi phục kho đồ: " + t.getMessage());
                }
            });
        });
    }

    private String serializePlayerData(Player player, String sourceNode) throws Exception {
        String invB64 = serializeItemStacks(player.getInventory().getContents());
        String armorB64 = serializeItemStacks(player.getInventory().getArmorContents());
        String offhandB64 = serializeItemStacks(new ItemStack[]{player.getInventory().getItemInOffHand()});
        String ecB64 = serializeItemStacks(player.getEnderChest().getContents());

        String checksum = computeSha256(invB64 + "|" + armorB64 + "|" + offhandB64 + "|" + ecB64);
        String effectsStr = serializePotionEffects(player.getActivePotionEffects());

        return "{" +
                "\"uuid\":\"" + player.getUniqueId() + "\"," +
                "\"source\":\"" + sourceNode + "\"," +
                "\"time\":" + System.currentTimeMillis() + "," +
                "\"checksum\":\"" + checksum + "\"," +
                "\"health\":" + player.getHealth() + "," +
                "\"food\":" + player.getFoodLevel() + "," +
                "\"saturation\":" + player.getSaturation() + "," +
                "\"level\":" + player.getLevel() + "," +
                "\"exp\":" + player.getExp() + "," +
                "\"totalExp\":" + player.getTotalExperience() + "," +
                "\"gamemode\":\"" + player.getGameMode().name() + "\"," +
                "\"inv\":\"" + invB64 + "\"," +
                "\"armor\":\"" + armorB64 + "\"," +
                "\"offhand\":\"" + offhandB64 + "\"," +
                "\"ec\":\"" + ecB64 + "\"," +
                "\"effects\":\"" + effectsStr + "\"" +
                "}";
    }

    private boolean applyPlayerData(Player player, String json) {
        try {
            String invB64 = extractField(json, "inv");
            String armorB64 = extractField(json, "armor");
            String offhandB64 = extractField(json, "offhand");
            String ecB64 = extractField(json, "ec");
            String checksum = extractField(json, "checksum");

            String expectedHash = computeSha256(invB64 + "|" + armorB64 + "|" + offhandB64 + "|" + ecB64);
            if (!expectedHash.equalsIgnoreCase(checksum)) {
                plugin.getLogger().warning("[ClusterMesh] Hủy áp dụng dữ liệu người chơi " + player.getName() + ": Sai SHA-256 Checksum!");
                return false;
            }

            ItemStack[] inv = deserializeItemStacks(invB64);
            ItemStack[] armor = deserializeItemStacks(armorB64);
            ItemStack[] offhand = deserializeItemStacks(offhandB64);
            ItemStack[] ec = deserializeItemStacks(ecB64);

            player.getInventory().clear();
            if (inv != null && inv.length > 0) player.getInventory().setContents(inv);
            if (armor != null && armor.length > 0) player.getInventory().setArmorContents(armor);
            if (offhand != null && offhand.length > 0) player.getInventory().setItemInOffHand(offhand[0]);
            if (ec != null && ec.length > 0) player.getEnderChest().setContents(ec);

            double health = parseDouble(extractField(json, "health"), 20.0);
            int food = parseInt(extractField(json, "food"), 20);
            float sat = (float) parseDouble(extractField(json, "saturation"), 5.0);
            int level = parseInt(extractField(json, "level"), 0);
            float exp = (float) parseDouble(extractField(json, "exp"), 0.0);
            int totalExp = parseInt(extractField(json, "totalExp"), 0);
            String gmStr = extractField(json, "gamemode");

            if (health > 0) player.setHealth(Math.min(health, player.getMaxHealth()));
            player.setFoodLevel(food);
            player.setSaturation(sat);
            player.setLevel(level);
            player.setExp(exp);
            player.setTotalExperience(totalExp);

            if (gmStr != null && !gmStr.isBlank()) {
                try {
                    player.setGameMode(GameMode.valueOf(gmStr));
                } catch (IllegalArgumentException ignored) {}
            }

            String effectsStr = extractField(json, "effects");
            if (effectsStr != null && !effectsStr.isBlank()) {
                for (PotionEffect pe : player.getActivePotionEffects()) {
                    player.removePotionEffect(pe.getType());
                }
                applyPotionEffects(player, effectsStr);
            }

            return true;
        } catch (Throwable t) {
            plugin.getLogger().severe("[ClusterMesh] Thất bại khi phân giải dữ liệu kho đồ: " + t.getMessage());
            return false;
        }
    }

    private void saveLocalBackup(UUID uuid, String json) {
        try {
            File f = new File(backupDir, uuid.toString() + ".json");
            Files.writeString(f.toPath(), json, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
    }

    private String serializeItemStacks(ItemStack[] items) {
        if (items == null || items.length == 0) return "";
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeInt(items.length);
            for (ItemStack item : items) {
                oos.writeObject(item);
            }
            oos.flush();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Throwable t) {
            return "";
        }
    }

    private ItemStack[] deserializeItemStacks(String base64) {
        if (base64 == null || base64.isEmpty()) return new ItemStack[0];
        try (ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            int len = ois.readInt();
            ItemStack[] items = new ItemStack[len];
            for (int i = 0; i < len; i++) {
                items[i] = (ItemStack) ois.readObject();
            }
            return items;
        } catch (Throwable t) {
            return new ItemStack[0];
        }
    }

    private String serializePotionEffects(Collection<PotionEffect> effects) {
        if (effects == null || effects.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (PotionEffect pe : effects) {
            if (sb.length() > 0) sb.append(";");
            sb.append(pe.getType().getName()).append(",")
                    .append(pe.getDuration()).append(",")
                    .append(pe.getAmplifier()).append(",")
                    .append(pe.isAmbient()).append(",")
                    .append(pe.hasParticles()).append(",")
                    .append(pe.hasIcon());
        }
        return sb.toString();
    }

    private void applyPotionEffects(Player player, String str) {
        String[] entries = str.split(";");
        for (String entry : entries) {
            String[] p = entry.split(",");
            if (p.length < 6) continue;
            try {
                PotionEffectType type = PotionEffectType.getByName(p[0]);
                if (type == null) continue;
                int duration = Integer.parseInt(p[1]);
                int amplifier = Integer.parseInt(p[2]);
                boolean ambient = Boolean.parseBoolean(p[3]);
                boolean particles = Boolean.parseBoolean(p[4]);
                boolean icon = Boolean.parseBoolean(p[5]);
                player.addPotionEffect(new PotionEffect(type, duration, amplifier, ambient, particles, icon));
            } catch (Throwable ignored) {}
        }
    }

    private String computeSha256(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String extractField(String json, String key) {
        String pat = "\"" + key + "\":";
        int idx = json.indexOf(pat);
        if (idx == -1) return "";
        int start = idx + pat.length();
        if (start >= json.length()) return "";
        char first = json.charAt(start);
        if (first == '\"') {
            int end = json.indexOf('\"', start + 1);
            if (end == -1) return "";
            return json.substring(start + 1, end);
        } else {
            int end = json.indexOf(',', start);
            if (end == -1) end = json.indexOf('}', start);
            if (end == -1) return json.substring(start).trim();
            return json.substring(start, end).trim();
        }
    }

    private double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
