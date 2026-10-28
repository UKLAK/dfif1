package com.javastudio.export;

import com.javastudio.JavaStudioPlugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * LicenseManager: Hệ thống bảo vệ bản quyền thương mại hóa và chống rò rỉ mã nguồn (Anti-Leak).
 * - Sử dụng thuật toán mật mã học HMAC-SHA256 để ký và xác thực chữ ký số độc lập.
 * - Hỗ trợ khóa theo thời gian hết hạn (Timestamp Expiration) và giới hạn IP/Port máy chủ.
 * - Anti-Leak Watermarking: Tự động đóng dấu thủy vân định danh người mua vào JAR và Bytecode.
 */
public class LicenseManager {

    @SuppressWarnings("unused")
    private final JavaStudioPlugin plugin;
    private final String secretKey;

    public record LicensePayload(
            String pluginName,
            String buyer,
            long expiryTimestamp,
            String boundIp,
            int boundPort,
            long issuedTimestamp
    ) {
        public boolean isExpired() {
            return expiryTimestamp > 0 && System.currentTimeMillis() > expiryTimestamp;
        }

        public String getExpiryFormatted() {
            if (expiryTimestamp <= 0) return "Vĩnh viễn (Lifetime)";
            return new Date(expiryTimestamp).toString();
        }
    }

    public record VerificationResult(
            boolean valid,
            String message,
            LicensePayload payload
    ) {}

    public LicenseManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        String cfgSecret = plugin.getConfig().getString("licensing.secret-key");
        if (cfgSecret == null || cfgSecret.isBlank() || "CHANGE_ME_SECRET".equals(cfgSecret)) {
            // Tự động tạo secret key an toàn cố định cho server nếu chưa cấu hình
            this.secretKey = "JS-SEC-" + UUID.nameUUIDFromBytes(plugin.getDataFolder().getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        } else {
            this.secretKey = cfgSecret;
        }
    }

    /**
     * Tạo khóa bản quyền HMAC-SHA256 có chữ ký số bảo mật.
     *
     * @param pluginName     Tên dynamic plugin được cấp phép
     * @param buyer          Tên hoặc UUID người mua (vd: Customer_Steve)
     * @param durationDays   Số ngày sử dụng (0 = vĩnh viễn)
     * @param boundIp        IP máy chủ được phép chạy (null/trống = không giới hạn)
     * @param boundPort      Port máy chủ được phép chạy (0 = không giới hạn)
     * @return Chuỗi License Key: payloadBase64.hmacSignatureHex
     */
    public String generateLicense(String pluginName, String buyer, long durationDays, String boundIp, int boundPort) {
        long now = System.currentTimeMillis();
        long expiry = (durationDays > 0) ? now + (durationDays * 86_400_000L) : -1L;
        String cleanIp = (boundIp != null && !boundIp.isBlank() && !boundIp.equals("*")) ? boundIp.trim() : "*";

        String rawPayload = String.format(Locale.ROOT, "%s|%s|%d|%s|%d|%d",
                pluginName.trim(), buyer.trim(), expiry, cleanIp, boundPort, now);

        String payloadBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(rawPayload.getBytes(StandardCharsets.UTF_8));
        String signature = computeHmacSha256(payloadBase64, secretKey);

        return payloadBase64 + "." + signature;
    }

    /**
     * Xác thực tính hợp lệ của License Key.
     */
    public VerificationResult verifyLicense(String licenseKey, String expectedPlugin, String currentIp, int currentPort) {
        if (licenseKey == null || !licenseKey.contains(".")) {
            return new VerificationResult(false, "Định dạng License Key không hợp lệ!", null);
        }

        String[] parts = licenseKey.split("\\.", 2);
        String payloadBase64 = parts[0];
        String signature = parts[1];

        // 1. Kiểm tra chữ ký số HMAC-SHA256
        String expectedSig = computeHmacSha256(payloadBase64, secretKey);
        if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), expectedSig.getBytes(StandardCharsets.UTF_8))) {
            return new VerificationResult(false, "Chữ ký số không khớp! Khóa đã bị giả mạo hoặc cấp bởi secret key khác.", null);
        }

        // 2. Giải mã Payload
        LicensePayload payload = decodePayload(payloadBase64);
        if (payload == null) {
            return new VerificationResult(false, "Không thể đọc nội dung License Payload!", null);
        }

        // 3. Kiểm tra tên Plugin
        if (expectedPlugin != null && !expectedPlugin.equalsIgnoreCase(payload.pluginName())) {
            return new VerificationResult(false, "Khóa này thuộc về plugin '" + payload.pluginName() + "', không phải '" + expectedPlugin + "'!", payload);
        }

        // 4. Kiểm tra thời hạn
        if (payload.isExpired()) {
            return new VerificationResult(false, "Khóa bản quyền đã hết hạn vào lúc " + payload.getExpiryFormatted() + "!", payload);
        }

        // 5. Kiểm tra ràng buộc IP máy chủ (nếu có)
        if (!"*".equals(payload.boundIp()) && currentIp != null && !currentIp.isBlank()) {
            if (!payload.boundIp().equalsIgnoreCase(currentIp) && !"127.0.0.1".equals(currentIp) && !"localhost".equalsIgnoreCase(currentIp)) {
                return new VerificationResult(false, "IP máy chủ không khớp (" + currentIp + " != " + payload.boundIp() + ")!", payload);
            }
        }

        // 6. Kiểm tra ràng buộc Port (nếu có)
        if (payload.boundPort() > 0 && currentPort > 0 && payload.boundPort() != currentPort) {
            return new VerificationResult(false, "Cổng máy chủ không khớp (" + currentPort + " != " + payload.boundPort() + ")!", payload);
        }

        return new VerificationResult(true, "Xác minh thành công (Hợp lệ)", payload);
    }

    /**
     * Đọc và giải mã thông tin License mà không cần secret key.
     */
    public LicensePayload parseLicense(String licenseKey) {
        if (licenseKey == null || !licenseKey.contains(".")) return null;
        String payloadBase64 = licenseKey.split("\\.", 2)[0];
        return decodePayload(payloadBase64);
    }

    private LicensePayload decodePayload(String payloadBase64) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(payloadBase64);
            String raw = new String(bytes, StandardCharsets.UTF_8);
            String[] tokens = raw.split("\\|");
            if (tokens.length < 6) return null;

            String plugin = tokens[0];
            String buyer = tokens[1];
            long expiry = Long.parseLong(tokens[2]);
            String ip = tokens[3];
            int port = Integer.parseInt(tokens[4]);
            long issued = Long.parseLong(tokens[5]);

            return new LicensePayload(plugin, buyer, expiry, ip, port, issued);
        } catch (Exception e) {
            return null;
        }
    }

    // ==========================================
    // ANTI-LEAK WATERMARKING
    // ==========================================

    /**
     * Tạo nội dung tệp Watermark ẩn được nhúng vào file JAR.
     */
    public String createWatermarkMetadata(String buyer, String pluginName) {
        long now = System.currentTimeMillis();
        String raw = "WATERMARK_BUYER=" + buyer + "\nPLUGIN=" + pluginName + "\nTIMESTAMP=" + now + "\nCHECKSUM=" + computeHmacSha256(buyer + ":" + pluginName, secretKey);
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Đọc và truy vết nguồn rò rỉ từ file JAR.
     */
    public String traceLeakFromJar(File jarFile) {
        if (!jarFile.exists()) return "Không tìm thấy file " + jarFile.getName();

        try (JarFile jf = new JarFile(jarFile)) {
            JarEntry entry = jf.getJarEntry("META-INF/BUYER-WATERMARK.INF");
            if (entry != null) {
                try (InputStream is = jf.getInputStream(entry)) {
                    String base64 = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
                    String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
                    return "Tìm thấy thủy vân Anti-Leak:\n" + decoded;
                }
            }

            // Quét license key nếu có
            JarEntry licEntry = jf.getJarEntry("javastudio_license.key");
            if (licEntry != null) {
                try (InputStream is = jf.getInputStream(licEntry)) {
                    String lic = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
                    LicensePayload p = parseLicense(lic);
                    if (p != null) {
                        return "Truy vết được từ License Key:\n- Người Mua: " + p.buyer()
                                + "\n- Plugin: " + p.pluginName()
                                + "\n- Hạn Dùng: " + p.getExpiryFormatted()
                                + "\n- IP Khóa: " + p.boundIp();
                    }
                }
            }

            return "Không tìm thấy dấu vết thủy vân (JAR không được gắn license hoặc đã bị chỉnh sửa).";
        } catch (Exception e) {
            return "Lỗi đọc file JAR: " + e.getMessage();
        }
    }

    // ==========================================
    // TIỆN ÍCH MẬT MÃ HMAC-SHA256
    // ==========================================

    private static String computeHmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : rawHmac) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
