package com.javastudio.control;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.ReflectionUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.Closeable;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.*;
import java.util.logging.Logger;

/**
 * Trình điều khiển Plugin toàn diện (tương tự PlugManX) cho phép:
 * - Xem danh sách, thông tin chi tiết, phụ thuộc của mọi plugin server
 * - Bật (Enable), Tắt (Disable), Nạp mới (Load) file .jar bất kỳ
 * - Gỡ bỏ hoàn toàn (Unload) và giải phóng khóa file đĩa trên Windows
 * - Nạp lại (Reload) mọi plugin ngoài lúc server đang chạy mà không cần restart
 */
public class PluginController {

    private final JavaStudioPlugin hostPlugin;
    private final Logger logger;

    public PluginController(JavaStudioPlugin hostPlugin) {
        this.hostPlugin = hostPlugin;
        this.logger = hostPlugin.getLogger();
    }

    /**
     * Lấy danh sách toàn bộ plugin đã đăng ký trên server.
     */
    public List<Plugin> getAllPlugins() {
        return Arrays.asList(Bukkit.getPluginManager().getPlugins());
    }

    /**
     * Tìm plugin theo tên (không phân biệt hoa thường).
     */
    public Plugin getPlugin(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        if (p != null) return p;

        for (Plugin pl : Bukkit.getPluginManager().getPlugins()) {
            if (pl.getName().equalsIgnoreCase(name)) {
                return pl;
            }
        }
        return null;
    }

    /**
     * Bật (Enable) một plugin đang bị tắt.
     */
    public synchronized boolean enablePlugin(String name, StringBuilder log) {
        Plugin plugin = getPlugin(name);
        if (plugin == null) {
            if (log != null) log.append("Không tìm thấy plugin '").append(name).append("'!");
            return false;
        }

        if (plugin.isEnabled()) {
            if (log != null) log.append("Plugin '").append(plugin.getName()).append("' đã được bật từ trước!");
            return false;
        }

        try {
            Bukkit.getPluginManager().enablePlugin(plugin);
            ReflectionUtil.syncCommands();
            if (log != null) log.append("Đã kích hoạt thành công plugin '").append(plugin.getName()).append("'!");
            return true;
        } catch (Throwable t) {
            if (log != null) log.append("Lỗi khi kích hoạt plugin: ").append(t.getMessage());
            logger.severe("Lỗi khi bật plugin " + name + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Tắt (Disable) một plugin đang chạy.
     */
    public synchronized boolean disablePlugin(String name, StringBuilder log) {
        Plugin plugin = getPlugin(name);
        if (plugin == null) {
            if (log != null) log.append("Không tìm thấy plugin '").append(name).append("'!");
            return false;
        }

        if (!plugin.isEnabled()) {
            if (log != null) log.append("Plugin '").append(plugin.getName()).append("' đã tắt từ trước!");
            return false;
        }

        try {
            Bukkit.getPluginManager().disablePlugin(plugin);
            ReflectionUtil.syncCommands();
            if (log != null) log.append("Đã vô hiệu hóa thành công plugin '").append(plugin.getName()).append("'!");
            return true;
        } catch (Throwable t) {
            if (log != null) log.append("Lỗi khi tắt plugin: ").append(t.getMessage());
            logger.severe("Lỗi khi tắt plugin " + name + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Nạp một file plugin .jar mới từ thư mục plugins/ vào server.
     */
    @SuppressWarnings("deprecation")
    public synchronized boolean loadPlugin(File jarFile, StringBuilder log) {
        if (jarFile == null || !jarFile.exists() || !jarFile.getName().endsWith(".jar")) {
            if (log != null) log.append("Tập tin JAR không tồn tại hoặc không hợp lệ!");
            return false;
        }

        PluginManager pm = Bukkit.getPluginManager();
        try {
            Plugin newPlugin = pm.loadPlugin(jarFile);
            if (newPlugin == null) {
                if (log != null) log.append("Không thể nạp plugin từ file ").append(jarFile.getName());
                return false;
            }

            newPlugin.onLoad();
            pm.enablePlugin(newPlugin);
            ReflectionUtil.syncCommands();

            if (log != null) {
                log.append("Đã nạp và kích hoạt thành công plugin '")
                   .append(newPlugin.getName())
                   .append("' (v").append(newPlugin.getDescription().getVersion()).append(")!");
            }
            return true;
        } catch (Throwable t) {
            if (log != null) log.append("Lỗi khi nạp JAR: ").append(t.getMessage());
            logger.severe("Lỗi khi nạp plugin JAR " + jarFile.getName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Nạp plugin theo tên file .jar trong thư mục plugins/.
     */
    public boolean loadPlugin(String jarFileName, StringBuilder log) {
        if (!jarFileName.endsWith(".jar")) {
            jarFileName += ".jar";
        }
        File pluginsDir = hostPlugin.getDataFolder().getParentFile();
        File targetFile = new File(pluginsDir, jarFileName);
        return loadPlugin(targetFile, log);
    }

    /**
     * Gỡ bỏ hoàn toàn (Unload) một plugin khỏi bộ nhớ, hủy toàn bộ event,
     * lệnh và đóng ClassLoader để mở khóa file JAR trên Windows.
     */
    public synchronized boolean unloadPlugin(String name, StringBuilder log) {
        Plugin plugin = getPlugin(name);
        if (plugin == null) {
            if (log != null) log.append("Không tìm thấy plugin '").append(name).append("'!");
            return false;
        }

        String pluginName = plugin.getName();
        PluginManager pm = Bukkit.getPluginManager();

        // 1. Tắt plugin nếu đang bật
        if (plugin.isEnabled()) {
            try {
                pm.disablePlugin(plugin);
            } catch (Throwable t) {
                logger.warning("Lỗi khi disable plugin " + pluginName + ": " + t.getMessage());
            }
        }

        // 2. Gỡ bỏ toàn bộ Event Listeners đã đăng ký của plugin
        try {
            for (HandlerList hl : HandlerList.getHandlerLists()) {
                hl.unregister(plugin);
            }
        } catch (Throwable t) {
            logger.warning("Lỗi khi unregister listeners của " + pluginName + ": " + t.getMessage());
        }

        // 3. Hủy bỏ toàn bộ Task Scheduler đang chạy của plugin
        try {
            Bukkit.getScheduler().cancelTasks(plugin);
        } catch (Throwable ignored) {}

        // 4. Gỡ bỏ toàn bộ Commands do plugin đăng ký vào CommandMap
        try {
            CommandMap map = ReflectionUtil.getCommandMap();
            Map<String, Command> knownCommands = ReflectionUtil.getKnownCommands();
            if (map != null && knownCommands != null) {
                List<String> toRemove = new ArrayList<>();
                for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
                    Command cmd = entry.getValue();
                    if (cmd instanceof PluginIdentifiableCommand pic && plugin.equals(pic.getPlugin())) {
                        cmd.unregister(map);
                        toRemove.add(entry.getKey());
                    } else if (entry.getKey().startsWith(pluginName.toLowerCase() + ":")) {
                        cmd.unregister(map);
                        toRemove.add(entry.getKey());
                    }
                }
                for (String key : toRemove) {
                    knownCommands.remove(key);
                }
                ReflectionUtil.syncCommands();
            }
        } catch (Throwable t) {
            logger.warning("Lỗi khi unregister commands của " + pluginName + ": " + t.getMessage());
        }

        // 5. Xóa khỏi danh sách plugins và lookupNames của PluginManager
        removeFromPluginManager(pm, plugin);

        // 6. Đóng ClassLoader để mở khóa file JAR trên Windows
        ClassLoader cl = plugin.getClass().getClassLoader();
        if (cl != null) {
            try {
                // Xóa tham chiếu plugin trong ClassLoader để tránh rò rỉ bộ nhớ
                Field pluginField = cl.getClass().getDeclaredField("plugin");
                pluginField.setAccessible(true);
                pluginField.set(cl, null);
            } catch (Throwable ignored) {}

            try {
                Field pluginInitField = cl.getClass().getDeclaredField("pluginInit");
                pluginInitField.setAccessible(true);
                pluginInitField.set(cl, null);
            } catch (Throwable ignored) {}

            if (cl instanceof URLClassLoader ucl) {
                try {
                    ucl.close();
                } catch (Throwable ignored) {}
            } else if (cl instanceof Closeable closeable) {
                try {
                    closeable.close();
                } catch (Throwable ignored) {}
            }
        }

        // Kích hoạt GC để giải phóng triệt để file descriptor trên Windows
        System.gc();

        if (log != null) {
            log.append("Đã gỡ bỏ (unload) hoàn toàn plugin '").append(pluginName).append("' và giải phóng tài nguyên!");
        }
        return true;
    }

    /**
     * Nạp lại (Reload) một plugin bằng cách Unload rồi Nạp lại từ file JAR.
     */
    public synchronized boolean reloadPlugin(String name, StringBuilder log) {
        Plugin plugin = getPlugin(name);
        if (plugin == null) {
            if (log != null) log.append("Không tìm thấy plugin '").append(name).append("'!");
            return false;
        }

        File jarFile = getPluginFile(plugin);
        if (jarFile == null || !jarFile.exists()) {
            if (log != null) log.append("Không thể định vị được file JAR của plugin '").append(name).append("'!");
            return false;
        }

        StringBuilder unloadLog = new StringBuilder();
        boolean unloaded = unloadPlugin(name, unloadLog);
        if (!unloaded) {
            if (log != null) log.append("Thất bại khi gỡ plugin: ").append(unloadLog);
            return false;
        }

        StringBuilder loadLog = new StringBuilder();
        boolean loaded = loadPlugin(jarFile, loadLog);
        if (loaded) {
            if (log != null) log.append("Nạp lại thành công plugin '").append(name).append("'!");
            return true;
        } else {
            if (log != null) log.append("Gỡ thành công nhưng lỗi khi nạp lại: ").append(loadLog);
            return false;
        }
    }

    /**
     * Định vị file .jar của một plugin đã cài đặt
     */
    public File getPluginFile(Plugin plugin) {
        if (plugin == null) return null;

        // 1. Thử gọi JavaPlugin.getFile()
        if (plugin instanceof JavaPlugin jp) {
            try {
                Method getFileMethod = JavaPlugin.class.getDeclaredMethod("getFile");
                getFileMethod.setAccessible(true);
                File f = (File) getFileMethod.invoke(jp);
                if (f != null && f.exists()) return f;
            } catch (Throwable ignored) {}
        }

        // 2. Thử lấy từ CodeSource Location
        try {
            java.security.CodeSource cs = plugin.getClass().getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = new File(cs.getLocation().toURI());
                if (f.exists() && f.getName().endsWith(".jar")) {
                    return f;
                }
            }
        } catch (Throwable ignored) {}

        // 3. Quét trong thư mục plugins/
        File pluginsDir = hostPlugin.getDataFolder().getParentFile();
        File direct = new File(pluginsDir, plugin.getName() + ".jar");
        if (direct.exists()) return direct;

        File[] list = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (list != null) {
            for (File jar : list) {
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
                    java.util.zip.ZipEntry entry = jf.getEntry("plugin.yml");
                    if (entry != null) {
                        try (java.io.InputStream is = jf.getInputStream(entry)) {
                            PluginDescriptionFile desc = new PluginDescriptionFile(is);
                            if (desc.getName().equalsIgnoreCase(plugin.getName())) {
                                return jar;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        return null;
    }

    /**
     * Tìm danh sách các plugin khác đang phụ thuộc (depend) vào plugin này.
     */
    @SuppressWarnings("deprecation")
    public List<String> getDependentPlugins(String pluginName) {
        List<String> dependents = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            PluginDescriptionFile desc = p.getDescription();
            if (desc.getDepend().contains(pluginName) || desc.getSoftDepend().contains(pluginName)) {
                dependents.add(p.getName());
            }
        }
        return dependents;
    }

    /**
     * Lấy danh sách toàn bộ các file .jar có trong thư mục plugins/.
     */
    public List<File> getAvailableJarFiles() {
        File pluginsDir = hostPlugin.getDataFolder().getParentFile();
        if (pluginsDir == null || !pluginsDir.exists()) return Collections.emptyList();
        File[] jars = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        return jars != null ? Arrays.asList(jars) : Collections.emptyList();
    }

    /**
     * Lấy thông tin cấu trúc chi tiết của 1 plugin.
     */
    @SuppressWarnings("deprecation")
    public Map<String, Object> getPluginDetails(String name) {
        Plugin plugin = getPlugin(name);
        if (plugin == null) return null;

        Map<String, Object> details = new LinkedHashMap<>();
        PluginDescriptionFile desc = plugin.getDescription();

        details.put("name", plugin.getName());
        details.put("version", desc.getVersion());
        details.put("enabled", plugin.isEnabled());
        details.put("authors", desc.getAuthors());
        details.put("description", desc.getDescription());
        details.put("website", desc.getWebsite());
        details.put("main", desc.getMain());
        details.put("depend", desc.getDepend());
        details.put("softDepend", desc.getSoftDepend());
        details.put("commands", desc.getCommands().keySet());

        File f = getPluginFile(plugin);
        if (f != null && f.exists()) {
            details.put("file", f.getName());
            details.put("fileSize", f.length());
        }

        details.put("dependents", getDependentPlugins(plugin.getName()));
        return details;
    }

    @SuppressWarnings("unchecked")
    private void removeFromPluginManager(PluginManager pm, Plugin plugin) {
        Class<?> clazz = pm.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field pluginsField = clazz.getDeclaredField("plugins");
                pluginsField.setAccessible(true);
                Object pluginsObj = pluginsField.get(pm);
                if (pluginsObj instanceof List<?> list) {
                    list.remove(plugin);
                }
            } catch (Throwable ignored) {}

            try {
                Field namesField = clazz.getDeclaredField("lookupNames");
                namesField.setAccessible(true);
                Object namesObj = namesField.get(pm);
                if (namesObj instanceof Map<?, ?> map) {
                    ((Map<String, Object>) map).remove(plugin.getName().toLowerCase());
                    ((Map<String, Object>) map).remove(plugin.getName());
                }
            } catch (Throwable ignored) {}

            clazz = clazz.getSuperclass();
        }
    }
}
