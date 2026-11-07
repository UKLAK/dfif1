package com.javastudio.analytics;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.control.ServerController;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DebugHudManager {

    public enum HudMode {
        OFF,
        ACTIONBAR,
        BOSSBAR
    }

    private final JavaStudioPlugin plugin;
    private final Map<UUID, HudMode> playerModes = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new ConcurrentHashMap<>();
    private PlatformUtil.TaskHandle taskHandle;

    public DebugHudManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        startHudLoop();
    }

    private void startHudLoop() {
        if (taskHandle != null) {
            taskHandle.cancel();
        }

        this.taskHandle = PlatformUtil.runTaskTimer(plugin, this::tickHud, 20L, 20L);
    }

    private void tickHud() {
        if (playerModes.isEmpty()) return;

        ServerController sc = plugin.getServerController();
        if (sc == null) return;

        double[] tpsArr = sc.getTPS();
        double tps = tpsArr != null && tpsArr.length > 0 ? tpsArr[0] : 20.0;
        double mspt = sc.getMSPT();
        ServerController.MemoryInfo mem = sc.getMemoryInfo();

        int totalChunks = 0;
        int totalEntities = 0;
        for (World w : Bukkit.getWorlds()) {
            totalChunks += w.getLoadedChunks().length;
            totalEntities += w.getEntities().size();
        }

        int activeDynPlugins = plugin.getPluginManager() != null ? plugin.getPluginManager().getActivePlugins().size() : 0;

        String tpsColor = tps >= 19.5 ? "§a" : (tps >= 17.0 ? "§e" : "§c");
        String msptColor = mspt <= 30.0 ? "§a" : (mspt <= 45.0 ? "§e" : "§c");
        double ramRatio = mem.maxMb() > 0 ? ((double) mem.usedMb() / mem.maxMb()) : 0.0;
        String ramColor = ramRatio < 0.7 ? "§a" : (ramRatio < 0.88 ? "§e" : "§c");

        String formattedTps = String.format("%.1f", Math.min(20.0, tps));
        String formattedMspt = String.format("%.1f", mspt);
        String formattedRam = String.format("%.1fG/%.1fG", mem.usedMb() / 1024.0, mem.maxMb() / 1024.0);

        String hudMessage = String.format(
                "§8[§bHUD§8] §7TPS: %s%s §8| §7MSPT: %s%sms §8| §7RAM: %s%s §8| §7Chunks: §b%d §8| §7Entities: §d%d §8| §7DynPl: §6%d",
                tpsColor, formattedTps,
                msptColor, formattedMspt,
                ramColor, formattedRam,
                totalChunks, totalEntities, activeDynPlugins
        );

        BarColor barColor = tps >= 19.5 && mspt < 35.0 ? BarColor.GREEN : (tps >= 17.0 ? BarColor.YELLOW : BarColor.RED);
        double healthProgress = Math.max(0.0, Math.min(1.0, 1.0 - (mspt / 50.0)));

        for (Map.Entry<UUID, HudMode> entry : playerModes.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            HudMode mode = entry.getValue();
            if (mode == HudMode.ACTIONBAR) {
                MessageUtil.sendActionBar(player, hudMessage);
            } else if (mode == HudMode.BOSSBAR) {
                BossBar bar = playerBossBars.computeIfAbsent(player.getUniqueId(), uuid -> {
                    BossBar bb = Bukkit.createBossBar(hudMessage, barColor, BarStyle.SOLID);
                    bb.addPlayer(player);
                    return bb;
                });
                bar.setTitle(hudMessage);
                bar.setColor(barColor);
                bar.setProgress(healthProgress);
                bar.setVisible(true);
            }
        }
    }

    public HudMode toggleMode(Player player) {
        if (player == null) return HudMode.OFF;
        HudMode current = playerModes.getOrDefault(player.getUniqueId(), HudMode.OFF);
        HudMode next = switch (current) {
            case OFF -> HudMode.ACTIONBAR;
            case ACTIONBAR -> HudMode.BOSSBAR;
            case BOSSBAR -> HudMode.OFF;
        };
        setMode(player, next);
        return next;
    }

    public void setMode(Player player, HudMode mode) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        if (mode == HudMode.OFF || mode == null) {
            playerModes.remove(uuid);
            removeBossBar(uuid);
            MessageUtil.sendActionBar(player, "");
        } else {
            if (mode == HudMode.ACTIONBAR) {
                removeBossBar(uuid);
            }
            playerModes.put(uuid, mode);
        }
    }

    public HudMode getMode(Player player) {
        return player != null ? playerModes.getOrDefault(player.getUniqueId(), HudMode.OFF) : HudMode.OFF;
    }

    private void removeBossBar(UUID uuid) {
        BossBar bar = playerBossBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
    }

    public void cleanupPlayer(UUID uuid) {
        playerModes.remove(uuid);
        removeBossBar(uuid);
    }

    public void cleanupAll() {
        if (taskHandle != null) {
            taskHandle.cancel();
            taskHandle = null;
        }
        for (BossBar bar : playerBossBars.values()) {
            bar.removeAll();
            bar.setVisible(false);
        }
        playerBossBars.clear();
        playerModes.clear();
    }
}
