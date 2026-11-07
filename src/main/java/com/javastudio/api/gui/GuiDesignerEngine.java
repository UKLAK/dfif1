package com.javastudio.api.gui;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.item.ItemBuilder;
import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("deprecation")
public final class GuiDesignerEngine implements Listener {

    public static final class DesignerHolder implements InventoryHolder {
        private final String guiId;
        private final String title;
        private final int rows;
        private final Map<Integer, GuiItemConfig> itemConfigs = new HashMap<>();
        private Inventory inventory;

        public DesignerHolder(String guiId, String title, int rows) {
            this.guiId = guiId;
            this.title = title;
            this.rows = rows;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public String getGuiId() {
            return guiId;
        }

        public String getTitle() {
            return title;
        }

        public int getRows() {
            return rows;
        }

        public Map<Integer, GuiItemConfig> getItemConfigs() {
            return itemConfigs;
        }
    }

    public static final class ActionEditorHolder implements InventoryHolder {
        private final DesignerHolder designerHolder;
        private final int targetSlot;
        private Inventory inventory;

        public ActionEditorHolder(DesignerHolder designerHolder, int targetSlot) {
            this.designerHolder = designerHolder;
            this.targetSlot = targetSlot;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public DesignerHolder getDesignerHolder() {
            return designerHolder;
        }

        public int getTargetSlot() {
            return targetSlot;
        }
    }

    public record ChatInputPrompt(UUID playerUuid, DesignerHolder holder, int slot, String actionType) {}

    private final JavaStudioPlugin plugin;
    private final File guisDir;
    private final Map<UUID, ChatInputPrompt> pendingChatInputs = new ConcurrentHashMap<>();

    public GuiDesignerEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.guisDir = new File(plugin.getDataFolder(), "guis");
        if (!guisDir.exists()) {
            guisDir.mkdirs();
        }
    }

    public void openDesigner(Player player, String guiId, int requestedRows, String customTitle) {
        int rows = Math.max(1, Math.min(5, requestedRows));
        String title = (customTitle != null && !customTitle.isBlank()) ? customTitle : "&6Thiết Kế: &e" + guiId;

        GuiDefinition existing = loadGui(guiId);
        DesignerHolder holder = new DesignerHolder(guiId, existing != null ? existing.title() : title, existing != null ? existing.rows() : rows);

        Inventory inv = Bukkit.createInventory(holder, 54, MessageUtil.color("&8[Designer] &e" + guiId));
        holder.setInventory(inv);

        if (existing != null) {
            holder.getItemConfigs().putAll(existing.items());
            for (Map.Entry<Integer, GuiItemConfig> entry : existing.items().entrySet()) {
                int slot = entry.getKey();
                if (slot >= 0 && slot < holder.getRows() * 9) {
                    GuiItemConfig cfg = entry.getValue();
                    try {
                        Material mat = Material.valueOf(cfg.material());
                        ItemStack is = ItemBuilder.of(mat, cfg.amount())
                                .name(cfg.displayName())
                                .lore(cfg.lore())
                                .build();
                        inv.setItem(slot, is);
                    } catch (Throwable ignored) {}
                }
            }
        }

        buildToolbar(inv);
        player.openInventory(inv);
        MessageUtil.send(player, "&a[GuiDesigner] &fĐang mở bàn thiết kế giao diện cho: &e" + guiId);
        MessageUtil.send(player, "&7Kéo thả vật phẩm tự do. Nhấn &eShift + Chuột Phải &7vào ô để cài đặt sự kiện click.");
    }

    private void buildToolbar(Inventory inv) {
        ItemStack divider = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name("&8---").build();
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, divider);
        }

        inv.setItem(45, ItemBuilder.of(Material.LIME_DYE)
                .name("&a&lLƯU CẤU HÌNH")
                .lore("&7Lưu toàn bộ bố cục vào tệp tin JSON.")
                .build());

        inv.setItem(46, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&6&lSINH MÃ JAVA")
                .lore("&7Tạo mã nguồn Java SmartGui trong thư mục projects/.")
                .build());

        inv.setItem(47, ItemBuilder.of(Material.ENDER_EYE)
                .name("&b&lXEM TRƯỚC (PREVIEW)")
                .lore("&7Mở menu thực tế để tương tác thử nghiệm ngay.")
                .build());

        inv.setItem(48, ItemBuilder.of(Material.BOOK)
                .name("&e&lHƯỚNG DẪN")
                .lore("&7- Kéo thả item vào các ô trên.", "&7- Shift+Chuột Phải để gán Lệnh/Âm thanh/Tin nhắn.")
                .build());

        inv.setItem(53, ItemBuilder.of(Material.BARRIER)
                .name("&c&lĐÓNG")
                .lore("&7Thoát khỏi trình thiết kế.")
                .build());
    }

    public void openActionEditor(Player player, DesignerHolder designerHolder, int slot) {
        ActionEditorHolder editorHolder = new ActionEditorHolder(designerHolder, slot);
        Inventory inv = Bukkit.createInventory(editorHolder, 27, MessageUtil.color("&8[Cài Đặt Ô " + slot + "]"));
        editorHolder.setInventory(inv);

        ItemStack currentItem = designerHolder.getInventory().getItem(slot);
        inv.setItem(4, currentItem != null ? currentItem.clone() : new ItemStack(Material.PAPER));

        GuiItemConfig cfg = designerHolder.getItemConfigs().get(slot);
        String currentAction = cfg != null ? (cfg.actionType() + " -> " + cfg.actionValue()) : "Chưa có";

        inv.setItem(10, ItemBuilder.of(Material.COMMAND_BLOCK)
                .name("&bLệnh Người Chơi")
                .lore("&7Người chơi tự thực thi lệnh.", "&eHiện tại: &f" + currentAction)
                .build());

        inv.setItem(11, ItemBuilder.of(Material.REPEATING_COMMAND_BLOCK)
                .name("&cLệnh Console")
                .lore("&7Máy chủ thực thi lệnh quyền cao nhất.", "&eHiện tại: &f" + currentAction)
                .build());

        inv.setItem(12, ItemBuilder.of(Material.WRITABLE_BOOK)
                .name("&aGửi Tin Nhắn")
                .lore("&7Gửi tin nhắn riêng cho người click.", "&eHiện tại: &f" + currentAction)
                .build());

        inv.setItem(13, ItemBuilder.of(Material.NOTE_BLOCK)
                .name("&ePhát Âm Thanh")
                .lore("&7Phát âm thanh Sound khi click.", "&eHiện tại: &f" + currentAction)
                .build());

        inv.setItem(14, ItemBuilder.of(Material.IRON_DOOR)
                .name("&7Đóng Giao Diện")
                .lore("&7Tự động đóng menu khi click.")
                .build());

        inv.setItem(16, ItemBuilder.of(Material.LAVA_BUCKET)
                .name("&4Xóa Hành Động")
                .lore("&7Xóa bỏ toàn bộ sự kiện của ô này.")
                .build());

        inv.setItem(22, ItemBuilder.of(Material.ARROW)
                .name("&fQuay Lại Bàn Thiết Kế")
                .build());

        player.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        InventoryHolder topHolder = event.getView().getTopInventory().getHolder();

        if (topHolder instanceof DesignerHolder designerHolder) {
            int rawSlot = event.getRawSlot();
            if (rawSlot >= 45 && rawSlot < 54) {
                event.setCancelled(true);
                handleToolbarClick(player, designerHolder, rawSlot);
                return;
            }

            if (rawSlot >= 0 && rawSlot < designerHolder.getRows() * 9) {
                if (event.getClick() == ClickType.SHIFT_RIGHT) {
                    event.setCancelled(true);
                    ItemStack clickedItem = event.getCurrentItem();
                    if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                        openActionEditor(player, designerHolder, rawSlot);
                    } else {
                        MessageUtil.send(player, "&cÔ này chưa có vật phẩm! Hãy đặt một vật phẩm vào trước.");
                    }
                }
            }
        } else if (topHolder instanceof ActionEditorHolder editorHolder) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            handleActionEditorClick(player, editorHolder, slot);
        }
    }

    private void handleToolbarClick(Player player, DesignerHolder holder, int slot) {
        switch (slot) {
            case 45 -> {
                GuiDefinition def = captureDefinitionFromHolder(holder);
                saveGui(def);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                MessageUtil.send(player, "&a[GuiDesigner] Đã lưu thành công giao diện '&e" + holder.getGuiId() + "&a' vào JSON!");
            }
            case 46 -> {
                GuiDefinition def = captureDefinitionFromHolder(holder);
                saveGui(def);
                File javaFile = generateJavaCode(def);
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                MessageUtil.send(player, "&6[GuiDesigner] &fĐã sinh mã nguồn Java thành công tại: &b" + javaFile.getName());
                MessageUtil.send(player, "&aBạn có thể biên dịch ngay bằng: &e/jplugin build " + def.id());
            }
            case 47 -> {
                GuiDefinition def = captureDefinitionFromHolder(holder);
                saveGui(def);
                player.closeInventory();
                openLiveGui(player, def.id());
            }
            case 53 -> {
                player.closeInventory();
            }
        }
    }

    private void handleActionEditorClick(Player player, ActionEditorHolder editorHolder, int slot) {
        DesignerHolder dHolder = editorHolder.getDesignerHolder();
        int targetSlot = editorHolder.getTargetSlot();

        switch (slot) {
            case 10 -> {
                player.closeInventory();
                pendingChatInputs.put(player.getUniqueId(), new ChatInputPrompt(player.getUniqueId(), dHolder, targetSlot, "COMMAND_PLAYER"));
                MessageUtil.send(player, "&b[GuiDesigner] Hãy nhập lệnh (không cần gạch chéo '/') trong khung chat (hoặc gõ 'cancel' để hủy):");
            }
            case 11 -> {
                player.closeInventory();
                pendingChatInputs.put(player.getUniqueId(), new ChatInputPrompt(player.getUniqueId(), dHolder, targetSlot, "COMMAND_CONSOLE"));
                MessageUtil.send(player, "&c[GuiDesigner] Hãy nhập lệnh Console (dùng %player% cho tên người click):");
            }
            case 12 -> {
                player.closeInventory();
                pendingChatInputs.put(player.getUniqueId(), new ChatInputPrompt(player.getUniqueId(), dHolder, targetSlot, "MESSAGE"));
                MessageUtil.send(player, "&a[GuiDesigner] Hãy nhập nội dung tin nhắn gửi cho người click:");
            }
            case 13 -> {
                player.closeInventory();
                pendingChatInputs.put(player.getUniqueId(), new ChatInputPrompt(player.getUniqueId(), dHolder, targetSlot, "SOUND"));
                MessageUtil.send(player, "&e[GuiDesigner] Hãy nhập tên âm thanh (Ví dụ: ENTITY_PLAYER_LEVELUP hoặc UI_BUTTON_CLICK):");
            }
            case 14 -> {
                setActionConfig(dHolder, targetSlot, "CLOSE", "");
                MessageUtil.send(player, "&7[GuiDesigner] Đã gán hành động Đóng Menu cho ô " + targetSlot);
                player.openInventory(dHolder.getInventory());
            }
            case 16 -> {
                dHolder.getItemConfigs().remove(targetSlot);
                MessageUtil.send(player, "&4[GuiDesigner] Đã xóa hành động của ô " + targetSlot);
                player.openInventory(dHolder.getInventory());
            }
            case 22 -> {
                player.openInventory(dHolder.getInventory());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ChatInputPrompt prompt = pendingChatInputs.remove(player.getUniqueId());
        if (prompt == null) return;

        event.setCancelled(true);
        String msg = event.getMessage().trim();

        if (msg.equalsIgnoreCase("cancel")) {
            MessageUtil.send(player, "&cĐã hủy nhập liệu.");
            Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(prompt.holder().getInventory()));
            return;
        }

        setActionConfig(prompt.holder(), prompt.slot(), prompt.actionType(), msg);
        MessageUtil.send(player, "&a[GuiDesigner] Đã cài đặt " + prompt.actionType() + ": &f" + msg);
        Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(prompt.holder().getInventory()));
    }

    private void setActionConfig(DesignerHolder holder, int slot, String actionType, String value) {
        ItemStack item = holder.getInventory().getItem(slot);
        if (item == null || item.getType() == Material.AIR) return;

        ItemMeta meta = item.getItemMeta();
        String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : item.getType().name();
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : List.of();

        GuiItemConfig cfg = new GuiItemConfig(slot, item.getType().name(), item.getAmount(), name, lore, actionType, value);
        holder.getItemConfigs().put(slot, cfg);
    }

    private GuiDefinition captureDefinitionFromHolder(DesignerHolder holder) {
        Map<Integer, GuiItemConfig> items = new HashMap<>();
        Inventory inv = holder.getInventory();

        for (int i = 0; i < holder.getRows() * 9; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                ItemMeta meta = item.getItemMeta();
                String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : item.getType().name();
                List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : List.of();

                GuiItemConfig existingCfg = holder.getItemConfigs().get(i);
                String actType = existingCfg != null ? existingCfg.actionType() : "NONE";
                String actVal = existingCfg != null ? existingCfg.actionValue() : "";

                items.put(i, new GuiItemConfig(i, item.getType().name(), item.getAmount(), name, lore, actType, actVal));
            }
        }
        return new GuiDefinition(holder.getGuiId(), holder.getTitle(), holder.getRows(), items);
    }

    public synchronized void saveGui(GuiDefinition def) {
        File file = new File(guisDir, def.id() + ".gui.json");
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"id\": \"").append(escapeJson(def.id())).append("\",\n");
        sb.append("  \"title\": \"").append(escapeJson(def.title())).append("\",\n");
        sb.append("  \"rows\": ").append(def.rows()).append(",\n");
        sb.append("  \"items\": [\n");

        List<GuiItemConfig> itemList = new ArrayList<>(def.items().values());
        for (int i = 0; i < itemList.size(); i++) {
            GuiItemConfig it = itemList.get(i);
            sb.append("    {\n");
            sb.append("      \"slot\": ").append(it.slot()).append(",\n");
            sb.append("      \"material\": \"").append(escapeJson(it.material())).append("\",\n");
            sb.append("      \"amount\": ").append(it.amount()).append(",\n");
            sb.append("      \"displayName\": \"").append(escapeJson(it.displayName())).append("\",\n");
            sb.append("      \"lore\": [");
            if (it.lore() != null) {
                for (int j = 0; j < it.lore().size(); j++) {
                    sb.append("\"").append(escapeJson(it.lore().get(j))).append("\"");
                    if (j < it.lore().size() - 1) sb.append(", ");
                }
            }
            sb.append("],\n");
            sb.append("      \"actionType\": \"").append(escapeJson(it.actionType())).append("\",\n");
            sb.append("      \"actionValue\": \"").append(escapeJson(it.actionValue())).append("\"\n");
            sb.append("    }").append(i < itemList.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        try {
            Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("[GuiDesigner] Không thể lưu GUI " + def.id() + ": " + e.getMessage());
        }
    }

    public synchronized GuiDefinition loadGui(String id) {
        File file = new File(guisDir, id + ".gui.json");
        if (!file.exists()) {
            return null;
        }

        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String title = extractString(json, "\"title\"\\s*:\\s*\"([^\"]*)\"");
            int rows = extractInt(json, "\"rows\"\\s*:\\s*(\\d+)");
            if (rows <= 0) rows = 4;

            Map<Integer, GuiItemConfig> items = new HashMap<>();
            String[] itemBlocks = json.split("\\{\\s*\"slot\"");
            for (int i = 1; i < itemBlocks.length; i++) {
                String block = "{\"slot\"" + itemBlocks[i];
                int endIdx = block.indexOf('}');
                if (endIdx != -1) {
                    block = block.substring(0, endIdx + 1);
                }
                int slot = extractInt(block, "\"slot\"\\s*:\\s*(\\d+)");
                String mat = extractString(block, "\"material\"\\s*:\\s*\"([^\"]*)\"");
                int amt = extractInt(block, "\"amount\"\\s*:\\s*(\\d+)");
                if (amt <= 0) amt = 1;
                String dName = extractString(block, "\"displayName\"\\s*:\\s*\"([^\"]*)\"");
                String actType = extractString(block, "\"actionType\"\\s*:\\s*\"([^\"]*)\"");
                String actVal = extractString(block, "\"actionValue\"\\s*:\\s*\"([^\"]*)\"");

                List<String> lore = new ArrayList<>();
                int loreStart = block.indexOf("\"lore\"");
                if (loreStart != -1) {
                    int arrOpen = block.indexOf('[', loreStart);
                    int arrClose = block.indexOf(']', arrOpen);
                    if (arrOpen != -1 && arrClose != -1) {
                        String rawArr = block.substring(arrOpen + 1, arrClose);
                        for (String l : rawArr.split("\",")) {
                            String cleaned = l.replace("\"", "").trim();
                            if (!cleaned.isEmpty()) lore.add(cleaned);
                        }
                    }
                }
                items.put(slot, new GuiItemConfig(slot, mat != null ? mat : "STONE", amt, dName != null ? dName : "", lore, actType != null ? actType : "NONE", actVal != null ? actVal : ""));
            }

            return new GuiDefinition(id, title != null ? title : id, rows, items);
        } catch (Throwable t) {
            plugin.getLogger().warning("[GuiDesigner] Lỗi đọc GUI " + id + ": " + t.getMessage());
            return null;
        }
    }

    public List<String> listGuiIds() {
        List<String> res = new ArrayList<>();
        File[] files = guisDir.listFiles((dir, name) -> name.endsWith(".gui.json"));
        if (files != null) {
            for (File f : files) {
                res.add(f.getName().replace(".gui.json", ""));
            }
        }
        return res;
    }

    public boolean deleteGui(String id) {
        File file = new File(guisDir, id + ".gui.json");
        return file.exists() && file.delete();
    }

    public void openLiveGui(Player player, String id) {
        GuiDefinition def = loadGui(id);
        if (def == null) {
            MessageUtil.send(player, "&cKhông tìm thấy GUI có ID: " + id);
            return;
        }

        SmartGui gui = SmartGui.of(def.title(), def.rows());
        for (GuiItemConfig cfg : def.items().values()) {
            try {
                Material mat = Material.valueOf(cfg.material());
                ItemStack is = ItemBuilder.of(mat, cfg.amount())
                        .name(cfg.displayName())
                        .lore(cfg.lore())
                        .build();

                gui.setButton(cfg.slot(), is, (p, event) -> {
                    executeAction(p, cfg.actionType(), cfg.actionValue());
                });
            } catch (Throwable ignored) {}
        }
        gui.open(player);
    }

    private void executeAction(Player player, String actionType, String actionValue) {
        if (actionType == null || actionType.equalsIgnoreCase("NONE")) return;

        switch (actionType.toUpperCase(Locale.ROOT)) {
            case "COMMAND_PLAYER" -> player.performCommand(actionValue);
            case "COMMAND_CONSOLE" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), actionValue.replace("%player%", player.getName()));
            case "MESSAGE" -> MessageUtil.send(player, actionValue);
            case "SOUND" -> {
                try {
                    Sound sound = Sound.valueOf(actionValue.toUpperCase(Locale.ROOT));
                    player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
                } catch (Throwable ignored) {}
            }
            case "CLOSE" -> player.closeInventory();
        }
    }

    public File generateJavaCode(GuiDefinition def) {
        File projectsDir = new File(plugin.getDataFolder(), "projects");
        if (!projectsDir.exists()) {
            projectsDir.mkdirs();
        }

        String className = toPascalCase(def.id()) + "Gui";
        File javaFile = new File(projectsDir, className + ".java");

        StringBuilder sb = new StringBuilder();
        sb.append("package com.javastudio.projects;\n\n");
        sb.append("import com.javastudio.api.gui.SmartGui;\n");
        sb.append("import com.javastudio.api.gui.GuiButton;\n");
        sb.append("import com.javastudio.api.item.ItemBuilder;\n");
        sb.append("import com.javastudio.utils.MessageUtil;\n");
        sb.append("import org.bukkit.Bukkit;\n");
        sb.append("import org.bukkit.Material;\n");
        sb.append("import org.bukkit.Sound;\n");
        sb.append("import org.bukkit.entity.Player;\n\n");
        sb.append("public class ").append(className).append(" {\n\n");
        sb.append("    public static void open(Player player) {\n");
        sb.append("        SmartGui gui = SmartGui.of(\"").append(escapeJava(def.title())).append("\", ").append(def.rows()).append(");\n\n");

        for (GuiItemConfig cfg : def.items().values()) {
            sb.append("        gui.setButton(").append(cfg.slot()).append(", ItemBuilder.of(Material.").append(cfg.material()).append(", ").append(cfg.amount()).append(")\n");
            sb.append("                .name(\"").append(escapeJava(cfg.displayName())).append("\")\n");
            if (cfg.lore() != null && !cfg.lore().isEmpty()) {
                sb.append("                .lore(");
                for (int i = 0; i < cfg.lore().size(); i++) {
                    sb.append("\"").append(escapeJava(cfg.lore().get(i))).append("\"");
                    if (i < cfg.lore().size() - 1) sb.append(", ");
                }
                sb.append(")\n");
            }
            sb.append("                .build(), (p, event) -> {\n");

            if ("COMMAND_PLAYER".equalsIgnoreCase(cfg.actionType())) {
                sb.append("            p.performCommand(\"").append(escapeJava(cfg.actionValue())).append("\");\n");
            } else if ("COMMAND_CONSOLE".equalsIgnoreCase(cfg.actionType())) {
                sb.append("            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), \"").append(escapeJava(cfg.actionValue())).append("\".replace(\"%player%\", p.getName()));\n");
            } else if ("MESSAGE".equalsIgnoreCase(cfg.actionType())) {
                sb.append("            MessageUtil.send(p, \"").append(escapeJava(cfg.actionValue())).append("\");\n");
            } else if ("SOUND".equalsIgnoreCase(cfg.actionType())) {
                sb.append("            try {\n");
                sb.append("                p.playSound(p.getLocation(), Sound.").append(cfg.actionValue().toUpperCase(Locale.ROOT)).append(", 1.0f, 1.0f);\n");
                sb.append("            } catch (Throwable ignored) {}\n");
            } else if ("CLOSE".equalsIgnoreCase(cfg.actionType())) {
                sb.append("            p.closeInventory();\n");
            }

            sb.append("        });\n\n");
        }

        sb.append("        gui.open(player);\n");
        sb.append("    }\n");
        sb.append("}\n");

        try {
            Files.writeString(javaFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("[GuiDesigner] Không thể tạo tệp Java " + javaFile.getName() + ": " + e.getMessage());
        }

        return javaFile;
    }

    private String toPascalCase(String s) {
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (c == '_' || c == '-' || c == ' ') {
                cap = true;
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String escapeJava(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String extractString(String text, String regex) {
        var m = java.util.regex.Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private int extractInt(String text, String regex) {
        var m = java.util.regex.Pattern.compile(regex).matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}

