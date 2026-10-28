package com.javastudio.api.annotations;

import org.bukkit.event.EventPriority;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation đăng ký sự kiện (Event Listener) tự động cho Dynamic Plugin.
 * Phương thức gắn @JEvent chỉ cần nhận 1 tham số là loại Event (ví dụ: PlayerJoinEvent event).
 * Hệ thống sẽ tự động đăng ký với Bukkit và tự gỡ bỏ khi plugin unload/reload.
 *
 * <pre>
 * &#64;JEvent(priority = EventPriority.HIGH, ignoreCancelled = true)
 * public void onPlayerJoin(PlayerJoinEvent event) {
 *     event.getPlayer().sendMessage("§eChào mừng bạn đến với server!");
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JEvent {
    /**
     * Độ ưu tiên xử lý sự kiện (Mặc định: NORMAL).
     */
    EventPriority priority() default EventPriority.NORMAL;

    /**
     * Bỏ qua sự kiện nếu sự kiện đó đã bị hủy bởi plugin khác.
     */
    boolean ignoreCancelled() default false;
}
