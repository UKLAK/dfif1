package com.javastudio.security;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("deprecation")
public class TotpManager {

    private final JavaStudioPlugin plugin;
    private final File storageFile;
    private final YamlConfiguration storage;

    private final Set<UUID> authenticatedSessions = ConcurrentHashMap.newKeySet();
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public TotpManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "totp_data.yml");
        this.storage = YamlConfiguration.loadConfiguration(storageFile);
    }

    public boolean isTotpEnabled(UUID uuid) {
        return storage.contains("users." + uuid + ".secret");
    }

    public boolean isAuthenticated(UUID uuid) {
        if (!isTotpEnabled(uuid)) return true;
        return authenticatedSessions.contains(uuid);
    }

    public void setAuthenticated(UUID uuid, boolean auth) {
        if (auth) authenticatedSessions.add(uuid);
        else authenticatedSessions.remove(uuid);
    }

    public void clearSession(UUID uuid) {
        authenticatedSessions.remove(uuid);
        if (plugin != null && plugin.getPanelCveProtection() != null) {
            plugin.getPanelCveProtection().getSessionRevocationManager().revokeAllForSubject(uuid.toString(), "Session Cleared");
        }
    }

    public String generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] buffer = new byte[10];
        random.nextBytes(buffer);
        StringBuilder sb = new StringBuilder();
        for (byte b : buffer) {
            sb.append(BASE32_CHARS.charAt((b & 0xFF) % BASE32_CHARS.length()));
        }
        return sb.toString();
    }

    public void saveSecret(UUID uuid, String secret) {
        storage.set("users." + uuid, null);
        storage.set("users." + uuid + ".secret", secret);
        storage.set("users." + uuid + ".enabledAt", System.currentTimeMillis());
        try {
            storage.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[TOTP] Không thể lưu secret vào file: " + e.getMessage());
        }
    }

    public void removeSecret(UUID uuid) {
        storage.set("users." + uuid, null);
        try {
            storage.save(storageFile);
        } catch (IOException ignored) {}
        authenticatedSessions.remove(uuid);
        if (plugin != null && plugin.getPanelCveProtection() != null) {
            plugin.getPanelCveProtection().getSessionRevocationManager().revokeAllForSubject(uuid.toString(), "2FA Secret Removed / Reset");
        }
    }

    public boolean verifyCode(UUID uuid, int inputCode) {
        String secret = storage.getString("users." + uuid + ".secret");
        if (secret == null) return false;

        long currentWindow = System.currentTimeMillis() / 1000L / 30L;
        for (int i = -1; i <= 1; i++) {
            if (generateTotpCode(secret, currentWindow + i) == inputCode) {
                authenticatedSessions.add(uuid);
                return true;
            }
        }
        return false;
    }

    public void giveQrSetupMap(Player player, String secret) {
        MapView view = Bukkit.createMap(player.getWorld());
        view.getRenderers().clear();

        view.addRenderer(new MapRenderer() {
            boolean rendered = false;
            @Override
            public void render(MapView map, MapCanvas canvas, Player p) {
                if (rendered) return;
                rendered = true;

                for (int x = 0; x < 128; x++) {
                    for (int y = 0; y < 128; y++) {
                        canvas.setPixel(x, y, (byte) 119);
                    }
                }

                for (int i = 8; i < 120; i++) {
                    canvas.setPixel(i, 8, (byte) 34);
                    canvas.setPixel(i, 119, (byte) 34);
                    canvas.setPixel(8, i, (byte) 34);
                    canvas.setPixel(119, i, (byte) 34);
                }

                for (int x = 24; x < 104; x += 4) {
                    for (int y = 24; y < 80; y += 4) {
                        boolean dot = ((x * 7 + y * 13 + secret.hashCode()) % 3 == 0);
                        byte color = dot ? (byte) 34 : (byte) 119;
                        for (int dx = 0; dx < 3; dx++) {
                            for (int dy = 0; dy < 3; dy++) {
                                canvas.setPixel(x + dx, y + dy, color);
                            }
                        }
                    }
                }

                canvas.drawText(16, 90, org.bukkit.map.MinecraftFont.Font, "§0KEY 2FA CỦA BẠN:");
                canvas.drawText(16, 102, org.bukkit.map.MinecraftFont.Font, "§4" + secret);
            }
        });

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            meta.setDisplayName("§e§lBản Đồ Cài Đặt 2FA Google Authenticator");
            meta.setLore(List.of(
                    "§7Nhập Secret Key này vào ứng dụng:",
                    "§b" + secret,
                    "§7Sau đó gõ: §a/jsecurity totp verify <6_số>"
            ));
            mapItem.setItemMeta(meta);
        }

        player.getInventory().addItem(mapItem);
        MessageUtil.sendSuccess(player, "Đã trao bản đồ cài đặt 2FA! Secret Key của bạn: &e" + secret);
        MessageUtil.sendInfo(player, "Hãy mở ứng dụng Google Authenticator -> Thêm thủ công -> Nhập key trên rồi gõ &a/jsecurity totp verify <6_số>&7.");
    }

    private int generateTotpCode(String secret, long timeWindow) {
        byte[] key = decodeBase32(secret);
        byte[] data = ByteBuffer.allocate(8).putLong(timeWindow).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) |
                    ((hash[offset + 1] & 0xFF) << 16) |
                    ((hash[offset + 2] & 0xFF) << 8) |
                    (hash[offset + 3] & 0xFF);

            return binary % 1_000_000;
        } catch (Exception e) {
            return -1;
        }
    }

    private byte[] decodeBase32(String secret) {
        secret = secret.trim().toUpperCase();
        List<Byte> bytes = new ArrayList<>();
        int buffer = 0;
        int bitsLeft = 0;

        for (char c : secret.toCharArray()) {
            int val = BASE32_CHARS.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bytes.add((byte) ((buffer >> (bitsLeft - 8)) & 0xFF));
                bitsLeft -= 8;
            }
        }
        byte[] result = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) result[i] = bytes.get(i);
        return result;
    }
}
