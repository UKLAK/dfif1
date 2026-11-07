package com.javastudio.api;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.item.ComponentItemHelper;
import com.javastudio.api.item.ItemBuilder;
import com.javastudio.control.PluginController;
import com.javastudio.control.ServerController;
import com.javastudio.utils.MessageUtil;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("deprecation")
public abstract class QuickScript implements DynamicScript {

    public JavaContext ctx;
    public CommandSender sender;
    public Player player;
    public Player p;
    public Server server;
    public World world;
    public World w;
    public Location location;
    public Location loc;
    public JavaStudioPlugin plugin;
    public PluginController pluginCtrl;
    public ServerController serverCtrl;
    public com.javastudio.display.DisplayEngine displayEngine;

    @Override
    public final Object execute(JavaContext context) throws Throwable {
        this.ctx = context;
        this.sender = context.getSender();
        this.player = context.getPlayer();
        this.p = this.player;
        this.server = context.getServer();
        this.world = context.getWorld();
        this.w = this.world;
        this.location = context.getLocation();
        this.loc = this.location;
        this.plugin = context.getPlugin();
        this.pluginCtrl = plugin.getPluginController();
        this.serverCtrl = plugin.getServerController();
        this.displayEngine = plugin.getDisplayEngine();

        return run();
    }

    public abstract Object run() throws Throwable;


    public String heal() {
        return heal(player);
    }

    public String heal(Player target) {
        if (target == null) return "Không tìm thấy người chơi!";
        double maxHealth = 20.0;
        try {
            maxHealth = target.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        } catch (Throwable ignored) {}
        target.setHealth(maxHealth);
        target.setFoodLevel(20);
        target.setSaturation(20f);
        target.setFireTicks(0);
        clearEffects(target);
        target.sendMessage("§a✔ Bạn đã được hồi phục hoàn toàn máu và thức ăn!");
        return "Đã hồi phục hoàn toàn máu và thức ăn cho " + target.getName();
    }

    public String feed() {
        if (player != null) {
            player.setFoodLevel(20);
            player.setSaturation(20f);
            player.sendMessage("§a✔ Đã làm đầy thanh thức ăn!");
            return "Đã làm đầy thanh thức ăn (20 food level)";
        }
        return "Không có người chơi!";
    }

    public boolean god() {
        if (player == null) return false;
        boolean newState = !player.isInvulnerable();
        player.setInvulnerable(newState);
        player.sendMessage(newState ? "§a✔ Chế độ bất tử: BẬT" : "§c✖ Chế độ bất tử: TẮT");
        return newState;
    }

    public boolean fly() {
        if (player == null) return false;
        boolean newState = !player.getAllowFlight();
        player.setAllowFlight(newState);
        player.setFlying(newState);
        player.sendMessage(newState ? "§a✔ Chế độ bay: BẬT" : "§c✖ Chế độ bay: TẮT");
        return newState;
    }

    public GameMode gm(Object mode) {
        if (player == null || mode == null) return null;
        GameMode gm = parseGameMode(mode);
        if (gm != null) {
            player.setGameMode(gm);
            player.sendMessage("§a✔ Chế độ chơi đã đổi thành: §e" + gm.name());
            return gm;
        }
        return null;
    }

    public float speed(float speedVal) {
        if (player == null) return 0.2f;
        float clamped = Math.max(0.0f, Math.min(1.0f, speedVal));
        player.setWalkSpeed(clamped);
        player.setFlySpeed(clamped);
        player.sendMessage("§a✔ Tốc độ di chuyển đã đặt thành: §e" + clamped);
        return clamped;
    }

    public String clearInv() {
        if (player != null) {
            player.getInventory().clear();
            player.sendMessage("§e✔ Đã xóa sạch túi đồ!");
            return "Đã xóa sạch túi đồ của " + player.getName();
        }
        return "Không có người chơi!";
    }

    public ItemStack give(Object itemOrMaterial, int amount) {
        return give(player, itemOrMaterial, amount);
    }

    public ItemStack give(Player target, Object itemOrMaterial, int amount) {
        if (target == null || itemOrMaterial == null) return null;
        ItemStack stack = resolveItemStack(itemOrMaterial, amount);
        if (stack != null) {
            target.getInventory().addItem(stack);
            target.sendMessage("§a✔ Nhận được §e" + stack.getAmount() + "x " + stack.getType().name());
            return stack;
        }
        return null;
    }

    public ItemStack item(String materialName, int amount, String displayName, String... lore) {
        Material mat = parseMaterial(materialName);
        if (mat == null) mat = Material.STONE;
        ItemStack is = new ItemStack(mat, Math.max(1, amount));
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            if (displayName != null) {
                meta.setDisplayName(MessageUtil.colorize(displayName));
            }
            if (lore != null && lore.length > 0) {
                List<String> coloredLore = Arrays.stream(lore).map(MessageUtil::colorize).collect(Collectors.toList());
                meta.setLore(coloredLore);
            }
            is.setItemMeta(meta);
        }
        return is;
    }

    public ItemBuilder itemBuilder(String materialName) {
        Material mat = parseMaterial(materialName);
        return ItemBuilder.of(mat != null ? mat : Material.STONE);
    }

    public ItemBuilder itemBuilder(Material material) {
        return ItemBuilder.of(material);
    }

    public ItemStack setMaxStack(ItemStack item, int maxStack) {
        ComponentItemHelper.setMaxStackSize(item, maxStack);
        return item;
    }

    public ItemStack setRarity(ItemStack item, String rarity) {
        ComponentItemHelper.setRarity(item, rarity);
        return item;
    }

    public ItemStack setFireResistant(ItemStack item, boolean fireResistant) {
        ComponentItemHelper.setFireResistant(item, fireResistant);
        return item;
    }

    public ItemStack setGlint(ItemStack item, boolean glint) {
        ComponentItemHelper.setGlintOverride(item, glint);
        return item;
    }

    public int exp(int levels) {
        if (player != null) {
            player.giveExpLevels(levels);
            player.sendMessage("§a✔ Đã nhận được §e" + levels + " §acấp độ kinh nghiệm!");
            return levels;
        }
        return 0;
    }

    public String title(String titleText, String subtitleText) {
        return title(titleText, subtitleText, 10, 50, 10);
    }

    public String title(String titleText, String subtitleText, int fadeIn, int stay, int fadeOut) {
        if (player != null) {
            player.sendTitle(MessageUtil.colorize(titleText), MessageUtil.colorize(subtitleText), fadeIn, stay, fadeOut);
            return "Đã gửi title: " + titleText + " / " + subtitleText;
        }
        return "Không có người chơi!";
    }

    public String actionbar(String message) {
        if (player != null && message != null) {
            try {
                if (com.javastudio.utils.PlatformUtil.isAdventureSupported()) {
                    MessageUtil.sendRaw(player, message);
                } else {
                    player.sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(MessageUtil.colorize(message)));
                }
            } catch (Throwable t) {
                player.sendMessage(MessageUtil.colorize(message));
            }
            return "Actionbar: " + message;
        }
        return "Không có người chơi!";
    }

    public String msg(String message) {
        if (sender != null) {
            MessageUtil.send(sender, message);
            return message;
        }
        return null;
    }

    public String broadcast(String message) {
        for (Player pl : Bukkit.getOnlinePlayers()) {
            MessageUtil.sendRaw(pl, message);
        }
        Bukkit.getConsoleSender().sendMessage(MessageUtil.colorize(message));
        return "Broadcast: " + message;
    }

    public Sound sound(Object soundObj) {
        return sound(soundObj, 1.0f, 1.0f);
    }

    public Sound sound(Object soundObj, float volume, float pitch) {
        if (player == null || soundObj == null) return null;
        Sound s = parseSound(soundObj);
        if (s != null) {
            player.playSound(player.getLocation(), s, volume, pitch);
            return s;
        }
        return null;
    }

    public Sound soundAll(Object soundObj) {
        Sound s = parseSound(soundObj);
        if (s != null) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                pl.playSound(pl.getLocation(), s, 1.0f, 1.0f);
            }
            return s;
        }
        return null;
    }

    public Particle particle(Object particleObj, int count) {
        return particle(particleObj, location != null ? location : new Location(world, 0, 70, 0), count);
    }

    public Particle particle(Object particleObj, Location targetLoc, int count) {
        if (world == null || particleObj == null || targetLoc == null) return null;
        Particle pType = parseParticle(particleObj);
        if (pType != null) {
            world.spawnParticle(pType, targetLoc, Math.max(1, count), 0.5, 0.5, 0.5, 0.05);
            return pType;
        }
        return null;
    }

    public PotionEffectType effect(String effectName, int seconds, int amplifier) {
        return effect(player, effectName, seconds, amplifier);
    }

    public PotionEffectType effect(LivingEntity entity, String effectName, int seconds, int amplifier) {
        if (entity == null || effectName == null) return null;
        PotionEffectType type = parsePotionEffect(effectName);
        if (type != null) {
            entity.addPotionEffect(new PotionEffect(type, seconds * 20, Math.max(0, amplifier - 1)));
            if (entity instanceof Player pl) {
                pl.sendMessage("§a✔ Đã nhận hiệu ứng: §e" + type.getName() + " (" + seconds + "s, Cấp " + amplifier + ")");
            }
            return type;
        }
        return null;
    }

    public int clearEffects() {
        return clearEffects(player);
    }

    public int clearEffects(LivingEntity entity) {
        if (entity == null) return 0;
        int count = entity.getActivePotionEffects().size();
        for (PotionEffect pe : entity.getActivePotionEffects()) {
            entity.removePotionEffect(pe.getType());
        }
        return count;
    }


    public Location tp(double x, double y, double z) {
        if (player != null && world != null) {
            Location target = new Location(world, x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());
            player.teleport(target);
            player.sendMessage("§a✔ Dịch chuyển tới: §e" + x + ", " + y + ", " + z);
            return target;
        }
        return null;
    }

    public Location tp(String worldName, double x, double y, double z) {
        World tw = Bukkit.getWorld(worldName);
        if (tw != null && player != null) {
            Location target = new Location(tw, x, y, z);
            player.teleport(target);
            player.sendMessage("§a✔ Dịch chuyển tới thế giới §e" + worldName + "§a tại §e" + x + ", " + y + ", " + z);
            return target;
        }
        return null;
    }

    public Location tp(String targetPlayerName) {
        Player target = Bukkit.getPlayer(targetPlayerName);
        if (target != null && player != null) {
            player.teleport(target.getLocation());
            player.sendMessage("§a✔ Đã dịch chuyển tới người chơi: §e" + target.getName());
            return target.getLocation();
        }
        return null;
    }

    public Location tp(Location targetLoc) {
        if (player != null && targetLoc != null) {
            player.teleport(targetLoc);
            return targetLoc;
        }
        return null;
    }

    public Location top() {
        if (player != null && world != null) {
            Location curr = player.getLocation();
            int topY = world.getHighestBlockYAt(curr.getBlockX(), curr.getBlockZ()) + 1;
            Location topLoc = new Location(world, curr.getX(), topY, curr.getZ(), curr.getYaw(), curr.getPitch());
            player.teleport(topLoc);
            player.sendMessage("§a✔ Đã dịch chuyển lên đỉnh bề mặt cao nhất (Y=" + topY + ")!");
            return topLoc;
        }
        return null;
    }


    public String time(long ticks) {
        if (world != null) {
            world.setTime(ticks);
            msg("§a✔ Thời gian thế giới đã đổi thành: §e" + ticks + " ticks");
            return "Thời gian: " + ticks;
        }
        return null;
    }

    public String day() {
        return time(1000);
    }

    public String noon() {
        return time(6000);
    }

    public String night() {
        return time(13000);
    }

    public String midnight() {
        return time(18000);
    }

    public String sun() {
        if (world != null) {
            world.setStorm(false);
            world.setThundering(false);
            msg("§a✔ Thời tiết đã chuyển sang: §eNắng ráo (Clear)");
            return "Thời tiết: Nắng ráo (Clear)";
        }
        return null;
    }

    public String storm() {
        if (world != null) {
            world.setStorm(true);
            world.setThundering(true);
            msg("§a✔ Thời tiết đã chuyển sang: §eBão sấm sét (Storm)");
            return "Thời tiết: Bão sấm sét (Storm)";
        }
        return null;
    }

    public Location lightning() {
        Location strikeLoc = targetLocation();
        if (strikeLoc == null && location != null) {
            strikeLoc = location;
        }
        if (strikeLoc != null && strikeLoc.getWorld() != null) {
            strikeLoc.getWorld().strikeLightning(strikeLoc);
            return strikeLoc;
        }
        return null;
    }

    public Location strike() {
        return lightning();
    }

    public Location lightning(Location targetLoc) {
        if (targetLoc != null && targetLoc.getWorld() != null) {
            targetLoc.getWorld().strikeLightning(targetLoc);
            return targetLoc;
        }
        return null;
    }

    public Location strike(Location targetLoc) {
        return lightning(targetLoc);
    }

    public Block targetBlock() {
        if (player == null) return null;
        try {
            return player.getTargetBlockExact(100);
        } catch (Throwable t) {
            return player.getTargetBlock(null, 100);
        }
    }

    public Location targetLocation() {
        Block b = targetBlock();
        return b != null ? b.getLocation() : null;
    }

    public Entity targetEntity() {
        if (player == null) return null;
        RayTraceResult r = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                100,
                e -> !e.equals(player)
        );
        return r != null ? r.getHitEntity() : null;
    }

    public Block setBlock(Object materialObj) {
        Block b = targetBlock();
        if (b != null) {
            Material m = parseMaterial(materialObj);
            if (m != null && m.isBlock()) {
                b.setType(m);
                return b;
            }
        }
        return null;
    }

    public Block setBlock(int x, int y, int z, Object materialObj) {
        if (world != null) {
            Material m = parseMaterial(materialObj);
            if (m != null && m.isBlock()) {
                Block b = world.getBlockAt(x, y, z);
                b.setType(m);
                return b;
            }
        }
        return null;
    }


    public Entity spawn(Object entityTypeObj) {
        return spawn(entityTypeObj, 1);
    }

    public Entity spawn(Object entityTypeObj, int count) {
        Location spawnLoc = targetLocation();
        if (spawnLoc == null && location != null) {
            spawnLoc = location;
        }
        if (spawnLoc == null) return null;

        EntityType type = parseEntityType(entityTypeObj);
        if (type == null) return null;

        Entity last = null;
        for (int i = 0; i < Math.max(1, count); i++) {
            last = spawnLoc.getWorld().spawnEntity(spawnLoc, type);
        }
        msg("§a✔ Đã spawn §e" + count + "x " + type.name());
        return last;
    }

    public List<Entity> nearby(double radius) {
        if (location == null || world == null) return Collections.emptyList();
        return new ArrayList<>(world.getNearbyEntities(location, radius, radius, radius));
    }

    public List<Player> nearbyPlayers(double radius) {
        return nearby(radius).stream().filter(e -> e instanceof Player).map(e -> (Player) e).collect(Collectors.toList());
    }

    public List<Monster> nearbyMonsters(double radius) {
        return nearby(radius).stream().filter(e -> e instanceof Monster).map(e -> (Monster) e).collect(Collectors.toList());
    }

    public int killNearby(double radius) {
        List<Monster> monsters = nearbyMonsters(radius);
        for (Monster m : monsters) {
            m.remove();
        }
        msg("§a✔ Đã dọn sạch §e" + monsters.size() + " §aquái vật xung quanh!");
        return monsters.size();
    }

    public double damage(double amount) {
        if (player != null) {
            player.damage(amount);
            return amount;
        }
        return 0;
    }

    public double damage(Damageable target, double amount) {
        if (target != null) {
            target.damage(amount, player);
            return amount;
        }
        return 0;
    }

    public boolean kill(Damageable target) {
        if (target != null) {
            target.setHealth(0);
            return true;
        }
        return false;
    }


    public boolean cmd(String commandLine) {
        return serverCtrl.executeConsoleCommand(commandLine);
    }

    public String sudo(Player target, String text) {
        if (target == null || text == null) return null;
        if (text.startsWith("/")) {
            target.performCommand(text.substring(1));
        } else {
            target.chat(text);
        }
        return "Sudo " + target.getName() + ": " + text;
    }

    public Collection<? extends Player> allPlayers() {
        return Bukkit.getOnlinePlayers();
    }

    public Player p(String name) {
        return Bukkit.getPlayer(name);
    }

    public World w(String name) {
        return Bukkit.getWorld(name);
    }

    public String kick(Player target, String reason) {
        if (target != null) {
            target.kickPlayer(MessageUtil.colorize(reason != null ? reason : "Bạn đã bị kick khỏi server!"));
            return "Đã kick " + target.getName();
        }
        return null;
    }


    private Material parseMaterial(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Material m) return m;
        String str = String.valueOf(obj).trim().toUpperCase().replace(" ", "_").replace("MINECRAFT:", "");
        Material direct = Material.matchMaterial(str);
        if (direct != null) return direct;
        try {
            return Material.valueOf(str);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ItemStack resolveItemStack(Object obj, int amount) {
        if (obj instanceof ItemStack is) {
            ItemStack copy = is.clone();
            if (amount > 0) copy.setAmount(amount);
            return copy;
        }
        Material mat = parseMaterial(obj);
        if (mat != null) {
            return new ItemStack(mat, Math.max(1, amount));
        }
        return null;
    }

    private Sound parseSound(Object obj) {
        if (obj instanceof Sound s) return s;
        String name = String.valueOf(obj).toLowerCase().replace(" ", "_");
        return switch (name) {
            case "levelup", "level_up" -> Sound.ENTITY_PLAYER_LEVELUP;
            case "pling", "note" -> Sound.BLOCK_NOTE_BLOCK_PLING;
            case "click" -> Sound.UI_BUTTON_CLICK;
            case "explode", "explosion" -> Sound.ENTITY_GENERIC_EXPLODE;
            case "pop", "pickup" -> Sound.ENTITY_ITEM_PICKUP;
            case "orb", "exp" -> Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
            case "bell" -> Sound.BLOCK_BELL_USE;
            case "anvil" -> Sound.BLOCK_ANVIL_USE;
            case "totem" -> Sound.ITEM_TOTEM_USE;
            default -> {
                try {
                    yield Sound.valueOf(name.toUpperCase());
                } catch (Throwable t) {
                    yield null;
                }
            }
        };
    }

    private Particle parseParticle(Object obj) {
        if (obj instanceof Particle p) return p;
        String name = String.valueOf(obj).trim().toLowerCase().replace(" ", "_").replace("minecraft:", "");

        try {
            return Particle.valueOf(name.toUpperCase());
        } catch (Throwable ignored) {}

        String mapped = switch (name) {
            case "flame", "fire" -> "FLAME";
            case "heart", "love" -> "HEART";
            case "smoke" -> "SMOKE";
            case "explosion", "boom" -> "EXPLOSION";
            case "portal" -> "PORTAL";
            case "firework" -> "FIREWORK";
            case "cloud" -> "CLOUD";
            default -> name.toUpperCase();
        };

        try {
            return Particle.valueOf(mapped);
        } catch (Throwable ignored) {}

        try {
            return Particle.valueOf(mapped + "_NORMAL");
        } catch (Throwable ignored) {}
        try {
            return Particle.valueOf(mapped + "S_SPARK");
        } catch (Throwable ignored) {}

        return null;
    }

    private EntityType parseEntityType(Object obj) {
        if (obj instanceof EntityType et) return et;
        String name = String.valueOf(obj).trim().toUpperCase().replace(" ", "_").replace("MINECRAFT:", "");
        if (name.equals("LIGHTNING") || name.equals("SET") || name.equals("LIGHTNING_BOLT")) {
            try {
                return EntityType.valueOf("LIGHTNING_BOLT");
            } catch (Throwable ignored) {
                try {
                    return EntityType.valueOf("LIGHTNING");
                } catch (Throwable ignored2) {}
            }
        }
        try {
            return EntityType.valueOf(name);
        } catch (Throwable t) {
            try {
                return EntityType.fromName(name.toLowerCase());
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private PotionEffectType parsePotionEffect(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase().replace(" ", "_").replace("minecraft:", "");
        String canonical = switch (n) {
            case "speed" -> "speed";
            case "slow", "slowness" -> "slowness";
            case "haste" -> "haste";
            case "strength" -> "strength";
            case "jump", "jump_boost" -> "jump_boost";
            case "regen", "regeneration" -> "regeneration";
            case "invis", "invisibility" -> "invisibility";
            case "glowing", "glow" -> "glowing";
            case "night_vision", "nightvision" -> "night_vision";
            case "fire_res", "fire_resistance" -> "fire_resistance";
            case "water_breathing" -> "water_breathing";
            case "levitation" -> "levitation";
            case "blindness" -> "blindness";
            case "hunger" -> "hunger";
            case "weakness" -> "weakness";
            case "poison" -> "poison";
            case "wither" -> "wither";
            case "health_boost" -> "health_boost";
            case "absorption" -> "absorption";
            case "saturation" -> "saturation";
            case "slow_falling" -> "slow_falling";
            case "resistance" -> "resistance";
            default -> n;
        };

        try {
            PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(canonical));
            if (type != null) return type;
        } catch (Throwable ignored) {}

        try {
            PotionEffectType type = PotionEffectType.getByName(canonical);
            if (type != null) return type;
        } catch (Throwable ignored) {}

        try {
            return PotionEffectType.getByName(canonical.toUpperCase());
        } catch (Throwable ignored) {}

        return null;
    }

    private GameMode parseGameMode(Object mode) {
        if (mode instanceof GameMode gm) return gm;
        if (mode instanceof Number num) {
            return switch (num.intValue()) {
                case 0 -> GameMode.SURVIVAL;
                case 1 -> GameMode.CREATIVE;
                case 2 -> GameMode.ADVENTURE;
                case 3 -> GameMode.SPECTATOR;
                default -> null;
            };
        }
        String str = String.valueOf(mode).trim().toLowerCase();
        return switch (str) {
            case "0", "s", "survival" -> GameMode.SURVIVAL;
            case "1", "c", "creative" -> GameMode.CREATIVE;
            case "3", "sp", "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
    }


    public List<Entity> holo(String... lines) {
        return holo(location != null ? location : (player != null ? player.getLocation() : null), lines);
    }

    public List<Entity> holo(Location loc, String... lines) {
        if (loc == null || displayEngine == null) return Collections.emptyList();
        return displayEngine.createHologram(loc, lines);
    }

    public int clearHolo(double radius) {
        if (location == null || displayEngine == null) return 0;
        return displayEngine.clearHologramsNear(location, radius);
    }

    public String scoreboard(String title, String... lines) {
        if (player == null || displayEngine == null) return "Chỉ người chơi mới có thể hiển thị Scoreboard!";
        displayEngine.setScoreboard(player, title, lines);
        return "Đã cập nhật Scoreboard!";
    }

    public String clearScoreboard() {
        if (player == null || displayEngine == null) return "Chỉ người chơi mới có thể xóa Scoreboard!";
        displayEngine.clearScoreboard(player);
        return "Đã xóa Scoreboard!";
    }

    public String bossbar(String title, double progress) {
        return bossbar(title, progress, "PURPLE", "SOLID");
    }

    public String bossbar(String title, double progress, String color, String style) {
        if (player == null || displayEngine == null) return "Chỉ người chơi mới có thể hiển thị BossBar!";
        displayEngine.showBossBar(player, title, progress, color, style);
        return "Đã hiển thị BossBar!";
    }

    public String clearBossbar() {
        if (player == null || displayEngine == null) return "Chỉ người chơi mới có thể xóa BossBar!";
        displayEngine.removeBossBar(player);
        return "Đã xóa BossBar!";
    }


    public World createWorld(String name, String env) {
        World.Environment e = World.Environment.NORMAL;
        try { e = World.Environment.valueOf(env.toUpperCase()); } catch (Exception ignored) {}
        return Bukkit.createWorld(new org.bukkit.WorldCreator(name).environment(e));
    }

    public World loadWorld(String name) {
        return Bukkit.createWorld(new org.bukkit.WorldCreator(name));
    }

    public boolean unloadWorld(String name) {
        return Bukkit.unloadWorld(name, true);
    }

    public boolean setWorldPvp(String worldName, boolean pvp) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return false;
        w.setPVP(pvp);
        return true;
    }

    public boolean gamerule(String worldName, String rule, String value) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return false;
        return w.setGameRuleValue(rule, value);
    }


    public com.javastudio.api.gui.SmartGui gui(String title, int rows) {
        return com.javastudio.api.gui.SmartGui.of(title, rows);
    }

    public com.javastudio.api.item.ItemBuilder item(Material material) {
        return com.javastudio.api.item.ItemBuilder.of(material);
    }

    public com.javastudio.api.item.ItemBuilder item(ItemStack item) {
        return com.javastudio.api.item.ItemBuilder.of(item);
    }

    public boolean ecoDeposit(double amount) {
        return player != null && com.javastudio.utils.VaultBridge.deposit(player, amount);
    }

    public boolean ecoWithdraw(double amount) {
        return player != null && com.javastudio.utils.VaultBridge.withdraw(player, amount);
    }

    public double ecoBalance() {
        return player != null ? com.javastudio.utils.VaultBridge.getBalance(player) : 0.0;
    }

    public com.javastudio.pipeline.FastBlockEngine fastBlock() {
        return plugin != null ? plugin.getFastBlock() : null;
    }

    public com.javastudio.anticheat.AntiCheatManager antiCheat() {
        return plugin != null ? plugin.getAntiCheatManager() : null;
    }

    public com.javastudio.security.cve.PanelCveProtectionSuite cveProtection() {
        return plugin != null ? plugin.getPanelCveProtection() : null;
    }

    public com.javastudio.network.NettyPacketEngine netty() {
        return plugin != null ? plugin.getNettyPacketEngine() : null;
    }

    public com.javastudio.ai.AiAssistant ai() {
        return plugin != null ? plugin.getAiAssistant() : null;
    }

    public com.javastudio.bot.FakePlayerManager bots() {
        return plugin != null ? plugin.getFakePlayerManager() : null;
    }

    public com.javastudio.pipeline.CircuitBreakerEngine breaker() {
        return plugin != null ? plugin.getCircuitBreakerEngine() : null;
    }

    public com.javastudio.pipeline.VisualFlowEngine flow() {
        return plugin != null ? plugin.getVisualFlowEngine() : null;
    }

    public com.javastudio.network.ClusterMeshEngine mesh() {
        return plugin != null ? plugin.getClusterMeshEngine() : null;
    }
}

