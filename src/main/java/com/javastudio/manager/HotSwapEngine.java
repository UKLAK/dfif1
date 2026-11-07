package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.agent.JavaStudioAgent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class HotSwapEngine {

    public record HotSwapResult(
            boolean success,
            boolean usedInstrumentation,
            String className,
            long elapsedNanos,
            String message
    ) {}

    private final JavaStudioPlugin plugin;
    private volatile Instrumentation instrumentation;
    private final AtomicLong hotSwapCounter = new AtomicLong(0);

    public HotSwapEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        attemptAcquireInstrumentation();
    }

    private void attemptAcquireInstrumentation() {
        if (JavaStudioAgent.getInstrumentation() != null) {
            this.instrumentation = JavaStudioAgent.getInstrumentation();
            return;
        }

        if (!plugin.getConfig().getBoolean("compiler.self-attach-hotswap", true)) {
            return;
        }

        try {
            File agentJar = buildSelfAgentJar();
            if (agentJar == null || !agentJar.exists()) {
                return;
            }
            String pid = String.valueOf(ProcessHandle.current().pid());
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            Method attachMethod = vmClass.getMethod("attach", String.class);
            Method loadAgentMethod = vmClass.getMethod("loadAgent", String.class);
            Method detachMethod = vmClass.getMethod("detach");

            Object vm = attachMethod.invoke(null, pid);
            try {
                loadAgentMethod.invoke(vm, agentJar.getAbsolutePath());
            } finally {
                detachMethod.invoke(vm);
            }

            this.instrumentation = JavaStudioAgent.getInstrumentation();
            if (isInstrumentationAvailable()) {
                plugin.getLogger().info("[HotSwap] Đã kích hoạt JVM Self-Attach Instrumentation (PID " + pid + ") - Hỗ trợ Hot-Swap Bytecode không mất biến RAM!");
            }
        } catch (Throwable ignored) {
            try {
                Class<?> byteBuddyAgent = Class.forName("net.bytebuddy.agent.ByteBuddyAgent");
                Method install = byteBuddyAgent.getMethod("install");
                this.instrumentation = (Instrumentation) install.invoke(null);
            } catch (Throwable ignored2) {}
        }
    }

    private File buildSelfAgentJar() {
        try {
            File cacheDir = new File(plugin.getDataFolder(), ".cache");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File jarFile = new File(cacheDir, "javastudio-agent.jar");

            String resourcePath = "com/javastudio/agent/JavaStudioAgent.class";
            byte[] agentBytes;
            try (InputStream is = JavaStudioAgent.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) return null;
                agentBytes = is.readAllBytes();
            }

            Manifest manifest = new Manifest();
            Attributes attrs = manifest.getMainAttributes();
            attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attrs.put(new Attributes.Name("Premain-Class"), JavaStudioAgent.class.getName());
            attrs.put(new Attributes.Name("Agent-Class"), JavaStudioAgent.class.getName());
            attrs.put(new Attributes.Name("Can-Redefine-Classes"), "true");
            attrs.put(new Attributes.Name("Can-Retransform-Classes"), "true");

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile), manifest)) {
                JarEntry entry = new JarEntry(resourcePath);
                jos.putNextEntry(entry);
                jos.write(agentBytes);
                jos.closeEntry();
            }
            return jarFile;
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean isInstrumentationAvailable() {
        Instrumentation inst = this.instrumentation != null ? this.instrumentation : JavaStudioAgent.getInstrumentation();
        if (inst != null) {
            this.instrumentation = inst;
            return inst.isRedefineClassesSupported();
        }
        return false;
    }

    public HotSwapResult hotSwapClass(Class<?> targetClass, byte[] newBytecode) {
        long t0 = System.nanoTime();
        if (targetClass == null || newBytecode == null || newBytecode.length == 0) {
            return new HotSwapResult(false, false, "unknown", 0, "Dữ liệu lớp hoặc bytecode không hợp lệ!");
        }

        String className = targetClass.getName();

        if (isInstrumentationAvailable()) {
            try {
                ClassDefinition def = new ClassDefinition(targetClass, newBytecode);
                instrumentation.redefineClasses(def);
                hotSwapCounter.incrementAndGet();
                long elapsed = System.nanoTime() - t0;
                return new HotSwapResult(true, true, className, elapsed, "Hot-Swap thành công qua JVM Instrumentation (" + String.format("%.2f", elapsed / 1_000_000.0) + "ms)");
            } catch (Throwable t) {
                plugin.getLogger().warning("[HotSwap] JVM Instrumentation không thể đổi cấu trúc lớp (" + t.getMessage() + "), chuyển sang Dynamic Reload...");
            }
        }

        long elapsed = System.nanoTime() - t0;
        return new HotSwapResult(false, false, className, elapsed, "Không khả dụng JVM Agent hoặc đổi schema lớp, chuyển sang fallback Dynamic Reload.");
    }

    public long getHotSwapCount() {
        return hotSwapCounter.get();
    }
}
