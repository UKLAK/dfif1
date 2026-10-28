package com.javastudio.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu phương thức kiểm thử đơn vị (Unit Test) trong Dynamic Plugin.
 * Có thể chạy bằng lệnh /jplugin test <name> hoặc nút Test trên Web IDE.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {
    String description() default "";
    long timeoutMs() default 2000;
}
