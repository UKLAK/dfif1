package com.javastudio.ai;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.compiler.CompileResult;
import com.javastudio.manager.DynamicPluginManager;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AiAssistant {

    private final JavaStudioPlugin plugin;
    private final Logger logger;

    private boolean enabled;
    private String provider;
    private String endpoint;
    private String apiKey;
    private String model;

    private static final String SYSTEM_PROMPT = """
        You are JavaStudio Copilot, an elite Minecraft Java developer assistant.
        You generate Bukkit/Spigot/Paper (1.20.5 - 26.3+) plugins using Java 21 LTS standard.
        Plugins must extend DynamicPlugin (package com.javastudio.api.DynamicPlugin).
        Key Available APIs in JavaStudio:
        - DynamicPlugin: registerEvent(Class<T>, Consumer<T>), registerCommand(String, DynamicCommand), getDataStore(), getLogger(), broadcast(String), info(String).
        - SmartScheduler: runAt(Location, Runnable), runFor(Player, Runnable), runGlobal(Runnable), runAsync(Runnable), teleport(Player, Location).
        - FastBlock: getFastBlock().fillCuboid(world, p1, p2, mat), getFastBlock().createArenaSnapshot(name, world, p1, p2), restoreSnapshot(name).
        - ItemBuilder: buildItem(Material).name(String).lore(String...).glow().unbreakable().build().
        - SmartGui: createGui(String title, int rows).setButton(slot, GuiButton).open(Player).
        - DataStore: getDataStore().set(key, val), getInt(key, def), getString(key, def), getBoolean(key, def).
        - VaultBridge: ecoBalance(Player), ecoDeposit(Player, amount), ecoWithdraw(Player, amount).
        - MessageUtil: colorize(String) supports &a, &b, <gradient:#ff0000:#ffff00>text</gradient>, <rainbow>text</rainbow>.
        Rule: Output ONLY valid Java code inside ```java ... ``` blocks. Do not add unnecessary markdown talk outside code if possible.
        """;

    private static final String SCRIPT_SYSTEM_PROMPT = """
        You are JavaStudio Copilot, generating single-file runnable QuickScripts.
        Scripts must extend com.javastudio.api.QuickScript and implement public Object run() throws Throwable.
        Available fields inside QuickScript: player (p), sender, world (w), location (loc), server, plugin.
        Available helper methods: heal(), feed(), broadcast(msg), give(mat, amount), spawn(type), explosion(power),
        lightning(), fly(bool), speed(val), sound(name), effect(type, duration, amp), item(material), gui(title, rows).
        Rule: Output ONLY valid Java code inside ```java ... ``` blocks.
        """;

    public AiAssistant(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        reload();
    }

    public final void reload() {
        this.enabled = plugin.getConfig().getBoolean("ai-assistant.enabled", true);
        this.provider = plugin.getConfig().getString("ai-assistant.provider", "openai");
        this.endpoint = plugin.getConfig().getString("ai-assistant.endpoint", "https://api.openai.com/v1/chat/completions");
        this.apiKey = plugin.getConfig().getString("ai-assistant.api-key", "");
        this.model = plugin.getConfig().getString("ai-assistant.model", "gpt-4o-mini");
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String p) {
        this.provider = p;
        if (p.equalsIgnoreCase("ollama")) {
            if (this.endpoint.contains("openai.com")) {
                this.endpoint = "http://localhost:11434/api/generate";
            }
            if (this.model.startsWith("gpt-")) {
                this.model = "llama3";
            }
        } else if (p.equalsIgnoreCase("gemini")) {
            this.endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + (model.startsWith("gemini") ? model : "gemini-1.5-flash") + ":generateContent";
            if (!this.model.startsWith("gemini")) this.model = "gemini-1.5-flash";
        } else if (p.equalsIgnoreCase("claude")) {
            this.endpoint = "https://api.anthropic.com/v1/messages";
            if (!this.model.startsWith("claude")) this.model = "claude-3-5-sonnet-20241022";
        }
    }

    public String getModel() {
        return model;
    }

    public void setModel(String m) {
        this.model = m;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String ep) {
        this.endpoint = ep;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isConfigured() {
        if (provider.equalsIgnoreCase("ollama") || endpoint.contains("localhost") || endpoint.contains("127.0.0.1")) {
            return true;
        }
        return apiKey != null && !apiKey.isBlank() && !apiKey.equalsIgnoreCase("sk-your-api-key-here");
    }

    public AiResult generatePlugin(String pluginName, String prompt) {
        if (!enabled) {
            return new AiResult(false, "Tính năng AI Assistant đang bị tắt trong config.yml!");
        }
        if (!isConfigured()) {
            return new AiResult(false, "Chưa cấu hình 'ai-assistant.api-key' trong plugins/JavaStudio/config.yml (hoặc chuyển sang Ollama local)!");
        }

        try {
            String fullPrompt = "Create a complete Minecraft DynamicPlugin named '" + pluginName + "'.\nRequirement: " + prompt;
            String aiResponse = callChatCompletion(SYSTEM_PROMPT, fullPrompt);

            String javaCode = extractJavaCode(aiResponse);
            if (javaCode.isBlank()) {
                return new AiResult(false, "AI không trả về khối mã nguồn Java hợp lệ!\nPhản hồi: " + aiResponse);
            }

            DynamicPluginManager pm = plugin.getPluginManager();
            File projectsDir = pm.getProjectsFolder();
            File pDir = new File(projectsDir, pluginName);
            if (!com.javastudio.security.SecuritySandbox.isPathSafe(projectsDir, pDir)) {
                return new AiResult(false, "Tên plugin không hợp lệ (Phát hiện Path Traversal)!");
            }
            File srcDir = new File(pDir, "src" + File.separator + "com" + File.separator + "javastudio" + File.separator + "projects");
            srcDir.mkdirs();

            File javaFile = new File(srcDir, pluginName + "Plugin.java");
            try (FileWriter writer = new FileWriter(javaFile, StandardCharsets.UTF_8)) {
                writer.write(javaCode);
            }

            File metaFile = new File(pDir, "metadata.yml");
            if (!metaFile.exists()) {
                String metaContent = "name: " + pluginName + "\nversion: 1.0.0\nmain: com.javastudio.projects." + pluginName + "Plugin\nauthor: JavaStudio AI\ndescription: Plugin tao boi AI Assistant\n";
                try (FileWriter mw = new FileWriter(metaFile, StandardCharsets.UTF_8)) {
                    mw.write(metaContent);
                }
            }

            CompileResult cr = pm.compileProject(pluginName);
            if (cr != null && cr.isSuccess()) {
                StringBuilder loadLog = new StringBuilder();
                boolean loaded = pm.loadPlugin(pluginName, loadLog);
                if (loaded) {
                    return new AiResult(true, "AI đã sinh mã và nạp thành công plugin: &e" + pluginName + " &a(Không có lỗi cú pháp)!");
                } else {
                    return new AiResult(true, "AI sinh mã thành công nhưng nạp gặp cảnh báo: " + loadLog);
                }
            } else {
                String errors = cr != null ? cr.getFormattedErrors() : "Unknown error";
                logger.info("[AiAssistant] Biên dịch lần 1 gặp lỗi. Đang kích hoạt Self-Healing tự động sửa...");
                String healingPrompt = "The generated code for '" + pluginName + "' had the following compilation errors:\n" + errors + "\nOriginal Code:\n" + javaCode + "\nPlease fix the errors and provide the corrected complete code.";
                String fixedAiResponse = callChatCompletion(SYSTEM_PROMPT, healingPrompt);
                String fixedCode = extractJavaCode(fixedAiResponse);

                if (!fixedCode.isBlank()) {
                    try (FileWriter writer = new FileWriter(javaFile, StandardCharsets.UTF_8)) {
                        writer.write(fixedCode);
                    }
                    CompileResult cr2 = pm.compileProject(pluginName);
                    if (cr2 != null && cr2.isSuccess()) {
                        StringBuilder loadLog = new StringBuilder();
                        pm.loadPlugin(pluginName, loadLog);
                        return new AiResult(true, "AI đã tự sửa lỗi thành công qua Self-Healing và nạp plugin &e" + pluginName + "&a!");
                    }
                }

                return new AiResult(false, "Biên dịch gặp lỗi cú pháp:\n" + errors);
            }

        } catch (Throwable t) {
            return new AiResult(false, "Lỗi kết nối AI: " + t.getMessage());
        }
    }

    public AiResult generateScript(String scriptName, String prompt) {
        if (!enabled) return new AiResult(false, "AI Assistant đang tắt trong config.yml!");
        if (!isConfigured()) return new AiResult(false, "Chưa cấu hình API Key hoặc Ollama!");

        try {
            File scriptsDir = new File(plugin.getDataFolder(), "scripts");
            if (!scriptsDir.exists()) scriptsDir.mkdirs();

            String normalizedName = scriptName.endsWith(".java") ? scriptName : scriptName + ".java";
            File scriptFile = new File(scriptsDir, normalizedName);
            if (!com.javastudio.security.SecuritySandbox.isPathSafe(scriptsDir, scriptFile)) {
                return new AiResult(false, "Tên script không hợp lệ (Phát hiện Path Traversal)!");
            }

            String fullPrompt = "Write a complete single-file Java script named '" + scriptName + "'.\nRequirement: " + prompt;
            String response = callChatCompletion(SCRIPT_SYSTEM_PROMPT, fullPrompt);
            String javaCode = extractJavaCode(response);

            if (javaCode.isBlank()) {
                return new AiResult(false, "AI không trả về mã nguồn script hợp lệ!");
            }

            try (FileWriter writer = new FileWriter(scriptFile, StandardCharsets.UTF_8)) {
                writer.write(javaCode);
            }

            return new AiResult(true, "AI đã tạo thành công file script: &e" + normalizedName + "&a! Chạy bằng: &f/jscript run " + scriptName);
        } catch (Throwable t) {
            return new AiResult(false, "Lỗi sinh script AI: " + t.getMessage());
        }
    }

    public AiResult optimizePlugin(String pluginName) {
        if (!enabled || !isConfigured()) return new AiResult(false, "AI chưa được cấu hình!");
        DynamicPluginManager pm = plugin.getPluginManager();
        File pDir = new File(pm.getProjectsFolder(), pluginName);
        if (!com.javastudio.security.SecuritySandbox.isPathSafe(pm.getProjectsFolder(), pDir)) {
            return new AiResult(false, "Tên plugin không hợp lệ!");
        }
        if (!pDir.exists()) return new AiResult(false, "Không tìm thấy thư mục plugin " + pluginName);

        File srcDir = new File(pDir, "src" + File.separator + "com" + File.separator + "javastudio" + File.separator + "projects");
        File javaFile = new File(srcDir, pluginName + "Plugin.java");
        if (!javaFile.exists()) return new AiResult(false, "Không tìm thấy file nguồn " + javaFile.getName());

        try {
            String origCode = java.nio.file.Files.readString(javaFile.toPath(), StandardCharsets.UTF_8);
            String optPrompt = "Please optimize the following Minecraft plugin code for high-performance Paper/Folia (1.20.5 - 26.3+).\n" +
                    "- Use SmartScheduler instead of standard Bukkit schedulers where applicable.\n" +
                    "- Optimize memory allocation and cache repeated lookups.\n" +
                    "- Keep all original features and class name intact.\nCode:\n" + origCode;

            String resp = callChatCompletion(SYSTEM_PROMPT, optPrompt);
            String optCode = extractJavaCode(resp);
            if (!optCode.isBlank()) {
                java.nio.file.Files.writeString(javaFile.toPath(), optCode, StandardCharsets.UTF_8);
                CompileResult cr = pm.compileProject(pluginName);
                if (cr != null && cr.isSuccess()) {
                    StringBuilder loadLog = new StringBuilder();
                    pm.reloadPlugin(pluginName, loadLog);
                    return new AiResult(true, "AI đã tối ưu hóa mã nguồn &e" + pluginName + " &avà nạp lại thành công!");
                } else {
                    java.nio.file.Files.writeString(javaFile.toPath(), origCode, StandardCharsets.UTF_8);
                    return new AiResult(false, "Mã tối ưu hóa của AI gặp lỗi cú pháp, đã tự động hoàn tác code gốc!");
                }
            }
            return new AiResult(false, "AI không trả về mã tối ưu hóa hợp lệ.");
        } catch (Throwable t) {
            return new AiResult(false, "Lỗi tối ưu hóa AI: " + t.getMessage());
        }
    }

    public AiResult fixPlugin(String pluginName) {
        if (!enabled || !isConfigured()) return new AiResult(false, "AI chưa được cấu hình!");
        DynamicPluginManager pm = plugin.getPluginManager();
        File pDir = new File(pm.getProjectsFolder(), pluginName);
        if (!com.javastudio.security.SecuritySandbox.isPathSafe(pm.getProjectsFolder(), pDir)) {
            return new AiResult(false, "Tên plugin không hợp lệ!");
        }
        if (!pDir.exists()) return new AiResult(false, "Không tìm thấy plugin " + pluginName);

        File srcDir = new File(pDir, "src" + File.separator + "com" + File.separator + "javastudio" + File.separator + "projects");
        File javaFile = new File(srcDir, pluginName + "Plugin.java");
        if (!javaFile.exists()) return new AiResult(false, "Không tìm thấy file nguồn " + javaFile.getName());

        CompileResult cr = pm.compileProject(pluginName);
        com.javastudio.pipeline.CircuitBreakerEngine.CrashSnapshot runtimeCrash =
                plugin.getCircuitBreakerEngine() != null ? plugin.getCircuitBreakerEngine().getLatestCrashForPlugin(pluginName) : null;

        if (cr != null && cr.isSuccess() && runtimeCrash == null) {
            return new AiResult(true, "Plugin " + pluginName + " hiện không có lỗi biên dịch lẫn lỗi Runtime hộp đen (CircuitBreaker)!");
        }

        try {
            String origCode = java.nio.file.Files.readString(javaFile.toPath(), StandardCharsets.UTF_8);
            String prompt;
            boolean healingRuntimeCrash = (cr != null && cr.isSuccess() && runtimeCrash != null);
            if (healingRuntimeCrash) {
                prompt = "Fix the runtime crash captured by JavaStudio CircuitBreaker in DynamicPlugin '" + pluginName + "'.\n"
                        + "Event: " + runtimeCrash.eventName + "\n"
                        + "Exception: " + runtimeCrash.exceptionType + ": " + runtimeCrash.exceptionMessage + "\n"
                        + "Stacktrace:\n" + runtimeCrash.fullStackTrace + "\n"
                        + "Listener Fields Snapshot: " + runtimeCrash.listenerFields + "\n"
                        + "Event Fields Snapshot: " + runtimeCrash.eventFields + "\n"
                        + "Original Code:\n" + origCode + "\n"
                        + "Add proper null/bounds/state guards and return the complete fixed Java class.";
            } else {
                String errors = cr != null ? cr.getFormattedErrors() : "Unknown error";
                prompt = "Fix all compilation errors in the following code:\nErrors:\n" + errors + "\nCode:\n" + origCode;
            }

            String resp = callChatCompletion(SYSTEM_PROMPT, prompt);
            String fixedCode = extractJavaCode(resp);
            if (!fixedCode.isBlank()) {
                java.nio.file.Files.writeString(javaFile.toPath(), fixedCode, StandardCharsets.UTF_8);
                CompileResult cr2 = pm.compileProject(pluginName);
                if (cr2 != null && cr2.isSuccess()) {
                    if (plugin.getCircuitBreakerEngine() != null) {
                        plugin.getCircuitBreakerEngine().resetBreaker(pluginName);
                    }
                    StringBuilder log = new StringBuilder();
                    pm.reloadPlugin(pluginName, log);
                    return new AiResult(true, healingRuntimeCrash
                            ? "AI Self-Healing đã phân tích hộp đen CircuitBreaker (" + runtimeCrash.id + "), vá lỗi Runtime và nạp lại &e" + pluginName + "&a!"
                            : "AI đã sửa toàn bộ lỗi cú pháp và nạp lại plugin &e" + pluginName + "&a!");
                } else {
                    java.nio.file.Files.writeString(javaFile.toPath(), origCode, StandardCharsets.UTF_8);
                    return new AiResult(false, "AI vẫn chưa giải quyết được hết lỗi (đã hoàn tác file gốc):\n" + (cr2 != null ? cr2.getFormattedErrors() : ""));
                }
            }
            return new AiResult(false, "AI không trả về khối mã hợp lệ.");
        } catch (Throwable t) {
            return new AiResult(false, "Lỗi khi tự sửa lỗi qua AI: " + t.getMessage());
        }
    }

    public String askQuestion(String question) {
        if (!enabled) return "Tính năng AI đang tắt trong config.yml!";
        if (!isConfigured()) return "Vui lòng nhập API Key trong config.yml (hoặc dùng Ollama local) trước khi dùng!";
        try {
            return callChatCompletion(SYSTEM_PROMPT, question);
        } catch (Exception e) {
            return "Lỗi gọi API: " + e.getMessage();
        }
    }

    private String callChatCompletion(String systemMsg, String userMsg) throws Exception {
        if (provider.equalsIgnoreCase("ollama") && endpoint.endsWith("/api/generate")) {
            return callOllamaGenerate(systemMsg, userMsg);
        } else if (provider.equalsIgnoreCase("gemini")) {
            return callGemini(systemMsg, userMsg);
        } else if (provider.equalsIgnoreCase("claude")) {
            return callClaude(systemMsg, userMsg);
        } else {
            return callOpenAiFormat(systemMsg, userMsg);
        }
    }

    private String callOpenAiFormat(String systemMsg, String userMsg) throws Exception {
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);

        String jsonPayload = "{"
                + "\"model\": \"" + escapeJson(model) + "\","
                + "\"messages\": ["
                + "  {\"role\": \"system\", \"content\": \"" + escapeJson(systemMsg) + "\"},"
                + "  {\"role\": \"user\", \"content\": \"" + escapeJson(userMsg) + "\"}"
                + "],"
                + "\"temperature\": 0.3"
                + "}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder resp = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) resp.append(line);
        }

        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + ": " + resp);
        }

        return parseChatResponse(resp.toString());
    }

    private String callOllamaGenerate(String systemMsg, String userMsg) throws Exception {
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);

        String prompt = systemMsg + "\n\nUser: " + userMsg;
        String jsonPayload = "{"
                + "\"model\": \"" + escapeJson(model) + "\","
                + "\"prompt\": \"" + escapeJson(prompt) + "\","
                + "\"stream\": false"
                + "}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder resp = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) resp.append(line);
        }

        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + ": " + resp);
        }

        Pattern p = Pattern.compile("\"response\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
        Matcher m = p.matcher(resp.toString());
        if (m.find()) {
            return unescapeJson(m.group(1));
        }
        return resp.toString();
    }

    private String callGemini(String systemMsg, String userMsg) throws Exception {
        String fullUrl = endpoint;
        if (!fullUrl.contains("key=") && apiKey != null && !apiKey.isBlank()) {
            fullUrl += (fullUrl.contains("?") ? "&" : "?") + "key=" + apiKey;
        }
        URL url = URI.create(fullUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);

        String combined = systemMsg + "\n\n" + userMsg;
        String jsonPayload = "{"
                + "\"contents\": [{\"parts\": [{\"text\": \"" + escapeJson(combined) + "\"}]}]"
                + "}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder resp = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) resp.append(line);
        }

        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + ": " + resp);
        }

        Pattern p = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
        Matcher m = p.matcher(resp.toString());
        if (m.find()) {
            return unescapeJson(m.group(1));
        }
        return resp.toString();
    }

    private String callClaude(String systemMsg, String userMsg) throws Exception {
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);

        String jsonPayload = "{"
                + "\"model\": \"" + escapeJson(model) + "\","
                + "\"max_tokens\": 4096,"
                + "\"system\": \"" + escapeJson(systemMsg) + "\","
                + "\"messages\": [{\"role\": \"user\", \"content\": \"" + escapeJson(userMsg) + "\"}]"
                + "}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder resp = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) resp.append(line);
        }

        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + ": " + resp);
        }

        Pattern p = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
        Matcher m = p.matcher(resp.toString());
        if (m.find()) {
            return unescapeJson(m.group(1));
        }
        return resp.toString();
    }

    private String parseChatResponse(String json) {
        Pattern p = Pattern.compile("\"content\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (m.find()) {
            String content = m.group(1);
            return unescapeJson(content);
        }
        return json;
    }

    private String extractJavaCode(String text) {
        Pattern p = Pattern.compile("```(?:java)?\\s*([\\s\\S]*?)\\s*```");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        if (text.contains("package ") || text.contains("public class ")) {
            return text.trim();
        }
        return "";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    public record AiResult(boolean success, String message) {}
}
