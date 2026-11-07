package com.javastudio.pipeline;

import com.javastudio.security.LoopWatchdog;

public final class LoopGuardTransformer {

    private static volatile boolean enabled = true;

    private LoopGuardTransformer() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean val) {
        enabled = val;
    }

    public static String transform(String sourceCode) {
        if (!enabled || sourceCode == null || sourceCode.isBlank()) {
            return sourceCode;
        }

        return LoopWatchdog.instrument(sourceCode);
    }
}
