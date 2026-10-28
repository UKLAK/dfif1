package com.javastudio.watcher;

import com.javastudio.JavaStudioPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Dịch vụ theo dõi file (Hot-Reload File Watcher) tự động phát hiện thay đổi
 * khi lập trình viên nhấn Ctrl + S trong VS Code / IntelliJ và tự động nạp lại plugin/script.
 */
public class FileWatcherService {

    private final JavaStudioPlugin plugin;
    private final Logger logger;
    private WatchService watchService;
    private Thread watcherThread;
    private volatile boolean running = false;

    private final Map<WatchKey, Path> keyPathMap = new ConcurrentHashMap<>();
    private final Map<String, Long> debounceMap = new ConcurrentHashMap<>();
    private static final long DEBOUNCE_MS = 600; // Tránh nạp lặp nhiều lần khi IDE đang ghi file

    public FileWatcherService(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public synchronized void start() {
        if (running) return;
        boolean enabled = plugin.getConfig().getBoolean("watcher.enabled", true);
        if (!enabled) {
            logger.info("[FileWatcher] Chế độ Hot-Reload tự động đang tắt trong config.yml");
            return;
        }

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            this.keyPathMap.clear();

            // Đăng ký theo dõi thư mục projects và scripts
            File projectsDir = plugin.getPluginManager().getProjectsFolder();
            File scriptsDir = plugin.getScriptManager().getScriptsFolder();

            if (projectsDir.exists()) registerAll(projectsDir.toPath());
            if (scriptsDir.exists()) registerAll(scriptsDir.toPath());

            running = true;
            watcherThread = new Thread(this::watchLoop, "JavaStudio-FileWatcher");
            watcherThread.setDaemon(true);
            watcherThread.start();

            logger.info("[FileWatcher] Hot-Reload Watcher đã kích hoạt! Server sẽ tự động nạp lại khi bạn lưu file .java");
        } catch (IOException e) {
            logger.warning("[FileWatcher] Không thể khởi động File Watcher: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {}
            watchService = null;
        }
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
        keyPathMap.clear();
        logger.info("[FileWatcher] Đã dừng Hot-Reload File Watcher.");
    }

    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        keyPathMap.put(key, dir);
    }

    private void watchLoop() {
        while (running && watchService != null) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }

            Path dir = keyPathMap.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();
                Path child = dir.resolve(filename);

                // Nếu tạo thư mục mới, đăng ký luôn vào watcher
                if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                    try {
                        registerAll(child);
                    } catch (IOException ignored) {}
                }

                // Nếu là file .java thay đổi
                if (filename.toString().endsWith(".java")) {
                    handleFileChange(child);
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                keyPathMap.remove(key);
            }
        }
    }

    private void handleFileChange(Path file) {
        Path projectsPath = plugin.getPluginManager().getProjectsFolder().toPath();
        Path scriptsPath = plugin.getScriptManager().getScriptsFolder().toPath();

        if (file.startsWith(projectsPath)) {
            // Xác định project name
            Path relative = projectsPath.relativize(file);
            if (relative.getNameCount() >= 1) {
                String projectName = relative.getName(0).toString();
                triggerDebouncedReload(projectName, false);
            }
        } else if (file.startsWith(scriptsPath)) {
            String scriptName = file.getFileName().toString().replaceAll("\\.java$", "");
            triggerDebouncedReload(scriptName, true);
        }
    }

    @SuppressWarnings("deprecation")
    private void triggerDebouncedReload(String targetName, boolean isScript) {
        long now = System.currentTimeMillis();
        Long last = debounceMap.get(targetName);
        if (last != null && (now - last) < DEBOUNCE_MS) {
            return;
        }
        debounceMap.put(targetName, now);

        // Chạy trên Main Thread của Bukkit
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!isScript) {
                if (plugin.getPluginManager().isLoaded(targetName)) {
                    StringBuilder err = new StringBuilder();
                    boolean success = plugin.getPluginManager().reloadPlugin(targetName, err);
                    if (success) {
                        String msg = "§8[§bHot-Reload§8] §a✔ Đã tự động nạp lại Dynamic Plugin §e" + targetName + " §ado phát hiện thay đổi!";
                        plugin.getServer().broadcast(msg, "javastudio.admin");
                        plugin.getLogger().info("[Hot-Reload] Tự động nạp lại " + targetName + " thành công!");
                        plugin.getDiscordWebhook().logPluginLifecycle(targetName, "Hot-Reload", true, "Tự động phát hiện thay đổi mã nguồn qua FileWatcher");
                    } else {
                        String msg = "§8[§bHot-Reload§8] §c✖ Lỗi tự động nạp lại plugin " + targetName + ": " + err;
                        plugin.getServer().broadcast(msg, "javastudio.admin");
                    }
                }
            } else {
                plugin.getLogger().info("[Hot-Reload] Đã phát hiện thay đổi trong script: " + targetName + ".java");
            }
        }, 5L); // Trì hoãn 5 ticks (0.25s) để đảm bảo file đã được lưu hoàn tất
    }

    public boolean isRunning() {
        return running;
    }
}
