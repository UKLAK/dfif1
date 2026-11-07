package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class SnapshotEngine {

    public record SnapshotInfo(String id, long timestamp, String label, int fileCount, long totalBytes) {}

    private final JavaStudioPlugin plugin;
    private final File snapshotsDir;

    public SnapshotEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.snapshotsDir = new File(plugin.getDataFolder(), ".snapshots");
        if (!snapshotsDir.exists()) {
            snapshotsDir.mkdirs();
        }
    }

    public synchronized SnapshotInfo createSnapshot(String label) {
        String id = "SNAP-" + System.currentTimeMillis();
        File targetDir = new File(snapshotsDir, id);
        targetDir.mkdirs();

        String tag = (label != null && !label.isBlank()) ? label : "Manual-Snapshot";
        int[] fileCount = new int[]{0};
        long[] totalBytes = new long[]{0};

        File projectsDir = new File(plugin.getDataFolder(), "projects");
        File scriptsDir = new File(plugin.getDataFolder(), "scripts");

        if (projectsDir.exists()) {
            copyDirectory(projectsDir.toPath(), new File(targetDir, "projects").toPath(), fileCount, totalBytes);
        }
        if (scriptsDir.exists()) {
            copyDirectory(scriptsDir.toPath(), new File(targetDir, "scripts").toPath(), fileCount, totalBytes);
        }

        File metaFile = new File(targetDir, "meta.txt");
        try {
            String meta = "id=" + id + "\ntimestamp=" + System.currentTimeMillis() + "\nlabel=" + tag + "\nfileCount=" + fileCount[0] + "\ntotalBytes=" + totalBytes[0];
            Files.writeString(metaFile.toPath(), meta);
        } catch (IOException ignored) {}

        return new SnapshotInfo(id, System.currentTimeMillis(), tag, fileCount[0], totalBytes[0]);
    }

    public synchronized boolean rollback(String snapshotId) {
        File targetDir = new File(snapshotsDir, snapshotId);
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            return false;
        }

        createSnapshot("auto-backup-before-rollback-" + snapshotId);

        File projectsTarget = new File(targetDir, "projects");
        File scriptsTarget = new File(targetDir, "scripts");

        File liveProjects = new File(plugin.getDataFolder(), "projects");
        File liveScripts = new File(plugin.getDataFolder(), "scripts");

        try {
            if (projectsTarget.exists()) {
                deleteDirectory(liveProjects.toPath());
                copyDirectory(projectsTarget.toPath(), liveProjects.toPath(), new int[]{0}, new long[]{0});
            }
            if (scriptsTarget.exists()) {
                deleteDirectory(liveScripts.toPath());
                copyDirectory(scriptsTarget.toPath(), liveScripts.toPath(), new int[]{0}, new long[]{0});
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().severe("[SnapshotEngine] Thất bại khi rollback snapshot " + snapshotId + ": " + t.getMessage());
            return false;
        }
    }

    public List<SnapshotInfo> listSnapshots() {
        List<SnapshotInfo> list = new ArrayList<>();
        File[] dirs = snapshotsDir.listFiles(File::isDirectory);
        if (dirs == null) return list;

        for (File dir : dirs) {
            File metaFile = new File(dir, "meta.txt");
            if (metaFile.exists()) {
                try {
                    List<String> lines = Files.readAllLines(metaFile.toPath());
                    String id = dir.getName();
                    long timestamp = 0L;
                    String label = "Snapshot";
                    int count = 0;
                    long bytes = 0L;

                    for (String l : lines) {
                        if (l.startsWith("id=")) id = l.substring(3);
                        else if (l.startsWith("timestamp=")) timestamp = Long.parseLong(l.substring(10));
                        else if (l.startsWith("label=")) label = l.substring(6);
                        else if (l.startsWith("fileCount=")) count = Integer.parseInt(l.substring(10));
                        else if (l.startsWith("totalBytes=")) bytes = Long.parseLong(l.substring(11));
                    }
                    list.add(new SnapshotInfo(id, timestamp, label, count, bytes));
                } catch (Throwable ignored) {}
            }
        }
        list.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
        return list;
    }

    public List<String> diff(String snapshotId) {
        File snapDir = new File(snapshotsDir, snapshotId);
        if (!snapDir.exists()) return List.of("Không tìm thấy snapshot: " + snapshotId);

        List<String> differences = new ArrayList<>();
        compareDirs(new File(snapDir, "projects"), new File(plugin.getDataFolder(), "projects"), "projects", differences);
        compareDirs(new File(snapDir, "scripts"), new File(plugin.getDataFolder(), "scripts"), "scripts", differences);

        return differences.isEmpty() ? List.of("Không có thay đổi so với snapshot.") : differences;
    }

    private void compareDirs(File snapBase, File liveBase, String prefix, List<String> diffs) {
        if (!snapBase.exists() && !liveBase.exists()) return;

        Set<String> snapFiles = getRelativeFilePaths(snapBase);
        Set<String> liveFiles = getRelativeFilePaths(liveBase);

        for (String sf : snapFiles) {
            if (!liveFiles.contains(sf)) {
                diffs.add("[-] ĐÃ XÓA: " + prefix + "/" + sf);
            } else {
                File sfObj = new File(snapBase, sf);
                File lfObj = new File(liveBase, sf);
                if (sfObj.length() != lfObj.length() || Math.abs(sfObj.lastModified() - lfObj.lastModified()) > 2000L) {
                    diffs.add("[~] THAY ĐỔI: " + prefix + "/" + sf);
                }
            }
        }
        for (String lf : liveFiles) {
            if (!snapFiles.contains(lf)) {
                diffs.add("[+] MỚI: " + prefix + "/" + lf);
            }
        }
    }

    private Set<String> getRelativeFilePaths(File base) {
        Set<String> res = new HashSet<>();
        if (!base.exists() || !base.isDirectory()) return res;

        Path basePath = base.toPath();
        try {
            Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    res.add(basePath.relativize(file).toString().replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
        return res;
    }

    private void copyDirectory(Path source, Path target, int[] count, long[] bytes) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path targetDir = target.resolve(source.relativize(dir));
                    Files.createDirectories(targetDir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path targetFile = target.resolve(source.relativize(file));
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    count[0]++;
                    bytes[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }
}

