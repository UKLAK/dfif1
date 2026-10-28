package com.javastudio.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation tự động lập lịch tác vụ (Scheduler Task) cho Dynamic Plugin.
 * Tự động nhận diện server đang chạy Spigot, Paper hay Folia để điều phối luồng an toàn.
 * Tác vụ sẽ tự động được hủy bỏ khi plugin reload hoặc unload.
 *
 * <pre>
 * &#64;JTask(period = 20L, async = true)
 * public void runAutoSave() {
 *     // Lưu dữ liệu mỗi 20 ticks (1 giây) trên Async thread
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JTask {
    /**
     * Thời gian chờ trước khi chạy lần đầu (tính bằng server ticks, 20 ticks = 1s).
     */
    long delay() default 0L;

    /**
     * Chu kỳ lặp lại (tính bằng server ticks). Nếu <= 0, tác vụ chỉ chạy 1 lần duy nhất (one-shot).
     */
    long period() default -1L;

    /**
     * Chạy bất đồng bộ ngoài Main Thread (Async) hay đồng bộ (Sync).
     */
    boolean async() default false;
}
