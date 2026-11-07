package com.javastudio.pack;

import com.javastudio.JavaStudioPlugin;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@SuppressWarnings("deprecation")
public final class ResourcePackHost {

    private final JavaStudioPlugin plugin;
    private final File packDir;

    private HttpServer httpServer;
    private volatile boolean httpRunning = false;
    private volatile boolean muxModeActive = false;
    private volatile byte[] zipBytes = new byte[0];

    public record PackHttpResponse(int status, byte[] body, int contentLength, String sha1, String etag) {}
    private volatile String sha1Hash = "";
    private volatile byte[] sha1Bytes = new byte[0];
    private volatile long lastBuilt = 0L;
    private final AtomicLong totalDownloads = new AtomicLong(0);

    private final Map<String, long[]> ipRateWindow = new ConcurrentHashMap<>();

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

    public synchronized void start() {
        if (!plugin.getConfig().getBoolean("resource-pack.http-enabled", true)) {
            return;
        }
        stop();
        String singlePortMode = plugin.getConfig().getString("resource-pack.single-port", "auto");
        var mux = plugin.getSinglePortMuxEngine();
        if (mux != null && mux.shouldUseLoopbackBackend(singlePortMode)) {
            mux.start();
            muxModeActive = true;
            httpRunning = true;
            plugin.getLogger().info("[ResourcePack] Chế độ Panel 1-Port đang BẬT -> Phục vụ trực tiếp qua cổng Minecraft tại " + getDownloadUrl());
            return;
        }

        int port = plugin.getConfig().getInt("resource-pack.port", 8088);
        String bindHost = plugin.getConfig().getString("resource-pack.bind-address", "0.0.0.0");
        try {
            InetSocketAddress addr = (bindHost == null || bindHost.isBlank() || bindHost.equals("*"))
                    ? new InetSocketAddress(port)
                    : new InetSocketAddress(bindHost, port);
            httpServer = HttpServer.create(addr, 0);
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.createContext("/pack/download", this::handleDownload);
            httpServer.createContext("/pack.zip", this::handleDownload);
            httpServer.start();
            muxModeActive = false;
            httpRunning = true;
            plugin.getLogger().info("[ResourcePack] Embedded HTTP Host đang phục vụ tại " + getDownloadUrl());
        } catch (Exception e) {
            if (mux != null && !"false".equalsIgnoreCase(singlePortMode) && !"off".equalsIgnoreCase(singlePortMode)) {
                mux.start();
                muxModeActive = true;
                httpRunning = true;
                plugin.getLogger().info("[ResourcePack] Cổng phụ " + port + " bận -> Đã tự động gộp chung vào cổng Minecraft (Single-Port): " + getDownloadUrl());
            } else {
                httpRunning = false;
                muxModeActive = false;
                plugin.getLogger().warning("[ResourcePack] Không thể mở cổng HTTP " + port + ": " + e.getMessage());
            }
        }
    }

    public synchronized void stop() {
        httpRunning = false;
        muxModeActive = false;
        if (httpServer != null) {
            try {
                httpServer.stop(0);
            } catch (Exception ignored) {}
            httpServer = null;
        }
        ipRateWindow.clear();
    }

    public PackHttpResponse prepareDirectDownload(String method, String clientIp, String ifNoneMatch) {
        if (!plugin.getConfig().getBoolean("resource-pack.http-enabled", true)) {
            return new PackHttpResponse(404, null, 0, "", "");
        }
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return new PackHttpResponse(405, null, 0, "", "");
        }
        String ip = (clientIp != null && !clientIp.isBlank()) ? clientIp : "unknown";
        if (!allowDownload(ip)) {
            return new PackHttpResponse(429, null, 0, "", "");
        }
        byte[] data = this.zipBytes;
        String hash = this.sha1Hash;
        String etag = "\"" + hash + "\"";
        if (data == null || data.length == 0) {
            return new PackHttpResponse(404, null, 0, "", "");
        }
        if (ifNoneMatch != null && ifNoneMatch.trim().equals(etag)) {
            return new PackHttpResponse(304, null, 0, hash, etag);
        }
        if ("HEAD".equalsIgnoreCase(method)) {
            return new PackHttpResponse(200, null, data.length, hash, etag);
        }
        totalDownloads.incrementAndGet();
        return new PackHttpResponse(200, data, data.length, hash, etag);
    }

    private void handleDownload(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String ip = exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null
                    ? exchange.getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            if (!allowDownload(ip)) {
                exchange.sendResponseHeaders(429, -1);
                return;
            }

            byte[] data = this.zipBytes;
            String etag = "\"" + this.sha1Hash + "\"";
            if (data == null || data.length == 0) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/zip");
            headers.set("Content-Disposition", "attachment; filename=\"javastudio-pack.zip\"");
            headers.set("ETag", etag);
            headers.set("X-Pack-SHA1", this.sha1Hash);
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Cache-Control", "public, max-age=60");

            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (ifNoneMatch != null && ifNoneMatch.trim().equals(etag)) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }

            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            totalDownloads.incrementAndGet();
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        } finally {
            exchange.close();
        }
    }

    private boolean allowDownload(String ip) {
        long now = System.currentTimeMillis();
        long[] bucket = ipRateWindow.computeIfAbsent(ip, k -> new long[]{now, 0L});
        synchronized (bucket) {
            if (now - bucket[0] > 60_000L) {
                bucket[0] = now;
                bucket[1] = 1L;
                return true;
            }
            bucket[1]++;
            return bucket[1] <= 30L;
        }
    }

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
                        "supported_formats": { "min_inclusive": 15, "max_inclusive": 64 },
                        "description": "§b§lJavaStudio Enterprise §7- Dynamic Assets Pack"
                      }
                    }
                    """;
            try {
                Files.writeString(mcmeta.toPath(), defaultMcmeta, StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }

        new File(packDir, "assets/minecraft/textures/item").mkdirs();
        new File(packDir, "assets/minecraft/models/item").mkdirs();
        new File(packDir, "assets/minecraft/sounds").mkdirs();
        new File(packDir, "assets/minecraft/font").mkdirs();
    }

    private void writeGeneratedManifests() {
        if (!customItems.isEmpty()) {
            Map<Material, List<CustomItemDef>> byMat = new EnumMap<>(Material.class);
            for (CustomItemDef def : customItems.values()) {
                byMat.computeIfAbsent(def.baseMaterial(), k -> new ArrayList<>()).add(def);
            }
            for (Map.Entry<Material, List<CustomItemDef>> entry : byMat.entrySet()) {
                String matKey = entry.getKey().name().toLowerCase(Locale.ROOT);
                List<CustomItemDef> defs = entry.getValue();
                defs.sort(Comparator.comparingInt(CustomItemDef::customModelData));

                String parent = entry.getKey().name().endsWith("_SWORD") || entry.getKey().name().endsWith("_AXE")
                        || entry.getKey().name().endsWith("_PICKAXE") || entry.getKey().name().endsWith("_ROD")
                        ? "minecraft:item/handheld"
                        : "minecraft:item/generated";

                StringBuilder sb = new StringBuilder();
                sb.append("{\n");
                sb.append("  \"parent\": \"").append(parent).append("\",\n");
                sb.append("  \"textures\": {\n");
                sb.append("    \"layer0\": \"minecraft:item/").append(matKey).append("\"\n");
                sb.append("  },\n");
                sb.append("  \"overrides\": [\n");
                for (int i = 0; i < defs.size(); i++) {
                    CustomItemDef d = defs.get(i);
                    String safeId = d.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
                    sb.append("    { \"predicate\": { \"custom_model_data\": ").append(d.customModelData())
                            .append(" }, \"model\": \"javastudio:item/").append(safeId).append("\" }");
                    if (i < defs.size() - 1) sb.append(",");
                    sb.append("\n");

                    File customModelDir = new File(packDir, "assets/javastudio/models/item");
                    customModelDir.mkdirs();
                    File customModelFile = new File(customModelDir, safeId + ".json");
                    if (!customModelFile.exists()) {
                        String itemJson = "{\n  \"parent\": \"" + parent + "\",\n  \"textures\": {\n    \"layer0\": \"javastudio:item/" + safeId + "\"\n  }\n}\n";
                        try {
                            Files.writeString(customModelFile.toPath(), itemJson, StandardCharsets.UTF_8);
                        } catch (IOException ignored) {}
                    }
                }
                sb.append("  ]\n}\n");

                File modelFile = new File(packDir, "assets/minecraft/models/item/" + matKey + ".json");
                try {
                    Files.writeString(modelFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
                } catch (IOException ignored) {}
            }
        }

        if (!customSounds.isEmpty()) {
            StringBuilder sb = new StringBuilder("{\n");
            List<CustomSoundDef> list = new ArrayList<>(customSounds.values());
            for (int i = 0; i < list.size(); i++) {
                CustomSoundDef s = list.get(i);
                String soundEvent = s.namespace().contains(":") ? s.namespace().substring(s.namespace().indexOf(':') + 1) : s.key();
                String soundPath = soundEvent.replace('.', '/');
                sb.append("  \"").append(escapeJson(soundEvent)).append("\": {\n");
                sb.append("    \"sounds\": [ \"").append(escapeJson(soundPath)).append("\" ]\n");
                sb.append("  }");
                if (i < list.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("}\n");
            File soundsFile = new File(packDir, "assets/javastudio/sounds.json");
            if (soundsFile.getParentFile() != null) soundsFile.getParentFile().mkdirs();
            try {
                Files.writeString(soundsFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }

        if (!fontGlyphs.isEmpty()) {
            StringBuilder sb = new StringBuilder("{\n  \"providers\": [\n");
            List<FontGlyphDef> list = new ArrayList<>(fontGlyphs.values());
            for (int i = 0; i < list.size(); i++) {
                FontGlyphDef g = list.get(i);
                String fileRef = g.texturePath().startsWith("textures/") ? g.texturePath().substring("textures/".length()) : g.texturePath();
                if (!fileRef.contains(":")) fileRef = "minecraft:" + fileRef;
                sb.append("    {\n");
                sb.append("      \"type\": \"bitmap\",\n");
                sb.append("      \"file\": \"").append(escapeJson(fileRef)).append("\",\n");
                sb.append("      \"ascent\": 8,\n");
                sb.append("      \"height\": 8,\n");
                sb.append("      \"chars\": [ \"").append(escapeUnicode(g.glyphChar())).append("\" ]\n");
                sb.append("    }");
                if (i < list.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ]\n}\n");
            File fontFile = new File(packDir, "assets/minecraft/font/default.json");
            try {
                Files.writeString(fontFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeUnicode(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            sb.append(String.format("\\u%04x", (int) s.charAt(i)));
        }
        return sb.toString();
    }

    public final synchronized boolean buildPack() {
        try {
            ensurePackStructure();
            writeGeneratedManifests();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                Path basePath = packDir.toPath();
                List<Path> sortedFiles = new ArrayList<>();
                try (var stream = Files.walk(basePath)) {
                    stream.filter(p -> !Files.isDirectory(p)).forEach(sortedFiles::add);
                }
                sortedFiles.sort(Comparator.comparing(p -> basePath.relativize(p).toString()));

                for (Path path : sortedFiles) {
                    String rel = basePath.relativize(path).toString().replace(File.separatorChar, '/');
                    try {
                        ZipEntry entry = new ZipEntry(rel);
                        entry.setTime(0L);
                        zos.putNextEntry(entry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        plugin.getLogger().warning("[ResourcePack] Lỗi nén file " + rel + ": " + e.getMessage());
                    }
                }
            }

            this.zipBytes = bos.toByteArray();
            this.sha1Hash = computeSha1(this.zipBytes);
            this.sha1Bytes = hexToBytes(this.sha1Hash);
            this.lastBuilt = System.currentTimeMillis();

            plugin.getLogger().info("[ResourcePack] Đã đóng gói thành công (" + Math.max(1, zipBytes.length / 1024) + " KB) | SHA-1: " + sha1Hash);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("[ResourcePack] Lỗi khi tạo file nén: " + e.getMessage());
            return false;
        }
    }

    public String getDownloadUrl() {
        return getDownloadUrl(null);
    }

    public String getDownloadUrl(Player viewer) {
        String customUrl = plugin.getConfig().getString("resource-pack.download-url", "");
        if (!customUrl.isBlank()) return customUrl;
        String host = plugin.getConfig().getString("resource-pack.host", "127.0.0.1");
        int publicPort = plugin.getConfig().getInt("resource-pack.public-port", 0);
        var mux = plugin.getSinglePortMuxEngine();
        boolean useMuxUrl = muxModeActive
                || (mux != null && mux.isActive() && viewer != null
                && com.javastudio.network.SinglePortMuxEngine.isLoopbackOrWildcard(host));
        if (useMuxUrl && mux != null) {
            String endpoint = mux.resolvePublicEndpoint(host, publicPort, viewer);
            return "http://" + endpoint + "/pack.zip";
        }
        int port = plugin.getConfig().getInt("resource-pack.port", 8088);
        return "http://" + host + ":" + port + "/pack/download";
    }

    public boolean isSinglePortMode() {
        return muxModeActive;
    }

    public boolean pushPack(Player player) {
        if (player == null || !player.isOnline()) return false;
        String url = getDownloadUrl(player);
        try {
            if (sha1Bytes.length == 20) {
                player.setResourcePack(url, sha1Bytes, "§e§lJavaStudio Resource Pack\n§7Tải gói giao diện và âm thanh tùy biến.", false);
            } else {
                player.setResourcePack(url, sha1Hash);
            }
            return true;
        } catch (Throwable t) {
            try {
                player.setResourcePack(url);
                return true;
            } catch (Throwable fallbackErr) {
                plugin.getLogger().warning("[ResourcePack] Không thể gửi pack cho " + player.getName() + ": " + fallbackErr.getMessage());
                return false;
            }
        }
    }

    public int pushAll() {
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (pushPack(p)) count++;
        }
        return count;
    }

    public void registerItem(String id, Material baseMaterial, int customModelData, String displayName) {
        customItems.put(id.toLowerCase(Locale.ROOT), new CustomItemDef(id, baseMaterial, customModelData, displayName));
    }

    public ItemStack createCustomItem(String id, int amount) {
        CustomItemDef def = customItems.get(id.toLowerCase(Locale.ROOT));
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

    public void registerSound(String key, String namespace) {
        customSounds.put(key.toLowerCase(Locale.ROOT), new CustomSoundDef(key, namespace));
    }

    public void registerFontGlyph(String key, String glyphChar, String texturePath) {
        fontGlyphs.put(key.toLowerCase(Locale.ROOT), new FontGlyphDef(key, glyphChar, texturePath));
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

    public boolean isHttpRunning() {
        return httpRunning;
    }

    public long getTotalDownloads() {
        return totalDownloads.get();
    }

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
