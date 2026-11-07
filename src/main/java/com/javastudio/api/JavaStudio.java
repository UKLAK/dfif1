package com.javastudio.api;

import com.javastudio.JavaStudioPlugin;

public final class JavaStudio {

    private JavaStudio() {}

    public static ServiceBroker getServices() {
        return ServiceBroker.getInstance();
    }

    public static ServiceBroker getBroker() {
        return ServiceBroker.getInstance();
    }

    public static JavaStudioPlugin getPlugin() {
        return JavaStudioPlugin.getInstance();
    }
}
