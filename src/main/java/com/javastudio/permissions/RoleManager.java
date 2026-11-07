package com.javastudio.permissions;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RoleManager {

    private final JavaStudioPlugin plugin;
    private final File rolesFile;
    private final Map<String, Role> roles = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRoles = new ConcurrentHashMap<>();

    public static class Role {
        public final String name;
        public final Set<String> permissions;
        public String parent;
        public int priority;

        public Role(String name, Set<String> permissions, String parent, int priority) {
            this.name = name;
            this.permissions = new LinkedHashSet<>(permissions);
            this.parent = parent;
            this.priority = priority;
        }
    }

    public RoleManager(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.rolesFile = new File(plugin.getDataFolder(), "roles.yml");
        loadRoles();
    }


    public void createRole(String name, List<String> permissions) {
        createRole(name, permissions, null, 0);
    }

    public void createRole(String name, List<String> permissions, String parent) {
        createRole(name, permissions, parent, 0);
    }

    public void createRole(String name, List<String> permissions, String parent, int priority) {
        roles.put(name.toLowerCase(), new Role(name, new LinkedHashSet<>(permissions), parent, priority));
        saveRoles();
    }

    public boolean deleteRole(String name) {
        Role removed = roles.remove(name.toLowerCase());
        if (removed != null) {
            playerRoles.entrySet().removeIf(e -> e.getValue().equalsIgnoreCase(name));
            saveRoles();
            return true;
        }
        return false;
    }

    public boolean addPermission(String roleName, String permission) {
        Role role = roles.get(roleName.toLowerCase());
        if (role != null) {
            role.permissions.add(permission.toLowerCase());
            saveRoles();
            return true;
        }
        return false;
    }

    public boolean removePermission(String roleName, String permission) {
        Role role = roles.get(roleName.toLowerCase());
        if (role != null) {
            role.permissions.remove(permission.toLowerCase());
            saveRoles();
            return true;
        }
        return false;
    }

    public Role getRole(String name) {
        return roles.get(name.toLowerCase());
    }

    public Collection<Role> getAllRoles() {
        return Collections.unmodifiableCollection(roles.values());
    }


    public void setPlayerRole(UUID playerId, String roleName) {
        playerRoles.put(playerId, roleName.toLowerCase());
        saveRoles();
    }

    public void removePlayerRole(UUID playerId) {
        playerRoles.remove(playerId);
        saveRoles();
    }

    public String getPlayerRole(UUID playerId) {
        return playerRoles.get(playerId);
    }

    public Role getPlayerRoleObject(UUID playerId) {
        String roleName = playerRoles.get(playerId);
        return roleName != null ? roles.get(roleName) : null;
    }


    public boolean hasPermission(Player player, String permission) {
        if (player.isOp()) return true;

        if (player.hasPermission(permission)) return true;

        String roleName = playerRoles.get(player.getUniqueId());
        if (roleName == null) return false;

        return roleHasPermission(roleName, permission, new HashSet<>());
    }

    public boolean hasPermission(CommandSender sender, String permission) {
        if (sender.isOp()) return true;
        if (sender.hasPermission(permission)) return true;

        if (sender instanceof Player player) {
            return hasPermission(player, permission);
        }
        return true;
    }

    private boolean roleHasPermission(String roleName, String permission, Set<String> visited) {
        if (visited.contains(roleName)) return false;
        visited.add(roleName);

        Role role = roles.get(roleName.toLowerCase());
        if (role == null) return false;

        String permLower = permission.toLowerCase();

        for (String perm : role.permissions) {
            if (perm.equals(permLower)) return true;

            if (perm.endsWith(".*")) {
                String prefix = perm.substring(0, perm.length() - 2);
                if (permLower.startsWith(prefix)) return true;
            }

            if (perm.equals("*")) return true;
        }

        if (role.parent != null && !role.parent.isBlank()) {
            return roleHasPermission(role.parent, permission, visited);
        }

        return false;
    }

    public Set<String> getEffectivePermissions(String roleName) {
        Set<String> effective = new LinkedHashSet<>();
        collectPermissions(roleName, effective, new HashSet<>());
        return effective;
    }

    private void collectPermissions(String roleName, Set<String> result, Set<String> visited) {
        if (visited.contains(roleName)) return;
        visited.add(roleName);

        Role role = roles.get(roleName.toLowerCase());
        if (role == null) return;

        result.addAll(role.permissions);

        if (role.parent != null && !role.parent.isBlank()) {
            collectPermissions(role.parent, result, visited);
        }
    }


    private void loadRoles() {
        if (!rolesFile.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(rolesFile);

        if (config.isConfigurationSection("roles")) {
            for (String key : config.getConfigurationSection("roles").getKeys(false)) {
                String path = "roles." + key;
                List<String> perms = config.getStringList(path + ".permissions");
                String parent = config.getString(path + ".parent", null);
                int priority = config.getInt(path + ".priority", 0);
                roles.put(key.toLowerCase(), new Role(key, new LinkedHashSet<>(perms), parent, priority));
            }
        }

        if (config.isConfigurationSection("players")) {
            for (String uuidStr : config.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String roleName = config.getString("players." + uuidStr);
                    if (roleName != null) {
                        playerRoles.put(uuid, roleName.toLowerCase());
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }

        plugin.getLogger().info("[RoleManager] Đã tải " + roles.size() + " vai trò và " + playerRoles.size() + " gán người chơi.");
    }

    private void saveRoles() {
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, Role> entry : roles.entrySet()) {
            Role role = entry.getValue();
            String path = "roles." + entry.getKey();
            config.set(path + ".permissions", new ArrayList<>(role.permissions));
            if (role.parent != null) config.set(path + ".parent", role.parent);
            config.set(path + ".priority", role.priority);
        }

        for (Map.Entry<UUID, String> entry : playerRoles.entrySet()) {
            config.set("players." + entry.getKey().toString(), entry.getValue());
        }

        try {
            config.save(rolesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[RoleManager] Không thể lưu file roles.yml: " + e.getMessage());
        }
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"roles\":[");
        boolean first = true;
        for (Role role : roles.values()) {
            if (!first) sb.append(",");
            sb.append("{\"name\":\"").append(role.name)
                    .append("\",\"priority\":").append(role.priority)
                    .append(",\"parent\":").append(role.parent != null ? "\"" + role.parent + "\"" : "null")
                    .append(",\"permissions\":[");
            boolean pFirst = true;
            for (String perm : role.permissions) {
                if (!pFirst) sb.append(",");
                sb.append("\"").append(perm).append("\"");
                pFirst = false;
            }
            sb.append("]}");
            first = false;
        }
        sb.append("],\"playerCount\":").append(playerRoles.size()).append("}");
        return sb.toString();
    }
}
