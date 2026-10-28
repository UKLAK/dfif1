package com.javastudio.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation tự động lập lịch tác vụ gắn với vùng (Region Scheduler / Folia first).
 * Tự động chuyển tiếp an toàn sang Spigot/Paper Main Thread nếu server không phải Folia.
 *
 * <pre>
 * &#64;JRegionTask(period = 40L)
 * public void onRegionPulse() {
 *     // Tác vụ đồng bộ hóa vùng
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JRegionTask {
    /**
     * Thời gian chờ trước khi chạy lần đầu (tính bằng server ticks).
     */
    long delay() default 0L;

    /**
     * Chu kỳ lặp lại (tính bằng server ticks). Nếu <= 0, chỉ chạy 1 lần.
     */
    long period() default -1L;
}
