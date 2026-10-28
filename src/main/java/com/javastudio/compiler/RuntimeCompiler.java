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

/**
 * Bộ biên dịch Java trong Runtime sử dụng javax.tools.JavaCompiler (JDK).
 * Tự động tìm kiếm Classpath từ Minecraft Server (Paper/Purpur/Spigot),
 * các thư viện phụ thuộc và các plugin khác đang chạy.
 */
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

    /**
     * Kiểm tra xem môi trường hiện tại có sẵn JavaCompiler không.
     */
    public boolean isAvailable() {
        return compiler != null;
    }

    /**
     * Biên dịch 1 file source đơn lẻ trực tiếp từ chuỗi code trong RAM.
     */
    public CompileResult compileSingleSource(String className, String sourceCode) {
        Map<String, String> map = new HashMap<>();
        map.put(className, sourceCode);
        return compileSources(map);
    }

    /**
     * Biên dịch danh sách nhiều file source trong RAM.
     *
     * @param sources Map từ tên class đầy đủ (vd: com.example.MyPlugin) đến nội dung code
     */
    public CompileResult compileSources(Map<String, String> sources) {
        if (!isAvailable()) {
            return new CompileResult(false, null, Collections.emptyList(), null);
        }

        // Kiểm tra an ninh Hộp Cát Sandbox trước khi đưa vào trình biên dịch
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

        if (success) {
            loader = new DynamicClassLoader(plugin.getClass().getClassLoader());
            loader.addByteCodes(classFileManager.getAllByteCodes());
        }

        try {
            classFileManager.close();
        } catch (IOException ignored) {}

        return new CompileResult(success, classFileManager.getAllByteCodes(), diagnostics.getDiagnostics(), loader);
    }

    /**
     * Biên dịch toàn bộ mã nguồn trong thư mục source của một dynamic plugin project.
     */
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

    /**
     * Xây dựng danh sách tùy chọn cho JavaCompiler, bao gồm Classpath đầy đủ của Server.
     */
    private List<String> buildCompilerOptions() {
        List<String> options = new ArrayList<>();
        options.add("-classpath");
        options.add(getClasspath());
        options.add("-parameters");
        options.add("-Xlint:none");

        // Tự động áp dụng --release tương thích toàn bộ Java 21+
        int targetRelease = plugin.getConfig().getInt("compiler.target-release", 21);
        int currentJvm = PlatformUtil.getJavaVersion();
        if (targetRelease >= 21 && targetRelease <= currentJvm) {
            options.add("--release");
            options.add(String.valueOf(targetRelease));
        }

        // Tùy chọn bổ sung từ config
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

    /**
     * Thu thập và cache Classpath của server Minecraft:
     * - java.class.path
     * - Các jar trong thư mục plugins/
     * - ClassLoader URLs
     */
    public synchronized String getClasspath() {
        if (cachedClasspath != null) {
            return cachedClasspath;
        }

        Set<String> paths = new LinkedHashSet<>();

        // 1. Trực tiếp trích xuất vị trí JAR của các class cốt lõi qua CodeSource (Hoạt động 100% trên Paper/Purpur/Spigot)
        addClassLocation(paths, org.bukkit.Bukkit.class);
        addClassLocation(paths, plugin.getServer().getClass());
        addClassLocation(paths, plugin.getClass());
        addClassLocation(paths, com.javastudio.api.DynamicPlugin.class);

        // 2. Nạp vị trí JAR của toàn bộ các plugin khác đang chạy trên server (Vault, LuckPerms, ProtocolLib, WorldEdit,...)
        try {
            for (org.bukkit.plugin.Plugin p : plugin.getServer().getPluginManager().getPlugins()) {
                if (p != null) {
                    addClassLocation(paths, p.getClass());
                }
            }
        } catch (Throwable ignored) {}

        // 3. System Classpath
        String sysPath = System.getProperty("java.class.path");
        if (sysPath != null && !sysPath.isEmpty()) {
            for (String part : sysPath.split(File.pathSeparator)) {
                paths.add(part);
            }
        }

        // 4. ClassLoader hierarchy URLs nếu có
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

        // 5. Thư mục libraries và bundler của Paper/Purpur (Mojmap / Modular Paperclip)
        addJarsRecursively(paths, new File("libraries"));
        addJarsRecursively(paths, new File("bundler"));
        addJarsRecursively(paths, new File("cache"));

        // 6. Thư mục .cache/libraries của JavaStudio (Maven Dependencies)
        if (plugin.getDependencyResolver() != null) {
            addJarsRecursively(paths, plugin.getDependencyResolver().getCacheDir());
        }

        // 7. Toàn bộ các plugin jar trong thư mục server "plugins"
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
                    // Giới hạn độ sâu để tránh quét quá lâu
                    if (!f.getName().equals(".git")) {
                        addJarsRecursively(paths, f);
                    }
                } else if (f.getName().endsWith(".jar")) {
                    paths.add(f.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Xóa cache classpath để tính toán lại khi có plugin mới được thêm vào server.
     */
    public synchronized void invalidateClasspathCache() {
        cachedClasspath = null;
        com.javastudio.pipeline.InMemoryJavaCompiler.clearCache();
    }
}
