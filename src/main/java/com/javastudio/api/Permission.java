package com.javastudio.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu phương thức yêu cầu kiểm tra quyền hạn (Permission) trước khi thực thi.
 * Kết hợp với hệ thống Dynamic Command để tự động chặn người chơi thiếu quyền.
 *
 * <pre>
 * {@code
 * @Permission(value = "myplugin.admin", message = "&cBạn cần quyền Admin để dùng lệnh này!")
 * public void onAdminCommand(Player player, String[] args) {
 *     player.sendMessage("§aChào Admin!");
 * }
 * }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Permission {
    /**
     * Node quyền hạn yêu cầu (ví dụ: "myplugin.admin", "javastudio.exec").
     */
    String value();

    /**
     * Thông báo lỗi gửi cho người chơi khi thiếu quyền.
     */
    String message() default "&cBạn không có quyền thực hiện hành động này!";

    /**
     * Nếu true, OP sẽ được tự động bỏ qua kiểm tra quyền.
     */
    boolean opBypass() default true;
}
