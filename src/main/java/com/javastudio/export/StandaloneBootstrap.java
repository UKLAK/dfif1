package com.javastudio.export;

import com.javastudio.api.DynamicPlugin;
import com.javastudio.utils.ReflectionUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class StandaloneBootstrap extends JavaPlugin {

    private DynamicPlugin dynamicPlugin;
    private final List<Listener> registeredListeners = new ArrayList<>();
    private final List<String> registeredCommands = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String mainClassName = null;
        try (InputStream in = getResource("javastudio_meta.yml")) {
            if (in != null) {
                YamlConfiguration meta = YamlConfiguration.loadConfiguration(new InputStreamReader(in));
                mainClassName = meta.getString("main");
            }
        } catch (Exception ignored) {}

        try (InputStream licIn = getResource("javastudio_license.key")) {
            if (licIn != null) {
                String licenseKey = new String(licIn.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (licenseKey.contains(".")) {
                    try {
                        String payloadBase64 = licenseKey.split("\\.", 2)[0];
                        byte[] bytes = java.util.Base64.getUrlDecoder().decode(payloadBase64);
                        String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        String[] tokens = raw.split("\\|");
                        if (tokens.length >= 6) {
                            String pName = tokens[0];
                            String buyer = tokens[1];
                            long expiry = Long.parseLong(tokens[2]);
                            if (expiry > 0 && System.currentTimeMillis() > expiry) {
                                getLogger().severe("==================================================");
                                getLogger().severe("[Bản Quyền] Giấy phép của plugin " + pName + " đã HẾT HẠN vào " + new java.util.Date(expiry) + "!");
                                getLogger().severe("[Bản Quyền] Khách hàng: " + buyer + ". Vui lòng liên hệ tác giả để gia hạn giấy phép.");
                                getLogger().severe("==================================================");
                                getServer().getPluginManager().disablePlugin(this);
                                return;
                            }
                            getLogger().info("[Bản Quyền] Đã xác thực giấy phép: Khách hàng '" + buyer + "' | Hạn dùng: " + (expiry > 0 ? new java.util.Date(expiry) : "Vĩnh viễn (Lifetime)"));
                        }
                    } catch (Exception ignored) {}
                } else if (licenseKey.startsWith("EXPIRED_") || licenseKey.equalsIgnoreCase("INVALID")) {
                    getLogger().severe("==================================================");
                    getLogger().severe("[Bản Quyền] Khóa bản quyền của plugin này không hợp lệ hoặc đã hết hạn!");
                    getLogger().severe("[Bản Quyền] Vui lòng liên hệ tác giả để gia hạn giấy phép.");
                    getLogger().severe("==================================================");
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                } else {
                    getLogger().info("[Bản Quyền] Giấy phép hoạt động: " + licenseKey + " (Đã xác minh)");
                }
            }
        } catch (Exception ignored) {}

        if (mainClassName == null) {
            getLogger().severe("Không tìm thấy thông tin class chính trong file JAR!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            Class<?> clazz = Class.forName(mainClassName);
            dynamicPlugin = (DynamicPlugin) clazz.getDeclaredConstructor().newInstance();

            injectBootstrapFields(dynamicPlugin);

            dynamicPlugin.setEnabled(true);
            dynamicPlugin.onEnable();

            getLogger().info(getName() + " (Exported Plugin) đã được kích hoạt thành công!");
        } catch (Throwable t) {
            getLogger().severe("Lỗi khi khởi chạy plugin: " + t.getMessage());
            t.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (dynamicPlugin != null) {
            try {
                dynamicPlugin.onDisable();
            } catch (Throwable t) {
                getLogger().warning("Lỗi trong onDisable(): " + t.getMessage());
            }

            for (Listener l : registeredListeners) {
                HandlerList.unregisterAll(l);
            }
            registeredListeners.clear();

            for (String cmd : registeredCommands) {
                ReflectionUtil.unregisterCommand(cmd);
            }
            registeredCommands.clear();

            dynamicPlugin.setEnabled(false);
        }
        getLogger().info(getName() + " đã tắt!");
    }

    @SuppressWarnings("deprecation")
    private void injectBootstrapFields(DynamicPlugin dp) {
        try {
            setField(dp, "name", getDescription().getName());
            setField(dp, "version", getDescription().getVersion());
            setField(dp, "author", getDescription().getAuthors().isEmpty() ? "Developer" : getDescription().getAuthors().get(0));
            setField(dp, "description", getDescription().getDescription());
            setField(dp, "dataFolder", getDataFolder());
            setField(dp, "configFile", new File(getDataFolder(), "config.yml"));
            setField(dp, "config", getConfig());
            setField(dp, "logger", getLogger());
        } catch (Exception e) {
            getLogger().warning("Không thể khởi tạo thuộc tính bootstrap: " + e.getMessage());
        }
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field f = DynamicPlugin.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ignored) {}
    }
}
