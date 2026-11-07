package com.javastudio.pipeline;

import io.netty.channel.embedded.EmbeddedChannel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class JBotEngine {

    private final Plugin plugin;
    private final Map<String, SimulatedBot> activeBots = new ConcurrentHashMap<>();
    private BukkitTask globalTickTask;

    public JBotEngine(Plugin plugin) {
        this.plugin = plugin;
        startGlobalTick();
    }

    private void startGlobalTick() {
        globalTickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (SimulatedBot bot : activeBots.values()) {
                bot.tick();
            }
        }, 1L, 1L);
    }

    public void shutdown() {
        if (globalTickTask != null) {
            globalTickTask.cancel();
        }
        for (String name : new ArrayList<>(activeBots.keySet())) {
            removeBot(name);
        }
    }


    public SimulatedBot spawnBot(String name, Location spawnLoc, BotBehavior initialBehavior) {
        if (activeBots.containsKey(name.toLowerCase(Locale.ROOT))) {
            removeBot(name);
        }

        try {
            SimulatedBot bot = new SimulatedBot(plugin, name, spawnLoc, initialBehavior);
            bot.inject();
            activeBots.put(name.toLowerCase(Locale.ROOT), bot);
            return bot;
        } catch (Exception e) {
            throw new RuntimeException("Không thể tạo Bot '" + name + "': " + e.getMessage(), e);
        }
    }

    public boolean removeBot(String name) {
        SimulatedBot bot = activeBots.remove(name.toLowerCase(Locale.ROOT));
        if (bot != null) {
            bot.disconnect();
            return true;
        }
        return false;
    }

    public int removeAllBots() {
        int count = activeBots.size();
        for (String name : new ArrayList<>(activeBots.keySet())) {
            removeBot(name);
        }
        return count;
    }

    public Collection<SimulatedBot> getActiveBots() {
        return activeBots.values();
    }

    public SimulatedBot getBot(String name) {
        return activeBots.get(name.toLowerCase(Locale.ROOT));
    }


    public enum BotBehavior {
        IDLE,
        WANDER,
        COMBAT,
        SPAM_CHAT
    }

    public static final class SimulatedBot {
        private final Plugin plugin;
        private final String name;
        private Location location;
        private BotBehavior behavior;
        private Object craftPlayer;
        private Object netServerPlayer;
        private EmbeddedChannel channel;
        private float yaw = 0f;
        private float pitch = 0f;
        private int tickCount = 0;

        SimulatedBot(Plugin plugin, String name, Location loc, BotBehavior behavior) throws Exception {
            this.plugin = plugin;
            this.name = name;
            this.location = loc.clone();
            this.behavior = behavior;
            initializeNMSConnection();
        }

        private void initializeNMSConnection() throws Exception {
            World world = location.getWorld();
            if (world == null) {
                throw new IllegalStateException("World không hợp lệ cho điểm spawn của bot: " + name);
            }
            MinecraftReflection.setupBotPlayer(this, world);
        }

        public void inject() {
            try {
                MinecraftReflection.addPlayerToWorld(this);
            } catch (Exception e) {
                plugin.getLogger().warning("[JBot] Lỗi inject bot " + name + ": " + e.getMessage());
            }
        }

        public void disconnect() {
            try {
                MinecraftReflection.removePlayerFromWorld(this);
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
            } catch (Exception ignored) {}
        }

        public void tick() {
            tickCount++;
            if (location == null || location.getWorld() == null) return;

            ThreadLocalRandom rand = ThreadLocalRandom.current();
            switch (behavior) {
                case WANDER -> {
                    if (tickCount % 20 == 0) {
                        yaw += (rand.nextFloat() - 0.5f) * 90f;
                        double rad = Math.toRadians(yaw);
                        double nx = location.getX() - Math.sin(rad) * 0.5;
                        double nz = location.getZ() + Math.cos(rad) * 0.5;
                        location.setX(nx);
                        location.setZ(nz);
                        location.setYaw(yaw);
                        location.setPitch(pitch);
                        MinecraftReflection.updatePlayerPosition(this);
                    }
                }
                case COMBAT -> {
                    if (tickCount % 10 == 0) {
                        yaw += 15f;
                        location.setYaw(yaw);
                        MinecraftReflection.sendArmAnimation(this);
                    }
                }
                case SPAM_CHAT -> {
                    if (tickCount % 40 == 0) {
                        MinecraftReflection.sendChatMessage(this, "Hello from JavaStudio Bot #" + rand.nextInt(1000));
                    }
                }
                case IDLE -> {}
            }
        }

        public void setBehavior(BotBehavior behavior) {
            this.behavior = behavior;
        }

        public Player getBukkitPlayer() {
            if (craftPlayer == null) return null;
            try {
                Method getBukkitView = craftPlayer.getClass().getMethod("getBukkitEntity");
                return (Player) getBukkitView.invoke(craftPlayer);
            } catch (Exception e) {
                if (craftPlayer instanceof Player p) {
                    return p;
                }
                return null;
            }
        }

        public String getName() { return name; }
        public Location getLocation() { return location; }
        public BotBehavior getBehavior() { return behavior; }
        public float getYaw() { return yaw; }
        public void setYaw(float yaw) { this.yaw = yaw; }
        public float getPitch() { return pitch; }
        public void setPitch(float pitch) { this.pitch = pitch; }
        public Object getNetServerPlayer() { return netServerPlayer; }
        public void setNetServerPlayer(Object p) { this.netServerPlayer = p; }
        public void setCraftPlayer(Object cp) { this.craftPlayer = cp; }
        public void setChannel(EmbeddedChannel ch) { this.channel = ch; }
    }


    private static final class MinecraftReflection {
        private static Object lastCreatedCookie = null;

        static void setupBotPlayer(SimulatedBot bot, World world) throws Exception {
            EmbeddedChannel channel = new EmbeddedChannel();
            bot.setChannel(channel);

            Class<?> craftServerClass = Bukkit.getServer().getClass();
            Method getServerMethod = craftServerClass.getMethod("getServer");
            Object nmsServer = getServerMethod.invoke(Bukkit.getServer());

            Method getHandleWorld = world.getClass().getMethod("getHandle");
            Object serverLevel = getHandleWorld.invoke(world);

            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            Constructor<?> gpConstructor = gameProfileClass.getConstructor(UUID.class, String.class);
            Object gameProfile = gpConstructor.newInstance(UUID.randomUUID(), bot.getName());

            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Constructor<?> spConstructor = null;
            for (Constructor<?> c : serverPlayerClass.getDeclaredConstructors()) {
                if (c.getParameterCount() >= 3) {
                    spConstructor = c;
                    break;
                }
            }

            if (spConstructor == null) {
                throw new NoSuchMethodException("Không tìm thấy Constructor của ServerPlayer");
            }

            spConstructor.setAccessible(true);
            Object serverPlayer = null;

            if (spConstructor.getParameterCount() == 4) {
                Class<?> clientInfoClass = null;
                try {
                    clientInfoClass = Class.forName("net.minecraft.server.level.ClientInformation");
                } catch (ClassNotFoundException e) {
                    try {
                        clientInfoClass = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl$ClientInformation");
                    } catch (ClassNotFoundException ignored) {}
                }

                Object defaultClientInfo = null;
                if (clientInfoClass != null) {
                    for (Method m : clientInfoClass.getDeclaredMethods()) {
                        if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && clientInfoClass.isAssignableFrom(m.getReturnType())) {
                            m.setAccessible(true);
                            defaultClientInfo = m.invoke(null);
                            break;
                        }
                    }
                }
                serverPlayer = spConstructor.newInstance(nmsServer, serverLevel, gameProfile, defaultClientInfo);
            } else {
                serverPlayer = spConstructor.newInstance(nmsServer, serverLevel, gameProfile);
            }
            bot.setNetServerPlayer(serverPlayer);

            Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");
            Class<?> packetFlowClass = Class.forName("net.minecraft.network.protocol.PacketFlow");
            Object serverboundEnum = packetFlowClass.getEnumConstants()[1];

            Object connection = null;
            for (Constructor<?> connConstr : connectionClass.getConstructors()) {
                if (connConstr.getParameterCount() == 1 && connConstr.getParameterTypes()[0].equals(packetFlowClass)) {
                    connection = connConstr.newInstance(serverboundEnum);
                    break;
                }
            }
            if (connection == null) {
                Constructor<?> defaultConn = connectionClass.getDeclaredConstructor();
                defaultConn.setAccessible(true);
                connection = defaultConn.newInstance();
            }

            Class<?> packetListenerClass = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            Constructor<?> plConstructor = null;
            for (Constructor<?> c : packetListenerClass.getDeclaredConstructors()) {
                Class<?>[] types = c.getParameterTypes();
                if (types.length >= 3 && types[0].getName().contains("MinecraftServer")
                        && types[1].getName().contains("Connection")
                        && types[2].getName().contains("ServerPlayer")) {
                    plConstructor = c;
                    break;
                }
            }

            if (plConstructor != null) {
                plConstructor.setAccessible(true);
                Object packetListener;
                if (plConstructor.getParameterCount() == 4) {
                    Class<?> cookieClass = plConstructor.getParameterTypes()[3];
                    Object cookie = createCookie(cookieClass, gameProfile);
                    lastCreatedCookie = cookie;
                    packetListener = plConstructor.newInstance(nmsServer, connection, serverPlayer, cookie);
                } else {
                    packetListener = plConstructor.newInstance(nmsServer, connection, serverPlayer);
                }

                try {
                    Field connectionField = null;
                    for (Field f : serverPlayerClass.getDeclaredFields()) {
                        if (f.getType().isAssignableFrom(packetListenerClass) || f.getName().equals("connection")) {
                            connectionField = f;
                            break;
                        }
                    }
                    if (connectionField != null) {
                        connectionField.setAccessible(true);
                        connectionField.set(serverPlayer, packetListener);
                    }
                } catch (Exception ignored) {}
            }

            Method getBukkitEntity = serverPlayerClass.getMethod("getBukkitEntity");
            Object cp = getBukkitEntity.invoke(serverPlayer);
            bot.setCraftPlayer(cp);

            if (cp instanceof Player bukkitPlayer) {
                bukkitPlayer.teleport(bot.getLocation());
            }
        }

        private static Object createCookie(Class<?> cookieClass, Object gameProfile) {
            try {
                for (Method m : cookieClass.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && cookieClass.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(gameProfile)) {
                            return m.invoke(null, gameProfile);
                        } else if (m.getParameterCount() == 2 && m.getParameterTypes()[0].isInstance(gameProfile) && m.getParameterTypes()[1] == boolean.class) {
                            return m.invoke(null, gameProfile, false);
                        }
                    }
                }
                for (Constructor<?> c : cookieClass.getDeclaredConstructors()) {
                    c.setAccessible(true);
                    Object[] args = new Object[c.getParameterCount()];
                    for (int i = 0; i < c.getParameterTypes().length; i++) {
                        Class<?> t = c.getParameterTypes()[i];
                        if (t.isInstance(gameProfile)) args[i] = gameProfile;
                        else if (t == int.class) args[i] = 0;
                        else if (t == boolean.class) args[i] = false;
                        else args[i] = null;
                    }
                    return c.newInstance(args);
                }
            } catch (Exception ignored) {}
            return null;
        }

        static void addPlayerToWorld(SimulatedBot bot) throws Exception {
            Object sp = bot.getNetServerPlayer();
            if (sp == null) return;

            Object nmsServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            Object playerList = nmsServer.getClass().getMethod("getPlayerList").invoke(nmsServer);

            for (Method m : playerList.getClass().getMethods()) {
                Class<?>[] pTypes = m.getParameterTypes();
                if (pTypes.length >= 2 && pTypes[0].getName().contains("Connection") && pTypes[1].getName().contains("ServerPlayer")) {
                    m.setAccessible(true);
                    Field connField = null;
                    for (Field f : sp.getClass().getDeclaredFields()) {
                        if (f.getName().equals("connection") || f.getType().getName().contains("ServerGamePacketListenerImpl")) {
                            connField = f;
                            break;
                        }
                    }
                    Object connection = connField != null ? connField.get(sp) : null;
                    if (connection != null) {
                        if (pTypes.length == 2) {
                            m.invoke(playerList, connection, sp);
                        } else if (pTypes.length == 3 && lastCreatedCookie != null) {
                            m.invoke(playerList, connection, sp, lastCreatedCookie);
                        }
                        break;
                    }
                }
            }
        }

        static void removePlayerFromWorld(SimulatedBot bot) throws Exception {
            Object sp = bot.getNetServerPlayer();
            if (sp == null) return;

            Object nmsServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            Object playerList = nmsServer.getClass().getMethod("getPlayerList").invoke(nmsServer);

            Method removeMethod = null;
            for (Method m : playerList.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(sp.getClass())) {
                    removeMethod = m;
                    break;
                }
            }
            if (removeMethod != null) {
                removeMethod.invoke(playerList, sp);
            }
        }

        static void updatePlayerPosition(SimulatedBot bot) {
            try {
                Player p = bot.getBukkitPlayer();
                if (p != null && p.isOnline()) {
                    p.teleport(bot.getLocation());
                }
            } catch (Exception ignored) {}
        }

        static void sendArmAnimation(SimulatedBot bot) {
            try {
                Player p = bot.getBukkitPlayer();
                if (p != null) {
                    p.swingMainHand();
                }
            } catch (Exception ignored) {}
        }

        static void sendChatMessage(SimulatedBot bot, String msg) {
            try {
                Player p = bot.getBukkitPlayer();
                if (p != null) {
                    p.chat(msg);
                }
            } catch (Exception ignored) {}
        }
    }
}
