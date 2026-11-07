package com.javastudio.agent;

import java.lang.instrument.Instrumentation;

public final class JavaStudioAgent {

    public static volatile Instrumentation instrumentation;

    private JavaStudioAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}

