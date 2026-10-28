package com.javastudio.anticheat;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cấu hình đã nạp sẵn của một check (đọc từ anticheat.checks.&lt;key&gt;).
 * Các giá trị riêng của check (ngưỡng, buffer...) được cache vào Map để truy xuất nhanh trong event.
 */
public final class CheckSettings {

    private final CheckType type;
    private volatile boolean enabled;
    private volatile double maxVl;
    private volatile boolean setback;
    private volatile boolean cancel;
    private final List<String> commands;
    private final Map<String, Object> extras = new HashMap<>();

    public CheckSettings(CheckType type, ConfigurationSection section) {
        this.type = type;
        if (section == null) {
            this.enabled = true;
            this.maxVl = type.getDefaultMaxVl();
            this.setback = type.isDefaultSetback();
            this.cancel = type.isDefaultCancel();
            this.commands = Collections.emptyList();
            return;
        }
        this.enabled = section.getBoolean("enabled", true);
        this.maxVl = section.getDouble("max-vl", type.getDefaultMaxVl());
        this.setback = section.getBoolean("setback", type.isDefaultSetback());
        this.cancel = section.getBoolean("cancel-event", type.isDefaultCancel());
        this.commands = new ArrayList<>(section.getStringList("commands"));
        for (String k : section.getKeys(false)) {
            Object v = section.get(k);
            if (v instanceof Number || v instanceof Boolean || v instanceof String) {
                extras.put(k, v);
            }
        }
    }

    public CheckType getType() {
        return type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getMaxVl() {
        return maxVl;
    }

    public boolean isSetback() {
        return setback;
    }

    public boolean isCancel() {
        return cancel;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setMaxVl(double maxVl) {
        this.maxVl = maxVl;
    }

    public void setSetback(boolean setback) {
        this.setback = setback;
    }

    public void setCancel(boolean cancel) {
        this.cancel = cancel;
    }

    public List<String> getCommands() {
        return commands;
    }

    public double d(String key, double def) {
        Object v = extras.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    public int i(String key, int def) {
        Object v = extras.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public long l(String key, long def) {
        Object v = extras.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }

    public boolean b(String key, boolean def) {
        Object v = extras.get(key);
        return v instanceof Boolean bo ? bo : def;
    }

    public Map<String, Object> getExtras() {
        return Collections.unmodifiableMap(extras);
    }
}
