package com.javastudio.manager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClassLoaderLeakDetector: Hệ thống phát hiện rò rỉ bộ nhớ ClassLoader & Metaspace.
 * Theo dõi bằng WeakReference sau khi dynamic plugin bị unload hoặc reload.
 * Tự động quét các biến static còn giữ tham chiếu và các Thread ngầm chưa đóng.
 */
public final class ClassLoaderLeakDetector {

    public record TrackedLoader(
            String pluginName,
            WeakReference<ClassLoader> weakRef,
            long unloadTimestamp,
            List<String> knownClassNames
    ) {}

    public record LeakReport(
            String pluginName,
            boolean reclaimed,
            long unloadAgeSeconds,
            List<String> retainedStaticFields,
            List<String> lingeringThreads
    ) {}

    private static final Map<String, TrackedLoader> trackedLoaders = new ConcurrentHashMap<>();

    private ClassLoaderLeakDetector() {}

    /**
     * Ghi nhận một ClassLoader vừa bị unload/reload
     */
    public static void recordUnload(String pluginName, ClassLoader loader, Collection<String> classNames) {
        if (pluginName == null || loader == null) return;
        List<String> names = classNames != null ? new ArrayList<>(classNames) : Collections.emptyList();
        trackedLoaders.put(pluginName.toLowerCase() + "_" + System.currentTimeMillis(),
                new TrackedLoader(pluginName, new WeakReference<>(loader), System.currentTimeMillis(), names));
    }

    /**
     * Kích hoạt dọn rác và quét toàn diện các ClassLoader bị rò rỉ
     */
    public static List<LeakReport> scanLeaks() {
        // Gợi ý JVM chạy Garbage Collection để thu hồi các loader không còn tham chiếu
        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}

        List<LeakReport> reports = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (TrackedLoader tracked : trackedLoaders.values()) {
            ClassLoader loader = tracked.weakRef().get();
            long ageSec = (now - tracked.unloadTimestamp()) / 1000L;

            if (loader == null) {
                // Đã được GC dọn dẹp sạch sẽ
                reports.add(new LeakReport(tracked.pluginName(), true, ageSec, Collections.emptyList(), Collections.emptyList()));
                continue;
            }

            // Loader vẫn còn nằm trong bộ nhớ Heap -> Bị rò rỉ tham chiếu!
            List<String> retainedFields = new ArrayList<>();
            List<String> lingeringThreads = new ArrayList<>();

            // 1. Quét các static field còn giữ tham chiếu
            for (String className : tracked.knownClassNames()) {
                try {
                    Class<?> clazz = loader.loadClass(className);
                    for (Field f : clazz.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()) {
                            f.setAccessible(true);
                            Object val = f.get(null);
                            if (val != null) {
                                String valStr = val.getClass().getSimpleName();
                                retainedFields.add(clazz.getSimpleName() + "." + f.getName() + " -> " + valStr);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 2. Quét các Thread ngầm chưa kết thúc
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.isAlive()) {
                    if (t.getContextClassLoader() == loader) {
                        lingeringThreads.add("Thread: " + t.getName() + " (ContextClassLoader)");
                    } else if (t.getClass().getClassLoader() == loader) {
                        lingeringThreads.add("Thread: " + t.getName() + " (" + t.getClass().getName() + ")");
                    }
                }
            }

            reports.add(new LeakReport(tracked.pluginName(), false, ageSec, retainedFields, lingeringThreads));
        }

        return reports;
    }

    public static int getTrackedCount() {
        return trackedLoaders.size();
    }

    public static void clearHistory() {
        trackedLoaders.clear();
    }
}
