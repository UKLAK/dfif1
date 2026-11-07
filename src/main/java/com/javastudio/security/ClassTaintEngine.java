package com.javastudio.security;

import com.javastudio.JavaStudioPlugin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ClassTaintEngine {

    private final JavaStudioPlugin plugin;

    public record TaintResult(boolean safe, List<String> violations) {}

    private record MemberRef(int classIndex, int nameAndTypeIndex) {}
    private record NameAndType(int nameIndex, int descriptorIndex) {}

    private static final Set<String> FORBIDDEN_CLASSES = Set.of(
            "sun/misc/Unsafe",
            "jdk/internal/misc/Unsafe",
            "java/lang/ProcessBuilder",
            "java/lang/Process",
            "java/net/URLClassLoader",
            "javax/script/ScriptEngineManager",
            "com/sun/tools/attach/VirtualMachine"
    );

    private static final Set<String> FORBIDDEN_METHODS = Set.of(
            "java/lang/System.exit",
            "java/lang/System.load",
            "java/lang/System.loadLibrary",
            "java/lang/System.setSecurityManager",
            "java/lang/Runtime.exec",
            "java/lang/Runtime.halt",
            "java/lang/Runtime.load",
            "java/lang/Runtime.loadLibrary",
            "java/lang/Runtime.addShutdownHook",
            "java/lang/Thread.stop",
            "java/lang/ClassLoader.defineClass",
            "java/lang/reflect/AccessibleObject.setAccessible",
            "java/lang/reflect/Field.setAccessible",
            "java/lang/reflect/Method.setAccessible",
            "java/lang/reflect/Constructor.setAccessible",
            "java/lang/invoke/MethodHandles.privateLookupIn",
            "java/lang/invoke/MethodHandles$Lookup.privateLookupIn",
            "java/lang/invoke/MethodHandles$Lookup.defineClass",
            "java/lang/invoke/MethodHandles$Lookup.defineHiddenClass",
            "java/nio/file/Files.createSymbolicLink",
            "java/nio/file/Files.createLink"
    );

    public ClassTaintEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public TaintResult analyzeBytecodeMap(Map<String, byte[]> bytecodes) {
        if (bytecodes == null || bytecodes.isEmpty()) {
            return new TaintResult(true, List.of());
        }
        List<String> allViolations = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
            TaintResult res = analyzeClass(entry.getValue());
            if (!res.safe()) {
                for (String v : res.violations()) {
                    allViolations.add("[" + entry.getKey() + "] " + v);
                }
            }
        }
        return new TaintResult(allViolations.isEmpty(), allViolations);
    }

    public TaintResult analyzeClass(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 8) {
            return new TaintResult(true, List.of());
        }

        List<String> violations = new ArrayList<>();

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                return new TaintResult(false, List.of("Tập tin bytecode không hợp lệ (Sai magic 0xCAFEBABE)"));
            }

            in.readUnsignedShort();
            in.readUnsignedShort();
            int constantPoolCount = in.readUnsignedShort();

            Map<Integer, String> utf8Table = new HashMap<>();
            Map<Integer, Integer> classTable = new HashMap<>();
            Map<Integer, NameAndType> nameAndTypeTable = new HashMap<>();
            Map<Integer, MemberRef> methodRefTable = new HashMap<>();
            List<Integer> methodHandleTargets = new ArrayList<>();

            int i = 1;
            while (i < constantPoolCount) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> {
                        int length = in.readUnsignedShort();
                        byte[] bytes = new byte[length];
                        in.readFully(bytes);
                        utf8Table.put(i, new String(bytes, StandardCharsets.UTF_8));
                        i++;
                    }
                    case 7 -> {
                        int nameIndex = in.readUnsignedShort();
                        classTable.put(i, nameIndex);
                        i++;
                    }
                    case 8, 16, 19, 20 -> {
                        in.skipBytes(2);
                        i++;
                    }
                    case 3, 4 -> {
                        in.skipBytes(4);
                        i++;
                    }
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i += 2;
                    }
                    case 9 -> {
                        int classIdx = in.readUnsignedShort();
                        int natIdx = in.readUnsignedShort();
                        methodRefTable.put(i, new MemberRef(classIdx, natIdx));
                        i++;
                    }
                    case 10, 11 -> {
                        int classIdx = in.readUnsignedShort();
                        int natIdx = in.readUnsignedShort();
                        methodRefTable.put(i, new MemberRef(classIdx, natIdx));
                        i++;
                    }
                    case 12 -> {
                        int nameIdx = in.readUnsignedShort();
                        int descIdx = in.readUnsignedShort();
                        nameAndTypeTable.put(i, new NameAndType(nameIdx, descIdx));
                        i++;
                    }
                    case 15 -> {
                        in.readUnsignedByte();
                        int refIndex = in.readUnsignedShort();
                        methodHandleTargets.add(refIndex);
                        i++;
                    }
                    case 17, 18 -> {
                        in.skipBytes(4);
                        i++;
                    }
                    default -> i++;
                }
            }

            for (Map.Entry<Integer, Integer> entry : classTable.entrySet()) {
                String className = utf8Table.get(entry.getValue());
                if (className != null && FORBIDDEN_CLASSES.contains(className)) {
                    violations.add("Tham chiếu trực tiếp lớp cấm trong Bytecode: " + className.replace('/', '.'));
                }
            }

            for (Map.Entry<Integer, MemberRef> entry : methodRefTable.entrySet()) {
                checkMemberRef(entry.getValue(), classTable, nameAndTypeTable, utf8Table, violations);
            }

            for (Integer targetIdx : methodHandleTargets) {
                MemberRef mref = methodRefTable.get(targetIdx);
                if (mref != null) {
                    checkMemberRef(mref, classTable, nameAndTypeTable, utf8Table, violations);
                }
            }

        } catch (Throwable t) {
            violations.add("Lỗi phân tích cú pháp bytecode: " + t.getMessage());
        }

        return new TaintResult(violations.isEmpty(), violations);
    }

    private void checkMemberRef(MemberRef mref, Map<Integer, Integer> classTable,
                                Map<Integer, NameAndType> nameAndTypeTable,
                                Map<Integer, String> utf8Table, List<String> violations) {
        Integer classNameIndex = classTable.get(mref.classIndex());
        String ownerClass = classNameIndex != null ? utf8Table.get(classNameIndex) : null;
        NameAndType nat = nameAndTypeTable.get(mref.nameAndTypeIndex());
        String methodName = (nat != null) ? utf8Table.get(nat.nameIndex()) : null;

        if (ownerClass != null && methodName != null) {
            String fullSig = ownerClass + "." + methodName;
            if (FORBIDDEN_METHODS.contains(fullSig)) {
                String msg = "Gọi phương thức cấm trong Bytecode: " + fullSig.replace('/', '.');
                if (!violations.contains(msg)) {
                    violations.add(msg);
                }
            }
        }
    }

    public TaintResult analyzeJar(File jarFile) {
        if (jarFile == null || !jarFile.exists() || !jarFile.getName().endsWith(".jar")) {
            return new TaintResult(true, List.of());
        }

        List<String> violations = new ArrayList<>();
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        byte[] bytes = is.readAllBytes();
                        TaintResult res = analyzeClass(bytes);
                        if (!res.safe()) {
                            for (String v : res.violations()) {
                                violations.add("[" + entry.getName() + "] " + v);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            violations.add("Lỗi đọc tập tin JAR: " + t.getMessage());
        }

        return new TaintResult(violations.isEmpty(), violations);
    }
}
