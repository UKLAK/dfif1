package com.javastudio.pipeline;

import org.bukkit.event.Event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public String getEventName() {
        return eventName;
    }

    public Event getBukkitEvent() {
        return bukkitEvent;
    }

    @SuppressWarnings("unchecked")
    public <T extends Event> T getBukkitEvent(Class<T> type) {
        if (bukkitEvent != null && type.isInstance(bukkitEvent)) {
            return (T) bukkitEvent;
        }
        return null;
    }

    public void set(String key, Object value) {
        data.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object val = data.get(key);
        return val != null ? (T) val : defaultValue;
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    public void next() {
        if (nextCallback != null && !stopped) {
            nextCallback.run();
        }
    }

    public void stop() {
        this.stopped = true;
    }

    public boolean isStopped() {
        return stopped;
    }

    void setNextCallback(Runnable callback) {
        this.nextCallback = callback;
    }

    public Map<String, Object> getAllData() {
        return Map.copyOf(data);
    }
}
