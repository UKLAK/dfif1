package com.javastudio.api;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class ServiceBroker {

    private static final ServiceBroker INSTANCE = new ServiceBroker();
    private static final Logger LOGGER = Logger.getLogger("JavaStudio[ServiceBroker]");

    public static ServiceBroker getInstance() {
        return INSTANCE;
    }

    private static class ServiceEntry {
        final Object instance;
        final String ownerPlugin;

        ServiceEntry(Object instance, String ownerPlugin) {
            this.instance = instance;
            this.ownerPlugin = ownerPlugin != null ? ownerPlugin.toLowerCase() : "system";
        }
    }

    private static class SubscriptionEntry {
        final Consumer<Object> consumer;
        final String ownerPlugin;

        SubscriptionEntry(Consumer<Object> consumer, String ownerPlugin) {
            this.consumer = consumer;
            this.ownerPlugin = ownerPlugin != null ? ownerPlugin.toLowerCase() : "system";
        }
    }

    private final Map<String, ServiceEntry> servicesByName = new ConcurrentHashMap<>();
    private final Map<String, List<SubscriptionEntry>> messageBus = new ConcurrentHashMap<>();
    private final Map<String, Object> sharedStore = new ConcurrentHashMap<>();

    private ServiceBroker() {}

    public void register(String serviceName, Object service, String ownerPlugin) {
        if (serviceName == null || service == null) return;
        servicesByName.put(serviceName.toLowerCase(), new ServiceEntry(service, ownerPlugin));
        LOGGER.info(String.format("[ServiceBroker] Plugin '%s' đã đăng ký dịch vụ: %s", ownerPlugin, serviceName));
    }

    public <T> void register(Class<T> serviceClass, T service, String ownerPlugin) {
        if (serviceClass == null || service == null) return;
        register(serviceClass.getName(), service, ownerPlugin);
    }

    public Object get(String serviceName) {
        if (serviceName == null) return null;
        ServiceEntry entry = servicesByName.get(serviceName.toLowerCase());
        return entry != null ? entry.instance : null;
    }

    @SuppressWarnings("unchecked")
    public <T> T getService(Class<T> serviceClass) {
        if (serviceClass == null) return null;
        ServiceEntry entry = servicesByName.get(serviceClass.getName().toLowerCase());
        if (entry == null) {
            entry = servicesByName.get(serviceClass.getSimpleName().toLowerCase());
        }
        if (entry == null) return null;

        Object instance = entry.instance;

        if (serviceClass.isInstance(instance)) {
            return (T) instance;
        }

        return createSafeProxy(serviceClass, instance);
    }

    public <T> T getService(String serviceName, Class<T> serviceInterface) {
        if (serviceName == null || serviceInterface == null) return null;
        ServiceEntry entry = servicesByName.get(serviceName.toLowerCase());
        if (entry == null) return null;

        Object instance = entry.instance;
        if (serviceInterface.isInstance(instance)) {
            return serviceInterface.cast(instance);
        }
        return createSafeProxy(serviceInterface, instance);
    }

    @SuppressWarnings("unchecked")
    private <T> T createSafeProxy(Class<T> serviceClass, Object target) {
        ClassLoader loader = serviceClass.getClassLoader();
        if (loader == null) loader = ClassLoader.getSystemClassLoader();

        InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            Class<?>[] paramTypes = method.getParameterTypes();

            if ("equals".equals(methodName) && paramTypes.length == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(methodName) && paramTypes.length == 0) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(methodName) && paramTypes.length == 0) {
                return "[JavaStudio ServiceProxy -> " + target.getClass().getName() + "]";
            }

            Method targetMethod = findMatchingMethod(target.getClass(), methodName, paramTypes);
            if (targetMethod == null) {
                throw new NoSuchMethodException("Không tìm thấy method " + methodName + " trên target " + target.getClass().getName());
            }

            targetMethod.setAccessible(true);
            return targetMethod.invoke(target, args);
        };

        return (T) Proxy.newProxyInstance(loader, new Class<?>[]{serviceClass}, handler);
    }

    private Method findMatchingMethod(Class<?> targetClass, String name, Class<?>[] paramTypes) {
        try {
            return targetClass.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {}

        for (Method m : targetClass.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
                return m;
            }
        }
        return null;
    }

    public void publish(String channel, Object payload) {
        if (channel == null) return;
        List<SubscriptionEntry> subs = messageBus.get(channel.toLowerCase());
        if (subs == null || subs.isEmpty()) return;

        for (SubscriptionEntry sub : subs) {
            try {
                sub.consumer.accept(payload);
            } catch (Throwable t) {
                LOGGER.warning(String.format("[ServiceBroker] Lỗi khi chuyển thông điệp kênh '%s' đến plugin '%s': %s",
                        channel, sub.ownerPlugin, t.getMessage()));
            }
        }
    }

    public void subscribe(String channel, Consumer<Object> consumer, String ownerPlugin) {
        if (channel == null || consumer == null) return;
        messageBus.computeIfAbsent(channel.toLowerCase(), k -> new CopyOnWriteArrayList<>())
                .add(new SubscriptionEntry(consumer, ownerPlugin));
    }

    public void setSharedData(String key, Object value) {
        if (key == null) return;
        if (value == null) {
            sharedStore.remove(key.toLowerCase());
        } else {
            sharedStore.put(key.toLowerCase(), value);
        }
    }

    public Object getSharedData(String key) {
        return key != null ? sharedStore.get(key.toLowerCase()) : null;
    }

    @SuppressWarnings("unchecked")
    public <T> T getSharedData(String key, Class<T> type) {
        Object val = getSharedData(key);
        if (val != null && type.isInstance(val)) {
            return (T) val;
        }
        return null;
    }

    public void unregisterAllByPlugin(String ownerPlugin) {
        if (ownerPlugin == null) return;
        String lower = ownerPlugin.toLowerCase();

        servicesByName.entrySet().removeIf(entry -> {
            boolean match = lower.equals(entry.getValue().ownerPlugin);
            if (match) {
                LOGGER.info(String.format("[ServiceBroker] Đã gỡ bỏ dịch vụ '%s' của plugin '%s'", entry.getKey(), ownerPlugin));
            }
            return match;
        });

        for (var entry : messageBus.entrySet()) {
            entry.getValue().removeIf(sub -> lower.equals(sub.ownerPlugin));
        }

        LOGGER.info("[ServiceBroker] Đã dọn dẹp toàn bộ tài nguyên Broker của plugin " + ownerPlugin);
    }
}
