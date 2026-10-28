package com.javastudio.pack;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ResourcePackHost: Trình quản lý, đóng gói và phân phối Resource Pack động qua Web Server (Port 8088).
 * Tự động tạo tệp zip trong RAM, tính toán mã băm SHA-1 và ép nạp gói tài nguyên cho người chơi
 * qua lệnh /jpack hoặc API in-game mà không cần thoát server.
 */
@SuppressWarnings("deprecation")
public final class ResourcePackHost {

    private final JavaStudioPlugin plugin;
    private final File packDir;

    private volatile byte[] zipBytes = new byte[0];
    private volatile String sha1Hash = "";
    private volatile byte[] sha1Bytes = new byte[0];
    private volatile long lastBuilt = 0L;

    // Registry cho vật phẩm tùy biến, âm thanh và font glyph
    public record CustomItemDef(String id, Material baseMaterial, int customModelData, String displayName) {}
    public record CustomSoundDef(String key, String namespace) {}
    public record FontGlyphDef(String key, String glyphChar, String texturePath) {}

    private final Map<String, CustomItemDef> customItems = new ConcurrentHashMap<>();
    private final Map<String, CustomSoundDef> customSounds = new ConcurrentHashMap<>();
    private final Map<String, FontGlyphDef> fontGlyphs = new ConcurrentHashMap<>();

    public ResourcePackHost(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.packDir = new File(plugin.getDataFolder(), "pack");
        ensurePackStructure();
        buildPack();
    }

    /**
     * Khởi tạo cấu trúc thư mục Resource Pack mẫu nếu chưa tồn tại.
     */
    private void ensurePackStructure() {
        if (!packDir.exists()) {
            packDir.mkdirs();
        }

        File mcmeta = new File(packDir, "pack.mcmeta");
        if (!mcmeta.exists()) {
            String defaultMcmeta = """
                    {
                      "pack": {
                        "pack_format": 34,
                        "description": "§b§lJavaStudio Enterprise §7- Dynamic Assets Pack"
                      }
                    }
                    """;
            try {
                Files.writeString(mcmeta.toPath(), defaultMcmeta, StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }

        // Tạo sẵn các thư mục asset cơ bản
        new File(packDir, "assets/minecraft/textures/item").mkdirs();
        new File(packDir, "assets/minecraft/models/item").mkdirs();
        new File(packDir, "assets/minecraft/sounds").mkdirs();
        new File(packDir, "assets/minecraft/font").mkdirs();
    }

    /**
     * Nén toàn bộ thư mục pack/ thành một mảng byte ZIP trong RAM và tính SHA-1.
     */
    public final synchronized boolean buildPack() {
        try {
            ensurePackStructure();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                Path basePath = packDir.toPath();
                Files.walk(basePath).forEach(path -> {
                    if (Files.isDirectory(path)) return;
                    String rel = basePath.relativize(path).toString().replace(File.separatorChar, '/');
                    try {
                        zos.putNextEntry(new ZipEntry(rel));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        plugin.getLogger().warning("[ResourcePack] Lỗi nén file " + rel + ": " + e.getMessage());
                    }
                });
            }

            this.zipBytes = bos.toByteArray();
            this.sha1Hash = computeSha1(this.zipBytes);
            this.sha1Bytes = hexToBytes(this.sha1Hash);
            this.lastBuilt = System.currentTimeMillis();

            plugin.getLogger().info("[ResourcePack] Đã đóng gói thành công (" + (zipBytes.length / 1024) + " KB) | SHA-1: " + sha1Hash);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("[ResourcePack] Lỗi khi tạo file nén: " + e.getMessage());
            return false;
        }
    }

    /**
     * Lấy URL tải trực tiếp Resource Pack.
     */
    public String getDownloadUrl() {
        String customUrl = plugin.getConfig().getString("resource-pack.download-url", "");
        if (!customUrl.isBlank()) return customUrl;
        int port = plugin.getConfig().getInt("resource-pack.port", 8088);
        String host = plugin.getConfig().getString("resource-pack.host", "localhost");
        return "http://" + host + ":" + port + "/pack/download";
    }

    /**
     * Gửi yêu cầu nạp Resource Pack tới một người chơi cụ thể.
     */
    public boolean pushPack(Player player) {
        if (player == null || !player.isOnline()) return false;
        String url = getDownloadUrl();
        try {
            // Thử phương thức Paper/Bukkit có mã băm byte[]
            if (sha1Bytes.length == 20) {
                player.setResourcePack(url, sha1Bytes, "§e§lJavaStudio Resource Pack\n§7Tải gói giao diện và âm thanh tùy biến.", false);
            } else {
                player.setResourcePack(url, sha1Hash);
            }
            return true;
        } catch (Throwable t) {
            try {
                // Fallback cho Spigot truyền thống
                player.setResourcePack(url);
                return true;
            } catch (Throwable fallbackErr) {
                plugin.getLogger().warning("[ResourcePack] Không thể gửi pack cho " + player.getName() + ": " + fallbackErr.getMessage());
                return false;
            }
        }
    }

    /**
     * Gửi yêu cầu nạp Resource Pack tới toàn bộ người chơi đang online.
     */
    public int pushAll() {
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (pushPack(p)) count++;
        }
        return count;
    }

    // ==========================================
    // API ĐĂNG KÝ VẬT PHẨM & GIAO DIỆN TÙY BIẾN
    // ==========================================

    /**
     * Đăng ký một vật phẩm tùy biến bằng CustomModelData.
     */
    public void registerItem(String id, Material baseMaterial, int customModelData, String displayName) {
        customItems.put(id.toLowerCase(), new CustomItemDef(id, baseMaterial, customModelData, displayName));
    }

    /**
     * Tạo ItemStack tương ứng từ id vật phẩm đã đăng ký.
     */
    public ItemStack createCustomItem(String id, int amount) {
        CustomItemDef def = customItems.get(id.toLowerCase());
        if (def == null) return null;
        ItemStack item = new ItemStack(def.baseMaterial(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(def.customModelData());
            meta.setDisplayName(def.displayName().replace('&', '§'));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Đăng ký một âm thanh tùy biến (.ogg).
     */
    public void registerSound(String key, String namespace) {
        customSounds.put(key.toLowerCase(), new CustomSoundDef(key, namespace));
    }

    /**
     * Đăng ký một ký tự hình ảnh Font Image Offset cho Custom GUI.
     */
    public void registerFontGlyph(String key, String glyphChar, String texturePath) {
        fontGlyphs.put(key.toLowerCase(), new FontGlyphDef(key, glyphChar, texturePath));
    }

    public Map<String, CustomItemDef> getCustomItems() {
        return Collections.unmodifiableMap(customItems);
    }

    public Map<String, CustomSoundDef> getCustomSounds() {
        return Collections.unmodifiableMap(customSounds);
    }

    public Map<String, FontGlyphDef> getFontGlyphs() {
        return Collections.unmodifiableMap(fontGlyphs);
    }

    public byte[] getZipBytes() {
        return zipBytes;
    }

    public String getSha1Hash() {
        return sha1Hash;
    }

    public long getLastBuilt() {
        return lastBuilt;
    }

    public File getPackDir() {
        return packDir;
    }

    // ==========================================
    // TIỆN ÍCH HASHING
    // ==========================================

    private static String computeSha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() != 40) return new byte[0];
        byte[] bytes = new byte[20];
        for (int i = 0; i < 20; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
