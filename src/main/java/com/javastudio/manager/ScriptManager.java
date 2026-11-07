package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.DynamicScript;
import com.javastudio.api.JavaContext;
import com.javastudio.compiler.CompileResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class ScriptManager {

    private final JavaStudioPlugin plugin;
    private final File scriptsFolder;

    public ScriptManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.scriptsFolder = new File(plugin.getDataFolder(), "scripts");
        if (!scriptsFolder.exists()) {
            scriptsFolder.mkdirs();
        }
        createDefaultScripts();
    }

    private void createDefaultScripts() {
        File healScript = new File(scriptsFolder, "heal.java");
        if (!healScript.exists()) {
            try {
                Files.writeString(healScript.toPath(), """
                        import com.javastudio.api.QuickScript;

                        public class heal extends QuickScript {
                            @Override
                            public Object run() throws Throwable {
                                heal();
                                feed();
                                msg("§a✔ Bạn đã được hồi phục toàn bộ máu và thức ăn!");
                                return "Đã hồi phục thành công!";
                            }
                        }
                        """, StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }

        File broadcastScript = new File(scriptsFolder, "broadcast_sound.java");
        if (!broadcastScript.exists()) {
            try {
                Files.writeString(broadcastScript.toPath(), """
                        import com.javastudio.api.QuickScript;

                        public class broadcast_sound extends QuickScript {
                            @Override
                            public Object run() throws Throwable {
                                String text = ctx.getArgs().length > 0 ? String.join(" ", ctx.getArgs()) : "Thông báo từ máy chủ!";
                                broadcast("§6§l[THÔNG BÁO] §f" + text);
                                soundAll("levelup");
                                return "Đã phát thông báo!";
                            }
                        }
                        """, StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }

        File demoScript = new File(scriptsFolder, "demo_oneliner.java");
        if (!demoScript.exists()) {
            try {
                Files.writeString(demoScript.toPath(), """
                        heal(); day(); give("diamond_sword", 1); broadcast("§e✔ Máy chủ vừa thực thi demo_oneliner.java thành công!");
                        """, StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
    }

    public Object runScript(String scriptName, JavaContext ctx, StringBuilder errorLog) throws Throwable {
        String cleanName = scriptName.endsWith(".java") ? scriptName.substring(0, scriptName.length() - 5) : scriptName;
        File scriptFile = new File(scriptsFolder, cleanName + ".java");

        if (!scriptFile.exists()) {
            if (errorLog != null) errorLog.append("Không tìm thấy script ").append(cleanName).append(".java");
            return null;
        }

        String sourceCode = Files.readString(scriptFile.toPath(), StandardCharsets.UTF_8).trim();

        String violation = plugin.getSecuritySandbox().validateCode(sourceCode);
        if (violation != null) {
            if (errorLog != null) errorLog.append("Vi phạm bảo mật: ").append(violation);
            return null;
        }

        String fullClassPath;
        String fullSource;

        if (sourceCode.contains("class ")) {
            String pkg = "";
            java.util.regex.Matcher mPkg = java.util.regex.Pattern.compile("package\\s+([a-zA-Z0-9_.]+);").matcher(sourceCode);
            if (mPkg.find()) {
                pkg = mPkg.group(1).trim() + ".";
            }
            java.util.regex.Matcher mCls = java.util.regex.Pattern.compile("(?:public\\s+)?class\\s+([a-zA-Z0-9_]+)").matcher(sourceCode);
            String className = cleanName;
            if (mCls.find()) {
                className = mCls.group(1);
            }
            fullClassPath = pkg + className;
            fullSource = sourceCode;
        } else {
            String sanitizedClassName = "Script_" + cleanName.replaceAll("[^a-zA-Z0-9_]", "_");
            fullClassPath = "com.javastudio.runtime." + sanitizedClassName;

            String body;
            if (!sourceCode.contains(";")) {
                body = "return String.valueOf(" + sourceCode + ");";
            } else if (!sourceCode.contains("return ") && !sourceCode.contains("return;")) {
                body = sourceCode + "\nreturn null;";
            } else {
                body = sourceCode;
            }

            fullSource = """
                    package com.javastudio.runtime;
                    import org.bukkit.*;
                    import org.bukkit.block.*;
                    import org.bukkit.command.*;
                    import org.bukkit.entity.*;
                    import org.bukkit.inventory.*;
                    import org.bukkit.potion.*;
                    import org.bukkit.util.*;
                    import java.util.*;
                    import java.util.stream.*;
                    import com.javastudio.api.*;

                    public class %NAME% extends QuickScript {
                        @Override
                        public Object run() throws Throwable {
                            %BODY%
                        }
                    }
                    """.replace("%NAME%", sanitizedClassName).replace("%BODY%", body);
        }

        fullSource = com.javastudio.security.GasMeter.instrumentSource(fullSource);
        CompileResult result = plugin.getCompiler().compileSingleSource(fullClassPath, fullSource);
        if (!result.isSuccess() && !sourceCode.contains("class ") && result.getFormattedErrors().contains("'void' type not allowed")) {
            String sanitizedClassName = "Script_" + cleanName.replaceAll("[^a-zA-Z0-9_]", "_");
            String body = sourceCode.endsWith(";") ? sourceCode + " return null;" : sourceCode + "; return null;";
            fullSource = """
                    package com.javastudio.runtime;
                    import org.bukkit.*;
                    import org.bukkit.block.*;
                    import org.bukkit.command.*;
                    import org.bukkit.entity.*;
                    import org.bukkit.inventory.*;
                    import org.bukkit.potion.*;
                    import org.bukkit.util.*;
                    import java.util.*;
                    import java.util.stream.*;
                    import com.javastudio.api.*;

                    public class %NAME% extends QuickScript {
                        @Override
                        public Object run() throws Throwable {
                            %BODY%
                        }
                    }
                    """.replace("%NAME%", sanitizedClassName).replace("%BODY%", body);
            result = plugin.getCompiler().compileSingleSource(fullClassPath, fullSource);
        }

        if (!result.isSuccess()) {
            if (errorLog != null) errorLog.append(result.getFormattedErrors());
            return null;
        }

        if (plugin.getClassTaintEngine() != null && result.getByteCodes() != null) {
            for (Map.Entry<String, byte[]> entry : result.getByteCodes().entrySet()) {
                var taint = plugin.getClassTaintEngine().analyzeClass(entry.getValue());
                if (!taint.safe()) {
                    if (errorLog != null) {
                        errorLog.append("Bytecode Taint trong ").append(entry.getKey()).append(": ").append(String.join("; ", taint.violations()));
                    }
                    return null;
                }
            }
        }

        Class<?> scriptClass = result.loadClass(fullClassPath);
        if (!DynamicScript.class.isAssignableFrom(scriptClass)) {
            if (errorLog != null) errorLog.append("Script phải implement interface com.javastudio.api.DynamicScript hoặc kế thừa QuickScript!");
            return null;
        }

        DynamicScript script = (DynamicScript) scriptClass.getDeclaredConstructor().newInstance();
        return plugin.getSecuritySandbox().executeWithTimeout(() -> {
            com.javastudio.security.GasMeter.start(10_000_000L, 3000L);
            try {
                return script.execute(ctx);
            } finally {
                com.javastudio.security.GasMeter.stop();
            }
        }, 5000);
    }

    public boolean createScript(String scriptName) throws IOException {
        String cleanName = scriptName.endsWith(".java") ? scriptName.substring(0, scriptName.length() - 5) : scriptName;
        File scriptFile = new File(scriptsFolder, cleanName + ".java");
        if (scriptFile.exists()) {
            return false;
        }

        String code = """
                import com.javastudio.api.QuickScript;
                import org.bukkit.entity.Player;

                public class %CLASS% extends QuickScript {
                    @Override
                    public Object run() throws Throwable {
                        heal();
                        feed();
                        msg("§a✔ Thực thi script %CLASS% thành công!");
                        return "OK";
                    }
                }
                """.replace("%CLASS%", cleanName);

        Files.writeString(scriptFile.toPath(), code, StandardCharsets.UTF_8);
        return true;
    }

    public List<String> getAvailableScripts() {
        File[] files = scriptsFolder.listFiles((dir, name) -> name.endsWith(".java"));
        if (files == null) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (File f : files) {
            list.add(f.getName().replaceAll("\\.java$", ""));
        }
        return list;
    }

    public File getScriptsFolder() {
        return scriptsFolder;
    }
}
