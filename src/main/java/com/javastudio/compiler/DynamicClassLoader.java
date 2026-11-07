package com.javastudio.compiler;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DynamicClassLoader extends URLClassLoader {

    private final Map<String, byte[]> extraClassBytes = new ConcurrentHashMap<>();

    public DynamicClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    public DynamicClassLoader(URL[] urls, ClassLoader parent) {
        super(urls != null ? urls : new URL[0], parent);
    }

    public void addByteCode(String className, byte[] bytes) {
        extraClassBytes.put(className, bytes);
    }

    @Override
    public void addURL(URL url) {
        super.addURL(url);
    }

    public void addByteCodes(Map<String, byte[]> map) {
        extraClassBytes.putAll(map);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = extraClassBytes.get(name);
        if (bytes != null) {
            return defineClass(name, bytes, 0, bytes.length);
        }
        return super.findClass(name);
    }

    public java.util.Set<String> getLoadedClassNames() {
        return java.util.Collections.unmodifiableSet(extraClassBytes.keySet());
    }

    @Override
    public void close() throws IOException {
        extraClassBytes.clear();
        super.close();
    }
}
