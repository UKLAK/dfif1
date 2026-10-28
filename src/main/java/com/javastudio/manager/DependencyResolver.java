package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Trình tải và phân giải thư viện phụ thuộc từ Maven Central thời gian thực.
 * Cho phép Dynamic Plugin sử dụng bất kỳ thư viện Java nào từ kho lưu trữ Maven.
 */
public class DependencyResolver {

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    private final JavaStudioPlugin plugin;
    private final Logger logger;
    private final File cacheDir;

    public DependencyResolver(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.cacheDir = new File(plugin.getDataFolder(), ".cache" + File.separator + "libraries");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    /**
     * Phân giải và tải về danh sách các Maven coordinates (dạng "groupId:artifactId:version")
     *
     * @param coordinates Danh sách tọa độ Maven (ví dụ: "com.squareup.okhttp3:okhttp:4.12.0")
     * @return Danh sách các file JAR đã tải về và sẵn sàng nạp vào ClassLoader
     */
    public List<File> resolveDependencies(List<String> coordinates) {
        List<File> resolvedFiles = new ArrayList<>();
        if (coordinates == null || coordinates.isEmpty()) {
            return resolvedFiles;
        }

        for (String coord : coordinates) {
            String trimmed = coord.trim();
            if (trimmed.isEmpty()) continue;

            File jarFile = resolveSingleDependency(trimmed);
            if (jarFile != null && jarFile.exists()) {
                resolvedFiles.add(jarFile);
            }
        }

        return resolvedFiles;
    }

    /**
     * Tải về 1 dependency Maven cụ thể.
     */
    public File resolveSingleDependency(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 3) {
            logger.warning("[DependencyResolver] Tọa độ Maven không hợp lệ: " + coordinate + " (Định dạng chuẩn: groupId:artifactId:version)");
            return null;
        }

        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts[2];
        String jarName = artifactId + "-" + version + ".jar";

        File targetFile = new File(cacheDir, jarName);
        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile;
        }

        String groupPath = groupId.replace('.', '/');
        String downloadUrl = MAVEN_CENTRAL + groupPath + "/" + artifactId + "/" + version + "/" + jarName;

        logger.info("[DependencyResolver] Đang tải thư viện: " + coordinate + " từ Maven Central...");

        try {
            URL url = URI.create(downloadUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "JavaStudio-Minecraft-Plugin");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(targetFile)) {
                    in.transferTo(out);
                }
                logger.info("[DependencyResolver] Tải thành công " + jarName + " (" + (targetFile.length() / 1024) + " KB)!");
                return targetFile;
            } else {
                logger.severe("[DependencyResolver] Không thể tải " + coordinate + " (HTTP " + conn.getResponseCode() + ")");
                if (targetFile.exists()) {
                    targetFile.delete();
                }
                return null;
            }
        } catch (Exception e) {
            logger.severe("[DependencyResolver] Lỗi khi kết nối tới Maven: " + e.getMessage());
            if (targetFile.exists()) {
                targetFile.delete();
            }
            return null;
        }
    }

    public JavaStudioPlugin getPlugin() {
        return plugin;
    }

    public File getCacheDir() {
        return cacheDir;
    }
}
