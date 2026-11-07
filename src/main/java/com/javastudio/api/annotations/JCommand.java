package com.javastudio.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface JCommand {
    String value() default "";

    String name() default "";

    String[] aliases() default {};

    String permission() default "";

    String permissionMessage() default "§cBạn không có quyền sử dụng lệnh này!";

    String description() default "Lệnh tạo tự động bởi JavaStudio Dynamic Plugin";

    String usage() default "";
}
