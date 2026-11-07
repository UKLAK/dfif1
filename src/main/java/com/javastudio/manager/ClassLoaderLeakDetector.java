package com.javastudio.manager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    public static void recordUnload(String pluginName, ClassLoader loader, Collection<String> classNames) {
        if (pluginName == null || loader == null) return;
        List<String> names = classNames != null ? new ArrayList<>(classNames) : Collections.emptyList();
        trackedLoaders.put(pluginName.toLowerCase() + "_" + System.currentTimeMillis(),
                new TrackedLoader(pluginName, new WeakReference<>(loader), System.currentTimeMillis(), names));
    }

    public static List<LeakReport> scanLeaks() {
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
                reports.add(new LeakReport(tracked.pluginName(), true, ageSec, Collections.emptyList(), Collections.emptyList()));
                continue;
            }

            List<String> retainedFields = new ArrayList<>();
            List<String> lingeringThreads = new ArrayList<>();

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
