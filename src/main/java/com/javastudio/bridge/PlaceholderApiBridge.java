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

/**
 * Cáº§u ná»‘i tÃ­ch há»£p chuyÃªn sÃ¢u vá»›i PlaceholderAPI (PAPI).
 * Tá»± Ä‘á»™ng Ä‘Äƒng kÃ½ PlaceholderExpansion chÃ­nh thá»©c %javastudio_...%
 * Cho phÃ©p nhÃºng mÃ£ Java tÃ­nh toÃ¡n biá»ƒu thá»©c trá»±c tiáº¿p vÃ o Scoreboard, Tablist, Chat vÃ  GUI,
 * cÃ¹ng cÃ¡c chá»‰ sá»‘ hiá»‡u nÄƒng thá»i gian thá»±c vÃ  Ä‘Äƒng kÃ½ biáº¿n tÃ¹y biáº¿n 1-Click.
 */
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
            plugin.getLogger().info("[PAPI Bridge] KhÃ´ng tÃ¬m tháº¥y PlaceholderAPI trÃªn server. Cháº¿ Ä‘á»™ thay tháº¿ Ä‘á»™c láº­p kÃ­ch hoáº¡t.");
            return;
        }

        try {
            expansionInstance = new JavaStudioExpansion(plugin);
            if (expansionInstance.register()) {
                papiAvailable = true;
                plugin.getLogger().info("[PAPI Bridge] ÄÃ£ Ä‘Äƒng kÃ½ thÃ nh cÃ´ng PlaceholderExpansion %javastudio_...% vá»›i PlaceholderAPI!");
            } else {
                plugin.getLogger().warning("[PAPI Bridge] KhÃ´ng thá»ƒ Ä‘Äƒng kÃ½ PlaceholderExpansion vá»›i PlaceholderAPI.");
            }
        } catch (Throwable e) {
            papiAvailable = false;
            plugin.getLogger().warning("[PAPI Bridge] Lá»—i khi khá»Ÿi táº¡o PlaceholderAPI Expansion: " + e.getMessage());
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

    /**
     * Dá»‹ch mÃ£ mÃ u vÃ  thay tháº¿ cÃ¡c placeholder %...% báº±ng PlaceholderAPI náº¿u cÃ³.
     */
    public static String setPlaceholders(@Nullable Player player, @NotNull String text) {
        if (papiAvailable && player != null) {
            try {
                return PlaceholderAPI.setPlaceholders(player, text);
            } catch (Throwable ignored) {}
        }
        return MessageUtil.colorize(text);
    }

    /**
     * ÄÄƒng kÃ½ má»™t biáº¿n placeholder tÃ¹y chá»‰nh 1-Click cho script/plugin Ä‘á»™ng.
     * VÃ­ dá»¥: registerCustomPlaceholder("rank_color", p -> getRankColor(p));
     * Sau Ä‘Ã³ gá»i trong game: %javastudio_custom_rank_color%
     */
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

    /**
     * Lá»›p Expansion chÃ­nh thá»©c cá»§a JavaStudio trong PlaceholderAPI.
     */
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

            // 1. Biáº¿n biá»ƒu thá»©c tÃ­nh toÃ¡n trá»±c tiáº¿p %javastudio_eval:<expr>%
            if (lower.startsWith("eval:")) {
                String expr = params.substring(5).trim();
                return evaluateInlineExpression(player, expr);
            }

            // 2. Biến do dynamic plugin/script đăng ký (%javastudio_custom_<key>% hoặc %javastudio_<key>%)
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

            // 3. CÃ¡c chá»‰ sá»‘ hiá»‡u nÄƒng mÃ¡y chá»§
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

                // Chá»‰ sá»‘ quáº£n lÃ½ plugin & script Ä‘á»™ng
                case "plugins_count" -> String.valueOf(plugin.getPluginManager().getActivePlugins().size());
                case "scripts_count" -> String.valueOf(plugin.getScriptManager().getAvailableScripts().size());

                // Chá»‰ sá»‘ báº£o máº­t ExploitGuard
                case "exploit_blocked" -> plugin.getExploitGuard() != null ?
                        String.valueOf(plugin.getExploitGuard().getStats().getPacketsBlocked()) : "0";
                case "exploit_checked" -> plugin.getExploitGuard() != null ?
                        String.valueOf(plugin.getExploitGuard().getStats().getTotalPacketsChecked()) : "0";

                // Chỉ số Anti-Cheat
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

                // Chá»‰ sá»‘ Cluster / Network
                case "cluster_status" -> plugin.getClusterHub() != null && plugin.getClusterHub().getRedis() != null && plugin.getClusterHub().getRedis().isConnected() ?
                        "REDIS_ONLINE" : "STANDALONE";

                default -> null;
            };
        }

        /**
         * TÃ­nh toÃ¡n biá»ƒu thá»©c Ä‘á»™ng inline cho placeholder.
         */
        private String evaluateInlineExpression(@Nullable Player player, String expr) {
            if (expr.isEmpty()) return "";

            // Má»™t sá»‘ hÃ m tiá»‡n Ã­ch nhanh khÃ´ng cáº§n qua compiler
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

            // Náº¿u lÃ  phÃ©p toÃ¡n cÆ¡ báº£n sá»‘ há»c: vÃ­ dá»¥ player.getHealth() * 5
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
