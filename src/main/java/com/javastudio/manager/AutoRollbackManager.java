package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Quản lý Snapshot và Tự Động Khôi Phục (Crash-Safe Auto Rollback & Versioning Manager).
 * Hỗ trợ lưu trữ đa phiên bản (Multi-Snapshot History) trước mỗi lần lưu file hoặc nạp lại plugin.
 * Cho phép rollback 1-click về bất kỳ thời điểm nào trong lịch sử ngay trên Web IDE hoặc in-game.
 */
public class AutoRollbackManager {

    public record SnapshotInfo(String id, String timestamp, String reason, int filesCount) {}

    private final JavaStudioPlugin hostPlugin;
    private final File snapshotsFolder;
    private static final int MAX_SNAPSHOTS = 30;

    public AutoRollbackManager(JavaStudioPlugin hostPlugin) {
        this.hostPlugin = hostPlugin;
        this.snapshotsFolder = new File(hostPlugin.getDataFolder(), ".snapshots");
        if (!snapshotsFolder.exists()) {
            snapshotsFolder.mkdirs();
        }
    }

    public boolean createSnapshot(String projectName) {
        return createSnapshot(projectName, "Tự động sao lưu trước khi nạp lại");
    }

    /**
     * Tạo một Snapshot sao lưu mới cho dự án kèm lý do / ghi chú.
     */
    public synchronized boolean createSnapshot(String projectName, String reason) {
        File projectDir = new File(hostPlugin.getPluginManager().getProjectsFolder(), projectName);
        if (!projectDir.exists()) return false;

        File projectSnapshots = new File(snapshotsFolder, projectName.toLowerCase());
        if (!projectSnapshots.exists()) {
            projectSnapshots.mkdirs();
        }

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String snapId = "snap_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        File snapshotTarget = new File(projectSnapshots, snapId);
        File latestTarget = new File(projectSnapshots, "latest");

        try {
            snapshotTarget.mkdirs();

            // Sao chép thư mục src/
            File srcDir = new File(projectDir, "src");
            int filesCount = 0;
            if (srcDir.exists()) {
                filesCount = countFiles(srcDir);
                copyDirectory(srcDir.toPath(), new File(snapshotTarget, "src").toPath());
            }

            // Sao chép metadata.yml
            File metaFile = new File(projectDir, "metadata.yml");
            if (metaFile.exists()) {
                Files.copy(metaFile.toPath(), new File(snapshotTarget, "metadata.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
                filesCount++;
            }

            // Ghi file metadata của snapshot
            String infoContent = time + "|" + (reason != null ? reason : "Tự động") + "|" + filesCount;
            Files.writeString(new File(snapshotTarget, "info.txt").toPath(), infoContent);
            Files.writeString(new File(snapshotTarget, "timestamp.txt").toPath(), time);

            // Cập nhật thư mục 'latest'
            if (latestTarget.exists()) {
                deleteDirectory(latestTarget);
            }
            copyDirectory(snapshotTarget.toPath(), latestTarget.toPath());

            // Tự động dọn dẹp các snapshot quá cũ (giới hạn MAX_SNAPSHOTS)
            pruneSnapshots(projectSnapshots);

            return true;
        } catch (IOException e) {
            hostPlugin.getLogger().warning("[AutoRollback] Lỗi khi tạo snapshot cho " + projectName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Lấy danh sách lịch sử Snapshot của một dự án (sắp xếp từ mới nhất tới cũ nhất).
     */
    public List<SnapshotInfo> getSnapshots(String projectName) {
        File projectSnapshots = new File(snapshotsFolder, projectName.toLowerCase());
        if (!projectSnapshots.exists() || !projectSnapshots.isDirectory()) {
            return Collections.emptyList();
        }

        List<SnapshotInfo> list = new ArrayList<>();
        File[] dirs = projectSnapshots.listFiles(File::isDirectory);
        if (dirs == null) return list;

        for (File dir : dirs) {
            if (dir.getName().equalsIgnoreCase("latest")) continue;
            File infoFile = new File(dir, "info.txt");
            String time = "";
            String reason = "Snapshot tự động";
            int count = 0;

            if (infoFile.exists()) {
                try {
                    String line = Files.readString(infoFile.toPath()).trim();
                    String[] parts = line.split("\\|", 3);
                    if (parts.length >= 1) time = parts[0];
                    if (parts.length >= 2) reason = parts[1];
                    if (parts.length >= 3) count = Integer.parseInt(parts[2]);
                } catch (Exception ignored) {}
            } else {
                File tsFile = new File(dir, "timestamp.txt");
                if (tsFile.exists()) {
                    try {
                        time = Files.readString(tsFile.toPath()).trim();
                    } catch (Exception ignored) {}
                }
            }

            list.add(new SnapshotInfo(dir.getName(), time, reason, count));
        }

        list.sort((a, b) -> b.id().compareTo(a.id()));
        return list;
    }

    /**
     * Khôi phục (Rollback) mã nguồn của dự án về một Snapshot chỉ định hoặc bản ổn định gần nhất.
     */
    public synchronized boolean rollback(String projectName, String snapshotId, StringBuilder log) {
        File projectDir = new File(hostPlugin.getPluginManager().getProjectsFolder(), projectName);
        File projectSnapshots = new File(snapshotsFolder, projectName.toLowerCase());

        String targetId = (snapshotId == null || snapshotId.trim().isEmpty() || snapshotId.equalsIgnoreCase("latest"))
                ? "latest" : snapshotId.trim();

        File targetSnapshot = new File(projectSnapshots, targetId);

        if (!targetSnapshot.exists()) {
            if (log != null) log.append("Không tìm thấy bản Snapshot '").append(targetId).append("' của ").append(projectName);
            return false;
        }

        try {
            // 1. Khôi phục thư mục src/
            File snapshotSrc = new File(targetSnapshot, "src");
            File projectSrc = new File(projectDir, "src");
            if (snapshotSrc.exists()) {
                if (projectSrc.exists()) {
                    deleteDirectory(projectSrc);
                }
                copyDirectory(snapshotSrc.toPath(), projectSrc.toPath());
            }

            // 2. Khôi phục metadata.yml
            File snapshotMeta = new File(targetSnapshot, "metadata.yml");
            if (snapshotMeta.exists()) {
                Files.copy(snapshotMeta.toPath(), new File(projectDir, "metadata.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            String timestamp = "Không rõ";
            File tsFile = new File(targetSnapshot, "timestamp.txt");
            if (tsFile.exists()) {
                timestamp = Files.readString(tsFile.toPath()).trim();
            }

            if (log != null) {
                log.append("Đã hoàn tác (Rollback) thành công về bản snapshot [").append(targetId)
                        .append("] thời điểm: ").append(timestamp);
            }
            hostPlugin.getLogger().warning("[AutoRollback] Đã khôi phục (Rollback) plugin " + projectName + " về snapshot [" + targetId + "] (" + timestamp + ")");
            return true;
        } catch (IOException e) {
            if (log != null) log.append("Lỗi trong quá trình Rollback: ").append(e.getMessage());
            return false;
        }
    }

    public boolean rollback(String projectName, StringBuilder log) {
        return rollback(projectName, "latest", log);
    }

    public boolean hasSnapshot(String projectName) {
        File latest = new File(new File(snapshotsFolder, projectName.toLowerCase()), "latest");
        return latest.exists() && new File(latest, "src").exists();
    }

    private void pruneSnapshots(File projectSnapshotsDir) {
        File[] dirs = projectSnapshotsDir.listFiles(File::isDirectory);
        if (dirs == null) return;

        List<File> snapDirs = new ArrayList<>();
        for (File f : dirs) {
            if (!f.getName().equalsIgnoreCase("latest")) {
                snapDirs.add(f);
            }
        }

        if (snapDirs.size() > MAX_SNAPSHOTS) {
            snapDirs.sort(Comparator.comparing(File::getName));
            int toRemove = snapDirs.size() - MAX_SNAPSHOTS;
            for (int i = 0; i < toRemove; i++) {
                deleteDirectory(snapDirs.get(i));
            }
        }
    }

    private int countFiles(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) count += countFiles(f);
                else count++;
            }
        }
        return count;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
