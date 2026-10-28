package com.javastudio.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu một phương thức void để tự động chạy định kỳ khi plugin được nạp.
 * Giống @Scheduled trong Spring hoặc @Task trong nhiều framework hiện đại.
 *
 * <pre>
 * {@code
 * @Schedule(delay = 0, period = 20 * 60, async = false)
 * public void autoSaveData() {
 *     getLogger().info("Auto-saving player data...");
 *     getDataStore().flush();
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Schedule {
    /**
     * Thời gian chờ trước khi bắt đầu lần chạy đầu tiên (đơn vị: tick, 1 giây = 20 ticks).
     */
    long delay() default 0;

    /**
     * Chu kỳ lặp lại giữa các lần chạy (đơn vị: tick). Nếu = 0, chỉ chạy 1 lần duy nhất.
     */
    long period() default 0;

    /**
     * Nếu true, task sẽ chạy trên luồng bất đồng bộ (Async). Mặc định là false (Main Thread).
     */
    boolean async() default false;

    /**
     * Mô tả ngắn gọn về tác vụ (dùng cho debug/log).
     */
    String description() default "";
}
