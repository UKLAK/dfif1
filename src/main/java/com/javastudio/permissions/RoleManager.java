package com.javastudio.permissions;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hệ thống quản lý quyền hạn theo vai trò (Role-Based Access Control) cho Dynamic Plugins.
 *
 * <p>Tính năng:</p>
 * <ul>
 *     <li><strong>Role Management</strong>: Tạo vai trò với danh sách permissions</li>
 *     <li><strong>Role Inheritance</strong>: Vai trò kế thừa quyền từ vai trò cha</li>
 *     <li><strong>Per-Plugin Roles</strong>: Mỗi dynamic plugin có thể tự định nghĩa roles riêng</li>
 *     <li><strong>Player-Role Mapping</strong>: Gán vai trò cho người chơi</li>
 *     <li><strong>Wildcard Support</strong>: Hỗ trợ wildcard permissions (ví dụ: "myplugin.*")</li>
 *     <li><strong>Persistent Storage</strong>: Tự động lưu trữ và khôi phục qua YML</li>
 * </ul>
 *
 * <pre>{@code
 * RoleManager roles = plugin.getRoleManager();
 *
 * // Tạo vai trò
 * roles.createRole("vip", List.of("myplugin.shop", "myplugin.fly", "myplugin.kit.vip"));
 * roles.createRole("admin", List.of("myplugin.*"), "vip"); // Kế thừa từ VIP
 *
 * // Gán vai trò cho người chơi
 * roles.setPlayerRole(player.getUniqueId(), "vip");
 *
 * // Kiểm tra quyền
 * if (roles.hasPermission(player, "myplugin.shop")) { ... }
 * }</pre>
 */
public class RoleManager {

    private final JavaStudioPlugin plugin;
    private final File rolesFile;
    private final Map<String, Role> roles = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRoles = new ConcurrentHashMap<>();

    /**
     * Định nghĩa một vai trò với danh sách quyền hạn.
     */
    public static class Role {
        public final String name;
        public final Set<String> permissions;
        public String parent; // Tên vai trò cha (kế thừa)
        public int priority;  // Ưu tiên (số cao hơn = quyền hạn cao hơn)

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

    // ========================================
    // ROLE MANAGEMENT
    // ========================================

    /**
     * Tạo vai trò mới.
     *
     * @param name        Tên vai trò (không phân biệt hoa thường)
     * @param permissions Danh sách quyền hạn
     */
    public void createRole(String name, List<String> permissions) {
        createRole(name, permissions, null, 0);
    }

    /**
     * Tạo vai trò mới kế thừa từ vai trò cha.
     */
    public void createRole(String name, List<String> permissions, String parent) {
        createRole(name, permissions, parent, 0);
    }

    /**
     * Tạo vai trò mới đầy đủ thông tin.
     */
    public void createRole(String name, List<String> permissions, String parent, int priority) {
        roles.put(name.toLowerCase(), new Role(name, new LinkedHashSet<>(permissions), parent, priority));
        saveRoles();
    }

    /**
     * Xoá vai trò.
     */
    public boolean deleteRole(String name) {
        Role removed = roles.remove(name.toLowerCase());
        if (removed != null) {
            // Xoá gán vai trò cho người chơi
            playerRoles.entrySet().removeIf(e -> e.getValue().equalsIgnoreCase(name));
            saveRoles();
            return true;
        }
        return false;
    }

    /**
     * Thêm quyền hạn vào vai trò.
     */
    public boolean addPermission(String roleName, String permission) {
        Role role = roles.get(roleName.toLowerCase());
        if (role != null) {
            role.permissions.add(permission.toLowerCase());
            saveRoles();
            return true;
        }
        return false;
    }

    /**
     * Xoá quyền hạn khỏi vai trò.
     */
    public boolean removePermission(String roleName, String permission) {
        Role role = roles.get(roleName.toLowerCase());
        if (role != null) {
            role.permissions.remove(permission.toLowerCase());
            saveRoles();
            return true;
        }
        return false;
    }

    /**
     * Lấy vai trò theo tên.
     */
    public Role getRole(String name) {
        return roles.get(name.toLowerCase());
    }

    /**
     * Lấy tất cả vai trò.
     */
    public Collection<Role> getAllRoles() {
        return Collections.unmodifiableCollection(roles.values());
    }

    // ========================================
    // PLAYER-ROLE MAPPING
    // ========================================

    /**
     * Gán vai trò cho người chơi.
     */
    public void setPlayerRole(UUID playerId, String roleName) {
        playerRoles.put(playerId, roleName.toLowerCase());
        saveRoles();
    }

    /**
     * Xoá vai trò của người chơi.
     */
    public void removePlayerRole(UUID playerId) {
        playerRoles.remove(playerId);
        saveRoles();
    }

    /**
     * Lấy vai trò hiện tại của người chơi.
     */
    public String getPlayerRole(UUID playerId) {
        return playerRoles.get(playerId);
    }

    /**
     * Lấy vai trò hiện tại của người chơi dạng Role object.
     */
    public Role getPlayerRoleObject(UUID playerId) {
        String roleName = playerRoles.get(playerId);
        return roleName != null ? roles.get(roleName) : null;
    }

    // ========================================
    // PERMISSION CHECKING
    // ========================================

    /**
     * Kiểm tra quyền hạn của người chơi thông qua hệ thống Role.
     *
     * @param player     Người chơi
     * @param permission Node quyền cần kiểm tra
     * @return true nếu có quyền
     */
    public boolean hasPermission(Player player, String permission) {
        // OP luôn có quyền
        if (player.isOp()) return true;

        // Kiểm tra qua Bukkit permission system trước
        if (player.hasPermission(permission)) return true;

        // Kiểm tra qua Role system
        String roleName = playerRoles.get(player.getUniqueId());
        if (roleName == null) return false;

        return roleHasPermission(roleName, permission, new HashSet<>());
    }

    /**
     * Kiểm tra quyền hạn của CommandSender (hỗ trợ cả Console).
     */
    public boolean hasPermission(CommandSender sender, String permission) {
        if (sender.isOp()) return true;
        if (sender.hasPermission(permission)) return true;

        if (sender instanceof Player player) {
            return hasPermission(player, permission);
        }
        return true; // Console luôn có quyền
    }

    private boolean roleHasPermission(String roleName, String permission, Set<String> visited) {
        if (visited.contains(roleName)) return false; // Tránh vòng lặp kế thừa
        visited.add(roleName);

        Role role = roles.get(roleName.toLowerCase());
        if (role == null) return false;

        String permLower = permission.toLowerCase();

        for (String perm : role.permissions) {
            // Exact match
            if (perm.equals(permLower)) return true;

            // Wildcard match (myplugin.* matches myplugin.anything)
            if (perm.endsWith(".*")) {
                String prefix = perm.substring(0, perm.length() - 2);
                if (permLower.startsWith(prefix)) return true;
            }

            // Full wildcard
            if (perm.equals("*")) return true;
        }

        // Kiểm tra vai trò cha (inheritance)
        if (role.parent != null && !role.parent.isBlank()) {
            return roleHasPermission(role.parent, permission, visited);
        }

        return false;
    }

    /**
     * Lấy tất cả quyền hạn hiệu lực của vai trò (bao gồm kế thừa).
     */
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

    // ========================================
    // PERSISTENCE
    // ========================================

    private void loadRoles() {
        if (!rolesFile.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(rolesFile);

        // Load roles
        if (config.isConfigurationSection("roles")) {
            for (String key : config.getConfigurationSection("roles").getKeys(false)) {
                String path = "roles." + key;
                List<String> perms = config.getStringList(path + ".permissions");
                String parent = config.getString(path + ".parent", null);
                int priority = config.getInt(path + ".priority", 0);
                roles.put(key.toLowerCase(), new Role(key, new LinkedHashSet<>(perms), parent, priority));
            }
        }

        // Load player-role mappings
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

        // Save roles
        for (Map.Entry<String, Role> entry : roles.entrySet()) {
            Role role = entry.getValue();
            String path = "roles." + entry.getKey();
            config.set(path + ".permissions", new ArrayList<>(role.permissions));
            if (role.parent != null) config.set(path + ".parent", role.parent);
            config.set(path + ".priority", role.priority);
        }

        // Save player-role mappings
        for (Map.Entry<UUID, String> entry : playerRoles.entrySet()) {
            config.set("players." + entry.getKey().toString(), entry.getValue());
        }

        try {
            config.save(rolesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[RoleManager] Không thể lưu file roles.yml: " + e.getMessage());
        }
    }

    /**
     * Chuyển danh sách roles thành JSON cho Web IDE.
     */
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
