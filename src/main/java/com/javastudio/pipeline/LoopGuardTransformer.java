package com.javastudio.pipeline;

import com.javastudio.security.LoopWatchdog;

/**
 * LoopGuardTransformer: Bước tiền xử lý/hậu xử lý mã nguồn và bytecode trong pipeline.
 * Tự động chèn cơ chế kiểm tra Instruction Quota và thời gian thực thi vào mọi vòng lặp
 * (for, while, do-while), đảm bảo không một đoạn script nào chạy trên Main Thread quá 200ms
 * có thể làm đóng băng hoặc crash server.
 */
public final class LoopGuardTransformer {

    private static volatile boolean enabled = true;

    private LoopGuardTransformer() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean val) {
        enabled = val;
    }

    /**
     * Biến đổi mã nguồn Java trước khi compile để chèn bộ bảo vệ vòng lặp.
     *
     * @param sourceCode Mã nguồn thô
     * @return Mã nguồn an toàn đã được chèn Loop Guard
     */
    public static String transform(String sourceCode) {
        if (!enabled || sourceCode == null || sourceCode.isBlank()) {
            return sourceCode;
        }

        // Tận dụng công cụ LoopWatchdog chuyên dụng
        return LoopWatchdog.instrument(sourceCode);
    }
}
