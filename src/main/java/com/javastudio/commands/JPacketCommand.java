package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.network.packet.PacketSnifferManager;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class JPacketCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final PacketSnifferManager sniffer;

    public JPacketCommand(JavaStudioPlugin plugin, PacketSnifferManager sniffer) {
        this.plugin = plugin;
        this.sniffer = sniffer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền quản lý Netty Packet Sniffer!");
            return true;
        }

        if (!plugin.getSettingsManager().isPacketSnifferEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Netty Packet Sniffer (/jpacket, /jpkt) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle packet-sniffer");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            MessageUtil.send(sender, "&6=== NETTY PACKET SNIFFER (ENTERPRISE PROTOCOL RUNTIME) ===");
            MessageUtil.sendRaw(sender, "  &7Trạng thái lắng nghe: " + (sniffer.isListening() || !sniffer.getListenedPackets().isEmpty() ? "&a[BẬT] ✔" : "&c[TẮT] ✖"));
            MessageUtil.sendRaw(sender, "  &7Số kết nối Pipeline đã Hook: &e" + sniffer.getHookedCount() + " players");
            MessageUtil.sendRaw(sender, "  &7Gói tin Inbound (Client->Server): &b" + String.format("%,d", sniffer.getTotalInbound()));
            MessageUtil.sendRaw(sender, "  &7Gói tin Outbound (Server->Client): &d" + String.format("%,d", sniffer.getTotalOutbound()));
            MessageUtil.sendRaw(sender, "  &7Gói tin đã Chặn (Cancelled / Pre-Main Drop): &c" + String.format("%,d", sniffer.getTotalCancelled()));
            MessageUtil.sendRaw(sender, "  &7Bộ lọc Packet: &f" + sniffer.getPacketFilter() + " &7| Bộ lọc Player: &e" + (sniffer.getPlayerFilter() != null ? sniffer.getPlayerFilter() : "Tất cả"));
            if (!sniffer.getCancelledPackets().isEmpty()) {
                MessageUtil.sendRaw(sender, "  &7Đang chặn: &c" + String.join(", ", sniffer.getCancelledPackets()));
            }
            if (!sniffer.getListenedPackets().isEmpty()) {
                MessageUtil.sendRaw(sender, "  &7Đang theo dõi sâu (Reflection Dump): &a" + String.join(", ", sniffer.getListenedPackets()));
            }
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "listen", "start" -> {
                String filter = args.length > 1 ? args[1] : "all";
                if (args.length > 2) {
                    sniffer.setPlayerFilter(args[2]);
                }
                sniffer.addListenedPacket(filter);
                sniffer.setPacketFilter(filter);
                sniffer.setListening(true);
                MessageUtil.sendSuccess(sender, "Đã BẬT theo dõi sâu gói tin Netty: &e" + filter + " &a(Reflection fields dump to Server Console)!");
            }
            case "unlisten" -> {
                if (args.length < 2) {
                    sniffer.getListenedPackets().forEach(sniffer::removeListenedPacket);
                    sniffer.setListening(false);
                    MessageUtil.sendSuccess(sender, "Đã gỡ bỏ toàn bộ danh sách gói tin đang theo dõi sâu.");
                } else {
                    boolean removed = sniffer.removeListenedPacket(args[1]);
                    if (removed) {
                        MessageUtil.sendSuccess(sender, "Đã gỡ bỏ gói tin &e" + args[1] + " &akhỏi danh sách theo dõi.");
                    } else {
                        MessageUtil.sendWarning(sender, "Gói tin &e" + args[1] + " &ckhông có trong danh sách theo dõi.");
                    }
                }
            }
            case "stop" -> {
                sniffer.setListening(false);
                sniffer.getListenedPackets().forEach(sniffer::removeListenedPacket);
                MessageUtil.sendWarning(sender, "Đã TẮT toàn bộ ghi nhận log và theo dõi gói tin Netty.");
            }
            case "cancel" -> {
                if (args.length < 2) {
                    MessageUtil.sendError(sender, "Cú pháp: /" + label + " cancel <PacketName>");
                    return true;
                }
                String target = args[1].toLowerCase();
                sniffer.cancelPacket(target);
                MessageUtil.sendSuccess(sender, "Đã thêm quy tắc CHẶN gói tin chứa chuỗi: &c" + target + " &a(Drops on Netty IO)!");
            }
            case "uncancel" -> {
                if (args.length < 2) {
                    sniffer.clearCancelled();
                    MessageUtil.sendSuccess(sender, "Đã bỏ chặn toàn bộ các gói tin.");
                    return true;
                }
                boolean removed = sniffer.uncancelPacket(args[1]);
                if (removed) {
                    MessageUtil.sendSuccess(sender, "Đã mở chặn gói tin &a" + args[1]);
                } else {
                    MessageUtil.sendWarning(sender, "Gói tin &e" + args[1] + " &ckhông nằm trong danh sách chặn.");
                }
            }
            case "listcancelled" -> {
                var set = sniffer.getCancelledPackets();
                if (set.isEmpty()) {
                    MessageUtil.send(sender, "&7Hiện không có gói tin nào bị chặn.");
                } else {
                    MessageUtil.send(sender, "&6Các gói tin đang bị chặn: &c" + String.join(", ", set));
                }
            }
            case "filter" -> {
                if (args.length < 2 || args[1].equalsIgnoreCase("clear")) {
                    sniffer.setPlayerFilter(null);
                    MessageUtil.sendSuccess(sender, "Đã xóa bộ lọc người chơi cho Packet Sniffer.");
                } else {
                    sniffer.setPlayerFilter(args[1]);
                    MessageUtil.sendSuccess(sender, "Đã đặt bộ lọc gói tin cho người chơi: &e" + args[1]);
                }
            }
            case "fakeblock" -> {
                if (!(sender instanceof Player p)) {
                    MessageUtil.sendError(sender, "Lệnh này chỉ dùng được trong game!");
                    return true;
                }
                Material mat = Material.DIAMOND_BLOCK;
                if (args.length > 1) {
                    try {
                        mat = Material.valueOf(args[1].toUpperCase());
                    } catch (Exception ignored) {}
                }
                Location loc = p.getLocation().getBlock().getLocation().add(0, -1, 0);
                sniffer.sendFakeBlock(p, loc, mat);
                MessageUtil.sendSuccess(p, "Đã gửi gói tin khối ảo &b" + mat.name() + " &adưới chân bạn (Chỉ bạn nhìn thấy, thế giới không đổi)!");
            }
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        MessageUtil.send(sender, "&6=== Hướng dẫn Lệnh /" + label + " (Netty Protocol Engine) ===");
        MessageUtil.sendRaw(sender, "  &e/" + label + " status &7- Xem tổng quan các gói tin vào/ra và các gói bị chặn");
        MessageUtil.sendRaw(sender, "  &e/" + label + " listen <PacketName|all> [player] &7- Bắt & in trường Reflection vào console");
        MessageUtil.sendRaw(sender, "  &e/" + label + " unlisten [packet] &7- Gỡ theo dõi gói tin");
        MessageUtil.sendRaw(sender, "  &e/" + label + " cancel <PacketName> &7- Chặn gói tin ngay trên Netty IO thread");
        MessageUtil.sendRaw(sender, "  &e/" + label + " uncancel <PacketName> &7- Bỏ chặn gói tin");
        MessageUtil.sendRaw(sender, "  &e/" + label + " listcancelled &7- Xem danh sách các gói tin đang bị chặn");
        MessageUtil.sendRaw(sender, "  &e/" + label + " stop &7- Dừng mọi ghi nhận gói tin");
        MessageUtil.sendRaw(sender, "  &e/" + label + " filter <player|clear> &7- Lọc gói tin theo người chơi");
        MessageUtil.sendRaw(sender, "  &e/" + label + " fakeblock [material] &7- Thử nghiệm gửi gói tin khối ảo client-side");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "listen", "unlisten", "cancel", "uncancel", "listcancelled", "stop", "filter", "fakeblock");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("fakeblock")) {
                return Arrays.asList("DIAMOND_BLOCK", "EMERALD_BLOCK", "GOLD_BLOCK", "NETHERITE_BLOCK", "OBSIDIAN");
            }
            if (sub.equals("listen") || sub.equals("cancel")) {
                return Arrays.asList("PacketPlayInFlying", "ServerboundMovePlayerPacket", "PacketPlayInChat", "PacketPlayInUseEntity", "PacketPlayInCustomPayload", "ClientboundSetEntityDataPacket");
            }
            if (sub.equals("uncancel")) {
                return new ArrayList<>(sniffer.getCancelledPackets());
            }
            if (sub.equals("unlisten")) {
                return new ArrayList<>(sniffer.getListenedPackets());
            }
            if (sub.equals("filter")) {
                List<String> list = new ArrayList<>();
                list.add("clear");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    list.add(p.getName());
                }
                return list;
            }
        }
        return Collections.emptyList();
    }
}

