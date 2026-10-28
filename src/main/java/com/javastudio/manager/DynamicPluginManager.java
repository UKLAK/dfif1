package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.DynamicPlugin;
import com.javastudio.compiler.CompileResult;
import com.javastudio.compiler.DynamicClassLoader;
import com.javastudio.compiler.RuntimeCompiler;
import com.javastudio.templates.PluginTemplates;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý vòng đời hoàn chỉnh của Dynamic Plugins:
 * Tạo mới dự án, Biên dịch, Nạp (Load), Gỡ (Unload) và Nạp lại (Reload).
 */
public class DynamicPluginManager {

    private final JavaStudioPlugin hostPlugin;
    private final File projectsFolder;
    private final AutoRollbackManager rollbackManager;
    private final Map<String, DynamicPlugin> activePlugins = new ConcurrentHashMap<>();
    private final Map<String, DynamicClassLoader> activeClassLoaders = new ConcurrentHashMap<>();

    public DynamicPluginManager(JavaStudioPlugin hostPlugin) {
        this.hostPlugin = hostPlugin;
        this.projectsFolder = new File(hostPlugin.getDataFolder(), "projects");
        if (!projectsFolder.exists()) {
            projectsFolder.mkdirs();
        }
        this.rollbackManager = new AutoRollbackManager(hostPlugin);
    }

    public File getSafeProjectDir(String name) {
        if (name == null || name.isBlank()) return null;
        return com.javastudio.security.cve.PathTraversalDefense.resolveSecureChild(projectsFolder, name);
    }

    /**
     * Tạo một dự án Dynamic Plugin mới với mã nguồn mẫu.
     */
    public boolean createProject(String name, String packageName, String templateKey) throws IOException {
        File projectDir = getSafeProjectDir(name);
        if (projectDir == null) {
            throw new IllegalArgumentException("Tên dự án vi phạm ranh giới hệ thống hoặc chứa ký tự Path Traversal (CVE-2026-13716)!");
        }
        if (projectDir.exists()) {
            return false;
        }

        if (packageName == null || packageName.trim().isEmpty()) {
            packageName = "com.javastudio.plugins." + name.toLowerCase();
        }

        String className = Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Plugin";
        String packagePath = packageName.replace('.', File.separatorChar);
        File srcDir = new File(projectDir, "src" + File.separator + packagePath);
        srcDir.mkdirs();

        // 1. Sinh mã nguồn từ template
        PluginTemplates.TemplateDefinition template = PluginTemplates.getTemplate(templateKey != null ? templateKey : "standard");
        if (template == null) {
            template = PluginTemplates.getTemplate("standard");
        }

        String code = template.getSourceTemplate()
                .replace("%PACKAGE%", packageName)
                .replace("%CLASS_NAME%", className)
                .replace("%PLUGIN_NAME%", name)
                .replace("%COMMAND%", name.toLowerCase());

        File mainJavaFile = new File(srcDir, className + ".java");
        Files.writeString(mainJavaFile.toPath(), code, StandardCharsets.UTF_8);

        // 2. Tạo file metadata.yml
        File metaFile = new File(projectDir, "metadata.yml");
        YamlConfiguration meta = new YamlConfiguration();
        meta.set("name", name);
        meta.set("main", packageName + "." + className);
        meta.set("version", "1.0.0");
        meta.set("author", "JavaStudio Developer");
        meta.set("description", template.getDescription());
        meta.save(metaFile);

        // 3. Tạo thư mục data và config.yml mẫu
        File dataDir = new File(projectDir, "data");
        dataDir.mkdirs();
        File configFile = new File(dataDir, "config.yml");
        YamlConfiguration config = new YamlConfiguration();
        config.set("enabled", true);
        config.set("example-message", "&aXin chào từ dynamic plugin " + name + "!");
        config.save(configFile);

        return true;
    }

    /**
     * Biên dịch dự án Dynamic Plugin.
     */
    public CompileResult compileProject(String name) {
        File projectDir = getSafeProjectDir(name);
        if (projectDir == null || !projectDir.exists()) {
            return null;
        }

        File metaFile = new File(projectDir, "metadata.yml");
        List<File> depJars = new ArrayList<>();
        if (metaFile.exists()) {
            YamlConfiguration meta = YamlConfiguration.loadConfiguration(metaFile);
            List<String> deps = meta.getStringList("dependencies");
            if (!deps.isEmpty()) {
                depJars = hostPlugin.getDependencyResolver().resolveDependencies(deps);
            }
        }

        File srcDir = new File(projectDir, "src");
        RuntimeCompiler compiler = hostPlugin.getCompiler();

        // Tạm thời thêm dependency jars vào classpath của compiler
        if (!depJars.isEmpty()) {
            compiler.invalidateClasspathCache();
        }

        return compiler.compileDirectory(srcDir);
    }

    /**
     * Nạp và kích hoạt một Dynamic Plugin.
     */
    public synchronized boolean loadPlugin(String name, StringBuilder errorLog) {
        File projectDir = getSafeProjectDir(name);
        if (projectDir == null || !projectDir.exists()) {
            if (errorLog != null) errorLog.append("Không tìm thấy thư mục dự án hoặc đường dẫn không hợp lệ: ").append(name);
            return false;
        }

        if (activePlugins.containsKey(name.toLowerCase())) {
            if (errorLog != null) errorLog.append("Plugin ").append(name).append(" đã được nạp từ trước!");
            return false;
        }

        File metaFile = new File(projectDir, "metadata.yml");
        if (!metaFile.exists()) {
            if (errorLog != null) errorLog.append("Thiếu file metadata.yml trong thư mục dự án!");
            return false;
        }

        YamlConfiguration meta = YamlConfiguration.loadConfiguration(metaFile);
        String mainClassName = meta.getString("main");
        String version = meta.getString("version", "1.0.0");
        String author = meta.getString("author", "Unknown");
        String description = meta.getString("description", "");

        if (mainClassName == null) {
            if (errorLog != null) errorLog.append("metadata.yml thiếu trường 'main'!");
            return false;
        }

        // 1. Phân giải và tải các thư viện Maven dependencies nếu có
        List<String> deps = meta.getStringList("dependencies");
        List<File> depJars = hostPlugin.getDependencyResolver().resolveDependencies(deps);

        // 2. Biên dịch mã nguồn
        CompileResult result = compileProject(name);
        if (result == null || !result.isSuccess()) {
            if (errorLog != null) {
                errorLog.append(result != null ? result.getFormattedErrors() : "Lỗi biên dịch không xác định!");
            }
            return false;
        }

        try {
            // Nạp dependency jars vào DynamicClassLoader
            DynamicClassLoader loader = result.getClassLoader();
            if (loader != null && !depJars.isEmpty()) {
                for (File jar : depJars) {
                    try {
                        loader.addURL(jar.toURI().toURL());
                    } catch (Exception ignored) {}
                }
            }

            Class<?> mainClass = result.loadClass(mainClassName);
            if (!DynamicPlugin.class.isAssignableFrom(mainClass)) {
                if (errorLog != null) errorLog.append("Class chính ").append(mainClassName).append(" không kế thừa từ DynamicPlugin!");
                return false;
            }

            DynamicPlugin pluginInstance = (DynamicPlugin) mainClass.getDeclaredConstructor().newInstance();
            File dataFolder = new File(projectDir, "data");
            pluginInstance.init(hostPlugin, name, version, author, description, dataFolder);

            // Khôi phục các biến trạng thái @Persist nếu có từ phiên trước (HotSwap State Preservation)
            restorePersistedFields(name, pluginInstance);

            // Quét và tự động đăng ký các annotation (@JCommand, @JEvent, @JTask, @JRegionTask, @JPlaceholder)
            pluginInstance.autoRegisterAnnotations();

            // Bật plugin
            pluginInstance.setEnabled(true);
            pluginInstance.onEnable();


            activePlugins.put(name.toLowerCase(), pluginInstance);
            if (loader != null) {
                activeClassLoaders.put(name.toLowerCase(), loader);
            }

            // Tự động tạo snapshot sao lưu phiên bản hoạt động ổn định
            rollbackManager.createSnapshot(name);

            return true;
        } catch (Throwable t) {
            if (errorLog != null) {
                errorLog.append("Lỗi khi khởi chạy plugin: ").append(t.getMessage());
            }
            t.printStackTrace();
            return false;
        }
    }

    private final Map<String, Map<String, Object>> persistedStateCache = new ConcurrentHashMap<>();

    private void savePersistedFields(String name, DynamicPlugin plugin) {
        if (plugin == null) return;
        Map<String, Object> stateMap = new HashMap<>();
        Class<?> current = plugin.getClass();
        while (current != null && current != DynamicPlugin.class && current != Object.class) {
            for (java.lang.reflect.Field f : current.getDeclaredFields()) {
                if (f.isAnnotationPresent(com.javastudio.api.Persist.class)) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(plugin);
                        stateMap.put(f.getName(), val);
                    } catch (Throwable ignored) {}
                }
            }
            current = current.getSuperclass();
        }
        if (!stateMap.isEmpty()) {
            persistedStateCache.put(name.toLowerCase(), stateMap);
            hostPlugin.getLogger().info("[HotSwap] Đã lưu " + stateMap.size() + " biến @Persist của plugin " + name);
        }
    }

    private void restorePersistedFields(String name, DynamicPlugin plugin) {
        if (plugin == null) return;
        Map<String, Object> stateMap = persistedStateCache.remove(name.toLowerCase());
        if (stateMap == null || stateMap.isEmpty()) return;

        int restored = 0;
        Class<?> current = plugin.getClass();
        while (current != null && current != DynamicPlugin.class && current != Object.class) {
            for (java.lang.reflect.Field f : current.getDeclaredFields()) {
                if (f.isAnnotationPresent(com.javastudio.api.Persist.class) && stateMap.containsKey(f.getName())) {
                    try {
                        f.setAccessible(true);
                        f.set(plugin, stateMap.get(f.getName()));
                        restored++;
                    } catch (Throwable ignored) {}
                }
            }
            current = current.getSuperclass();
        }
        if (restored > 0) {
            hostPlugin.getLogger().info("[HotSwap] Đã khôi phục thành công " + restored + " biến @Persist cho plugin " + name + "!");
        }
    }

    /**
     * Tắt và gỡ bỏ một Dynamic Plugin.
     */
    public synchronized boolean unloadPlugin(String name) {
        String key = name.toLowerCase();
        DynamicPlugin plugin = activePlugins.remove(key);
        if (plugin == null) {
            return false;
        }

        try {
            plugin.onDisable();
        } catch (Throwable t) {
            hostPlugin.getLogger().warning("Lỗi trong onDisable() của " + name + ": " + t.getMessage());
        }

        try {
            plugin.cleanup();
        } catch (Throwable t) {
            hostPlugin.getLogger().warning("Lỗi trong cleanup() của " + name + ": " + t.getMessage());
        }

        DynamicClassLoader loader = activeClassLoaders.remove(key);
        if (loader != null) {
            try {
                ClassLoaderLeakDetector.recordUnload(name, loader, loader.getLoadedClassNames());
                loader.close();
            } catch (IOException ignored) {}
        }

        return true;
    }

    /**
     * Nạp lại (Unload -> Recompile -> Load).
     */
    public synchronized boolean reloadPlugin(String name, StringBuilder errorLog) {
        DynamicPlugin old = activePlugins.get(name.toLowerCase());
        if (old != null) {
            savePersistedFields(name, old);
        }

        // Tạo snapshot trước khi reload
        rollbackManager.createSnapshot(name);

        unloadPlugin(name);
        boolean ok = loadPlugin(name, errorLog);

        // Crash-Safe Auto Rollback: nếu phiên bản mới bị lỗi cú pháp hoặc crash khi khởi chạy
        if (!ok && rollbackManager.hasSnapshot(name)) {
            hostPlugin.getLogger().warning("[AutoRollback] Reload thất bại cho " + name + "! Đang kích hoạt tự động Rollback về bản ổn định...");
            StringBuilder rLog = new StringBuilder();
            if (rollbackManager.rollback(name, rLog)) {
                if (loadPlugin(name, new StringBuilder())) {
                    if (errorLog != null) {
                        errorLog.append("\n[CẢNH BÁO AN TOÀN] Phiên bản mới bị lỗi hoặc crash, hệ thống đã tự động Rollback về bản hoạt động ổn định gần nhất!");
                    }
                    return true;
                }
            }
        }
        return ok;
    }

    /**
     * Tắt tất cả các Dynamic Plugin khi Server dừng hoặc JavaStudio bị tắt.
     */
    public synchronized void unloadAll() {
        for (String name : new ArrayList<>(activePlugins.keySet())) {
            unloadPlugin(name);
        }
        activePlugins.clear();
        activeClassLoaders.clear();
    }

    /**
     * Lấy danh sách tên tất cả các dự án dynamic plugin.
     */
    public List<String> getAllProjects() {
        File[] dirs = projectsFolder.listFiles(File::isDirectory);
        if (dirs == null) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (File dir : dirs) {
            list.add(dir.getName());
        }
        return list;
    }

    public boolean isLoaded(String name) {
        return activePlugins.containsKey(name.toLowerCase());
    }

    public DynamicPlugin getActivePlugin(String name) {
        return activePlugins.get(name.toLowerCase());
    }

    public Map<String, DynamicPlugin> getActivePlugins() {
        return Collections.unmodifiableMap(activePlugins);
    }

    public File getProjectsFolder() {
        return projectsFolder;
    }

    public AutoRollbackManager getRollbackManager() {
        return rollbackManager;
    }
}
