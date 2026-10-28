package com.javastudio.api;

import com.javastudio.JavaStudioPlugin;

/**
 * Lớp API toàn cục JavaStudio - Cung cấp điểm truy cập nhanh cho tất cả dynamic plugins và scripts.
 */
public final class JavaStudio {

    private JavaStudio() {}

    /**
     * Lấy ServiceBroker quản lý giao tiếp liên plugin và Dynamic Proxy.
     */
    public static ServiceBroker getServices() {
        return ServiceBroker.getInstance();
    }

    /**
     * Lấy ServiceBroker (alias).
     */
    public static ServiceBroker getBroker() {
        return ServiceBroker.getInstance();
    }

    /**
     * Lấy instance chính của JavaStudioPlugin trên server.
     */
    public static JavaStudioPlugin getPlugin() {
        return JavaStudioPlugin.getInstance();
    }
}
