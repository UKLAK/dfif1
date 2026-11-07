package com.javastudio.anticheat;

public enum CheckType {

    SPEED("speed", Category.MOVEMENT, "Speed", 14, true, false),
    FLY("fly", Category.MOVEMENT, "Fly", 10, true, false),
    JUMP("jump", Category.MOVEMENT, "HighJump/Step", 10, true, false),
    NOFALL("nofall", Category.MOVEMENT, "NoFall", 8, false, false),
    JESUS("jesus", Category.MOVEMENT, "Jesus", 10, true, false),
    TIMER("timer", Category.MOVEMENT, "Timer", 12, true, false),
    NOSLOW("noslow", Category.MOVEMENT, "NoSlow", 12, true, false),
    PHASE("phase", Category.MOVEMENT, "Phase", 6, true, false),
    INVALIDMOVE("invalidmove", Category.MOVEMENT, "InvalidMove", 6, true, false),
    SPRINT("sprint", Category.MOVEMENT, "Sprint", 12, true, false),
    ELYTRA("elytra", Category.MOVEMENT, "ElytraFly", 10, true, false),
    VELOCITY("velocity", Category.MOVEMENT, "Velocity (AntiKB)", 8, false, false),
    FASTCLIMB("fastclimb", Category.MOVEMENT, "FastClimb", 10, true, false),
    VEHICLE("vehicle", Category.MOVEMENT, "Vehicle/BoatFly", 8, true, true),
    BLINK("blink", Category.MOVEMENT, "Blink/Disabler", 8, true, false),
    PHYSICS("physics", Category.MOVEMENT, "PhysicsSimulation", 10, true, false),

    REACH("reach", Category.COMBAT, "Reach", 10, false, true),
    KILLAURA("killaura", Category.COMBAT, "KillAura", 10, false, true),
    WALLHIT("wallhit", Category.COMBAT, "HitThroughWall", 8, false, true),
    AIM("aim", Category.COMBAT, "AimAssist", 12, false, false),
    AUTOCLICKER("autoclicker", Category.COMBAT, "AutoClicker", 12, false, false),
    NOSWING("noswing", Category.COMBAT, "NoSwing", 10, false, false),
    CRITICALS("criticals", Category.COMBAT, "Criticals", 10, false, true),
    MULTIACTIONS("multiactions", Category.COMBAT, "MultiActions", 10, false, true),
    HITBOX("hitbox", Category.COMBAT, "HitboxExpand", 8, false, true),
    AUTOTOTEM("autototem", Category.COMBAT, "AutoTotem", 6, false, true),

    FASTBREAK("fastbreak", Category.WORLD, "FastBreak", 10, false, true),
    NUKER("nuker", Category.WORLD, "Nuker", 8, false, true),
    FASTPLACE("fastplace", Category.WORLD, "FastPlace", 10, false, true),
    SCAFFOLD("scaffold", Category.WORLD, "Scaffold", 10, false, true),
    AIRPLACE("airplace", Category.WORLD, "AirPlace", 6, false, true),
    BLOCKREACH("blockreach", Category.WORLD, "BlockReach", 8, false, true),
    FASTEAT("fasteat", Category.WORLD, "FastEat", 8, false, true),
    FASTBOW("fastbow", Category.WORLD, "FastBow", 8, false, true),
    INVENTORY("inventory", Category.WORLD, "InventoryMove", 10, false, true),
    CHESTSTEALER("cheststealer", Category.WORLD, "ChestStealer", 8, false, true),
    AUTOARMOR("autoarmor", Category.WORLD, "AutoArmor", 8, false, true),
    BADPACKETS("badpackets", Category.WORLD, "BadPackets", 8, true, true),
    BARITONE("baritone", Category.WORLD, "Bot/Baritone", 10, false, false),
    DISABLER("disabler", Category.WORLD, "Disabler/PingSpoof", 8, true, true),
    XRAY("xray", Category.WORLD, "XRay/Honeypot", 6, false, true),
    DUPE("dupe", Category.WORLD, "InventoryDupe", 6, true, true);

    public enum Category {
        MOVEMENT("Di chuyển"),
        COMBAT("Chiến đấu"),
        WORLD("Thế giới/Người chơi");

        private final String display;

        Category(String display) {
            this.display = display;
        }

        public String getDisplay() {
            return display;
        }
    }

    private final String key;
    private final Category category;
    private final String displayName;
    private final double defaultMaxVl;
    private final boolean defaultSetback;
    private final boolean defaultCancel;

    CheckType(String key, Category category, String displayName, double defaultMaxVl,
              boolean defaultSetback, boolean defaultCancel) {
        this.key = key;
        this.category = category;
        this.displayName = displayName;
        this.defaultMaxVl = defaultMaxVl;
        this.defaultSetback = defaultSetback;
        this.defaultCancel = defaultCancel;
    }

    public String getKey() {
        return key;
    }

    public Category getCategory() {
        return category;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getDefaultMaxVl() {
        return defaultMaxVl;
    }

    public boolean isDefaultSetback() {
        return defaultSetback;
    }

    public boolean isDefaultCancel() {
        return defaultCancel;
    }

    public static CheckType fromKey(String text) {
        if (text == null) return null;
        String clean = text.trim().toLowerCase().replace("_", "").replace("-", "");
        for (CheckType t : values()) {
            if (t.key.equals(clean)) return t;
        }
        return null;
    }
}
