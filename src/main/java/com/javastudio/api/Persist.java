package com.javastudio.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu trường dữ liệu (Field) cần bảo toàn trạng thái khi Hot-Reload.
 * Khi lưu file (Ctrl+S) hoặc gõ /jplugin reload <name>, giá trị của các biến có gắn @Persist
 * sẽ được tự động lưu vào bộ nhớ tạm và khôi phục vào instance mới của plugin
 * mà không làm mất dữ liệu người chơi, bộ đếm hay trạng thái minigame đang chạy!
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Persist {
}
