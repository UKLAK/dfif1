package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.pack.ResourcePackHost;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class JPackCommand implements CommandExecutor, TabCompleter {

    private final JavaStudioPlugin plugin;
    private final ResourcePackHost packHost;

    public JPackCommand(JavaStudioPlugin plugin, ResourcePackHost packHost) {
        this.plugin = plugin;
        this.packHost = packHost;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.sendError(sender, "Bạn không có quyền quản lý Resource Pack!");
            return true;
        }

        if (!plugin.getSettingsManager().isResourcePackEnabled()) {
            MessageUtil.sendError(sender, "Tính năng Quản lý Resource Pack (/jpack) đang bị tắt trong cài đặt!");
            MessageUtil.sendInfo(sender, "Bật lại bằng lệnh: &e/javastudio toggle resource-pack");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            showStatus(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "build" -> {
                MessageUtil.sendInfo(sender, "Đang nén lại Resource Pack trong RAM...");
                boolean ok = packHost.buildPack();
                if (ok) {
                    Player viewer = sender instanceof Player p ? p : null;
                    MessageUtil.sendSuccess(sender, "Đóng gói thành công (" + (packHost.getZipBytes().length / 1024) + " KB)!");
                    MessageUtil.sendRaw(sender, "§7Mã kiểm tra SHA-1: §e" + packHost.getSha1Hash());
                    MessageUtil.sendRaw(sender, "§7Địa chỉ tải: §b" + packHost.getDownloadUrl(viewer));
                } else {
                    MessageUtil.sendError(sender, "Đóng gói Resource Pack thất bại. Kiểm tra console để xem chi tiết!");
                }
            }
            case "push" -> {
                if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
                    int sent = packHost.pushAll();
                    MessageUtil.sendSuccess(sender, "Đã gửi yêu cầu nạp Resource Pack tới §a" + sent + " §fngười chơi online!");
                } else {
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null || !target.isOnline()) {
                        MessageUtil.sendError(sender, "Người chơi '" + args[1] + "' không trực tuyến!");
                        return true;
                    }
                    boolean ok = packHost.pushPack(target);
                    if (ok) {
                        MessageUtil.sendSuccess(sender, "Đã gửi yêu cầu nạp Resource Pack tới §e" + target.getName() + "§f!");
                    } else {
                        MessageUtil.sendError(sender, "Không thể gửi Resource Pack cho " + target.getName() + "!");
                    }
                }
            }
            case "list" -> {
                MessageUtil.sendHeader(sender, "DANH SÁCH TÀI NGUYÊN CUSTOM ĐÃ ĐĂNG KÝ");
                var items = packHost.getCustomItems();
                MessageUtil.sendRaw(sender, "§e✦ Vật phẩm tùy biến (Custom Items: " + items.size() + "):");
                if (items.isEmpty()) {
                    MessageUtil.sendRaw(sender, "  §7(Chưa có vật phẩm nào được đăng ký)");
                } else {
                    items.forEach((id, def) -> {
                        MessageUtil.sendRaw(sender, "  §f- §b" + id + " §7(Base: " + def.baseMaterial() + ", CMD: §e" + def.customModelData() + "§7, Tên: " + def.displayName() + "§7)");
                    });
                }

                var sounds = packHost.getCustomSounds();
                MessageUtil.sendRaw(sender, "§e✦ Âm thanh tùy biến (Custom Sounds: " + sounds.size() + "):");
                if (sounds.isEmpty()) {
                    MessageUtil.sendRaw(sender, "  §7(Chưa có âm thanh nào)");
                } else {
                    sounds.forEach((k, def) -> MessageUtil.sendRaw(sender, "  §f- §a" + k + " §7(Namespace: §d" + def.namespace() + "§7)"));
                }

                var glyphs = packHost.getFontGlyphs();
                MessageUtil.sendRaw(sender, "§e✦ Ký tự Font Glyph (Custom GUI: " + glyphs.size() + "):");
                if (glyphs.isEmpty()) {
                    MessageUtil.sendRaw(sender, "  §7(Chưa có glyph nào)");
                } else {
                    glyphs.forEach((k, def) -> MessageUtil.sendRaw(sender, "  §f- §6" + k + " §7(Ký tự: '" + def.glyphChar() + "', Texture: §f" + def.texturePath() + "§7)"));
                }
            }
            case "give" -> {
                if (args.length < 3) {
                    MessageUtil.sendError(sender, "Cách dùng: /jpack give <người_chơi> <item_id> [số_lượng]");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    MessageUtil.sendError(sender, "Không tìm thấy người chơi '" + args[1] + "'!");
                    return true;
                }
                String itemId = args[2];
                int amount = 1;
                if (args.length >= 4) {
                    try { amount = Integer.parseInt(args[3]); } catch (Exception ignored) {}
                }

                ItemStack item = packHost.createCustomItem(itemId, amount);
                if (item == null) {
                    MessageUtil.sendError(sender, "Không tìm thấy vật phẩm custom ID '" + itemId + "'! Dùng /jpack list để xem.");
                    return true;
                }

                target.getInventory().addItem(item);
                MessageUtil.sendSuccess(sender, "Đã trao §a" + amount + "x " + itemId + " §fcho §e" + target.getName() + "§f!");
                MessageUtil.sendInfo(target, "Bạn vừa nhận được vật phẩm custom: §e" + itemId);
            }
            case "demo" -> {
                packHost.registerItem("ruby_sword", Material.DIAMOND_SWORD, 10001, "&c&lKiếm Huyết Ngọc (Ruby Sword)");
                packHost.registerItem("magic_wand", Material.BLAZE_ROD, 10002, "&d&lGậy Phép Thuật (Magic Wand)");
                packHost.registerSound("magic_cast", "javastudio:custom.magic");
                packHost.registerFontGlyph("chest_overlay", "\uE001", "textures/font/chest_overlay.png");
                packHost.buildPack();

                MessageUtil.sendSuccess(sender, "Đã khởi tạo và đóng gói JSON định nghĩa mẫu:");
                MessageUtil.sendRaw(sender, "  §f- Vật phẩm: §bruby_sword §7(CMD: 10001)");
                MessageUtil.sendRaw(sender, "  §f- Vật phẩm: §bmagic_wand §7(CMD: 10002)");
                MessageUtil.sendRaw(sender, "  §f- Âm thanh: §amagic_cast §7(javastudio:custom.magic)");
                MessageUtil.sendRaw(sender, "  §f- Font GUI: §6chest_overlay §7(\\uE001)");
                MessageUtil.sendRaw(sender, "§7Gõ §e/jpack give <tên> ruby_sword §7hoặc §e/jpack push §7để trải nghiệm!");
            }
            default -> {
                MessageUtil.sendError(sender, "Lệnh không hợp lệ! Dùng: /jpack [status|build|push|list|give|demo]");
            }
        }
        return true;
    }

    private void showStatus(CommandSender sender) {
        Player viewer = sender instanceof Player p ? p : null;
        MessageUtil.sendHeader(sender, "QUẢN LÝ RESOURCE PACK (EMBEDDED HTTP HOST)");
        MessageUtil.sendRaw(sender, "§fTrạng thái Pack: " + (packHost.getZipBytes().length > 0 ? "§a✔ Đã nén sẵn sàng" : "§c✖ Chưa nén"));
        MessageUtil.sendRaw(sender, "§fMáy chủ HTTP: " + (packHost.isHttpRunning() ? "§a✔ Đang chạy" : "§e⚠ Đang tắt") + " §7(Lượt tải: §b" + packHost.getTotalDownloads() + "§7)");
        MessageUtil.sendRaw(sender, "§fChế độ cổng: " + (packHost.isSinglePortMode()
                ? "§a§lPANEL 1-PORT §7(Đi chung cổng Minecraft §e:" + Bukkit.getPort() + "§7)"
                : "§e§lCỔNG ĐỘC LẬP §7(:" + plugin.getConfig().getInt("resource-pack.port", 8088) + ")"));
        MessageUtil.sendRaw(sender, "§fDung lượng ZIP: §e" + Math.max(1, packHost.getZipBytes().length / 1024) + " KB");
        MessageUtil.sendRaw(sender, "§fMã SHA-1 Hash: §d" + (packHost.getSha1Hash().isEmpty() ? "Chưa có" : packHost.getSha1Hash()));
        MessageUtil.sendRaw(sender, "§fĐường dẫn HTTP: §b" + packHost.getDownloadUrl(viewer));
        MessageUtil.sendRaw(sender, "§fVật phẩm đã đăng ký: §a" + packHost.getCustomItems().size() + " items");
        MessageUtil.sendRaw(sender, "§fThư mục tài nguyên: §7" + packHost.getPackDir().getAbsolutePath());
        MessageUtil.sendRaw(sender, "§7Lệnh thao tác:");
        MessageUtil.sendRaw(sender, "  §e/jpack build §7- Nén lại pack và tính lại SHA-1 hash");
        MessageUtil.sendRaw(sender, "  §e/jpack push [all|player] §7- Ép tải lại pack trong game");
        MessageUtil.sendRaw(sender, "  §e/jpack list §7- Xem danh sách vật phẩm và âm thanh custom");
        MessageUtil.sendRaw(sender, "  §e/jpack give <player> <item> §7- Trao vật phẩm custom");
        MessageUtil.sendRaw(sender, "  §e/jpack demo §7- Tạo mẫu các vật phẩm test nhanh");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filter(Arrays.asList("status", "build", "push", "list", "give", "demo"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("push")) {
            List<String> list = new ArrayList<>();
            list.add("all");
            for (Player p : Bukkit.getOnlinePlayers()) {
                list.add(p.getName());
            }
            return filter(list, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> list = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                list.add(p.getName());
            }
            return filter(list, args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(new ArrayList<>(packHost.getCustomItems().keySet()), args[2]);
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> input, String prefix) {
        if (prefix == null || prefix.isBlank()) return input;
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String s : input) {
            if (s.toLowerCase().startsWith(lower)) {
                result.add(s);
            }
        }
        return result;
    }
}
