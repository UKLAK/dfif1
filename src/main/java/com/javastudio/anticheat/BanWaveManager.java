package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hướng 3: Hệ Thống Ban Theo Đợt (Ban Wave) & Discord Rich Evidence Report.
 * Trì hoãn xử phạt ngay lập tức để hacker không biết chính xác thuật toán nào vừa bắt họ;
 * gom các đối tượng vi phạm 100% vào hàng đợi và thực thi thanh trừng đồng loạt theo chu kỳ
 * (ví dụ 45 phút/lần hoặc thủ công qua lệnh /jac wave execute).
 */
public final class BanWaveManager {

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    public record BanEntry(UUID uuid, String name, CheckType check, String reason, double vl, long queuedTime) {}

    private final Map<UUID, BanEntry> queue = new ConcurrentHashMap<>();
    private PlatformUtil.TaskHandle waveTask;

    private boolean enabled = true;
    private boolean autoExecute = true;
    private int intervalMinutes = 45;
    private List<String> banCommands = new ArrayList<>();

    public BanWaveManager(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        loadConfig();
    }

    public void loadConfig() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("anticheat.ban-wave.enabled", true);
        this.autoExecute = cfg.getBoolean("anticheat.ban-wave.auto-execute", true);
        this.intervalMinutes = Math.max(5, cfg.getInt("anticheat.ban-wave.interval-minutes", 45));
        this.banCommands = new ArrayList<>(cfg.getStringList("anticheat.ban-wave.commands"));
        if (this.banCommands.isEmpty()) {
            this.banCommands.add("ban %player% &c[AntiCheat Ban-Wave] Gian lận bị phát hiện: %check%");
        }
    }

    public void start() {
        stop();
        if (!enabled || !autoExecute) return;
        long intervalTicks = (long) intervalMinutes * 60L * 20L;
        waveTask = PlatformUtil.runTaskTimer(plugin, () -> {
            if (!queue.isEmpty()) {
                executeWave(Bukkit.getConsoleSender());
            }
        }, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (waveTask != null) {
            waveTask.cancel();
            waveTask = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isQueued(UUID uuid) {
        return queue.containsKey(uuid);
    }

    public Collection<BanEntry> getQueue() {
        return queue.values();
    }

    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Đưa người chơi vào hàng đợi Ban Wave khi có vi phạm chắc chắn 100%.
     */
    public boolean queuePlayer(Player p, CheckType check, String reason, double vl) {
        if (!enabled || p == null) return false;
        PlayerData d = manager.peek(p.getUniqueId());
        if (d != null) {
            d.inBanWave = true;
            d.banWaveReason = check.getDisplayName() + " (" + reason + ")";
            d.banWaveAddedTime = System.currentTimeMillis();
        }

        BanEntry entry = new BanEntry(p.getUniqueId(), p.getName(), check, reason, vl, System.currentTimeMillis());
        queue.put(p.getUniqueId(), entry);

        // Gửi báo cáo bằng chứng vi phạm ngay về Discord nếu có Webhook
        sendDiscordEvidence(entry, p);
        return true;
    }

    public boolean unqueuePlayer(UUID id) {
        BanEntry removed = queue.remove(id);
        PlayerData d = manager.peek(id);
        if (d != null) {
            d.inBanWave = false;
            d.banWaveReason = null;
        }
        return removed != null;
    }

    public void clearQueue() {
        for (UUID id : queue.keySet()) {
            PlayerData d = manager.peek(id);
            if (d != null) {
                d.inBanWave = false;
                d.banWaveReason = null;
            }
        }
        queue.clear();
    }

    /**
     * Thực thi thanh trừng toàn bộ hàng đợi Ban Wave.
     */
    public int executeWave(CommandSender staff) {
        if (queue.isEmpty()) {
            MessageUtil.sendInfo(staff, "Hàng đợi Ban Wave hiện đang trống!");
            return 0;
        }

        int count = queue.size();
        List<BanEntry> targets = new ArrayList<>(queue.values());
        clearQueue();

        // Phát sóng thông điệp hoành tráng tới toàn server
        MessageUtil.broadcast("&b╔════════════════════════════════════════════════════╗");
        MessageUtil.broadcast("&b║   &c&lJAVA-STUDIO BAN-WAVE ACTIVATED               &b║");
        MessageUtil.broadcast("&b║   &eĐợt thanh trừng tự động đã xử lý &c&l" + count + " &eđối tượng!   &b║");
        MessageUtil.broadcast("&b╚════════════════════════════════════════════════════╝");

        PlatformUtil.runTask(plugin, () -> {
            for (BanEntry entry : targets) {
                for (String cmd : banCommands) {
                    String formatted = cmd.replace("%player%", entry.name())
                            .replace("%uuid%", entry.uuid().toString())
                            .replace("%check%", entry.check().getDisplayName())
                            .replace("%reason%", entry.reason())
                            .replace("%vl%", String.format(Locale.ROOT, "%.0f", entry.vl()));
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted.startsWith("/") ? formatted.substring(1) : formatted);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("[BanWave] Lỗi thực thi lệnh ban '" + formatted + "': " + t.getMessage());
                    }
                }
            }
        });

        return count;
    }

    /**
     * Xuất báo cáo chứng cứ vi phạm chi tiết về Discord Webhook.
     */
    private void sendDiscordEvidence(BanEntry entry, Player p) {
        if (!plugin.getSettingsManager().isDiscordWebhookEnabled() || plugin.getDiscordWebhook() == null) return;
        try {
            int ping = manager.safePing(p);
            String detail = String.format(Locale.ROOT, "Hacker: **%s** | Check: **%s** | Chi tiết: `%s` | VL: **%.1f** | Ping: **%dms** | Tọa độ: `X=%.0f Y=%.0f Z=%.0f` (Đã thêm vào Ban-Wave Queue)",
                    entry.name(), entry.check().getDisplayName(), entry.reason(), entry.vl(), ping,
                    p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ());
            plugin.getDiscordWebhook().logExploit(entry.name(), detail, "Ban-Wave Queue");
        } catch (Throwable ignored) {}
    }
}
