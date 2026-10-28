package com.javastudio.pipeline;

import org.bukkit.event.Event;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Hệ thống Event Pipeline & Middleware tiên tiến cho JavaStudio Dynamic Plugins.
 *
 * Cho phép đăng ký chuỗi middleware xử lý trước/sau sự kiện Bukkit hoặc Custom Events nội bộ,
 * tương tự Express.js middleware chain hoặc Spring Interceptor.
 *
 * <p>Tính năng chính:</p>
 * <ul>
 *     <li><strong>Middleware Chain</strong>: Đăng ký nhiều handler theo thứ tự ưu tiên</li>
 *     <li><strong>Pre/Post Processing</strong>: Xử lý trước và sau sự kiện chính</li>
 *     <li><strong>Custom Event Bus</strong>: Hệ thống sự kiện riêng cho dynamic plugins giao tiếp liên plugin</li>
 *     <li><strong>Error Isolation</strong>: Lỗi trong 1 middleware không ảnh hưởng pipeline còn lại</li>
 *     <li><strong>Conditional Execution</strong>: Middleware có thể hủy bỏ sự kiện hoặc dừng chuỗi</li>
 * </ul>
 *
 * <pre>{@code
 * EventPipeline pipeline = plugin.getEventPipeline();
 * pipeline.use("player-join", ctx -> {
 *     ctx.set("joinTime", System.currentTimeMillis());
 *     ctx.next(); // Chuyển tiếp đến middleware tiếp theo
 * });
 * pipeline.use("player-join", ctx -> {
 *     long joinTime = ctx.get("joinTime");
 *     player.sendMessage("Joined at: " + joinTime);
 * });
 * }</pre>
 */
public class EventPipeline {

    private final Logger logger;
    private final Map<String, List<PrioritizedMiddleware>> middlewareChains = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<EventContext>>> customEventHandlers = new ConcurrentHashMap<>();

    /**
     * Middleware entry with priority ordering.
     */
    private record PrioritizedMiddleware(int priority, String pluginName, Middleware handler)
            implements Comparable<PrioritizedMiddleware> {
        @Override
        public int compareTo(PrioritizedMiddleware other) {
            return Integer.compare(this.priority, other.priority);
        }
    }

    public EventPipeline(Logger logger) {
        this.logger = logger;
    }

    // ========================================
    // MIDDLEWARE REGISTRATION
    // ========================================

    /**
     * Đăng ký middleware vào pipeline sự kiện với ưu tiên mặc định (100).
     *
     * @param eventName Tên sự kiện (ví dụ: "player-join", "block-break")
     * @param handler   Hàm xử lý middleware
     */
    public void use(String eventName, Middleware handler) {
        use(eventName, 100, "unknown", handler);
    }

    /**
     * Đăng ký middleware vào pipeline sự kiện với ưu tiên và tên plugin.
     *
     * @param eventName  Tên sự kiện
     * @param priority   Ưu tiên (số nhỏ chạy trước)
     * @param pluginName Tên dynamic plugin sở hữu middleware
     * @param handler    Hàm xử lý middleware
     */
    public void use(String eventName, int priority, String pluginName, Middleware handler) {
        String key = eventName.toLowerCase();
        middlewareChains.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                .add(new PrioritizedMiddleware(priority, pluginName, handler));
        // Sắp xếp lại theo priority
        middlewareChains.get(key).sort(PrioritizedMiddleware::compareTo);
        logger.info("[EventPipeline] Đã đăng ký middleware cho sự kiện '" + eventName + "' từ plugin '" + pluginName + "' (priority: " + priority + ")");
    }

    /**
     * Gỡ bỏ toàn bộ middleware của một plugin khỏi pipeline.
     *
     * @param pluginName Tên dynamic plugin cần gỡ
     */
    public void removeAll(String pluginName) {
        int removed = 0;
        for (Map.Entry<String, List<PrioritizedMiddleware>> entry : middlewareChains.entrySet()) {
            int before = entry.getValue().size();
            entry.getValue().removeIf(m -> m.pluginName().equalsIgnoreCase(pluginName));
            removed += (before - entry.getValue().size());
        }
        if (removed > 0) {
            logger.info("[EventPipeline] Đã gỡ " + removed + " middleware của plugin '" + pluginName + "'");
        }
    }

    // ========================================
    // EVENT DISPATCH
    // ========================================

    /**
     * Kích hoạt pipeline middleware cho một sự kiện.
     *
     * @param eventName Tên sự kiện
     * @param event     Đối tượng Bukkit Event (hoặc null nếu là custom event)
     * @return EventContext chứa kết quả xử lý
     */
    public EventContext dispatch(String eventName, Event event) {
        return dispatch(eventName, event, null);
    }

    /**
     * Kích hoạt pipeline middleware cho một sự kiện với dữ liệu tùy chỉnh.
     *
     * @param eventName  Tên sự kiện
     * @param event      Đối tượng Bukkit Event (có thể null)
     * @param initialData Dữ liệu khởi tạo cho EventContext
     * @return EventContext chứa kết quả xử lý
     */
    public EventContext dispatch(String eventName, Event event, Map<String, Object> initialData) {
        String key = eventName.toLowerCase();
        List<PrioritizedMiddleware> chain = middlewareChains.get(key);

        EventContext ctx = new EventContext(eventName, event, initialData);

        if (chain == null || chain.isEmpty()) {
            return ctx;
        }

        // Thực thi chuỗi middleware theo thứ tự priority
        Iterator<PrioritizedMiddleware> iterator = chain.iterator();
        executeNext(ctx, iterator);

        return ctx;
    }

    private void executeNext(EventContext ctx, Iterator<PrioritizedMiddleware> iterator) {
        if (ctx.isStopped() || !iterator.hasNext()) {
            return;
        }

        PrioritizedMiddleware current = iterator.next();
        ctx.setNextCallback(() -> executeNext(ctx, iterator));

        try {
            current.handler().handle(ctx);
        } catch (Throwable t) {
            logger.warning("[EventPipeline] Lỗi trong middleware của plugin '" + current.pluginName() +
                    "' cho sự kiện '" + ctx.getEventName() + "': " + t.getMessage());
            // Tiếp tục thực thi middleware tiếp theo dù có lỗi (Error Isolation)
            if (!ctx.isStopped()) {
                executeNext(ctx, iterator);
            }
        }
    }

    // ========================================
    // CUSTOM EVENT BUS (Giao tiếp liên plugin)
    // ========================================

    /**
     * Đăng ký lắng nghe một Custom Event nội bộ (không phải Bukkit Event).
     *
     * @param eventName Tên sự kiện tùy chỉnh
     * @param handler   Consumer xử lý khi sự kiện được phát
     */
    public void on(String eventName, Consumer<EventContext> handler) {
        customEventHandlers.computeIfAbsent(eventName.toLowerCase(), k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Phát (emit) một Custom Event nội bộ đến tất cả các listener.
     *
     * @param eventName Tên sự kiện tùy chỉnh
     * @param data      Dữ liệu đính kèm sự kiện
     */
    public void emit(String eventName, Map<String, Object> data) {
        String key = eventName.toLowerCase();
        EventContext ctx = new EventContext(eventName, null, data);

        // Dispatch qua middleware chain nếu có
        List<PrioritizedMiddleware> chain = middlewareChains.get(key);
        if (chain != null && !chain.isEmpty()) {
            dispatch(eventName, null, data);
        }

        // Notify custom handlers
        List<Consumer<EventContext>> handlers = customEventHandlers.get(key);
        if (handlers != null) {
            for (Consumer<EventContext> handler : handlers) {
                try {
                    handler.accept(ctx);
                } catch (Throwable t) {
                    logger.warning("[EventBus] Lỗi khi xử lý custom event '" + eventName + "': " + t.getMessage());
                }
            }
        }
    }

    /**
     * Gỡ bỏ toàn bộ custom event handlers.
     */
    public void clearCustomHandlers(String eventName) {
        customEventHandlers.remove(eventName.toLowerCase());
    }

    // ========================================
    // STATS & INFO
    // ========================================

    /**
     * Lấy số lượng middleware đã đăng ký cho mỗi sự kiện.
     */
    public Map<String, Integer> getMiddlewareStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (Map.Entry<String, List<PrioritizedMiddleware>> entry : middlewareChains.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().size());
        }
        return stats;
    }

    /**
     * Lấy tổng số middleware đã đăng ký.
     */
    public int getTotalMiddlewareCount() {
        return middlewareChains.values().stream().mapToInt(List::size).sum();
    }
}
