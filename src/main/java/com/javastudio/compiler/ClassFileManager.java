package com.javastudio.compiler;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * File Manager định tuyến bytecode được sinh ra bởi JavaCompiler lưu thẳng vào Map bộ nhớ.
 */
public class ClassFileManager extends ForwardingJavaFileManager<JavaFileManager> {

    private final Map<String, MemoryJavaFileObject> outputFiles = new HashMap<>();

    public ClassFileManager(JavaFileManager fileManager) {
        super(fileManager);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
        MemoryJavaFileObject fileObject = new MemoryJavaFileObject(className, kind);
        outputFiles.put(className, fileObject);
        return fileObject;
    }

    /**
     * Lấy toàn bộ bytecode của các class đã được biên dịch (Class Name -> Byte array)
     */
    public Map<String, byte[]> getAllByteCodes() {
        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<String, MemoryJavaFileObject> entry : outputFiles.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getByteCode());
        }
        return Collections.unmodifiableMap(result);
    }
}
