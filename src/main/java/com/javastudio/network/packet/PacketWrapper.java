package com.javastudio.network.packet;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PacketWrapper {

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    protected final Object packet;
    protected final String packetName;

    public PacketWrapper(Object packet) {
        this.packet = packet;
        this.packetName = packet != null ? packet.getClass().getSimpleName() : "UnknownPacket";
    }

    public Object getHandle() {
        return packet;
    }

    public String getPacketName() {
        return packetName;
    }

    protected double readDouble(String fieldName, double defVal) {
        Object val = readField(fieldName);
        return (val instanceof Number n) ? n.doubleValue() : defVal;
    }

    protected float readFloat(String fieldName, float defVal) {
        Object val = readField(fieldName);
        return (val instanceof Number n) ? n.floatValue() : defVal;
    }

    protected int readInt(String fieldName, int defVal) {
        Object val = readField(fieldName);
        return (val instanceof Number n) ? n.intValue() : defVal;
    }

    protected boolean readBoolean(String fieldName, boolean defVal) {
        Object val = readField(fieldName);
        return (val instanceof Boolean b) ? b : defVal;
    }

    @SuppressWarnings("unchecked")
    protected <T> T readFirstFieldOfType(Class<T> type) {
        if (packet == null) return null;
        Class<?> clazz = packet.getClass();
        String cacheKey = clazz.getName() + "::type::" + type.getName();

        Field targetField = FIELD_CACHE.computeIfAbsent(cacheKey, k -> {
            Class<?> cur = clazz;
            while (cur != null && cur != Object.class) {
                for (Field f : cur.getDeclaredFields()) {
                    if (type.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        return f;
                    }
                }
                cur = cur.getSuperclass();
            }
            return null;
        });

        if (targetField != null) {
            try {
                return (T) targetField.get(packet);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    protected Object readField(String fieldName) {
        if (packet == null) return null;
        Class<?> clazz = packet.getClass();
        String cacheKey = clazz.getName() + "::" + fieldName;

        Field targetField = FIELD_CACHE.computeIfAbsent(cacheKey, k -> {
            Class<?> cur = clazz;
            while (cur != null && cur != Object.class) {
                try {
                    Field f = cur.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    cur = cur.getSuperclass();
                }
            }
            return null;
        });

        if (targetField != null) {
            try {
                return targetField.get(packet);
            } catch (Throwable ignored) {}
        }
        return null;
    }


    public static final class WrappedInMovePacket extends PacketWrapper {
        public WrappedInMovePacket(Object packet) {
            super(packet);
        }

        public boolean isMovePacket() {
            return packetName.contains("MovePlayer") || packetName.contains("Flying");
        }

        public double getX(double def) { return readDouble("x", def); }
        public double getY(double def) { return readDouble("y", def); }
        public double getZ(double def) { return readDouble("z", def); }
        public float getYaw(float def) { return readFloat("yRot", readFloat("yaw", def)); }
        public float getPitch(float def) { return readFloat("xRot", readFloat("pitch", def)); }
        public boolean isOnGround() { return readBoolean("onGround", false); }
        public boolean hasPosition() { return packetName.contains("Pos"); }
        public boolean hasRotation() { return packetName.contains("Rot"); }
    }

    public static final class WrappedInUseEntityPacket extends PacketWrapper {
        public WrappedInUseEntityPacket(Object packet) {
            super(packet);
        }

        public boolean isInteractPacket() {
            return packetName.contains("Interact") || packetName.contains("UseEntity");
        }

        public int getEntityId() {
            return readInt("entityId", -1);
        }

        public boolean isAttack() {
            Object action = readField("action");
            if (action != null) {
                String s = action.toString().toUpperCase();
                return s.contains("ATTACK");
            }
            return packetName.contains("Attack");
        }

        public boolean isUsingSecondaryAction() {
            return readBoolean("usingSecondaryAction", false);
        }
    }

    public static final class WrappedOutVelocityPacket extends PacketWrapper {
        public WrappedOutVelocityPacket(Object packet) {
            super(packet);
        }

        public boolean isVelocityPacket() {
            return packetName.contains("SetEntityMotion") || packetName.contains("Velocity");
        }

        public int getEntityId() { return readInt("id", readInt("entityId", -1)); }
        public double getVelocityX() { return readInt("xa", 0) / 8000.0; }
        public double getVelocityY() { return readInt("ya", 0) / 8000.0; }
        public double getVelocityZ() { return readInt("za", 0) / 8000.0; }
    }
}

