package com.javastudio.bridge;

import com.javastudio.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.*;

public class EcosystemBridge {

    private static final WorldGuardHook worldGuard = new WorldGuardHook();
    private static final HologramHook holograms = new HologramHook();
    private static final CitizensHook citizens = new CitizensHook();
    private static final LuckPermsHook luckPerms = new LuckPermsHook();
    private static final ItemsAdderHook itemsAdder = new ItemsAdderHook();
    private static final OraxenHook oraxen = new OraxenHook();
    private static final MythicMobsHook mythicMobs = new MythicMobsHook();
    private static final ModelEngineHook modelEngine = new ModelEngineHook();

    public static WorldGuardHook getWorldGuard() { return worldGuard; }
    public static HologramHook getHolograms() { return holograms; }
    public static CitizensHook getCitizens() { return citizens; }
    public static LuckPermsHook getLuckPerms() { return luckPerms; }
    public static ItemsAdderHook getItemsAdder() { return itemsAdder; }
    public static OraxenHook getOraxen() { return oraxen; }
    public static MythicMobsHook getMythicMobs() { return mythicMobs; }
    public static ModelEngineHook getModelEngine() { return modelEngine; }

    public static class WorldGuardHook {
        private Boolean available = null;

        public boolean isAvailable() {
            if (available == null) {
                available = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
            }
            return available;
        }

        public boolean canBuild(Player player, Location loc) {
            if (!isAvailable() || player == null || loc == null) return true;
            try {
                Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
                Object wgInstance = wgClass.getMethod("getInstance").invoke(null);
                Object platform = wgClass.getMethod("getPlatform").invoke(wgInstance);
                Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
                Object query = container.getClass().getMethod("createQuery").invoke(container);

                Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Object weLoc = bukkitAdapter.getMethod("adapt", Location.class).invoke(null, loc);
                Object wgPlayer = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin")
                        .getMethod("wrapPlayer", Player.class).invoke(null, player);

                Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
                Object buildFlag = flagsClass.getField("BUILD").get(null);

                Method testState = query.getClass().getMethod("testState",
                        Class.forName("com.sk89q.worldedit.util.Location"),
                        Class.forName("com.sk89q.worldguard.protection.association.RegionAssociable"),
                        Class.forName("com.sk89q.worldguard.protection.flags.StateFlag[]"));

                Object stateFlagArray = java.lang.reflect.Array.newInstance(Class.forName("com.sk89q.worldguard.protection.flags.StateFlag"), 1);
                java.lang.reflect.Array.set(stateFlagArray, 0, buildFlag);

                return (boolean) testState.invoke(query, weLoc, wgPlayer, stateFlagArray);
            } catch (Throwable ignored) {
                return true;
            }
        }

        public boolean canPvp(Player player, Location loc) {
            if (!isAvailable() || player == null || loc == null) return true;
            try {
                Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
                Object wgInstance = wgClass.getMethod("getInstance").invoke(null);
                Object platform = wgClass.getMethod("getPlatform").invoke(wgInstance);
                Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
                Object query = container.getClass().getMethod("createQuery").invoke(container);

                Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Object weLoc = bukkitAdapter.getMethod("adapt", Location.class).invoke(null, loc);
                Object wgPlayer = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin")
                        .getMethod("wrapPlayer", Player.class).invoke(null, player);

                Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
                Object pvpFlag = flagsClass.getField("PVP").get(null);

                Method testState = query.getClass().getMethod("testState",
                        Class.forName("com.sk89q.worldedit.util.Location"),
                        Class.forName("com.sk89q.worldguard.protection.association.RegionAssociable"),
                        Class.forName("com.sk89q.worldguard.protection.flags.StateFlag[]"));

                Object stateFlagArray = java.lang.reflect.Array.newInstance(Class.forName("com.sk89q.worldguard.protection.flags.StateFlag"), 1);
                java.lang.reflect.Array.set(stateFlagArray, 0, pvpFlag);

                return (boolean) testState.invoke(query, weLoc, wgPlayer, stateFlagArray);
            } catch (Throwable ignored) {
                return true;
            }
        }

        public boolean isInRegion(Location loc, String regionName) {
            if (!isAvailable() || loc == null || regionName == null) return false;
            try {
                Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
                Object wgInstance = wgClass.getMethod("getInstance").invoke(null);
                Object platform = wgClass.getMethod("getPlatform").invoke(wgInstance);
                Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);

                Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Object weWorld = bukkitAdapter.getMethod("adapt", org.bukkit.World.class).invoke(null, loc.getWorld());
                Object manager = container.getClass().getMethod("get", Class.forName("com.sk89q.worldedit.world.World")).invoke(container, weWorld);

                if (manager != null) {
                    Class<?> blockVector3 = Class.forName("com.sk89q.worldedit.math.BlockVector3");
                    Object pt = blockVector3.getMethod("at", double.class, double.class, double.class)
                            .invoke(null, loc.getX(), loc.getY(), loc.getZ());
                    Object applicable = manager.getClass().getMethod("getApplicableRegions", blockVector3).invoke(manager, pt);

                    Iterable<?> set = (Iterable<?>) applicable;
                    for (Object reg : set) {
                        String id = (String) reg.getClass().getMethod("getId").invoke(reg);
                        if (id != null && id.equalsIgnoreCase(regionName)) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return false;
        }
    }

    public static class HologramHook {
        private Boolean available = null;

        public boolean isAvailable() {
            if (available == null) {
                available = Bukkit.getPluginManager().getPlugin("DecentHolograms") != null;
            }
            return available;
        }

        public boolean createHologram(String id, Location loc, List<String> lines) {
            if (!isAvailable() || id == null || loc == null) return false;
            try {
                Class<?> dhApi = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
                List<String> coloredLines = new ArrayList<>();
                if (lines != null) {
                    for (String l : lines) coloredLines.add(MessageUtil.colorize(l));
                }
                dhApi.getMethod("createHologram", String.class, Location.class, List.class)
                        .invoke(null, id, loc, coloredLines);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        public boolean updateHologram(String id, List<String> lines) {
            if (!isAvailable() || id == null) return false;
            try {
                Class<?> dhApi = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
                List<String> coloredLines = new ArrayList<>();
                if (lines != null) {
                    for (String l : lines) coloredLines.add(MessageUtil.colorize(l));
                }
                dhApi.getMethod("setHologramLines", String.class, List.class)
                        .invoke(null, id, coloredLines);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        public boolean deleteHologram(String id) {
            if (!isAvailable() || id == null) return false;
            try {
                Class<?> dhApi = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
                dhApi.getMethod("removeHologram", String.class).invoke(null, id);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static class CitizensHook {
        private Boolean available = null;

        public boolean isAvailable() {
            if (available == null) {
                available = Bukkit.getPluginManager().getPlugin("Citizens") != null;
            }
            return available;
        }

        public Integer spawnNpc(String name, EntityType type, Location loc) {
            if (!isAvailable() || name == null || loc == null) return null;
            try {
                Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
                Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);
                Object npc = registry.getClass().getMethod("createNPC", EntityType.class, String.class)
                        .invoke(registry, type != null ? type : EntityType.PLAYER, MessageUtil.colorize(name));
                if (npc != null) {
                    npc.getClass().getMethod("spawn", Location.class).invoke(npc, loc);
                    return (int) npc.getClass().getMethod("getId").invoke(npc);
                }
            } catch (Throwable ignored) {}
            return null;
        }

        public boolean removeNpc(int npcId) {
            if (!isAvailable()) return false;
            try {
                Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
                Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);
                Object npc = registry.getClass().getMethod("getById", int.class).invoke(registry, npcId);
                if (npc != null) {
                    npc.getClass().getMethod("destroy").invoke(npc);
                    return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }
    }

    public static class LuckPermsHook {
        private Boolean available = null;
        private Object luckPermsInstance = null;

        public boolean isAvailable() {
            if (available == null) {
                available = Bukkit.getPluginManager().getPlugin("LuckPerms") != null;
            }
            return available;
        }

        private Object getApi() {
            if (!isAvailable()) return null;
            if (luckPermsInstance == null) {
                try {
                    Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
                    luckPermsInstance = providerClass.getMethod("get").invoke(null);
                } catch (Throwable ignored) {}
            }
            return luckPermsInstance;
        }

        public String getPrefix(Player player) {
            if (!isAvailable() || player == null) return "";
            try {
                Object api = getApi();
                if (api != null) {
                    Object userManager = api.getClass().getMethod("getUserManager").invoke(api);
                    Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUniqueId());
                    if (user != null) {
                        Object metaData = user.getClass().getMethod("getCachedData").invoke(user);
                        Object meta = metaData.getClass().getMethod("getMetaData").invoke(metaData);
                        String prefix = (String) meta.getClass().getMethod("getPrefix").invoke(meta);
                        return prefix != null ? prefix : "";
                    }
                }
            } catch (Throwable ignored) {}
            return "";
        }

        public String getSuffix(Player player) {
            if (!isAvailable() || player == null) return "";
            try {
                Object api = getApi();
                if (api != null) {
                    Object userManager = api.getClass().getMethod("getUserManager").invoke(api);
                    Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUniqueId());
                    if (user != null) {
                        Object metaData = user.getClass().getMethod("getCachedData").invoke(user);
                        Object meta = metaData.getClass().getMethod("getMetaData").invoke(metaData);
                        String suffix = (String) meta.getClass().getMethod("getSuffix").invoke(meta);
                        return suffix != null ? suffix : "";
                    }
                }
            } catch (Throwable ignored) {}
            return "";
        }

        public String getPrimaryGroup(Player player) {
            if (!isAvailable() || player == null) return "default";
            try {
                Object api = getApi();
                if (api != null) {
                    Object userManager = api.getClass().getMethod("getUserManager").invoke(api);
                    Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUniqueId());
                    if (user != null) {
                        String grp = (String) user.getClass().getMethod("getPrimaryGroup").invoke(user);
                        return grp != null ? grp : "default";
                    }
                }
            } catch (Throwable ignored) {}
            return "default";
        }
    }

    public static class ItemsAdderHook {
        private Boolean available = null;

        public boolean isAvailable() {
            if (available == null) {
                available = Bukkit.getPluginManager().getPlugin("ItemsAdder") != null;
            }
            return available;
        }

        public org.bukkit.inventory.ItemStack getItem(String namespacedId) {
            if (!isAvailable() || namespacedId == null) return null;
            try {
                Class<?> csClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                Object customStack = csClass.getMethod("getInstance", String.class).invoke(null, namespacedId);
                if (customStack != null) {
                    return (org.bukkit.inventory.ItemStack) customStack.getClass().getMethod("getItemStack").invoke(customStack);
                }
            } catch (Throwable ignored) {}
            return null;
        }

        public boolean isCustomItem(org.bukkit.inventory.ItemStack item) {
            if (!isAvailable() || item == null) return false;
            try {
                Class<?> csClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                Object customStack = csClass.getMethod("byItemStack", org.bukkit.inventory.ItemStack.class).invoke(null, item);
                return customStack != null;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static class OraxenHook {
        private Boolean available = null;

        public boolean isAvailable() {
            if (available == null) {
                available = Bukkit.getPluginManager().getPlugin("Oraxen") != null;
            }
            return available;
        }

        public org.bukkit.inventory.ItemStack getItem(String itemId) {
            if (!isAvailable() || itemId == null) return null;
            try {
                Class<?> oraxenItems = Class.forName("io.thismirebal.oraxen.api.OraxenItems");
                Object itemModifier = oraxenItems.getMethod("getItemById", String.class).invoke(null, itemId);
                if (itemModifier != null) {
                    return (org.bukkit.inventory.ItemStack) itemModifier.getClass().getMethod("build").invoke(itemModifier);
                }
            } catch (Throwable ignored) {}
            return null;
        }

        public boolean isCustomItem(org.bukkit.inventory.ItemStack item) {
            if (!isAvailable() || item == null) return false;
            try {
                Class<?> oraxenItems = Class.forName("io.thismirebal.oraxen.api.OraxenItems");
                Object id = oraxenItems.getMethod("getIdByItem", org.bukkit.inventory.ItemStack.class).invoke(null, item);
                return id != null;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static class MythicMobsHook {
        private Boolean available = null;

        public boolean isAvailable() {
            if (available == null) {
                available = Bukkit.getPluginManager().getPlugin("MythicMobs") != null;
            }
            return available;
        }

        public org.bukkit.entity.Entity spawnMob(String mobType, Location loc, int level) {
            if (!isAvailable() || mobType == null || loc == null) return null;
            try {
                Class<?> mythicBukkit = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
                Object instance = mythicBukkit.getMethod("get").invoke(null);
                Object mobManager = instance.getClass().getMethod("getMobManager").invoke(instance);
                Method spawnMethod = mobManager.getClass().getMethod("spawnMob", String.class, Location.class, double.class);
                Object activeMob = spawnMethod.invoke(mobManager, mobType, loc, (double) level);
                if (activeMob != null) {
                    Object mythicEntity = activeMob.getClass().getMethod("getEntity").invoke(activeMob);
                    return (org.bukkit.entity.Entity) mythicEntity.getClass().getMethod("getBukkitEntity").invoke(mythicEntity);
                }
            } catch (Throwable ignored) {}
            return null;
        }

        public boolean isMythicMob(org.bukkit.entity.Entity entity) {
            if (!isAvailable() || entity == null) return false;
            try {
                Class<?> mythicBukkit = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
                Object instance = mythicBukkit.getMethod("get").invoke(null);
                Object mobManager = instance.getClass().getMethod("getMobManager").invoke(instance);
                Method isMob = mobManager.getClass().getMethod("isMythicMob", org.bukkit.entity.Entity.class);
                return (boolean) isMob.invoke(mobManager, entity);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static class ModelEngineHook {
        private Boolean available = null;

        public boolean isAvailable() {
            if (available == null) {
                available = Bukkit.getPluginManager().getPlugin("ModelEngine") != null;
            }
            return available;
        }

        public boolean isModeledEntity(org.bukkit.entity.Entity entity) {
            if (!isAvailable() || entity == null) return false;
            try {
                Class<?> api = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
                Method isModeled = api.getMethod("isModeledEntity", UUID.class);
                return (boolean) isModeled.invoke(null, entity.getUniqueId());
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
