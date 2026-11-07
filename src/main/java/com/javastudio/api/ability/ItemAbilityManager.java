package com.javastudio.api.ability;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemAbilityManager implements Listener {

    private final JavaStudioPlugin plugin;
    private final NamespacedKey abilityKey;
    private final Map<String, ItemAbility> registeredAbilities = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldownMap = new ConcurrentHashMap<>();

    public ItemAbilityManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.abilityKey = new NamespacedKey(plugin, "item_abilities");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void registerAbility(ItemAbility ability) {
        if (ability != null) {
            registeredAbilities.put(ability.getId().toLowerCase(), ability);
            plugin.getLogger().info("[AbilityManager] Đã đăng ký kỹ năng vật phẩm: " + ability.getName() + " (" + ability.getId() + ")");
        }
    }

    public void unregisterAbility(String id) {
        if (id != null) {
            registeredAbilities.remove(id.toLowerCase());
        }
    }

    public ItemStack applyAbility(ItemStack item, String abilityId) {
        if (item == null || abilityId == null || !item.hasItemMeta()) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String current = pdc.getOrDefault(abilityKey, PersistentDataType.STRING, "");
        Set<String> set = new LinkedHashSet<>(Arrays.asList(current.split(";")));
        set.remove("");
        set.add(abilityId.toLowerCase());

        pdc.set(abilityKey, PersistentDataType.STRING, String.join(";", set));
        item.setItemMeta(meta);
        return item;
    }

    public boolean hasAbility(ItemStack item, String abilityId) {
        if (item == null || abilityId == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        String current = meta.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        if (current == null) return false;
        return Arrays.asList(current.split(";")).contains(abilityId.toLowerCase());
    }

    public List<String> getAbilities(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return Collections.emptyList();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Collections.emptyList();

        String current = meta.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        if (current == null || current.isEmpty()) return Collections.emptyList();
        return Arrays.asList(current.split(";"));
    }

    public double getRemainingCooldown(Player player, String abilityId) {
        if (player == null || abilityId == null) return 0.0;
        Map<String, Long> userCooldowns = cooldownMap.get(player.getUniqueId());
        if (userCooldowns == null) return 0.0;

        Long expireTime = userCooldowns.get(abilityId.toLowerCase());
        if (expireTime == null) return 0.0;

        long remainingMs = expireTime - System.currentTimeMillis();
        return remainingMs > 0 ? (remainingMs / 1000.0) : 0.0;
    }

    public void setCooldown(Player player, String abilityId, double seconds) {
        if (player == null || abilityId == null || seconds <= 0) return;
        long expireTime = System.currentTimeMillis() + (long)(seconds * 1000.0);
        cooldownMap.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(abilityId.toLowerCase(), expireTime);
    }

    private boolean triggerAbility(Player player, ItemStack item, AbilityTrigger trigger) {
        List<String> abilityIds = getAbilities(item);
        if (abilityIds.isEmpty()) return false;

        boolean activatedAny = false;
        for (String id : abilityIds) {
            ItemAbility ability = registeredAbilities.get(id);
            if (ability == null || ability.getTrigger() != trigger) continue;

            double remaining = getRemainingCooldown(player, id);
            if (remaining > 0.0) {
                MessageUtil.sendActionBar(player, "§c⏳ Kỹ năng " + ability.getName() + " §cđang hồi: §e" + String.format("%.1f", remaining) + "s");
                continue;
            }

            if (!ability.test(player, item)) continue;

            ability.execute(player, item);
            if (ability.getCooldownSeconds() > 0) {
                setCooldown(player, id, ability.getCooldownSeconds());
            }

            MessageUtil.sendActionBar(player, "§a⚡ Kích hoạt: " + ability.getName() + "§a!");
            activatedAny = true;
        }

        return activatedAny;
    }


    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) return;

        Action action = event.getAction();
        boolean isSneak = player.isSneaking();

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            AbilityTrigger trigger = isSneak ? AbilityTrigger.SHIFT_RIGHT_CLICK : AbilityTrigger.RIGHT_CLICK;
            if (triggerAbility(player, item, trigger)) {
                event.setCancelled(true);
            }
        } else if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            AbilityTrigger trigger = isSneak ? AbilityTrigger.SHIFT_LEFT_CLICK : AbilityTrigger.LEFT_CLICK;
            if (triggerAbility(player, item, trigger)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            ItemStack item = player.getInventory().getItemInMainHand();
            triggerAbility(player, item, AbilityTrigger.HIT_ENTITY);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            Player player = event.getPlayer();
            ItemStack item = player.getInventory().getItemInMainHand();
            triggerAbility(player, item, AbilityTrigger.SNEAK);
        }
    }

    public void cleanup() {
        registeredAbilities.clear();
        cooldownMap.clear();
    }
}
