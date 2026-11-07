package com.javastudio.bridge;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.control.ServerController;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class PlaceholderApiBridge {

    private final JavaStudioPlugin plugin;
    private static boolean papiAvailable = false;
    private static JavaStudioExpansion expansionInstance = null;
    private static final Map<String, Function<Player, String>> customPlaceholders = new ConcurrentHashMap<>();

    public PlaceholderApiBridge(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            papiAvailable = false;
            plugin.getLogger().info("[PAPI Bridge] Không tìm thấy PlaceholderAPI trên server. Chế độ thay thế độc lập kích hoạt.");
            return;
        }

        try {
            expansionInstance = new JavaStudioExpansion(plugin);
            if (expansionInstance.register()) {
                papiAvailable = true;
                plugin.getLogger().info("[PAPI Bridge] " + "\u0110\u00e3 \u0111\u0103ng k\u00fd th\u00e0nh c\u00f4ng PlaceholderExpansion %javastudio_...% v\u1edbi PlaceholderAPI!");
            } else {
                plugin.getLogger().warning("[PAPI Bridge] " + "Kh\u00f4ng th\u1ec3 \u0111\u0103ng k\u00fd PlaceholderExpansion v\u1edbi PlaceholderAPI.");
            }
        } catch (Throwable e) {
            papiAvailable = false;
            plugin.getLogger().warning("[PAPI Bridge] " + "L\u1ed7i khi kh\u1edfi t\u1ea1o PlaceholderAPI Expansion: " + e.getMessage());
        }
    }

    public void unregister() {
        if (expansionInstance != null) {
            try {
                expansionInstance.unregister();
            } catch (Throwable ignored) {}
            expansionInstance = null;
        }
        customPlaceholders.clear();
        papiAvailable = false;
    }

    public static boolean isAvailable() {
        return papiAvailable;
    }

    public static String setPlaceholders(@Nullable Player player, @NotNull String text) {
        if (papiAvailable && player != null) {
            try {
                return PlaceholderAPI.setPlaceholders(player, text);
            } catch (Throwable ignored) {}
        }
        return MessageUtil.colorize(text);
    }

    public static void registerCustomPlaceholder(String key, Function<Player, String> resolver) {
        if (key != null && resolver != null) {
            customPlaceholders.put(key.toLowerCase(Locale.ROOT), resolver);
        }
    }

    public static void unregisterCustomPlaceholder(String key) {
        if (key != null) {
            customPlaceholders.remove(key.toLowerCase(Locale.ROOT));
        }
    }

    private static class JavaStudioExpansion extends PlaceholderExpansion {

        private final JavaStudioPlugin plugin;

        public JavaStudioExpansion(JavaStudioPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull String getIdentifier() {
            return "javastudio";
        }

        @Override
        public @NotNull String getAuthor() {
            return "JavaStudio";
        }

        @Override
        public @NotNull String getVersion() {
            return "2.0.0";
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
            Player player = offlinePlayer != null ? offlinePlayer.getPlayer() : null;
            String lower = params.toLowerCase(Locale.ROOT);

            if (lower.startsWith("eval:")) {
                String expr = params.substring(5).trim();
                return evaluateInlineExpression(player, expr);
            }

            if (lower.startsWith("custom_")) {
                String customKey = lower.substring(7);
                Function<Player, String> resolver = customPlaceholders.get(customKey);
                if (resolver != null) {
                    try {
                        return resolver.apply(player);
                    } catch (Throwable e) {
                        return "Error: " + e.getMessage();
                    }
                }
                return "Unknown_Var";
            }
            if (customPlaceholders.containsKey(lower)) {
                Function<Player, String> resolver = customPlaceholders.get(lower);
                if (resolver != null) {
                    try {
                        return resolver.apply(player);
                    } catch (Throwable e) {
                        return "Error: " + e.getMessage();
                    }
                }
            }


            ServerController sc = plugin.getServerController();
            ServerController.MemoryInfo mem = sc != null ? sc.getMemoryInfo() : null;

            return switch (lower) {
                case "tps" -> {
                    double[] tps = sc != null ? sc.getTPS() : new double[]{20.0};
                    yield String.format(Locale.US, "%.2f", Math.min(20.0, tps[0]));
                }
                case "mspt" -> {
                    double mspt = sc != null ? sc.getMSPT() : 0.0;
                    yield String.format(Locale.US, "%.1f", mspt);
                }
                case "cpu" -> {
                    double load = 0.0;
                    try {
                        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
                        load = os.getSystemLoadAverage();
                    } catch (Throwable ignored) {}
                    yield String.format(Locale.US, "%.1f", Math.max(0.0, load));
                }
                case "ram_used" -> mem != null ? mem.usedMb() + " MB" : "0 MB";
                case "ram_max" -> mem != null ? mem.maxMb() + " MB" : "0 MB";
                case "ram_free" -> mem != null ? mem.freeMb() + " MB" : "0 MB";
                case "ram_percent" -> mem != null ? mem.usedPercent() + "%" : "0%";
                case "java_version" -> String.valueOf(PlatformUtil.getJavaVersion());
                case "virtual_threads" -> PlatformUtil.isVirtualThreadsSupported() ? "Enabled" : "Disabled";

                case "plugins_count" -> String.valueOf(plugin.getPluginManager().getActivePlugins().size());
                case "scripts_count" -> String.valueOf(plugin.getScriptManager().getAvailableScripts().size());

                case "exploit_blocked" -> plugin.getExploitGuard() != null ?
                        String.valueOf(plugin.getExploitGuard().getStats().getPacketsBlocked()) : "0";
                case "exploit_checked" -> plugin.getExploitGuard() != null ?
                        String.valueOf(plugin.getExploitGuard().getStats().getTotalPacketsChecked()) : "0";

                case "ac_status" -> (plugin.getAntiCheatManager() != null && plugin.getAntiCheatManager().isActive()) ? "Active" : "Disabled";
                case "ac_flags_total" -> plugin.getAntiCheatManager() != null ? String.valueOf(plugin.getAntiCheatManager().getTotalFlags()) : "0";
                case "ac_punishments" -> plugin.getAntiCheatManager() != null ? String.valueOf(plugin.getAntiCheatManager().getTotalPunishments()) : "0";
                case "ac_vl" -> {
                    if (player != null && plugin.getAntiCheatManager() != null) {
                        com.javastudio.anticheat.PlayerData d = plugin.getAntiCheatManager().peek(player.getUniqueId());
                        yield d != null ? String.format(java.util.Locale.ROOT, "%.1f", d.getTotalVl()) : "0.0";
                    }
                    yield "0.0";
                }
                case "ac_frozen" -> {
                    if (player != null && plugin.getAntiCheatManager() != null) {
                        com.javastudio.anticheat.PlayerData d = plugin.getAntiCheatManager().peek(player.getUniqueId());
                        yield (d != null && d.isFrozen) ? "Yes" : "No";
                    }
                    yield "No";
                }
                case "ac_cps" -> {
                    if (player != null && plugin.getAntiCheatManager() != null) {
                        com.javastudio.anticheat.PlayerData d = plugin.getAntiCheatManager().peek(player.getUniqueId());
                        yield d != null ? String.valueOf(d.clicks.size()) : "0";
                    }
                    yield "0";
                }

                case "cluster_status" -> plugin.getClusterHub() != null && plugin.getClusterHub().getRedis() != null && plugin.getClusterHub().getRedis().isConnected() ?
                        "REDIS_ONLINE" : "STANDALONE";

                default -> null;
            };
        }

        private String evaluateInlineExpression(@Nullable Player player, String expr) {
            if (expr.isEmpty()) return "";

            if (player != null) {
                switch (expr) {
                    case "player.getName()" -> { return player.getName(); }
                    case "player.getHealth()" -> { return String.format(Locale.US, "%.1f", player.getHealth()); }
                    case "player.getFoodLevel()" -> { return String.valueOf(player.getFoodLevel()); }
                    case "player.getLevel()" -> { return String.valueOf(player.getLevel()); }
                    case "player.getPing()" -> { return String.valueOf(player.getPing()); }
                    case "player.getWorld().getName()" -> { return player.getWorld().getName(); }
                    case "player.isOp()" -> { return String.valueOf(player.isOp()); }
                    case "player.getGameMode().name()" -> { return player.getGameMode().name(); }
                }
            }

            try {
                if (player != null && expr.startsWith("player.getHealth()") && expr.contains("*")) {
                    String factorStr = expr.substring(expr.indexOf('*') + 1).trim();
                    double factor = Double.parseDouble(factorStr);
                    return String.format(Locale.US, "%.1f", player.getHealth() * factor);
                }
                if (player != null && expr.startsWith("player.getHealth()") && expr.contains("+")) {
                    String addStr = expr.substring(expr.indexOf('+') + 1).trim();
                    double add = Double.parseDouble(addStr);
                    return String.format(Locale.US, "%.1f", player.getHealth() + add);
                }
            } catch (Exception ignored) {}

            return "{" + expr + "}";
        }
    }
}
