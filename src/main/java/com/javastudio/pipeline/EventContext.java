package com.javastudio.pipeline;

import org.bukkit.event.Event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ngữ cảnh chia sẻ giữa các middleware trong Event Pipeline.
 * Chứa thông tin sự kiện, dữ liệu tùy chỉnh và điều khiển luồng pipeline.
 */
public class EventContext {

    private final String eventName;
    private final Event bukkitEvent;
    private final Map<String, Object> data;
    private volatile boolean stopped = false;
    private Runnable nextCallback;

    public EventContext(String eventName, Event bukkitEvent, Map<String, Object> initialData) {
        this.eventName = eventName;
        this.bukkitEvent = bukkitEvent;
        this.data = new ConcurrentHashMap<>();
        if (initialData != null) {
            this.data.putAll(initialData);
        }
    }

    /**
     * Lấy tên sự kiện đang được xử lý.
     */
    public String getEventName() {
        return eventName;
    }

    /**
     * Lấy Bukkit Event gốc (có thể null nếu là Custom Event nội bộ).
     */
    public Event getBukkitEvent() {
        return bukkitEvent;
    }

    /**
     * Lấy Bukkit Event gốc với kiểu cụ thể.
     */
    @SuppressWarnings("unchecked")
    public <T extends Event> T getBukkitEvent(Class<T> type) {
        if (bukkitEvent != null && type.isInstance(bukkitEvent)) {
            return (T) bukkitEvent;
        }
        return null;
    }

    /**
     * Lưu giá trị vào context để chia sẻ giữa các middleware.
     */
    public void set(String key, Object value) {
        data.put(key, value);
    }

    /**
     * Lấy giá trị từ context.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    /**
     * Lấy giá trị từ context với giá trị mặc định.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object val = data.get(key);
        return val != null ? (T) val : defaultValue;
    }

    /**
     * Kiểm tra xem context có chứa key không.
     */
    public boolean has(String key) {
        return data.containsKey(key);
    }

    /**
     * Chuyển tiếp đến middleware tiếp theo trong chuỗi.
     * Nếu không gọi next(), chuỗi sẽ dừng sau middleware hiện tại.
     */
    public void next() {
        if (nextCallback != null && !stopped) {
            nextCallback.run();
        }
    }

    /**
     * Dừng hoàn toàn chuỗi middleware (không chạy bất kỳ middleware nào phía sau).
     */
    public void stop() {
        this.stopped = true;
    }

    /**
     * Kiểm tra xem pipeline đã bị dừng chưa.
     */
    public boolean isStopped() {
        return stopped;
    }

    /**
     * Thiết lập callback cho next() (được gọi nội bộ bởi EventPipeline).
     */
    void setNextCallback(Runnable callback) {
        this.nextCallback = callback;
    }

    /**
     * Lấy toàn bộ dữ liệu trong context dưới dạng Map.
     */
    public Map<String, Object> getAllData() {
        return Map.copyOf(data);
    }
}
