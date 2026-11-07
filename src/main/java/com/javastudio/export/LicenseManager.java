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
            this.secretKey = "JS-SEC-" + UUID.nameUUIDFromBytes(plugin.getDataFolder().getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        } else {
            this.secretKey = cfgSecret;
        }
    }

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

    public VerificationResult verifyLicense(String licenseKey, String expectedPlugin, String currentIp, int currentPort) {
        if (licenseKey == null || !licenseKey.contains(".")) {
            return new VerificationResult(false, "Định dạng License Key không hợp lệ!", null);
        }

        String[] parts = licenseKey.split("\\.", 2);
        String payloadBase64 = parts[0];
        String signature = parts[1];

        String expectedSig = computeHmacSha256(payloadBase64, secretKey);
        if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), expectedSig.getBytes(StandardCharsets.UTF_8))) {
            return new VerificationResult(false, "Chữ ký số không khớp! Khóa đã bị giả mạo hoặc cấp bởi secret key khác.", null);
        }

        LicensePayload payload = decodePayload(payloadBase64);
        if (payload == null) {
            return new VerificationResult(false, "Không thể đọc nội dung License Payload!", null);
        }

        if (expectedPlugin != null && !expectedPlugin.equalsIgnoreCase(payload.pluginName())) {
            return new VerificationResult(false, "Khóa này thuộc về plugin '" + payload.pluginName() + "', không phải '" + expectedPlugin + "'!", payload);
        }

        if (payload.isExpired()) {
            return new VerificationResult(false, "Khóa bản quyền đã hết hạn vào lúc " + payload.getExpiryFormatted() + "!", payload);
        }

        if (!"*".equals(payload.boundIp()) && currentIp != null && !currentIp.isBlank()) {
            if (!payload.boundIp().equalsIgnoreCase(currentIp) && !"127.0.0.1".equals(currentIp) && !"localhost".equalsIgnoreCase(currentIp)) {
                return new VerificationResult(false, "IP máy chủ không khớp (" + currentIp + " != " + payload.boundIp() + ")!", payload);
            }
        }

        if (payload.boundPort() > 0 && currentPort > 0 && payload.boundPort() != currentPort) {
            return new VerificationResult(false, "Cổng máy chủ không khớp (" + currentPort + " != " + payload.boundPort() + ")!", payload);
        }

        return new VerificationResult(true, "Xác minh thành công (Hợp lệ)", payload);
    }

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


    public String createWatermarkMetadata(String buyer, String pluginName) {
        long now = System.currentTimeMillis();
        String raw = "WATERMARK_BUYER=" + buyer + "\nPLUGIN=" + pluginName + "\nTIMESTAMP=" + now + "\nCHECKSUM=" + computeHmacSha256(buyer + ":" + pluginName, secretKey);
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

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
