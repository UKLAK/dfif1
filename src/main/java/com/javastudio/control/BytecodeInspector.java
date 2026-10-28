package com.javastudio.control;

import com.javastudio.JavaStudioPlugin;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Trình dịch ngược và mổ xẻ bytecode (Bytecode Inspector & Disassembler).
 * Cho phép xem danh sách class, phương thức, trường dữ liệu và cấu trúc mã nguồn
 * của bất kỳ plugin nào trên server ngay trong game hoặc trên Web IDE.
 */
public class BytecodeInspector {

    private final JavaStudioPlugin plugin;

    public BytecodeInspector(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public JavaStudioPlugin getPlugin() {
        return plugin;
    }

    /**
     * Lấy danh sách toàn bộ các class (.class) bên trong một file plugin JAR.
     */
    public List<String> listClassesInJar(File jarFile) {
        if (jarFile == null || !jarFile.exists()) return Collections.emptyList();
        List<String> classes = new ArrayList<>();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.contains("$")) {
                    classes.add(name.replace('/', '.').replace(".class", ""));
                }
            }
        } catch (Throwable ignored) {}

        Collections.sort(classes);
        return classes;
    }

    /**
     * Dịch ngược cấu trúc một class trong file JAR thành mã nguồn Java giả lập đẹp mắt.
     */
    public String decompileClass(File jarFile, String className) {
        if (jarFile == null || !jarFile.exists()) return "// Không tìm thấy file JAR!";
        String entryPath = className.replace('.', '/') + ".class";

        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(entryPath);
            if (entry == null) {
                // Thử tìm case-insensitive
                Enumeration<JarEntry> en = jar.entries();
                while (en.hasMoreElements()) {
                    JarEntry e = en.nextElement();
                    if (e.getName().equalsIgnoreCase(entryPath)) {
                        entry = e;
                        break;
                    }
                }
            }
            if (entry == null) return "// Không tìm thấy class " + className + " trong file JAR!";

            try (InputStream is = jar.getInputStream(entry)) {
                byte[] bytes = is.readAllBytes();
                return disassembleClassBytes(className, bytes);
            }
        } catch (Throwable e) {
            return "// Lỗi khi dịch ngược class " + className + ": " + e.getMessage();
        }
    }

    /**
     * Phân tích byte header, ConstantPool và cấu trúc ClassFile thuần túy.
     */
    private String disassembleClassBytes(String fullClassName, byte[] bytes) {
        if (bytes.length < 8) return "// File bytecode không hợp lệ (dung lượng quá nhỏ)";

        StringBuilder sb = new StringBuilder();
        int pkgIdx = fullClassName.lastIndexOf('.');
        String pkg = pkgIdx > 0 ? fullClassName.substring(0, pkgIdx) : "";
        String simpleName = pkgIdx > 0 ? fullClassName.substring(pkgIdx + 1) : fullClassName;

        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }

        sb.append("// ==========================================================\n");
        sb.append("// Dịch ngược cấu trúc Bytecode bởi JavaStudio Inspector\n");
        sb.append("// ==========================================================\n\n");

        // Trích xuất các chuỗi từ Constant Pool
        List<String> strings = extractUtf8Constants(bytes);
        Set<String> imports = new TreeSet<>();

        for (String s : strings) {
            if (s.startsWith("L") && s.endsWith(";") && s.contains("/")) {
                String type = s.substring(1, s.length() - 1).replace('/', '.');
                if (!type.startsWith("java.lang.") && !type.equals(fullClassName)) {
                    imports.add(type);
                }
            } else if (s.startsWith("(") && s.contains(")")) {
                // Method descriptor
            }
        }

        for (String imp : imports) {
            if (imp.contains(".") && !imp.contains("$")) {
                sb.append("import ").append(imp).append(";\n");
            }
        }
        if (!imports.isEmpty()) sb.append("\n");

        sb.append("public class ").append(simpleName).append(" {\n\n");

        // Liệt kê các chuỗi hằng số tìm thấy
        sb.append("    // --- Các hằng số & chuỗi văn bản nhận diện được ---\n");
        int count = 0;
        for (String s : strings) {
            if (s.length() >= 4 && !s.contains("/") && !s.startsWith("(") && count < 25) {
                sb.append("    // Constant: \"").append(s.replace("\"", "\\\"")).append("\"\n");
                count++;
            }
        }

        sb.append("\n    // --- Các phương thức (Methods) phát hiện trong Bytecode ---\n");
        for (int i = 0; i < strings.size(); i++) {
            String s = strings.get(i);
            if (s.startsWith("(") && s.contains(")")) {
                String methodName = (i > 0) ? strings.get(i - 1) : "method";
                if (!methodName.contains("/") && !methodName.contains("(") && !methodName.contains("<clinit>")) {
                    String cleanDesc = formatDescriptor(s);
                    sb.append("    public ").append(cleanDesc).append(" ").append(methodName).append("() {\n");
                    sb.append("        // Bytecode compiled method\n");
                    sb.append("    }\n\n");
                }
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    private List<String> extractUtf8Constants(byte[] bytes) {
        List<String> list = new ArrayList<>();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            int magic = dis.readInt();
            if (magic != 0xCAFEBABE) return list;
            dis.readUnsignedShort(); // minor
            dis.readUnsignedShort(); // major
            int cpCount = dis.readUnsignedShort();

            for (int i = 1; i < cpCount; i++) {
                int tag = dis.readUnsignedByte();
                switch (tag) {
                    case 1 -> { // CONSTANT_Utf8
                        String s = dis.readUTF();
                        list.add(s);
                    }
                    case 3, 4 -> dis.readInt(); // Integer, Float
                    case 5, 6 -> { // Long, Double (takes 2 entries)
                        dis.readLong();
                        i++;
                    }
                    case 7 -> dis.readUnsignedShort(); // Class
                    case 8 -> dis.readUnsignedShort(); // String
                    case 9, 10, 11 -> { // Fieldref, Methodref, InterfaceMethodref
                        dis.readUnsignedShort();
                        dis.readUnsignedShort();
                    }
                    case 12 -> { // NameAndType
                        dis.readUnsignedShort();
                        dis.readUnsignedShort();
                    }
                    case 15 -> { // MethodHandle
                        dis.readByte();
                        dis.readUnsignedShort();
                    }
                    case 16 -> dis.readUnsignedShort(); // MethodType
                    case 17, 18 -> { // Dynamic, InvokeDynamic
                        dis.readUnsignedShort();
                        dis.readUnsignedShort();
                    }
                    case 19, 20 -> dis.readUnsignedShort(); // Module, Package
                    default -> {}
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private String formatDescriptor(String desc) {
        int closeParen = desc.indexOf(')');
        if (closeParen < 0) return "void";
        String returnType = desc.substring(closeParen + 1);
        return switch (returnType) {
            case "V" -> "void";
            case "I" -> "int";
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            default -> {
                if (returnType.startsWith("L") && returnType.endsWith(";")) {
                    String simple = returnType.substring(1, returnType.length() - 1);
                    int slash = simple.lastIndexOf('/');
                    yield slash >= 0 ? simple.substring(slash + 1) : simple;
                }
                yield "Object";
            }
        };
    }
}
