package com.javastudio.compiler;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.PlatformUtil;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class RuntimeCompiler {

    private final JavaStudioPlugin plugin;
    private final Logger logger;
    private final JavaCompiler compiler;
    private final com.javastudio.pipeline.InMemoryJavaCompiler inMemoryCompiler = new com.javastudio.pipeline.InMemoryJavaCompiler();
    private String cachedClasspath;

    public RuntimeCompiler(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.compiler = ToolProvider.getSystemJavaCompiler();

        if (this.compiler == null) {
            logger.severe("===============================================================");
            logger.severe("[JavaStudio] CẢNH BÁO: Không tìm thấy System Java Compiler!");
            logger.severe("Server hiện tại có thể đang chạy bằng JRE thay vì JDK.");
            logger.severe("Vui lòng khởi động server Minecraft với JDK 21+ để hỗ trợ biên dịch.");
            logger.severe("===============================================================");
        } else {
            logger.info("[JavaStudio] Java Compiler (JDK) đã sẵn sàng hoạt động!");
        }
    }

    public com.javastudio.pipeline.InMemoryJavaCompiler getInMemoryCompiler() {
        return inMemoryCompiler;
    }

    public boolean isAvailable() {
        return compiler != null;
    }

    public CompileResult compileSingleSource(String className, String sourceCode) {
        Map<String, String> map = new HashMap<>();
        map.put(className, sourceCode);
        return compileSources(map);
    }

    public CompileResult compileSources(Map<String, String> sources) {
        if (!isAvailable()) {
            return new CompileResult(false, null, Collections.emptyList(), null);
        }

        if (plugin.getConfig().getBoolean("security.sandbox.enabled", true) && plugin.getSecuritySandbox() != null) {
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                String violation = plugin.getSecuritySandbox().validateCode(entry.getValue());
                if (violation != null) {
                    logger.warning("[Security Sandbox] Chặn biên dịch lớp '" + entry.getKey() + "': " + violation);
                    return CompileResult.securityFailure("Lớp '" + entry.getKey() + "' bị chặn: " + violation);
                }
            }
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager stdFileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        ClassFileManager classFileManager = new ClassFileManager(stdFileManager);

        List<JavaFileObject> compilationUnits = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String code = com.javastudio.security.LoopWatchdog.instrument(entry.getValue());
            compilationUnits.add(new MemoryJavaFileObject(entry.getKey(), code));
        }

        List<String> options = buildCompilerOptions();

        JavaCompiler.CompilationTask task = compiler.getTask(
                null,
                classFileManager,
                diagnostics,
                options,
                null,
                compilationUnits
        );

        boolean success = Boolean.TRUE.equals(task.call());
        DynamicClassLoader loader = null;
        Map<String, byte[]> compiledBytes = classFileManager.getAllByteCodes();

        if (success && plugin.getConfig().getBoolean("security.sandbox.enabled", true) && plugin.getClassTaintEngine() != null) {
            var taint = plugin.getClassTaintEngine().analyzeBytecodeMap(compiledBytes);
            if (!taint.safe()) {
                try {
                    classFileManager.close();
                } catch (IOException ignored) {}
                String msg = String.join("; ", taint.violations());
                logger.warning("[Bytecode Taint] Chặn nạp bytecode nguy hiểm: " + msg);
                return CompileResult.securityFailure("[Bytecode Taint] " + msg);
            }
        }

        if (success) {
            loader = new DynamicClassLoader(plugin.getClass().getClassLoader());
            loader.addByteCodes(compiledBytes);
        }

        try {
            classFileManager.close();
        } catch (IOException ignored) {}

        return new CompileResult(success, compiledBytes, diagnostics.getDiagnostics(), loader);
    }

    public CompileResult compileDirectory(File srcDir) {
        if (!srcDir.exists() || !srcDir.isDirectory()) {
            return new CompileResult(false, null, Collections.emptyList(), null);
        }

        Map<String, String> sources = new HashMap<>();
        Path srcPath = srcDir.toPath();

        try (Stream<Path> stream = Files.walk(srcPath)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    Path relative = srcPath.relativize(p);
                    String className = relative.toString()
                            .replace(File.separatorChar, '.')
                            .replaceAll("\\.java$", "");
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    sources.put(className, content);
                } catch (IOException e) {
                    logger.warning("Không thể đọc file: " + p + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.severe("Lỗi khi duyệt thư mục source: " + e.getMessage());
            return new CompileResult(false, null, Collections.emptyList(), null);
        }

        return compileSources(sources);
    }

    private List<String> buildCompilerOptions() {
        List<String> options = new ArrayList<>();
        options.add("-classpath");
        options.add(getClasspath());
        options.add("-parameters");
        options.add("-Xlint:none");

        int targetRelease = plugin.getConfig().getInt("compiler.target-release", 21);
        int currentJvm = PlatformUtil.getJavaVersion();
        if (targetRelease >= 21 && targetRelease <= currentJvm) {
            options.add("--release");
            options.add(String.valueOf(targetRelease));
        }

        List<String> extra = plugin.getConfig().getStringList("compiler.options");
        if (extra != null) {
            for (String opt : extra) {
                if (!opt.isBlank() && !options.contains(opt)) {
                    options.add(opt);
                }
            }
        }

        return options;
    }

    public synchronized String getClasspath() {
        if (cachedClasspath != null) {
            return cachedClasspath;
        }

        Set<String> paths = new LinkedHashSet<>();

        addClassLocation(paths, org.bukkit.Bukkit.class);
        addClassLocation(paths, plugin.getServer().getClass());
        addClassLocation(paths, plugin.getClass());
        addClassLocation(paths, com.javastudio.api.DynamicPlugin.class);

        try {
            for (org.bukkit.plugin.Plugin p : plugin.getServer().getPluginManager().getPlugins()) {
                if (p != null) {
                    addClassLocation(paths, p.getClass());
                }
            }
        } catch (Throwable ignored) {}

        String sysPath = System.getProperty("java.class.path");
        if (sysPath != null && !sysPath.isEmpty()) {
            for (String part : sysPath.split(File.pathSeparator)) {
                paths.add(part);
            }
        }

        ClassLoader cl = plugin.getClass().getClassLoader();
        while (cl != null) {
            if (cl instanceof URLClassLoader ucl) {
                for (URL url : ucl.getURLs()) {
                    try {
                        paths.add(new File(url.toURI()).getAbsolutePath());
                    } catch (Exception ignored) {}
                }
            }
            cl = cl.getParent();
        }

        addJarsRecursively(paths, new File("libraries"));
        addJarsRecursively(paths, new File("bundler"));
        addJarsRecursively(paths, new File("cache"));

        if (plugin.getDependencyResolver() != null) {
            addJarsRecursively(paths, plugin.getDependencyResolver().getCacheDir());
        }

        File pluginsDir = plugin.getDataFolder().getParentFile();
        if (pluginsDir != null && pluginsDir.exists() && pluginsDir.isDirectory()) {
            File[] pluginJars = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
            if (pluginJars != null) {
                for (File jar : pluginJars) {
                    paths.add(jar.getAbsolutePath());
                }
            }
        }

        cachedClasspath = String.join(File.pathSeparator, paths);
        return cachedClasspath;
    }

    private void addClassLocation(Set<String> paths, Class<?> clazz) {
        if (clazz == null) return;
        try {
            java.security.CodeSource cs = clazz.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File file = new File(cs.getLocation().toURI());
                if (file.exists()) {
                    paths.add(file.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) {}
    }

    private void addJarsRecursively(Set<String> paths, File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isDirectory()) {
                    if (!f.getName().equals(".git")) {
                        addJarsRecursively(paths, f);
                    }
                } else if (f.getName().endsWith(".jar")) {
                    paths.add(f.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) {}
    }

    public synchronized void invalidateClasspathCache() {
        cachedClasspath = null;
        com.javastudio.pipeline.InMemoryJavaCompiler.clearCache();
    }
}
