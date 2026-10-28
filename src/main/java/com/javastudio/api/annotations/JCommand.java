package com.javastudio.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation khai báo câu lệnh nhanh (Zero-Boilerplate) cho Dynamic Plugin.
 * Tự động đăng ký vào CommandMap và tự động gỡ sạch khi plugin reload/unload.
 *
 * <pre>
 * &#64;JCommand(name = "heal", permission = "myplugin.heal", description = "Hồi đầy máu")
 * public void onHeal(Player player, String[] args) {
 *     player.setHealth(player.getMaxHealth());
 *     player.sendMessage("§aBạn đã được hồi máu!");
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface JCommand {
    /**
     * Tên lệnh chính (Ví dụ: "heal" -> /heal). Nếu để trống, sẽ lấy tên của method.
     */
    String value() default "";

    /**
     * Tên lệnh chính (bí danh cho value).
     */
    String name() default "";

    /**
     * Danh sách các bí danh (aliases) của lệnh.
     */
    String[] aliases() default {};

    /**
     * Quyền hạn yêu cầu (permission node).
     */
    String permission() default "";

    /**
     * Tin nhắn hiển thị khi không có quyền.
     */
    String permissionMessage() default "§cBạn không có quyền sử dụng lệnh này!";

    /**
     * Mô tả câu lệnh.
     */
    String description() default "Lệnh tạo tự động bởi JavaStudio Dynamic Plugin";

    /**
     * Hướng dẫn cú pháp câu lệnh (usage message).
     */
    String usage() default "";
}
