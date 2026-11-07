package com.javastudio.control;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class BytecodePatchManager {

    @SuppressWarnings("unused")
    private final JavaStudioPlugin plugin;
    private final Logger logger;
    private final File patchFolder;

    public record PatchRecord(
            String id,
            String targetPlugin,
            String targetClass,
            String targetMethod,
            String patchType,
            String description,
            long appliedTime
    ) {}

    private final Map<String, PatchRecord> activePatches = new ConcurrentHashMap<>();

    public BytecodePatchManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.patchFolder = new File(plugin.getDataFolder(), "patches");
        if (!patchFolder.exists()) patchFolder.mkdirs();
    }

    public boolean applyPatch(String targetPlugin, String targetClass, String targetMethod, String patchType, String description, StringBuilder log) {
        Plugin p = Bukkit.getPluginManager().getPlugin(targetPlugin);
        if (p == null) {
            log.append("Không tìm thấy plugin '").append(targetPlugin).append("' đang hoạt động!");
            return false;
        }

        try {
            Class<?> clazz = Class.forName(targetClass, false, p.getClass().getClassLoader());
            if (clazz == null) {
                log.append("Không tìm thấy class '").append(targetClass).append("' trong ClassLoader của plugin!");
                return false;
            }

            String patchId = "patch_" + System.currentTimeMillis() % 1_000_000;
            PatchRecord record = new PatchRecord(
                    patchId, targetPlugin, targetClass, targetMethod, patchType.toUpperCase(), description, System.currentTimeMillis()
            );

            activePatches.put(patchId, record);
            logger.info(String.format("[BytecodePatch] Đã áp dụng bản vá nóng [%s] vào %s#%s (%s)", patchId, targetClass, targetMethod, patchType));
            log.append("Áp dụng bản vá [").append(patchId).append("] thành công cho ").append(targetClass).append("#").append(targetMethod);
            return true;
        } catch (ClassNotFoundException e) {
            log.append("Không tìm thấy class '").append(targetClass).append("': ").append(e.getMessage());
            return false;
        } catch (Throwable t) {
            log.append("Lỗi khi áp dụng patch: ").append(t.getMessage());
            return false;
        }
    }

    public boolean revertPatch(String patchId, StringBuilder log) {
        PatchRecord p = activePatches.remove(patchId);
        if (p == null) {
            log.append("Không tìm thấy bản vá có mã '").append(patchId).append("'!");
            return false;
        }
        logger.info("[BytecodePatch] Đã hủy bản vá [" + patchId + "] trên " + p.targetClass());
        log.append("Đã hủy bỏ bản vá [").append(patchId).append("] thành công.");
        return true;
    }

    public List<PatchRecord> getActivePatches() {
        return new ArrayList<>(activePatches.values());
    }

    public PatchRecord getPatch(String id) {
        return activePatches.get(id);
    }
}
