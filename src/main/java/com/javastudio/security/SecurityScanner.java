package com.javastudio.security;

import com.javastudio.JavaStudioPlugin;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trình quét và chẩn đoán an ninh mã độc cho toàn bộ các plugin trong server (Security Scanner).
 * Giúp chủ máy chủ phát hiện các backdoor, force OP, remote code execution (RCE) và token stealer.
 */
public class SecurityScanner {

    private final JavaStudioPlugin plugin;
    private static final Pattern BASE64_PATTERN = Pattern.compile("[A-Za-z0-9+/=]{20,}");
    private static final Pattern RAW_IP_PATTERN = Pattern.compile("https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private static final List<String> IP_LOGGER_DOMAINS = List.of(
            "api.ipify.org", "checkip.amazonaws.com", "icanhazip.com", "ipinfo.io",
            "wtfismyip.com", "ip-api.com", "whatismyipaddress.com", "ident.me", "ip.42.pl"
    );

    private static final List<String> TUNNEL_DOMAINS = List.of(
            "ngrok.io", "portmap.host", "serveo.net", "localtunnel.me", "no-ip.com", "duckdns.org"
    );

    public record ScanFinding(String className, String severity, String rule, String description) {}

    public record ScanReport(String pluginName, File jarFile, String overallStatus, int dangerCount, int warningCount, List<ScanFinding> findings) {}

    public SecurityScanner(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Quét toàn bộ thư mục plugins/ của máy chủ.
     */
    public List<ScanReport> scanAllPlugins() {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        if (pluginsDir == null || !pluginsDir.exists()) return Collections.emptyList();

        File[] jarFiles = pluginsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (jarFiles == null) return Collections.emptyList();

        List<ScanReport> reports = new ArrayList<>();
        for (File jar : jarFiles) {
            reports.add(scanPluginJar(jar));
        }
        return reports;
    }

    /**
     * Quét chi tiết một file JAR plugin cụ thể.
     */
    public ScanReport scanPluginJar(File jarFile) {
        if (jarFile == null || !jarFile.exists()) {
            return new ScanReport(jarFile != null ? jarFile.getName() : "Unknown", jarFile, "ERROR", 0, 0,
                    List.of(new ScanFinding("", "ERROR", "FILE_NOT_FOUND", "Không tìm thấy file jar để quét!")));
        }

        String pluginName = jarFile.getName().replaceAll("\\.jar$", "");
        List<ScanFinding> findings = new ArrayList<>();
        int dangerCount = 0;
        int warningCount = 0;

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                String lowerName = name.toLowerCase();

                // 0. Phát hiện thư viện native nhị phân máy tính (.dll, .so, .dylib)
                if (lowerName.endsWith(".dll") || lowerName.endsWith(".so") || lowerName.endsWith(".dylib")) {
                    findings.add(new ScanFinding(name, "DANGER", "EMBEDDED_NATIVE_BINARY",
                            "Phát hiện tệp thư viện thực thi nhị phân máy tính (native binary: " + name + ") nhúng trong JAR!"));
                    dangerCount++;
                    continue;
                }

                // 0b. Phát hiện file giả dạng tài nguyên nhưng thực chất là Bytecode Class (0xCAFEBABE)
                if (!name.endsWith(".class") && !name.endsWith("/") && !name.startsWith("META-INF/")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        byte[] magic = is.readNBytes(4);
                        if (magic.length == 4 && magic[0] == (byte) 0xCA && magic[1] == (byte) 0xFE && magic[2] == (byte) 0xBA && magic[3] == (byte) 0xBE) {
                            findings.add(new ScanFinding(name, "DANGER", "DISGUISED_CLASS_PAYLOAD",
                                    "Phát hiện tệp Class mã độc giả dạng tệp tài nguyên (" + name + ")!"));
                            dangerCount++;
                            continue;
                        }
                    } catch (Throwable ignored) {}
                }

                if (!name.endsWith(".class")) continue;
                String className = name.replace('/', '.').replace(".class", "");

                try (InputStream is = jar.getInputStream(entry)) {
                    byte[] bytes = is.readAllBytes();
                    String stringPool = extractStringsFromClass(bytes);
                    String lowerPool = stringPool.toLowerCase();

                    // 1. Kiểm tra Force OP Backdoor
                    if (stringPool.contains("setOp") && (stringPool.contains("true") || stringPool.contains("boolean"))) {
                        findings.add(new ScanFinding(className, "DANGER", "FORCE_OP",
                                "Phát hiện gọi hàm setOp(true) - nguy cơ tự cấp quyền OP trái phép!"));
                        dangerCount++;
                    }

                    // 2. Kiểm tra Remote Code Execution (RCE)
                    if (stringPool.contains("java/lang/Runtime") && stringPool.contains("exec") ||
                        stringPool.contains("java/lang/ProcessBuilder")) {
                        findings.add(new ScanFinding(className, "DANGER", "RCE_EXEC",
                                "Phát hiện gọi lệnh Process/Runtime.exec - có khả năng can thiệp hệ điều hành!"));
                        dangerCount++;
                    }

                    // 3. Kiểm tra Unsafe Memory Injection
                    if (stringPool.contains("sun/misc/Unsafe") || stringPool.contains("jdk/internal/misc/Unsafe")) {
                        findings.add(new ScanFinding(className, "WARNING", "UNSAFE_MEMORY",
                                "Sử dụng sun.misc.Unsafe - có thể can thiệp bộ nhớ JVM trực tiếp."));
                        warningCount++;
                    }

                    // 4. Kiểm tra Reverse Shell / Socket mở port lạ
                    if (stringPool.contains("java/net/Socket") || stringPool.contains("java/net/ServerSocket")) {
                        // Ngoại trừ chính JavaStudio Web Server
                        if (!pluginName.equalsIgnoreCase("JavaStudio")) {
                            findings.add(new ScanFinding(className, "WARNING", "RAW_SOCKET",
                                    "Khởi tạo Socket/ServerSocket thô - cần kiểm tra kết nối mạng ngoài."));
                            warningCount++;
                        }
                    }

                    // 5. Kiểm tra Discord Webhook Grabber / Session Stealer
                    if (stringPool.contains("discord.com/api/webhooks") || stringPool.contains("discordapp.com/api/webhooks")) {
                        if (!pluginName.equalsIgnoreCase("JavaStudio")) {
                            findings.add(new ScanFinding(className, "DANGER", "WEBHOOK_LEAK",
                                    "Chứa chuỗi Discord Webhook ẩn - nguy cơ đánh cắp token hoặc dữ liệu máy chủ!"));
                            dangerCount++;
                        }
                    }

                    // 6. Kiểm tra Dynamic Bytecode Injector (Payload ẩn)
                    if (stringPool.contains("defineClass") && stringPool.contains("ClassLoader")) {
                        findings.add(new ScanFinding(className, "WARNING", "DYNAMIC_CLASS_INJECTION",
                                "Tự giải mã và tiêm bytecode lớp động vào JVM lúc runtime."));
                        warningCount++;
                    }

                    // 7. Giải mã và quét chuỗi Base64 Obfuscated Backdoor
                    Matcher b64Matcher = BASE64_PATTERN.matcher(stringPool);
                    while (b64Matcher.find()) {
                        String match = b64Matcher.group();
                        try {
                            byte[] decodedBytes = Base64.getDecoder().decode(match);
                            if (decodedBytes.length >= 8) {
                                String decoded = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
                                if (decoded.contains("setop") || decoded.contains("runtime.getruntime") ||
                                    decoded.contains("processbuilder") || decoded.contains("/op ") ||
                                    decoded.contains("server.properties") || decoded.contains("discord.com/api")) {
                                    findings.add(new ScanFinding(className, "DANGER", "OBFUSCATED_BACKDOOR",
                                            "Phát hiện chuỗi mã độc giấu dưới dạng mã hóa Base64: \"" + match.substring(0, Math.min(25, match.length())) + "...\"!"));
                                    dangerCount++;
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }

                    // 8. Kiểm tra Reflection Attack ngầm
                    if (stringPool.contains("Class.forName") || stringPool.contains("getDeclaredMethod")) {
                        if (stringPool.contains("setOp") || stringPool.contains("dispatchCommand") || stringPool.contains("System.exit")) {
                            findings.add(new ScanFinding(className, "DANGER", "REFLECTION_ATTACK",
                                    "Sử dụng Reflection can thiệp các phương thức nhạy cảm (setOp / dispatchCommand / exit)!"));
                            dangerCount++;
                        }
                    }

                    // 9. Kiểm tra gọi HTTP tới địa chỉ IP thô (Raw IP Exfiltration)
                    if (RAW_IP_PATTERN.matcher(stringPool).find()) {
                        if (!pluginName.equalsIgnoreCase("JavaStudio")) {
                            findings.add(new ScanFinding(className, "WARNING", "RAW_IP_CONNECTION",
                                    "Phát hiện kết nối HTTP trực tiếp tới địa chỉ IP thô (Raw IP) - nguy cơ rò rỉ dữ liệu máy chủ!"));
                            warningCount++;
                        }
                    }

                    // 10. Kiểm tra IP Logger & Dịch vụ đánh cắp IP máy chủ
                    for (String ipLogger : IP_LOGGER_DOMAINS) {
                        if (lowerPool.contains(ipLogger)) {
                            findings.add(new ScanFinding(className, "DANGER", "IP_LOGGER_STEALER",
                                    "Phát hiện dịch vụ trích xuất và rò rỉ địa chỉ IP máy chủ (" + ipLogger + ")!"));
                            dangerCount++;
                            break;
                        }
                    }

                    // 11. Kiểm tra gọi nạp mã máy tính Native (JNI DLL/SO execution)
                    if (stringPool.contains("loadLibrary") || (stringPool.contains("java/lang/System") && stringPool.contains("load"))) {
                        if (stringPool.contains("System.load") || stringPool.contains("System.loadLibrary")) {
                            findings.add(new ScanFinding(className, "DANGER", "NATIVE_EXECUTION",
                                    "Gọi nạp mã nhị phân native qua System.load/loadLibrary - nguy cơ RCE cấp hệ điều hành!"));
                            dangerCount++;
                        }
                    }

                    // 12. Kiểm tra thực thi lệnh can thiệp quyền OP / Permissions qua Console
                    if (stringPool.contains("dispatchCommand")) {
                        if (lowerPool.contains("/op ") || lowerPool.contains("op ") || lowerPool.contains("pex user") ||
                            lowerPool.contains("lp user") || lowerPool.contains("luckperms user") || lowerPool.contains("manuadd") ||
                            lowerPool.contains("deop *")) {
                            findings.add(new ScanFinding(className, "DANGER", "COMMAND_HIJACK_OP",
                                    "Phát hiện thực thi lệnh can thiệp quyền OP / Permissions qua Console dispatchCommand!"));
                            dangerCount++;
                        }
                    }

                    // 13. Kiểm tra hành vi can thiệp / ghi đè các tệp cấu hình cốt lõi của máy chủ
                    if (lowerPool.contains("server.properties") || lowerPool.contains("ops.json") || lowerPool.contains("whitelist.json") ||
                        lowerPool.contains("bungee.yml") || lowerPool.contains("velocity.toml")) {
                        if (stringPool.contains("FileOutputStream") || stringPool.contains("FileWriter") ||
                            stringPool.contains("Files.write") || stringPool.contains("deleteOnExit") || stringPool.contains("Files.delete")) {
                            findings.add(new ScanFinding(className, "DANGER", "SERVER_FILE_TAMPERING",
                                    "Phát hiện hành vi ghi đè hoặc xóa tệp cấu hình cốt lõi của máy chủ (server.properties/ops.json)!"));
                            dangerCount++;
                        }
                    }

                    // 14. Kiểm tra tên miền đường hầm (Reverse Tunnel Exfiltration)
                    for (String tunnel : TUNNEL_DOMAINS) {
                        if (lowerPool.contains(tunnel)) {
                            findings.add(new ScanFinding(className, "WARNING", "TUNNEL_EXFILTRATION",
                                    "Chứa tên miền đường hầm (Reverse Tunnel: " + tunnel + ") - nguy cơ bypass tường lửa!"));
                            warningCount++;
                            break;
                        }
                    }

                    // 15. Kiểm tra leo thang đặc quyền qua setAccessible
                    if (stringPool.contains("setAccessible") && (stringPool.contains("java/lang/reflect/Field") || stringPool.contains("java/lang/reflect/Method"))) {
                        findings.add(new ScanFinding(className, "WARNING", "PRIVILEGE_ESCALATION",
                                "Sử dụng Reflection setAccessible để vượt qua ranh giới đóng gói JVM!"));
                        warningCount++;
                    }

                    // 16. Kiểm tra Panel CVE Exploitation Patterns (Crafty Controller & Pterodactyl/Wings)
                    // CVE-2025-14700: Webhook SSTI / Reverse shell
                    if (lowerPool.contains("__globals__") || lowerPool.contains("cycler.__init__") || lowerPool.contains("os.popen") ||
                        lowerPool.contains("/dev/tcp/") || lowerPool.contains("nosiume")) {
                        findings.add(new ScanFinding(className, "DANGER", "CVE_2025_14700_SSTI",
                                "Phát hiện mã khai thác SSTI Reverse Shell qua Webhook Template (CVE-2025-14700)!"));
                        dangerCount++;
                    }

                    // CVE-2025-49132: Pterodactyl LFI & pearcmd.php
                    if (lowerPool.contains("pearcmd.php") || lowerPool.contains("locales/locale.json") || lowerPool.contains("+config-create+")) {
                        findings.add(new ScanFinding(className, "DANGER", "CVE_2025_49132_LFI",
                                "Phát hiện payload khai thác Pterodactyl LFI qua locale.json & pearcmd.php (CVE-2025-49132)!"));
                        dangerCount++;
                    }

                    // CVE-2026-13716: Nested Path Traversal
                    if (lowerPool.contains(".../...//") || lowerPool.contains(".../") || lowerPool.contains("..//..//")) {
                        findings.add(new ScanFinding(className, "DANGER", "CVE_2026_13716_TRAVERSAL",
                                "Phát hiện biến thể Nested Path Traversal lồng nhau (.../...//) (CVE-2026-13716)!"));
                        dangerCount++;
                    }

                    // CVE-2024-27102: Wings Sandbox Escape
                    if (lowerPool.contains("createsymboliclink") && (lowerPool.contains("/etc/") || lowerPool.contains("passwd") || lowerPool.contains(".."))) {
                        findings.add(new ScanFinding(className, "DANGER", "CVE_2024_27102_ESCAPE",
                                "Phát hiện hành vi tạo Symlink vượt rào Wings Container Sandbox (CVE-2024-27102)!"));
                        dangerCount++;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            findings.add(new ScanFinding("", "ERROR", "JAR_READ_FAILED", "Không thể đọc cấu trúc file JAR: " + e.getMessage()));
        }

        String overallStatus = dangerCount > 0 ? "DANGEROUS" : (warningCount > 0 ? "SUSPICIOUS" : "SAFE");
        return new ScanReport(pluginName, jarFile, overallStatus, dangerCount, warningCount, findings);
    }

    /**
     * Trích xuất các chuỗi ký tự UTF-8 thô từ bytecode ClassFile mà không cần thư viện ngoài.
     */
    private String extractStringsFromClass(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        int len = bytes.length;
        for (int i = 0; i < len; i++) {
            byte b = bytes[i];
            if (b >= 32 && b <= 126) {
                sb.append((char) b);
            } else if (b == 0 || b == 10 || b == 13) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }
}
