package com.javastudio.security.cve;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class PterodactylLfiHoneypot {

    private final PanelCveProtectionSuite suite;
    private final Map<String, Long> trappedIps = new ConcurrentHashMap<>();

    private static final Pattern LFI_INDICATORS = Pattern.compile(
            "(\\.\\.[\\\\/])|(pearcmd\\.php)|(\\+config-create\\+)|(\\/tmp\\/sess_)|(\\.env)|" +
            "(php:\\/\\/)|(data:\\/\\/)|(phar:\\/\\/)|(input:\\/\\/)|(expect:\\/\\/)|(proc\\/self)|" +
            "(\\/etc\\/passwd)|(\\/etc\\/shadow)|(storage\\/logs)|(vendor\\/autoload\\.php)",
            Pattern.CASE_INSENSITIVE
    );

    public PterodactylLfiHoneypot(PanelCveProtectionSuite suite) {
        this.suite = suite;
    }

    public boolean checkAndIntercept(String clientIp, String path, String rawQuery) {
        if (path == null) return false;
        String lowerPath = path.toLowerCase(Locale.ROOT);

        boolean isLocalesEndpoint = lowerPath.contains("/locales/locale.json") || lowerPath.endsWith("/locale.json");
        boolean containsLfiPayload = false;
        String detectedSign = null;

        if (rawQuery != null && !rawQuery.isBlank()) {
            String lowerQuery = rawQuery.toLowerCase(Locale.ROOT);
            if (lowerQuery.contains("locale=") || lowerQuery.contains("namespace=")) {
                if (LFI_INDICATORS.matcher(rawQuery).find()) {
                    containsLfiPayload = true;
                    detectedSign = "locale/namespace LFI payload";
                }
            } else if (LFI_INDICATORS.matcher(rawQuery).find()) {
                containsLfiPayload = true;
                detectedSign = "generic LFI payload in query";
            }
        }

        if (isLocalesEndpoint || containsLfiPayload) {
            trappedIps.put(clientIp, System.currentTimeMillis());
            suite.getStats().incrementCve2025_49132();

            String signDetail = detectedSign != null ? detectedSign : "Truy cập endpoint mục tiêu /locales/locale.json";
            suite.logAlert("CVE-2025-49132",
                    "🚨 BẪY HONEYPOT ĐÃ KÍCH HOẠT: Phát hiện tấn công Pterodactyl Panel LFI / pearcmd.php từ IP: " +
                    clientIp + " (" + signDetail + ")");

            if (suite.getPlugin() != null && suite.getPlugin().getClusterHub() != null) {
                suite.getPlugin().getClusterHub().broadcast("javastudio:security:honeypot_trap", clientIp);
            }

            return true;
        }

        return false;
    }

    public boolean isIpTrapped(String clientIp) {
        return trappedIps.containsKey(clientIp);
    }

    public Map<String, Long> getTrappedIps() {
        return trappedIps;
    }
}
