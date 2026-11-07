package com.javastudio.pipeline;

import org.bukkit.event.Event;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class EventPipeline {

    private final Logger logger;
    private final Map<String, List<PrioritizedMiddleware>> middlewareChains = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<EventContext>>> customEventHandlers = new ConcurrentHashMap<>();

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


    public void use(String eventName, Middleware handler) {
        use(eventName, 100, "unknown", handler);
    }

    public void use(String eventName, int priority, String pluginName, Middleware handler) {
        String key = eventName.toLowerCase();
        middlewareChains.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                .add(new PrioritizedMiddleware(priority, pluginName, handler));
        middlewareChains.get(key).sort(PrioritizedMiddleware::compareTo);
        logger.info("[EventPipeline] Đã đăng ký middleware cho sự kiện '" + eventName + "' từ plugin '" + pluginName + "' (priority: " + priority + ")");
    }

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


    public EventContext dispatch(String eventName, Event event) {
        return dispatch(eventName, event, null);
    }

    public EventContext dispatch(String eventName, Event event, Map<String, Object> initialData) {
        String key = eventName.toLowerCase();
        List<PrioritizedMiddleware> chain = middlewareChains.get(key);

        EventContext ctx = new EventContext(eventName, event, initialData);

        if (chain == null || chain.isEmpty()) {
            return ctx;
        }

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
            if (!ctx.isStopped()) {
                executeNext(ctx, iterator);
            }
        }
    }


    public void on(String eventName, Consumer<EventContext> handler) {
        customEventHandlers.computeIfAbsent(eventName.toLowerCase(), k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public void emit(String eventName, Map<String, Object> data) {
        String key = eventName.toLowerCase();
        EventContext ctx = new EventContext(eventName, null, data);

        List<PrioritizedMiddleware> chain = middlewareChains.get(key);
        if (chain != null && !chain.isEmpty()) {
            dispatch(eventName, null, data);
        }

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

    public void clearCustomHandlers(String eventName) {
        customEventHandlers.remove(eventName.toLowerCase());
    }


    public Map<String, Integer> getMiddlewareStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (Map.Entry<String, List<PrioritizedMiddleware>> entry : middlewareChains.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().size());
        }
        return stats;
    }

    public int getTotalMiddlewareCount() {
        return middlewareChains.values().stream().mapToInt(List::size).sum();
    }
}
