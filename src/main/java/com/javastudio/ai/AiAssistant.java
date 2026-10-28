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

/**
 * AiAssistant: Trợ lý Lập trình AI Thông minh (/jai)
 * Cho phép lập trình viên và Quản trị viên nhập yêu cầu bằng ngôn ngữ tự nhiên,
 * AI sẽ tự động sinh mã Java chuẩn API của JavaStudio, kiểm tra lỗi cú pháp,
 * tự động sửa lỗi (Self-Healing) và nạp chạy tức thì trên máy chủ!
 */
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
        - ItemBuilder: buildItem(Material).name(String).lore(String...).glow().unbreakable().build().
        - SmartGui: createGui(String title, int rows).setButton(slot, GuiButton).open(Player).
        - DataStore: getDataStore().set(key, val), getInt(key, def), getString(key, def), getBoolean(key, def).
        - VaultBridge: ecoBalance(Player), ecoDeposit(Player, amount), ecoWithdraw(Player, amount).
        - MessageUtil: colorize(String) supports &a, &b, <gradient:#ff0000:#ffff00>text</gradient>, <rainbow>text</rainbow>.
        Rule: Output ONLY valid Java code inside ```java ... ``` blocks. Do not add unnecessary markdown talk outside code if possible.
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

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.equalsIgnoreCase("sk-your-api-key-here");
    }

    /**
     * Gửi yêu cầu sinh code cho AI và tự động biên dịch, nạp thử nghiệm.
     */
    public AiResult generatePlugin(String pluginName, String prompt) {
        if (!enabled) {
            return new AiResult(false, "Tính năng AI Assistant đang bị tắt trong config.yml!");
        }
        if (!isConfigured()) {
            return new AiResult(false, "Chưa cấu hình 'ai-assistant.api-key' trong plugins/JavaStudio/config.yml!");
        }

        try {
            String fullPrompt = "Create a complete Minecraft DynamicPlugin named '" + pluginName + "'.\nRequirement: " + prompt;
            String aiResponse = callChatCompletion(SYSTEM_PROMPT, fullPrompt);

            String javaCode = extractJavaCode(aiResponse);
            if (javaCode.isBlank()) {
                return new AiResult(false, "AI không trả về khối mã nguồn Java hợp lệ!\nPhản hồi: " + aiResponse);
            }

            // Ghi code vào thư mục project
            DynamicPluginManager pm = plugin.getPluginManager();
            File projectsDir = pm.getProjectsFolder();
            File pDir = new File(projectsDir, pluginName);
            File srcDir = new File(pDir, "src" + File.separator + "com" + File.separator + "javastudio" + File.separator + "projects");
            srcDir.mkdirs();

            File javaFile = new File(srcDir, pluginName + "Plugin.java");
            try (FileWriter writer = new FileWriter(javaFile, StandardCharsets.UTF_8)) {
                writer.write(javaCode);
            }

            // Tạo metadata.yml nếu chưa có
            File metaFile = new File(pDir, "metadata.yml");
            if (!metaFile.exists()) {
                String metaContent = "name: " + pluginName + "\nversion: 1.0.0\nmain: com.javastudio.projects." + pluginName + "Plugin\nauthor: JavaStudio AI\ndescription: Plugin tao boi AI Assistant\n";
                try (FileWriter mw = new FileWriter(metaFile, StandardCharsets.UTF_8)) {
                    mw.write(metaContent);
                }
            }

            // Dry-run biên dịch thử
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
                // Tự động kích hoạt cơ chế Self-Healing (Tự sửa lỗi)
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

    /**
     * Hỏi đáp giải thích code hoặc tư vấn lập trình Bukkit.
     */
    public String askQuestion(String question) {
        if (!enabled) return "Tính năng AI đang tắt trong config.yml!";
        if (!isConfigured()) return "Vui lòng nhập API Key trong config.yml trước khi dùng!";
        try {
            return callChatCompletion(SYSTEM_PROMPT, question);
        } catch (Exception e) {
            return "Lỗi gọi API: " + e.getMessage();
        }
    }

    /**
     * Sinh đoạn code hoặc sửa đổi code theo prompt và ngữ cảnh file hiện tại.
     */
    public String generateCodeSnippet(String prompt, String contextCode) throws Exception {
        if (!enabled) throw new IllegalStateException("AI Assistant đang bị tắt trong config.yml");
        if (!isConfigured()) throw new IllegalStateException("Chưa cấu hình API Key trong config.yml");
        String userMsg = "Yêu cầu: " + prompt;
        if (contextCode != null && !contextCode.isBlank()) {
            userMsg += "\n\nMã nguồn ngữ cảnh hiện tại:\n" + contextCode;
        }
        String resp = callChatCompletion(SYSTEM_PROMPT, userMsg);
        String code = extractJavaCode(resp);
        return (!code.isBlank()) ? code : resp;
    }

    /**
     * Tự động sửa lỗi cú pháp dựa trên thông báo lỗi từ trình biên dịch.
     */
    public String fixCodeSnippet(String errors, String currentCode) throws Exception {
        if (!enabled) throw new IllegalStateException("AI Assistant đang bị tắt trong config.yml");
        if (!isConfigured()) throw new IllegalStateException("Chưa cấu hình API Key trong config.yml");
        String userMsg = "Mã nguồn sau gặp lỗi khi biên dịch:\n" + errors + "\n\nCode cần sửa:\n" + currentCode + "\n\nHãy sửa toàn bộ các lỗi trên và chỉ trả về đoạn code hoàn chỉnh đã sửa.";
        String resp = callChatCompletion(SYSTEM_PROMPT, userMsg);
        String code = extractJavaCode(resp);
        return (!code.isBlank()) ? code : resp;
    }

    private String callChatCompletion(String systemMsg, String userMsg) throws Exception {
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(45000);
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

        // Trích xuất content từ JSON response
        return parseChatResponse(resp.toString());
    }

    private String parseChatResponse(String json) {
        // Tìm "content": "..."
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
