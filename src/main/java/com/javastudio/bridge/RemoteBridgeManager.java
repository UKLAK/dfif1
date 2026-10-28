package com.javastudio.bridge;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.discord.DiscordWebhook;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * RemoteBridgeManager: Cầu nối điều hành từ xa & cảnh báo thời gian thực về Discord.
 * Hỗ trợ đồng bộ cảnh báo /jac, /jsecurity, sụt giảm TPS /jdiag và thực thi lệnh cứu hộ khẩn cấp.
 */
public class RemoteBridgeManager {

    private final JavaStudioPlugin plugin;

    public RemoteBridgeManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Gửi cảnh báo Anti-Cheat (/jac) về kênh Discord Staff.
     */
    public void sendAntiCheatAlert(Player player, String checkName, int vl, int ping, double tps) {
        DiscordWebhook hook = plugin.getDiscordWebhook();
        if (hook == null) return;

        String title = "🚨 [Anti-Cheat Alert] Phát hiện nghi vấn gian lận";
        String description = String.format(Locale.US,
                "**Người chơi**: `%s`\n" +
                        "**Hành vi vi phạm**: `%s` (VL: `%d`)\n" +
                        "**Thông số mạng**: Ping `%d ms` | Server TPS: `%.2f`",
                player.getName(), checkName, vl, ping, tps
        );

        hook.sendCustomEmbed(title, description, 0xFF4500); // Màu cam đỏ
    }

    /**
     * Gửi cảnh báo An ninh / Mã độc (/jsecurity) về Discord.
     */
    public void sendSecurityAlert(String module, String details, String severity) {
        DiscordWebhook hook = plugin.getDiscordWebhook();
        if (hook == null) return;

        String title = "🛡️ [Security Sentinel] Cảnh báo an ninh (" + severity.toUpperCase() + ")";
        String description = String.format("**Phân hệ**: `%s`\n**Chi tiết**: %s", module, details);

        hook.sendCustomEmbed(title, description, 0xDC143C); // Màu đỏ thẫm
    }

    /**
     * Gửi cảnh báo Sụt giảm TPS / Lag Spike (/jdiag) về Discord.
     */
    public void sendLagSpikeAlert(double tps, double mspt, int loadedChunks) {
        DiscordWebhook hook = plugin.getDiscordWebhook();
        if (hook == null) return;

        String title = "⚠️ [Telemetry Nexus] Cảnh báo sụt giảm hiệu năng máy chủ";
        String description = String.format(Locale.US,
                "**TPS hiện tại**: `%.2f` (Ngưỡng cảnh báo: < 18.0)\n" +
                        "**MSPT**: `%.2f ms`\n" +
                        "**Số Chunk đã nạp**: `%d` chunks\n" +
                        "*Hãy dùng lệnh cứu hộ: `/jbridge rescue gc`*",
                tps, mspt, loadedChunks
        );

        hook.sendCustomEmbed(title, description, 0xFFD700); // Màu vàng
    }

    /**
     * Thực thi các hành động cứu hộ khẩn cấp từ xa hoặc in-game.
     */
    public boolean executeRescue(String action, CommandSender sender) {
        switch (action.toLowerCase()) {
            case "gc" -> {
                long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                System.gc();
                long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long freed = Math.max(0, before - after);
                MessageUtil.sendSuccess(sender, "Cứu hộ GC: Đã giải phóng &b" + (freed / (1024 * 1024)) + " MB &aRAM!");
                if (plugin.getDiscordWebhook() != null) {
                    plugin.getDiscordWebhook().sendCustomEmbed("🧹 [Rescue Ops] Đã kích hoạt khẩn cấp JVM GC",
                            "Thực hiện bởi: `" + sender.getName() + "`\nGiải phóng: `" + (freed / (1024 * 1024)) + " MB`", 0x32CD32);
                }
                return true;
            }
        }
        return false;
    }
}
