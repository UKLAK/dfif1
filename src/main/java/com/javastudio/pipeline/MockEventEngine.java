package com.javastudio.pipeline;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("deprecation")
public final class MockEventEngine {

    public record MockDispatchResult(boolean success, String eventName, boolean cancelled, double elapsedMs, String message) {}

    private final JavaStudioPlugin plugin;

    public MockEventEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public MockDispatchResult fireMockEvent(Player sender, String eventType, String[] args) {
        long start = System.nanoTime();
        Event eventToCall = null;
        String evName = eventType;

        switch (eventType.toLowerCase()) {
            case "join", "playerjoinevent" -> {
                if (sender == null) return new MockDispatchResult(false, "PlayerJoinEvent", false, 0, "Cần có Player để giả lập PlayerJoinEvent");
                eventToCall = new PlayerJoinEvent(sender, "§e" + sender.getName() + " joined the game (Mock)");
                evName = "PlayerJoinEvent";
            }
            case "quit", "playerquitevent" -> {
                if (sender == null) return new MockDispatchResult(false, "PlayerQuitEvent", false, 0, "Cần có Player để giả lập PlayerQuitEvent");
                eventToCall = new PlayerQuitEvent(sender, "§e" + sender.getName() + " left the game (Mock)");
                evName = "PlayerQuitEvent";
            }
            case "chat", "asyncplayerchatevent" -> {
                if (sender == null) return new MockDispatchResult(false, "AsyncPlayerChatEvent", false, 0, "Cần có Player để giả lập ChatEvent");
                String msg = (args != null && args.length > 0) ? String.join(" ", args) : "Mock chat test message";
                Set<Player> recipients = new HashSet<>(Bukkit.getOnlinePlayers());
                eventToCall = new AsyncPlayerChatEvent(false, sender, msg, recipients);
                evName = "AsyncPlayerChatEvent";
            }
            case "break", "blockbreakevent" -> {
                if (sender == null) return new MockDispatchResult(false, "BlockBreakEvent", false, 0, "Cần có Player để giả lập BlockBreakEvent");
                Block targetBlock = sender.getTargetBlockExact(5);
                if (targetBlock == null || targetBlock.getType() == Material.AIR) {
                    targetBlock = sender.getLocation().getBlock();
                }
                eventToCall = new BlockBreakEvent(targetBlock, sender);
                evName = "BlockBreakEvent";
            }
            default -> {
                try {
                    Class<?> clazz = Class.forName(eventType.contains(".") ? eventType : "org.bukkit.event." + eventType);
                    if (Event.class.isAssignableFrom(clazz)) {
                        for (var ctor : clazz.getConstructors()) {
                            if (ctor.getParameterCount() == 0) {
                                eventToCall = (Event) ctor.newInstance();
                                evName = clazz.getSimpleName();
                                break;
                            }
                        }
                    }
                } catch (Throwable t) {
                    return new MockDispatchResult(false, eventType, false, 0, "Không thể khởi tạo Event: " + t.getMessage());
                }
            }
        }

        if (eventToCall == null) {
            return new MockDispatchResult(false, eventType, false, 0, "Không nhận diện được loại Event hoặc thiếu constructor phù hợp.");
        }

        Bukkit.getPluginManager().callEvent(eventToCall);
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;

        boolean isCancelled = (eventToCall instanceof Cancellable c) && c.isCancelled();
        return new MockDispatchResult(true, evName, isCancelled, elapsedMs, "Đã bắn event thành công qua toàn bộ Event Listeners trên server!");
    }
}

