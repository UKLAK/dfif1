package com.javastudio.utils;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageUtil {

    private static final Pattern GRADIENT_PATTERN = Pattern.compile("<gradient:#([A-Fa-f0-9]{6}):#([A-Fa-f0-9]{6})>(.*?)</gradient>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAINBOW_PATTERN = Pattern.compile("<rainbow>(.*?)</rainbow>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_HEX_PATTERN = Pattern.compile("<#([A-Fa-f0-9]{6})>");
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final String PREFIX = "&8[&bJava&3Studio&8] &r";

    @SuppressWarnings("deprecation")
    public static String colorize(String text) {
        if (text == null) return "";

        Matcher gradMatcher = GRADIENT_PATTERN.matcher(text);
        StringBuilder gradBuffer = new StringBuilder();
        while (gradMatcher.find()) {
            String hex1 = gradMatcher.group(1);
            String hex2 = gradMatcher.group(2);
            String content = gradMatcher.group(3);
            gradMatcher.appendReplacement(gradBuffer, Matcher.quoteReplacement(applyGradient(content, hex1, hex2)));
        }
        gradMatcher.appendTail(gradBuffer);
        text = gradBuffer.toString();

        Matcher rainMatcher = RAINBOW_PATTERN.matcher(text);
        StringBuilder rainBuffer = new StringBuilder();
        while (rainMatcher.find()) {
            String content = rainMatcher.group(1);
            rainMatcher.appendReplacement(rainBuffer, Matcher.quoteReplacement(applyRainbow(content)));
        }
        rainMatcher.appendTail(rainBuffer);
        text = rainBuffer.toString();

        Matcher tagHex = TAG_HEX_PATTERN.matcher(text);
        StringBuilder tagBuffer = new StringBuilder();
        while (tagHex.find()) {
            String hex = tagHex.group(1);
            tagHex.appendReplacement(tagBuffer, toSpigotHex(hex));
        }
        tagHex.appendTail(tagBuffer);
        text = tagBuffer.toString();

        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(buffer, toSpigotHex(hex));
        }
        matcher.appendTail(buffer);

        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    private static String toSpigotHex(String hex) {
        StringBuilder replacement = new StringBuilder("§x");
        for (char c : hex.toCharArray()) {
            replacement.append('§').append(c);
        }
        return replacement.toString();
    }

    private static String applyGradient(String text, String hex1, String hex2) {
        if (text == null || text.isEmpty()) return "";
        int r1 = Integer.parseInt(hex1.substring(0, 2), 16);
        int g1 = Integer.parseInt(hex1.substring(2, 4), 16);
        int b1 = Integer.parseInt(hex1.substring(4, 6), 16);

        int r2 = Integer.parseInt(hex2.substring(0, 2), 16);
        int g2 = Integer.parseInt(hex2.substring(2, 4), 16);
        int b2 = Integer.parseInt(hex2.substring(4, 6), 16);

        int len = text.length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            float ratio = len > 1 ? (float) i / (len - 1) : 0f;
            int r = Math.round(r1 + (r2 - r1) * ratio);
            int g = Math.round(g1 + (g2 - g1) * ratio);
            int b = Math.round(b1 + (b2 - b1) * ratio);
            String hex = String.format("%02x%02x%02x", r, g, b);
            sb.append(toSpigotHex(hex)).append(text.charAt(i));
        }
        return sb.toString();
    }

    private static String applyRainbow(String text) {
        if (text == null || text.isEmpty()) return "";
        int len = text.length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            float hue = (float) i / Math.max(1, len);
            int rgb = java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f) & 0xFFFFFF;
            String hex = String.format("%06x", rgb);
            sb.append(toSpigotHex(hex)).append(text.charAt(i));
        }
        return sb.toString();
    }

    public static String color(String text) {
        return colorize(text);
    }

    public static void send(CommandSender sender, String message) {
        if (sender == null || message == null) return;
        String formatted = PREFIX + message;
        if (PlatformUtil.isAdventureSupported()) {
            try {
                AdventureBridge.sendMessage(sender, formatted);
                return;
            } catch (Throwable ignored) {}
        }
        sender.sendMessage(colorize(formatted));
    }

    public static void sendRaw(CommandSender sender, String message) {
        if (sender == null || message == null) return;
        if (PlatformUtil.isAdventureSupported()) {
            try {
                AdventureBridge.sendMessage(sender, message);
                return;
            } catch (Throwable ignored) {}
        }
        sender.sendMessage(colorize(message));
    }

    public static void broadcast(String message) {
        if (message == null) return;
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            sendRaw(p, message);
        }
        sendRaw(org.bukkit.Bukkit.getConsoleSender(), message);
    }

    private static class AdventureBridge {
        static void sendMessage(CommandSender sender, String text) {
            try {
                Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
                Method legacySectionMethod = serializerClass.getMethod("legacySection");
                Object serializer = legacySectionMethod.invoke(null);
                Method deserializeMethod = serializer.getClass().getMethod("deserialize", String.class);
                Object comp = deserializeMethod.invoke(serializer, colorize(text));

                Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
                Method sendMsgMethod = sender.getClass().getMethod("sendMessage", componentClass);
                sendMsgMethod.invoke(sender, comp);
            } catch (Throwable t) {
                sender.sendMessage(colorize(text));
            }
        }
    }

    public static void sendSuccess(CommandSender sender, String message) {
        send(sender, "&a✔ " + message);
    }

    public static void sendError(CommandSender sender, String message) {
        send(sender, "&c✖ " + message);
    }

    public static void sendWarning(CommandSender sender, String message) {
        send(sender, "&e⚠ " + message);
    }

    public static void sendInfo(CommandSender sender, String message) {
        send(sender, "&bℹ &7" + message);
    }

    public static void sendHeader(CommandSender sender, String title) {
        if (sender == null) return;
        sendRaw(sender, "&8&m═════════════════&r &b&l" + (title != null ? title : "") + " &8&m═════════════════");
    }

    @SuppressWarnings("deprecation")
    public static void sendActionBar(org.bukkit.entity.Player player, String message) {
        if (player == null || message == null) return;
        String colored = colorize(message);
        if (PlatformUtil.isAdventureSupported()) {
            try {
                Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
                java.lang.reflect.Method legacySectionMethod = serializerClass.getMethod("legacySection");
                Object serializer = legacySectionMethod.invoke(null);
                java.lang.reflect.Method deserializeMethod = serializer.getClass().getMethod("deserialize", String.class);
                Object comp = deserializeMethod.invoke(serializer, colored);

                Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
                java.lang.reflect.Method sendBarMethod = player.getClass().getMethod("sendActionBar", componentClass);
                sendBarMethod.invoke(player, comp);
                return;
            } catch (Throwable ignored) {}
        }

        try {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(colored));
        } catch (Throwable ignored) {
            player.sendMessage(colored);
        }
    }
}
