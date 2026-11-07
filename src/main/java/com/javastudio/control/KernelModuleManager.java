package com.javastudio.control;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class KernelModuleManager {

    private final JavaStudioPlugin plugin;
    private final File modulesFile;
    private final Map<String, Boolean> moduleStates = new ConcurrentHashMap<>();

    private static final Map<String, Boolean> DEFAULT_MODULES;
    static {
        Map<String, Boolean> map = new HashMap<>();
        for (com.javastudio.manager.SettingsManager.ModuleKey mk : com.javastudio.manager.SettingsManager.ModuleKey.values()) {
            map.put(mk.getKey(), mk.isDefaultEnabled());
        }
        DEFAULT_MODULES = Collections.unmodifiableMap(map);
    }

    public KernelModuleManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.modulesFile = new File(plugin.getDataFolder(), "modules.yml");
        loadModules();
    }

    public synchronized void loadModules() {
        moduleStates.clear();
        moduleStates.putAll(DEFAULT_MODULES);

        if (!modulesFile.exists()) {
            saveDefaultFile();
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(modulesFile);
        if (config.isConfigurationSection("modules")) {
            for (String key : config.getConfigurationSection("modules").getKeys(false)) {
                moduleStates.put(key.toLowerCase(Locale.ROOT), config.getBoolean("modules." + key, true));
            }
        }
    }

    private void saveDefaultFile() {
        try {
            if (plugin.getResource("modules.yml") != null) {
                plugin.saveResource("modules.yml", false);
                return;
            }
        } catch (Throwable ignored) {}

        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, Boolean> entry : DEFAULT_MODULES.entrySet()) {
            config.set("modules." + entry.getKey(), entry.getValue());
        }
        try {
            config.save(modulesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[Kernel] Không thể lưu modules.yml: " + e.getMessage());
        }
    }

    public boolean isModuleEnabled(String moduleName) {
        if (moduleName == null) return true;
        String key = moduleName.toLowerCase(Locale.ROOT).replace("_", "-");
        if (plugin != null && plugin.getSettingsManager() != null) {
            return plugin.getSettingsManager().isEnabled(key);
        }
        return moduleStates.getOrDefault(key, true);
    }

    public synchronized void setModuleEnabled(String moduleName, boolean enabled) {
        if (moduleName == null) return;
        String key = moduleName.toLowerCase(Locale.ROOT).replace("_", "-");
        moduleStates.put(key, enabled);

        YamlConfiguration config = YamlConfiguration.loadConfiguration(modulesFile);
        config.set("modules." + key, enabled);
        try {
            config.save(modulesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[Kernel] Không thể lưu trạng thái module vào modules.yml: " + e.getMessage());
        }
    }

    public Map<String, Boolean> getAllModules() {
        return Collections.unmodifiableMap(moduleStates);
    }
}

