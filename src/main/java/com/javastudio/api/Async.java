package com.javastudio.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu một phương thức sẽ được thực thi trên luồng bất đồng bộ (Async Thread Pool).
 * Sử dụng khi cần thực hiện tác vụ nặng (gọi API, đọc/ghi file lớn, tính toán phức tạp)
 * mà không muốn gây lag main thread server.
 *
 * <pre>
 * {@code
 * @Async
 * public void fetchPlayerStats(Player player) {
 *     // Tác vụ I/O nặng chạy trên luồng riêng
 *     String data = httpGet("https://api.example.com/stats/" + player.getUniqueId());
 *     runTask(() -> player.sendMessage("Stats: " + data)); // Trả về main thread để gửi tin nhắn
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Async {
    /**
     * Mô tả ngắn gọn về tác vụ bất đồng bộ.
     */
    String value() default "";
}
