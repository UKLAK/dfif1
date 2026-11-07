package com.javastudio.pipeline;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryJavaCompiler {

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    private static final Map<String, CachedClass> INCREMENTAL_CACHE = new ConcurrentHashMap<>();

    public record CachedClass(String className, String sha256, byte[] bytecode, long timestamp) {}

    public InMemoryJavaCompiler() {
    }

    public Map<String, byte[]> compile(String className, String sourceCode, String classpath) {
        Map<String, String> sources = new HashMap<>();
        sources.put(className, sourceCode);
        return compileSources(sources, classpath);
    }

    public Map<String, byte[]> compileSources(Map<String, String> sources, String classpath) {
        if (compiler == null) {
            throw new IllegalStateException("Yêu cầu chạy máy chủ trên JDK 21+ (không phải JRE) để sử dụng JavaCompiler.");
        }

        Map<String, byte[]> resultBytecode = new HashMap<>();
        Map<String, String> sourcesToCompile = new HashMap<>();

        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String className = entry.getKey();
            String code = entry.getValue();
            String hash = computeSha256(code);

            CachedClass cached = INCREMENTAL_CACHE.get(className);
            if (cached != null && cached.sha256().equals(hash)) {
                resultBytecode.put(className, cached.bytecode());
            } else {
                String guardedCode = LoopGuardTransformer.transform(code);
                sourcesToCompile.put(className, guardedCode);
            }
        }

        if (sourcesToCompile.isEmpty()) {
            return resultBytecode;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager stdFileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        MemoryFileManager fileManager = new MemoryFileManager(stdFileManager);

        List<JavaFileObject> compilationUnits = new ArrayList<>();
        for (Map.Entry<String, String> entry : sourcesToCompile.entrySet()) {
            compilationUnits.add(new MemorySourceJavaFileObject(entry.getKey(), entry.getValue()));
        }

        List<String> options = new ArrayList<>();
        options.add("-proc:none");
        options.add("-parameters");
        options.add("-Xlint:none");
        if (classpath != null && !classpath.isBlank()) {
            options.add("-classpath");
            options.add(classpath);
        }
        options.add("--release");
        options.add("21");

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null, compilationUnits
        );

        boolean success = Boolean.TRUE.equals(task.call());
        if (!success) {
            StringBuilder errorLog = new StringBuilder("Lỗi biên dịch trong RAM:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                errorLog.append("Dòng ").append(d.getLineNumber())
                        .append(" (Cột ").append(d.getColumnNumber()).append("): ")
                        .append(d.getMessage(Locale.ROOT)).append("\n");
            }
            throw new RuntimeException(errorLog.toString());
        }

        Map<String, byte[]> newlyCompiled = fileManager.getCompiledBytes();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, byte[]> compiled : newlyCompiled.entrySet()) {
            String name = compiled.getKey();
            byte[] bytes = compiled.getValue();
            resultBytecode.put(name, bytes);

            String origCode = sources.get(name);
            if (origCode != null) {
                String hash = computeSha256(origCode);
                INCREMENTAL_CACHE.put(name, new CachedClass(name, hash, bytes, now));
            }
        }

        return resultBytecode;
    }

    public static String computeSha256(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }

    public static void clearCache() {
        INCREMENTAL_CACHE.clear();
    }

    public static void invalidateClass(String className) {
        INCREMENTAL_CACHE.remove(className);
    }

    public static int getCacheSize() {
        return INCREMENTAL_CACHE.size();
    }


    public static final class MemorySourceJavaFileObject extends SimpleJavaFileObject {
        private final String code;

        public MemorySourceJavaFileObject(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    public static final class MemoryBytecodeJavaFileObject extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        public MemoryBytecodeJavaFileObject(String name, Kind kind) {
            super(URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            return bos;
        }

        public byte[] getBytes() {
            return bos.toByteArray();
        }
    }

    public static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, MemoryBytecodeJavaFileObject> classBytes = new ConcurrentHashMap<>();

        public MemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            MemoryBytecodeJavaFileObject fileObject = new MemoryBytecodeJavaFileObject(className, kind);
            classBytes.put(className, fileObject);
            return fileObject;
        }

        public Map<String, byte[]> getCompiledBytes() {
            Map<String, byte[]> result = new HashMap<>();
            classBytes.forEach((name, obj) -> result.put(name, obj.getBytes()));
            return result;
        }
    }
}
