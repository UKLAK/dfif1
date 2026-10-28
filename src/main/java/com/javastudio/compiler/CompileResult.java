package com.javastudio.compiler;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Kết quả sau khi thực hiện biên dịch mã nguồn Java.
 */
public class CompileResult {

    private final boolean success;
    private final Map<String, byte[]> byteCodes;
    private final List<Diagnostic<? extends JavaFileObject>> diagnostics;
    private final DynamicClassLoader classLoader;
    private final String customErrorMessage;

    public CompileResult(boolean success, Map<String, byte[]> byteCodes, List<Diagnostic<? extends JavaFileObject>> diagnostics, DynamicClassLoader classLoader) {
        this(success, byteCodes, diagnostics, classLoader, null);
    }

    public CompileResult(boolean success, Map<String, byte[]> byteCodes, List<Diagnostic<? extends JavaFileObject>> diagnostics, DynamicClassLoader classLoader, String customErrorMessage) {
        this.success = success;
        this.byteCodes = byteCodes != null ? byteCodes : Collections.emptyMap();
        this.diagnostics = diagnostics != null ? diagnostics : Collections.emptyList();
        this.classLoader = classLoader;
        this.customErrorMessage = customErrorMessage;
    }

    public static CompileResult securityFailure(String message) {
        return new CompileResult(false, Collections.emptyMap(), Collections.emptyList(), null, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public Map<String, byte[]> getByteCodes() {
        return byteCodes;
    }

    public List<Diagnostic<? extends JavaFileObject>> getDiagnostics() {
        return diagnostics;
    }

    public DynamicClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Nạp class chính từ classloader kết quả
     */
    public Class<?> loadClass(String fullClassName) throws ClassNotFoundException {
        if (!success || classLoader == null) {
            throw new IllegalStateException("Biên dịch thất bại, không thể nạp class!");
        }
        return classLoader.loadClass(fullClassName);
    }

    /**
     * Tạo chuỗi thông báo lỗi định dạng đẹp để hiển thị trong chat Minecraft.
     */
    public String getFormattedErrors() {
        if (customErrorMessage != null && !customErrorMessage.isBlank()) {
            return "§c✖ [Bảo Mật Sandbox] " + customErrorMessage;
        }
        if (diagnostics.isEmpty()) {
            return "§cKhông có thông tin chi tiết lỗi.";
        }
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                sb.append("§c✖ Lỗi tại dòng §e").append(d.getLineNumber())
                  .append("§c, cột §e").append(d.getColumnNumber()).append("§c: §f")
                  .append(d.getMessage(null)).append("\n");
            } else if (d.getKind() == Diagnostic.Kind.WARNING) {
                sb.append("§e⚠ Cảnh báo dòng §6").append(d.getLineNumber())
                  .append("§e: §7").append(d.getMessage(null)).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
