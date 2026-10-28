package com.javastudio.api;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * ServiceBroker: Cầu nối chia sẻ dịch vụ và thông điệp liên Dynamic Plugin (Cross-Plugin Service Broker).
 * Giải quyết triệt để lỗi ClassCastException khi 2 dynamic plugin nằm ở 2 ClassLoader riêng biệt
 * bằng kỹ thuật Dynamic Reflection Proxy và Safe In-Memory Event Bus.
 */
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

    /**
     * Đăng ký dịch vụ theo tên định danh.
     *
     * @param serviceName Tên dịch vụ (ví dụ: "EconomyService", "GuildManager")
     * @param service     Đối tượng cung cấp dịch vụ
     * @param ownerPlugin Tên plugin sở hữu (dùng để tự động gỡ khi unload)
     */
    public void register(String serviceName, Object service, String ownerPlugin) {
        if (serviceName == null || service == null) return;
        servicesByName.put(serviceName.toLowerCase(), new ServiceEntry(service, ownerPlugin));
        LOGGER.info(String.format("[ServiceBroker] Plugin '%s' đã đăng ký dịch vụ: %s", ownerPlugin, serviceName));
    }

    /**
     * Đăng ký dịch vụ theo Interface chuẩn.
     */
    public <T> void register(Class<T> serviceClass, T service, String ownerPlugin) {
        if (serviceClass == null || service == null) return;
        register(serviceClass.getName(), service, ownerPlugin);
    }

    /**
     * Lấy dịch vụ thô (Raw instance).
     */
    public Object get(String serviceName) {
        if (serviceName == null) return null;
        ServiceEntry entry = servicesByName.get(serviceName.toLowerCase());
        return entry != null ? entry.instance : null;
    }

    /**
     * Lấy dịch vụ theo Interface với cơ chế Safe Dynamic Proxy.
     * Nếu dịch vụ được tải bởi ClassLoader khác với ClassLoader của Caller,
     * hệ thống tự động sinh Dynamic Proxy chuyển tiếp các lời gọi hàm bằng Reflection,
     * ngăn chặn hoàn toàn lỗi ClassCastException.
     *
     * @param serviceClass Interface mong muốn
     * @return Đối tượng đã được bọc Proxy an toàn
     */
    @SuppressWarnings("unchecked")
    public <T> T getService(Class<T> serviceClass) {
        if (serviceClass == null) return null;
        ServiceEntry entry = servicesByName.get(serviceClass.getName().toLowerCase());
        if (entry == null) {
            entry = servicesByName.get(serviceClass.getSimpleName().toLowerCase());
        }
        if (entry == null) return null;

        Object instance = entry.instance;

        // Nếu cùng ClassLoader và tương thích trực tiếp
        if (serviceClass.isInstance(instance)) {
            return (T) instance;
        }

        // Khác ClassLoader: Tạo Safe Dynamic Reflection Proxy
        return createSafeProxy(serviceClass, instance);
    }

    /**
     * Lấy dịch vụ theo tên và bọc vào Interface định nghĩa bởi caller.
     */
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

            // Xử lý các hàm cơ bản của Object
            if ("equals".equals(methodName) && paramTypes.length == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(methodName) && paramTypes.length == 0) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(methodName) && paramTypes.length == 0) {
                return "[JavaStudio ServiceProxy -> " + target.getClass().getName() + "]";
            }

            // Tìm method tương ứng trên target class qua tên và số lượng đối số
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
        // Thử tìm khớp chính xác
        try {
            return targetClass.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {}

        // Thử tìm theo tên và số lượng đối số (linh hoạt giữa các classloader)
        for (Method m : targetClass.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
                return m;
            }
        }
        return null;
    }

    /**
     * Phát một thông điệp vào kênh Message Bus nội bộ.
     *
     * @param channel Tên kênh (ví dụ: "player_level_up", "guild_disband")
     * @param payload Dữ liệu truyền tải
     */
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

    /**
     * Đăng ký lắng nghe thông điệp từ kênh Message Bus.
     *
     * @param channel     Tên kênh
     * @param consumer    Bộ xử lý thông điệp
     * @param ownerPlugin Tên plugin đăng ký
     */
    public void subscribe(String channel, Consumer<Object> consumer, String ownerPlugin) {
        if (channel == null || consumer == null) return;
        messageBus.computeIfAbsent(channel.toLowerCase(), k -> new CopyOnWriteArrayList<>())
                .add(new SubscriptionEntry(consumer, ownerPlugin));
    }

    /**
     * Lưu trữ dữ liệu dùng chung (Shared Global State).
     */
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

    /**
     * Tự động dọn dẹp sạch sẽ toàn bộ dịch vụ và subscription khi một plugin bị unload hoặc reload.
     * Ngăn chặn rò rỉ ClassLoader và Metaspace.
     */
    public void unregisterAllByPlugin(String ownerPlugin) {
        if (ownerPlugin == null) return;
        String lower = ownerPlugin.toLowerCase();

        // 1. Gỡ Services
        servicesByName.entrySet().removeIf(entry -> {
            boolean match = lower.equals(entry.getValue().ownerPlugin);
            if (match) {
                LOGGER.info(String.format("[ServiceBroker] Đã gỡ bỏ dịch vụ '%s' của plugin '%s'", entry.getKey(), ownerPlugin));
            }
            return match;
        });

        // 2. Gỡ Event Bus subscriptions
        for (var entry : messageBus.entrySet()) {
            entry.getValue().removeIf(sub -> lower.equals(sub.ownerPlugin));
        }

        LOGGER.info("[ServiceBroker] Đã dọn dẹp toàn bộ tài nguyên Broker của plugin " + ownerPlugin);
    }
}
