package com.javastudio.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu phương thức có thể được vá nóng (Hot Patch) tại runtime mà không cần reload plugin.
 * Hệ thống sẽ tự động tạo proxy delegate cho phương thức được đánh dấu,
 * cho phép thay đổi logic mà không gián đoạn trạng thái plugin.
 *
 * <pre>
 * {@code
 * @HotPatch(version = 1)
 * public double calculateDamage(Player player, double baseDamage) {
 *     return baseDamage * 1.5; // Có thể patch runtime thành baseDamage * 2.0
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HotPatch {
    /**
     * Phiên bản hiện tại của phương thức (dùng để theo dõi lịch sử patch).
     */
    int version() default 1;

    /**
     * Mô tả ngắn gọn về phiên bản patch.
     */
    String description() default "";
}
