package com.javastudio.security;

import com.javastudio.JavaStudioPlugin;

import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Hộp cát an ninh (Security Sandbox) và Giám sát thời gian thực thi (Timeout Watchdog).
 * Ngăn chặn mã độc phá hoại server (System.exit, ProcessBuilder, Unsafe) và vòng lặp vô tận (while true).
 */
public class SecuritySandbox {

    private final JavaStudioPlugin plugin;
    private final ExecutorService watchdogExecutor = Executors.newCachedThreadPool();

    public record SandboxRule(Pattern pattern, String ruleId, String description) {}

    private static final List<SandboxRule> BLACKLISTED_RULES = List.of(
            new SandboxRule(
                    Pattern.compile("System\\s*\\.\\s*exit", Pattern.CASE_INSENSITIVE),
                    "SYSTEM_EXIT",
                    "Phát hiện lệnh System.exit() - Hành vi cố tình tắt máy chủ!"
            ),
            new SandboxRule(
                    Pattern.compile("Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*(exec|halt)|Runtime\\s*\\.\\s*halt", Pattern.CASE_INSENSITIVE),
                    "RUNTIME_EXEC",
                    "Phát hiện Runtime.exec/halt - Nguy cơ Remote Code Execution (RCE) can thiệp hệ điều hành!"
            ),
            new SandboxRule(
                    Pattern.compile("(ProcessBuilder|java\\s*\\.\\s*lang\\s*\\.\\s*ProcessBuilder|java\\s*\\.\\s*lang\\s*\\.\\s*Process)", Pattern.CASE_INSENSITIVE),
                    "PROCESS_BUILDER",
                    "Phát hiện khởi tạo ProcessBuilder - Nguy cơ thực thi lệnh ngoài máy chủ!"
            ),
            new SandboxRule(
                    Pattern.compile("(sun|jdk\\s*\\.\\s*internal)\\s*\\.\\s*misc\\s*\\.\\s*Unsafe", Pattern.CASE_INSENSITIVE),
                    "UNSAFE_MEMORY",
                    "Phát hiện Unsafe memory access - Hành vi can thiệp trái phép bộ nhớ JVM!"
            ),
            new SandboxRule(
                    Pattern.compile("Thread\\s*\\.\\s*(currentThread\\s*\\(\\s*\\)\\s*\\.)?stop\\s*\\(", Pattern.CASE_INSENSITIVE),
                    "THREAD_STOP",
                    "Phát hiện Thread.stop() - Nguy cơ làm sập hoặc treo tiến trình máy chủ!"
            ),
            new SandboxRule(
                    Pattern.compile("System\\s*\\.\\s*load(Library)?\\s*\\(", Pattern.CASE_INSENSITIVE),
                    "NATIVE_LIBRARY",
                    "Phát hiện nạp thư viện Native nhị phân (.dll/.so) - Nguy cơ bypass toàn bộ bảo mật JVM!"
            ),
            new SandboxRule(
                    Pattern.compile("setAccessible\\s*\\(\\s*true\\s*\\)", Pattern.CASE_INSENSITIVE),
                    "SET_ACCESSIBLE",
                    "Phát hiện can thiệp Reflection cấp cao (setAccessible(true)) - Vi phạm nguyên tắc đóng gói an toàn!"
            ),
            new SandboxRule(
                    Pattern.compile("MethodHandles\\s*\\.\\s*(lookup|privateLookupIn)\\s*\\(", Pattern.CASE_INSENSITIVE),
                    "METHOD_HANDLES",
                    "Phát hiện MethodHandles Lookup can thiệp sâu vào cấu trúc bytecode của JVM!"
            ),
            new SandboxRule(
                    Pattern.compile("Class\\s*\\.\\s*forName\\s*\\(\\s*[\"']\\s*(java\\.lang\\.Runtime|sun\\.misc\\.Unsafe|java\\.lang\\.ProcessBuilder|java\\.lang\\.System)", Pattern.CASE_INSENSITIVE),
                    "DANGEROUS_REFLECTION",
                    "Phát hiện nạp Reflection các lớp hệ thống bị cấm!"
            ),
            new SandboxRule(
                    Pattern.compile("(ClassLoader\\s*\\.\\s*defineClass|defineAnonymousClass|URLClassLoader)", Pattern.CASE_INSENSITIVE),
                    "CLASS_LOADER_INJECTION",
                    "Phát hiện hành vi tiêm lớp động (Dynamic ClassLoader/defineClass)!"
            ),
            new SandboxRule(
                    Pattern.compile("addShutdownHook\\s*\\(", Pattern.CASE_INSENSITIVE),
                    "SHUTDOWN_HOOK",
                    "Phát hiện đăng ký Shutdown Hook can thiệp vào vòng đời tắt của JVM!"
            ),
            new SandboxRule(
                    Pattern.compile("System\\s*\\.\\s*setSecurityManager\\s*\\(", Pattern.CASE_INSENSITIVE),
                    "SECURITY_MANAGER",
                    "Phát hiện hành vi cố tình thay đổi SecurityManager!"
            ),
            new SandboxRule(
                    Pattern.compile("([\"'])\\s*(\\.\\.[\\/\\\\])+", Pattern.CASE_INSENSITIVE),
                    "PATH_TRAVERSAL",
                    "Phát hiện ký tự Path Traversal (../) - Nguy cơ truy cập tệp tin ngoài phạm vi cho phép!"
            ),
            new SandboxRule(
                    Pattern.compile("new\\s+(java\\s*\\.\\s*net\\s*\\.\\s*)?(Socket|ServerSocket|DatagramSocket)\\s*\\(", Pattern.CASE_INSENSITIVE),
                    "RAW_SOCKET",
                    "Phát hiện mở kết nối Socket thô - Vi phạm chính sách an ninh mạng (Egress Whitelist)!"
            ),
            new SandboxRule(
                    Pattern.compile("(\\.\\.\\.+[\\\\/])|(\\.\\.[\\\\/]{2,})|(%2e%2e)", Pattern.CASE_INSENSITIVE),
                    "NESTED_PATH_TRAVERSAL",
                    "Phát hiện Nested Path Traversal lồng nhau (.../...//) - Nguy cơ leo thang tùy ý ghi file (CVE-2026-13716)!"
            ),
            new SandboxRule(
                    Pattern.compile("(\\{\\{|\\{%)[^}]*(__globals__|__builtins__|os\\.popen|cycler|lipsum|subprocess)", Pattern.CASE_INSENSITIVE),
                    "SSTI_WEBHOOK_INJECTION",
                    "Phát hiện biểu thức Server-Side Template Injection (SSTI / Jinja2) - Nguy cơ Remote Code Execution (CVE-2025-14700)!"
            ),
            new SandboxRule(
                    Pattern.compile("(\\.env|pearcmd\\.php|locales\\/locale\\.json|\\/etc\\/shadow)", Pattern.CASE_INSENSITIVE),
                    "PTERODACTYL_SENSITIVE_LFI",
                    "Phát hiện truy cập tệp tin cấu hình nhạy cảm .env/pearcmd.php/shadow - Nguy cơ rò rỉ cơ sở dữ liệu (CVE-2025-49132)!"
            ),
            new SandboxRule(
                    Pattern.compile("(Files\\s*\\.\\s*createSymbolicLink|createLink)", Pattern.CASE_INSENSITIVE),
                    "WINGS_SYMLINK_ESCAPE",
                    "Phát hiện tạo Symbolic Link trong runtime - Nguy cơ vượt khỏi ranh giới Wings Sandbox (CVE-2024-27102)!"
            )
    );

    public SecuritySandbox(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Kiểm tra mã nguồn xem có vi phạm quy tắc an ninh không.
     *
     * @param sourceCode Nội dung mã nguồn cần quét
     * @return null nếu an toàn, hoặc chuỗi cảnh báo lý do từ chối nếu vi phạm
     */
    public String validateCode(String sourceCode) {
        if (!plugin.getConfig().getBoolean("security.sandbox.enabled", true)) {
            return null; // Sandbox bị vô hiệu hóa
        }

        if (sourceCode == null || sourceCode.isEmpty()) {
            return null;
        }

        for (SandboxRule rule : BLACKLISTED_RULES) {
            if (rule.pattern().matcher(sourceCode).find()) {
                return "[Bảo Mật] Vi phạm quy tắc [" + rule.ruleId() + "]: " + rule.description();
            }
        }

        List<String> customBlocked = plugin.getConfig().getStringList("security.sandbox.blocked-keywords");
        for (String keyword : customBlocked) {
            if (!keyword.isEmpty() && sourceCode.contains(keyword)) {
                return "[Bảo Mật] Mã nguồn chứa từ khóa bị cấm: '" + keyword + "'";
            }
        }

        return null;
    }

    /**
     * Kiểm tra đường dẫn tệp tin có nằm an toàn trong thư mục gốc không (Chống Path Traversal).
     */
    public static boolean isPathSafe(java.io.File baseDir, java.io.File targetFile) {
        if (baseDir == null || targetFile == null) return false;
        try {
            String base = baseDir.getCanonicalPath();
            String target = targetFile.getCanonicalPath();
            return target.startsWith(base);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static final List<String> DEFAULT_ALLOWED_DOMAINS = List.of(
            "mojang.com", "minecraft.net", "papermc.io", "spigotmc.org",
            "github.com", "githubusercontent.com", "discord.com", "discordapp.com",
            "googleapis.com", "openai.com", "anthropic.com", "repo1.maven.org"
    );

    /**
     * Kiểm tra xem địa chỉ Host/Domain có nằm trong danh sách mạng an toàn (Egress Whitelist) không.
     */
    public boolean isDomainAllowed(String host) {
        if (host == null || host.isBlank()) return false;
        String lower = host.toLowerCase().trim();
        List<String> allowed = plugin.getConfig().getStringList("security.network-whitelist");
        if (allowed == null || allowed.isEmpty()) {
            allowed = DEFAULT_ALLOWED_DOMAINS;
        }
        for (String domain : allowed) {
            if (lower.equals(domain) || lower.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    public interface DangerousCallable<T> {
        T call() throws Throwable;
    }

    /**
     * Thực thi một tác vụ có giới hạn thời gian (Timeout Watchdog) để chống vòng lặp vô tận.
     */
    public <T> T executeWithTimeout(DangerousCallable<T> task, long timeoutMs) throws Throwable {
        if (timeoutMs <= 0) {
            return task.call();
        }

        Future<T> future = watchdogExecutor.submit(() -> {
            try {
                return task.call();
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("Thực thi quá thời gian cho phép (" + timeoutMs + "ms). Đã dừng tác vụ để tránh treo máy chủ!");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CompletionException && cause.getCause() != null) {
                throw cause.getCause();
            }
            throw cause != null ? cause : e;
        }
    }

    public void shutdown() {
        watchdogExecutor.shutdownNow();
    }
}
