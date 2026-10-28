package com.javastudio.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation tự động đăng ký biến PlaceholderAPI (%javastudio_<name>%).
 * Phương thức có thể nhận tham số (Player player) hoặc không nhận tham số và trả về String / Object.
 *
 * <pre>
 * &#64;JPlaceholder("rank")
 * public String getPlayerRank(Player player) {
 *     return player.isOp() ? "§cADMIN" : "§7MEMBER";
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JPlaceholder {
    /**
     * Tên biến placeholder (Ví dụ: "rank" -> %javastudio_rank% hoặc %<plugin>_rank%).
     */
    String value() default "";

    /**
     * Tên biến placeholder (bí danh cho value).
     */
    String name() default "";

    /**
     * Mô tả biến placeholder.
     */
    String description() default "";
}
