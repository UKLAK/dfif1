package com.javastudio.compiler;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;

/**
 * Đối tượng lưu trữ mã nguồn Java hoặc Bytecode trực tiếp trong bộ nhớ RAM.
 */
public class MemoryJavaFileObject extends SimpleJavaFileObject {

    private final String sourceCode;
    private ByteArrayOutputStream byteCodeStream;

    /**
     * Dùng cho mã nguồn đầu vào (Source code input)
     */
    public MemoryJavaFileObject(String className, String sourceCode) {
        super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
        this.sourceCode = sourceCode;
    }

    /**
     * Dùng cho Bytecode đầu ra (Bytecode output)
     */
    public MemoryJavaFileObject(String name, Kind kind) {
        super(URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind);
        this.sourceCode = null;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        if (sourceCode == null) {
            throw new UnsupportedOperationException("File này không phải là source file!");
        }
        return sourceCode;
    }

    @Override
    public OutputStream openOutputStream() {
        byteCodeStream = new ByteArrayOutputStream();
        return byteCodeStream;
    }

    public byte[] getByteCode() {
        return byteCodeStream != null ? byteCodeStream.toByteArray() : new byte[0];
    }
}
