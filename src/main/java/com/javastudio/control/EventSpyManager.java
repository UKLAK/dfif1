package com.javastudio.control;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trình Giám Sát & Bắt Gói Sự Kiện In-Game Trực Tiếp (In-Game Dynamic Event Spy).
 * Cho phép Developer/Admin theo dõi bất kỳ Bukkit Event nào trong game thời gian thực
 * mà không cần viết lại mã nguồn hay khởi động lại server.
 */
public final class EventSpyManager {

    private final JavaStudioPlugin plugin;
    private final Set<UUID> subscribedAdmins = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, DynamicEventListener> activeSpies = new ConcurrentHashMap<>();

    private volatile String playerFilter = null; // Lọc theo người chơi cụ thể
    private volatile boolean showCancelledOnly = false;

    // Rate limiter chống flood chat: max 15 event/giây
    private final AtomicInteger eventRateCounter = new AtomicInteger(0);
    private volatile long currentSecond = 0;

    private static class DynamicEventListener implements Listener {
        final Class<? extends Event> eventClass;
        DynamicEventListener(Class<? extends Event> eventClass) {
            this.eventClass = eventClass;
        }
    }

    public EventSpyManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Bắt đầu theo dõi một Event bất kỳ.
     */
    public boolean startSpy(String eventName, CommandSender notifySender) {
        Class<? extends Event> clazz = resolveEventClass(eventName);
        if (clazz == null) {
            MessageUtil.sendError(notifySender, "Không tìm thấy lớp sự kiện nào có tên: §e" + eventName);
            return false;
        }

        String simpleName = clazz.getSimpleName();
        if (activeSpies.containsKey(simpleName)) {
            MessageUtil.sendWarning(notifySender, "Sự kiện §e" + simpleName + " §yđã đang được theo dõi rồi!");
            return false;
        }

        DynamicEventListener dummyListener = new DynamicEventListener(clazz);
        EventExecutor executor = (l, event) -> {
            if (clazz.isInstance(event)) {
                handleCapturedEvent(event);
            }
        };

        try {
            plugin.getServer().getPluginManager().registerEvent(
                    clazz,
                    dummyListener,
                    EventPriority.MONITOR,
                    executor,
                    plugin,
                    false // ignoreCancelled = false: bắt cả event đã bị huỷ
            );
            activeSpies.put(simpleName, dummyListener);
            MessageUtil.sendSuccess(notifySender, "Đã kích hoạt EventSpy cho: §e§l" + simpleName);
            return true;
        } catch (Throwable t) {
            MessageUtil.sendError(notifySender, "Không thể đăng ký EventSpy: " + t.getMessage());
            return false;
        }
    }

    /**
     * Dừng theo dõi một sự kiện cụ thể.
     */
    public boolean stopSpy(String eventName, CommandSender notifySender) {
        String matchKey = null;
        for (String k : activeSpies.keySet()) {
            if (k.equalsIgnoreCase(eventName)) {
                matchKey = k;
                break;
            }
        }

        if (matchKey == null) {
            MessageUtil.sendError(notifySender, "Sự kiện '" + eventName + "' hiện không có trong danh sách theo dõi!");
            return false;
        }

        DynamicEventListener listener = activeSpies.remove(matchKey);
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            MessageUtil.sendSuccess(notifySender, "Đã dừng theo dõi sự kiện: §e" + listener.eventClass.getSimpleName());
        } else {
            MessageUtil.sendSuccess(notifySender, "Đã dừng theo dõi sự kiện: §e" + matchKey);
        }
        return true;
    }

    /**
     * Dừng toàn bộ các sự kiện đang theo dõi.
     */
    public void stopAll(CommandSender notifySender) {
        for (DynamicEventListener listener : activeSpies.values()) {
            HandlerList.unregisterAll(listener);
        }
        int count = activeSpies.size();
        activeSpies.clear();
        if (notifySender != null) {
            MessageUtil.sendSuccess(notifySender, "Đã tắt toàn bộ (" + count + ") EventSpy!");
        }
    }

    /**
     * Đăng ký nhận tin nhắn debug EventSpy.
     */
    public boolean toggleSubscription(Player player) {
        if (player == null) return false;
        UUID uuid = player.getUniqueId();
        if (subscribedAdmins.contains(uuid)) {
            subscribedAdmins.remove(uuid);
            MessageUtil.sendInfo(player, "Đã hủy đăng ký nhận thông báo EventSpy.");
            return false;
        } else {
            subscribedAdmins.add(uuid);
            MessageUtil.sendSuccess(player, "Đã đăng ký nhận thông báo EventSpy in-game! (Đang theo dõi §e" + activeSpies.size() + "§a sự kiện)");
            return true;
        }
    }

    private void handleCapturedEvent(Event event) {
        if (subscribedAdmins.isEmpty()) return;

        long nowSec = System.currentTimeMillis() / 1000L;
        if (nowSec != currentSecond) {
            currentSecond = nowSec;
            eventRateCounter.set(0);
        }
        if (eventRateCounter.incrementAndGet() > 15) {
            return; // Chống spam chat
        }

        String playerStr = extractPlayerName(event);
        if (playerFilter != null && (playerStr == null || !playerStr.equalsIgnoreCase(playerFilter))) {
            return;
        }

        boolean cancelled = (event instanceof Cancellable c) && c.isCancelled();
        if (showCancelledOnly && !cancelled) {
            return;
        }

        String details = formatDetails(event);
        String cancelTag = cancelled ? "§c[CANCELLED]" : "§a[OK]";
        String message = String.format("§8[§cEventSpy§8] §e%s %s §8| §7Player: §b%s §8| %s",
                event.getEventName(), cancelTag, (playerStr != null ? playerStr : "Server/Entity"), details);

        for (UUID uuid : subscribedAdmins) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(message);
            }
        }
    }

    private String extractPlayerName(Event event) {
        try {
            Method m = event.getClass().getMethod("getPlayer");
            Object p = m.invoke(event);
            if (p instanceof Player player) return player.getName();
        } catch (Throwable ignored) {}

        try {
            Method m = event.getClass().getMethod("getWhoClicked");
            Object p = m.invoke(event);
            if (p instanceof Player player) return player.getName();
        } catch (Throwable ignored) {}

        try {
            Method m = event.getClass().getMethod("getDamager");
            Object d = m.invoke(event);
            if (d instanceof Player player) return player.getName();
        } catch (Throwable ignored) {}

        return null;
    }

    private String formatDetails(Event event) {
        StringBuilder sb = new StringBuilder();
        // Trích xuất Block
        try {
            Method m = event.getClass().getMethod("getBlock");
            Object b = m.invoke(event);
            if (b != null) {
                sb.append("§7Block: §a").append(b.toString().replaceAll("CraftBlock\\{.*type=", "").replace("}", "")).append(" ");
            }
        } catch (Throwable ignored) {}

        // Trích xuất Item
        try {
            Method m = event.getClass().getMethod("getItem");
            Object item = m.invoke(event);
            if (item != null) {
                sb.append("§7Item: §6").append(item.toString().replaceAll("ItemStack\\{", "").replace("}", "")).append(" ");
            }
        } catch (Throwable ignored) {}

        // Trích xuất Action
        try {
            Method m = event.getClass().getMethod("getAction");
            Object act = m.invoke(event);
            if (act != null) {
                sb.append("§7Action: §e").append(act).append(" ");
            }
        } catch (Throwable ignored) {}

        return sb.length() > 0 ? sb.toString() : "§8(Không có thêm thông số)";
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Event> resolveEventClass(String name) {
        // 1. Thử tên đầy đủ
        try {
            Class<?> c = Class.forName(name);
            if (Event.class.isAssignableFrom(c)) return (Class<? extends Event>) c;
        } catch (Throwable ignored) {}

        // 2. Thử các package thông dụng của Bukkit
        String[] packages = {
                "org.bukkit.event.player.",
                "org.bukkit.event.block.",
                "org.bukkit.event.entity.",
                "org.bukkit.event.inventory.",
                "org.bukkit.event.server.",
                "org.bukkit.event.weather.",
                "org.bukkit.event.vehicle."
        };

        for (String pkg : packages) {
            try {
                Class<?> c = Class.forName(pkg + name);
                if (Event.class.isAssignableFrom(c)) return (Class<? extends Event>) c;
            } catch (Throwable ignored) {}
        }

        return null;
    }

    public Set<String> getActiveSpies() {
        return Collections.unmodifiableSet(activeSpies.keySet());
    }

    public void setPlayerFilter(String filter) {
        this.playerFilter = filter;
    }

    public String getPlayerFilter() {
        return playerFilter;
    }

    public void setShowCancelledOnly(boolean val) {
        this.showCancelledOnly = val;
    }

    public boolean isShowCancelledOnly() {
        return showCancelledOnly;
    }

    public void cleanup() {
        stopAll(null);
        subscribedAdmins.clear();
    }
}
