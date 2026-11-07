package com.javastudio.pipeline;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.DynamicScript;
import com.javastudio.api.JavaContext;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public final class VisualFlowEngine {

    public record FlowDefinition(
            String id,
            String name,
            String trigger,
            List<Map<String, String>> conditions,
            List<Map<String, String>> actions
    ) {}

    private final JavaStudioPlugin plugin;
    private final File flowsDir;
    private final Map<String, FlowDefinition> loadedFlows = new LinkedHashMap<>();

    public VisualFlowEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.flowsDir = new File(plugin.getDataFolder(), "flows");
        if (!flowsDir.exists()) {
            flowsDir.mkdirs();
        }
        loadDefaultFlowIfEmpty();
        reloadAllFlows();
    }

    private void loadDefaultFlowIfEmpty() {
        File defaultFile = new File(flowsDir, "welcome_reward.flow.json");
        if (!defaultFile.exists()) {
            String json = """
                    {
                      "id": "welcome_reward",
                      "name": "Thưởng Chào Mừng & Danh Hiệu",
                      "trigger": "on_player_join",
                      "conditions": [
                        {"type": "chance", "percentage": "100"}
                      ],
                      "actions": [
                        {"type": "broadcast", "message": "&b[JavaStudio Flow] &aChào mừng chiến binh &e%player% &agia nhập thế giới!"},
                        {"type": "send_title", "title": "&6&lCHÀO MỪNG", "subtitle": "&eNhận trang bị tân thủ thành công!"},
                        {"type": "play_sound", "sound": "ENTITY_PLAYER_LEVELUP", "volume": "1.0", "pitch": "1.2"},
                        {"type": "give_item", "material": "DIAMOND_SWORD", "amount": "1", "name": "&b&lTân Thủ Kiếm", "rarity": "RARE", "glint": "true"}
                      ]
                    }
                    """;
            try {
                Files.writeString(defaultFile.toPath(), json, StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
    }

    public synchronized void reloadAllFlows() {
        loadedFlows.clear();
        File[] files = flowsDir.listFiles((dir, name) -> name.endsWith(".flow.json"));
        if (files == null) return;

        for (File f : files) {
            try {
                String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                FlowDefinition def = parseFlowJson(content);
                if (def != null) {
                    loadedFlows.put(def.id(), def);
                    compileAndRegisterFlow(def);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[VisualFlow] Lỗi khi nạp flow " + f.getName() + ": " + t.getMessage());
            }
        }
    }

    public synchronized boolean saveFlow(String json, StringBuilder log) {
        try {
            FlowDefinition def = parseFlowJson(json);
            if (def == null || def.id() == null || def.id().isBlank()) {
                if (log != null) log.append("Dữ liệu Flow JSON không hợp lệ hoặc thiếu ID!");
                return false;
            }

            File file = new File(flowsDir, def.id() + ".flow.json");
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
            loadedFlows.put(def.id(), def);

            boolean compiled = compileAndRegisterFlow(def);
            if (compiled) {
                if (log != null) log.append("Đã lưu và biên dịch Flow [").append(def.id()).append("] thành công!");
                return true;
            } else {
                if (log != null) log.append("Đã lưu file nhưng biên dịch mã Java thất bại!");
                return false;
            }
        } catch (Throwable t) {
            if (log != null) log.append("Lỗi: ").append(t.getMessage());
            return false;
        }
    }

    public synchronized boolean executeFlow(String flowId, JavaContext ctx, StringBuilder log) {
        FlowDefinition def = loadedFlows.get(flowId);
        if (def == null) {
            if (log != null) log.append("Không tìm thấy Flow [").append(flowId).append("]!");
            return false;
        }

        try {
            String className = "FlowExec_" + sanitizeId(flowId) + "_" + System.currentTimeMillis();
            String javaSource = generateJavaSource(className, def);

            var cr = plugin.getCompiler().compileSingleSource("com.javastudio.runtime." + className, javaSource);
            if (!cr.isSuccess()) {
                if (log != null) log.append("Lỗi biên dịch: ").append(cr.getFormattedErrors());
                return false;
            }

            Class<?> clazz = cr.loadClass("com.javastudio.runtime." + className);
            DynamicScript script = (DynamicScript) clazz.getDeclaredConstructor().newInstance();
            Object res = plugin.getSecuritySandbox().executeWithTimeout(() -> script.execute(ctx), 5000);
            if (log != null) log.append(String.valueOf(res));
            return true;
        } catch (Throwable t) {
            if (log != null) log.append("Lỗi thực thi: ").append(t.getMessage());
            return false;
        }
    }

    public boolean compileAndRegisterFlow(FlowDefinition def) {
        try {
            String className = "FlowRuntime_" + sanitizeId(def.id());
            String javaSource = generateJavaSource(className, def);

            File scriptsDir = new File(plugin.getDataFolder(), "scripts");
            if (!scriptsDir.exists()) scriptsDir.mkdirs();
            File targetJava = new File(scriptsDir, className + ".java");
            Files.writeString(targetJava.toPath(), javaSource, StandardCharsets.UTF_8);

            var cr = plugin.getCompiler().compileSingleSource("com.javastudio.runtime." + className, javaSource);
            return cr.isSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    public String generateJavaSource(String className, FlowDefinition def) {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.javastudio.runtime;\n");
        sb.append("import org.bukkit.*;\n");
        sb.append("import org.bukkit.entity.*;\n");
        sb.append("import org.bukkit.inventory.*;\n");
        sb.append("import com.javastudio.api.*;\n");
        sb.append("import com.javastudio.api.item.*;\n\n");

        sb.append("public class ").append(className).append(" extends QuickScript {\n");
        sb.append("    @Override\n");
        sb.append("    public Object run() throws Throwable {\n");

        for (var cond : def.conditions()) {
            String type = cond.getOrDefault("type", "").toLowerCase(Locale.ROOT);
            switch (type) {
                case "chance" -> {
                    int percent = Integer.parseInt(cond.getOrDefault("percentage", "100"));
                    if (percent < 100) {
                        sb.append("        if (new java.util.Random().nextInt(100) >= ").append(percent).append(") return \"Điều kiện xác suất không đạt\";\n");
                    }
                }
                case "has_permission" -> {
                    String perm = cond.getOrDefault("permission", "");
                    sb.append("        if (player != null && !player.hasPermission(\"").append(escape(perm)).append("\")) return \"Thiếu quyền hạn\";\n");
                }
                case "is_sneaking" -> {
                    sb.append("        if (player != null && !player.isSneaking()) return \"Người chơi không lén lút\";\n");
                }
                case "health_below" -> {
                    double hp = Double.parseDouble(cond.getOrDefault("value", "10"));
                    sb.append("        if (player != null && player.getHealth() >= ").append(hp).append(") return \"Máu không thấp hơn ngưỡng\";\n");
                }
            }
        }

        for (var act : def.actions()) {
            String type = act.getOrDefault("type", "").toLowerCase(Locale.ROOT);
            switch (type) {
                case "broadcast" -> {
                    String msg = act.getOrDefault("message", "");
                    sb.append("        broadcast(\"").append(escape(msg)).append("\".replace(\"%player%\", player != null ? player.getName() : \"Server\"));\n");
                }
                case "send_title" -> {
                    String title = act.getOrDefault("title", "");
                    String sub = act.getOrDefault("subtitle", "");
                    sb.append("        title(\"").append(escape(title)).append("\", \"").append(escape(sub)).append("\");\n");
                }
                case "play_sound" -> {
                    String snd = act.getOrDefault("sound", "ENTITY_PLAYER_LEVELUP");
                    float vol = Float.parseFloat(act.getOrDefault("volume", "1.0"));
                    float pit = Float.parseFloat(act.getOrDefault("pitch", "1.0"));
                    sb.append("        sound(\"").append(escape(snd)).append("\", ").append(vol).append("f, ").append(pit).append("f);\n");
                }
                case "heal" -> {
                    sb.append("        heal();\n");
                }
                case "strike_lightning" -> {
                    sb.append("        if (player != null) player.getWorld().strikeLightningEffect(player.getLocation());\n");
                }
                case "execute_command" -> {
                    String cmd = act.getOrDefault("command", "");
                    sb.append("        cmd(\"").append(escape(cmd)).append("\".replace(\"%player%\", player != null ? player.getName() : \"\"));\n");
                }
                case "give_item" -> {
                    String mat = act.getOrDefault("material", "DIAMOND");
                    int amount = Integer.parseInt(act.getOrDefault("amount", "1"));
                    String name = act.getOrDefault("name", "");
                    String rarity = act.getOrDefault("rarity", "COMMON");
                    boolean glint = Boolean.parseBoolean(act.getOrDefault("glint", "false"));

                    sb.append("        if (player != null) {\n");
                    sb.append("            ItemStack item = itemBuilder(\"").append(escape(mat)).append("\")\n");
                    if (!name.isBlank()) {
                        sb.append("                .name(\"").append(escape(name)).append("\")\n");
                    }
                    sb.append("                .amount(").append(amount).append(")\n");
                    sb.append("                .rarity(\"").append(escape(rarity)).append("\")\n");
                    if (glint) {
                        sb.append("                .glintOverride(true)\n");
                    }
                    sb.append("                .build();\n");
                    sb.append("            player.getInventory().addItem(item);\n");
                    sb.append("        }\n");
                }
            }
        }

        sb.append("        return \"Thực thi Flow [").append(escape(def.id())).append("] hoàn tất thành công!\";\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    public List<FlowDefinition> getLoadedFlows() {
        return new ArrayList<>(loadedFlows.values());
    }

    public String exportFlowsJson() {
        StringBuilder sb = new StringBuilder("{\"flows\":[");
        int idx = 0;
        for (FlowDefinition f : loadedFlows.values()) {
            if (idx++ > 0) sb.append(",");
            sb.append("{\"id\":\"").append(escape(f.id()))
              .append("\",\"name\":\"").append(escape(f.name()))
              .append("\",\"trigger\":\"").append(escape(f.trigger()))
              .append("\",\"conditions\":").append(f.conditions().size())
              .append(",\"actions\":").append(f.actions().size())
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private FlowDefinition parseFlowJson(String json) {
        String id = extractField(json, "id");
        String name = extractField(json, "name");
        String trigger = extractField(json, "trigger");
        if (id.isBlank()) return null;
        if (name.isBlank()) name = id;
        if (trigger.isBlank()) trigger = "manual";

        List<Map<String, String>> conditions = parseNodeArray(json, "conditions");
        List<Map<String, String>> actions = parseNodeArray(json, "actions");

        return new FlowDefinition(id, name, trigger, conditions, actions);
    }

    private List<Map<String, String>> parseNodeArray(String json, String arrayName) {
        List<Map<String, String>> list = new ArrayList<>();
        String key = "\"" + arrayName + "\"";
        int idx = json.indexOf(key);
        if (idx == -1) return list;

        int open = json.indexOf('[', idx);
        if (open == -1) return list;
        int close = json.indexOf(']', open);
        if (close == -1) return list;

        String slice = json.substring(open + 1, close);
        String[] objects = slice.split("(?<=\\}),\\s*(?=\\{)");

        for (String obj : objects) {
            obj = obj.trim();
            if (obj.startsWith("{") && obj.endsWith("}")) {
                Map<String, String> map = new HashMap<>();
                String inner = obj.substring(1, obj.length() - 1);
                String[] pairs = inner.split(",");
                for (String p : pairs) {
                    String[] kv = p.split(":", 2);
                    if (kv.length == 2) {
                        String k = kv[0].replaceAll("[\"\\s]", "");
                        String v = kv[1].replaceAll("[\"\\s]", "");
                        map.put(k, v);
                    }
                }
                if (!map.isEmpty()) list.add(map);
            }
        }
        return list;
    }

    private String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx == -1) return "";
        int colon = json.indexOf(':', idx + key.length());
        if (colon == -1) return "";
        int startQuote = json.indexOf('\"', colon);
        if (startQuote == -1) return "";
        int endQuote = json.indexOf('\"', startQuote + 1);
        if (endQuote == -1) return "";
        return json.substring(startQuote + 1, endQuote);
    }

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}

