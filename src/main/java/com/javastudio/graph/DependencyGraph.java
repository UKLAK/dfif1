package com.javastudio.graph;

import java.util.*;
import java.util.logging.Logger;

/**
 * Trình phân tích đồ thị phụ thuộc (Dependency Graph) cho Dynamic Plugins.
 *
 * <p>Tính năng:</p>
 * <ul>
 *     <li><strong>Topological Sort</strong>: Xác định thứ tự nạp plugin chính xác dựa trên depend/softdepend</li>
 *     <li><strong>Circular Detection</strong>: Phát hiện vòng lặp phụ thuộc (A→B→C→A)</li>
 *     <li><strong>Impact Analysis</strong>: Xác định plugins bị ảnh hưởng khi unload/reload 1 plugin</li>
 *     <li><strong>Semantic Versioning</strong>: Kiểm tra tương thích phiên bản giữa các plugins</li>
 *     <li><strong>Visualization</strong>: Xuất đồ thị dạng text/JSON cho debugging</li>
 * </ul>
 *
 * <pre>{@code
 * DependencyGraph graph = new DependencyGraph(logger);
 * graph.addPlugin("EcoShop", "1.0.0", List.of("VaultBridge"), List.of("Hologram"));
 * graph.addPlugin("VaultBridge", "2.0.0", List.of(), List.of());
 * graph.addPlugin("Hologram", "1.5.0", List.of(), List.of());
 *
 * List<String> loadOrder = graph.resolveLoadOrder();
 * // → ["VaultBridge", "Hologram", "EcoShop"]
 *
 * Set<String> affected = graph.getAffectedPlugins("VaultBridge");
 * // → ["EcoShop"]
 * }</pre>
 */
public class DependencyGraph {

    private final Logger logger;
    private final Map<String, PluginNode> nodes = new LinkedHashMap<>();

    /**
     * Thông tin một node trong đồ thị phụ thuộc.
     */
    public static class PluginNode {
        public final String name;
        public final String version;
        public final List<String> hardDepends;    // Bắt buộc phải có
        public final List<String> softDepends;    // Tùy chọn (nạp trước nếu có)
        public final Set<String> dependents = new LinkedHashSet<>(); // Các plugin phụ thuộc vào plugin này

        public PluginNode(String name, String version, List<String> hardDepends, List<String> softDepends) {
            this.name = name;
            this.version = version;
            this.hardDepends = hardDepends != null ? new ArrayList<>(hardDepends) : new ArrayList<>();
            this.softDepends = softDepends != null ? new ArrayList<>(softDepends) : new ArrayList<>();
        }
    }

    public DependencyGraph(Logger logger) {
        this.logger = logger;
    }

    public Logger getLogger() {
        return logger;
    }

    /**
     * Thêm plugin vào đồ thị phụ thuộc.
     *
     * @param name        Tên plugin
     * @param version     Phiên bản (Semantic Versioning, ví dụ: "1.2.3")
     * @param hardDepends Danh sách plugin bắt buộc phải nạp trước
     * @param softDepends Danh sách plugin tùy chọn nạp trước nếu có
     */
    public void addPlugin(String name, String version, List<String> hardDepends, List<String> softDepends) {
        PluginNode node = new PluginNode(name, version, hardDepends, softDepends);
        nodes.put(name.toLowerCase(), node);

        // Cập nhật reverse dependencies
        for (String dep : node.hardDepends) {
            PluginNode depNode = nodes.get(dep.toLowerCase());
            if (depNode != null) {
                depNode.dependents.add(name);
            }
        }
        for (String dep : node.softDepends) {
            PluginNode depNode = nodes.get(dep.toLowerCase());
            if (depNode != null) {
                depNode.dependents.add(name);
            }
        }
    }

    /**
     * Xoá plugin khỏi đồ thị.
     */
    public void removePlugin(String name) {
        PluginNode removed = nodes.remove(name.toLowerCase());
        if (removed != null) {
            // Xoá khỏi dependents của các plugin khác
            for (PluginNode node : nodes.values()) {
                node.dependents.remove(name);
            }
        }
    }

    // ========================================
    // TOPOLOGICAL SORT (Thứ tự nạp)
    // ========================================

    /**
     * Xác định thứ tự nạp plugin chính xác bằng Topological Sort (Kahn's Algorithm).
     *
     * @return Danh sách tên plugin theo thứ tự nạp, hoặc empty nếu có vòng lặp
     * @throws IllegalStateException Nếu phát hiện vòng lặp phụ thuộc (Circular Dependency)
     */
    public List<String> resolveLoadOrder() {
        // Xây dựng in-degree map
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, Set<String>> adjList = new LinkedHashMap<>();

        for (PluginNode node : nodes.values()) {
            String key = node.name.toLowerCase();
            inDegree.putIfAbsent(key, 0);
            adjList.putIfAbsent(key, new LinkedHashSet<>());

            for (String dep : node.hardDepends) {
                String depKey = dep.toLowerCase();
                if (nodes.containsKey(depKey)) {
                    adjList.computeIfAbsent(depKey, k -> new LinkedHashSet<>()).add(key);
                    inDegree.merge(key, 1, Integer::sum);
                }
            }
            for (String dep : node.softDepends) {
                String depKey = dep.toLowerCase();
                if (nodes.containsKey(depKey)) {
                    adjList.computeIfAbsent(depKey, k -> new LinkedHashSet<>()).add(key);
                    inDegree.merge(key, 1, Integer::sum);
                }
            }
        }

        // Kahn's algorithm
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            PluginNode node = nodes.get(current);
            result.add(node != null ? node.name : current);

            Set<String> neighbors = adjList.getOrDefault(current, Collections.emptySet());
            for (String neighbor : neighbors) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (result.size() < nodes.size()) {
            List<String> cycle = detectCycle();
            String cycleStr = cycle.isEmpty() ? "unknown" : String.join(" → ", cycle);
            throw new IllegalStateException("Phát hiện vòng lặp phụ thuộc (Circular Dependency): " + cycleStr);
        }

        return result;
    }

    // ========================================
    // CIRCULAR DEPENDENCY DETECTION
    // ========================================

    /**
     * Phát hiện vòng lặp phụ thuộc trong đồ thị (DFS cycle detection).
     *
     * @return Danh sách tên plugin trong vòng lặp, hoặc empty nếu không có
     */
    public List<String> detectCycle() {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        List<String> cyclePath = new ArrayList<>();

        for (String key : nodes.keySet()) {
            if (!visited.contains(key)) {
                if (dfsFindCycle(key, visited, inStack, cyclePath)) {
                    return cyclePath;
                }
            }
        }
        return Collections.emptyList();
    }

    private boolean dfsFindCycle(String current, Set<String> visited, Set<String> inStack, List<String> path) {
        visited.add(current);
        inStack.add(current);
        path.add(nodes.containsKey(current) ? nodes.get(current).name : current);

        PluginNode node = nodes.get(current);
        if (node != null) {
            for (String dep : node.hardDepends) {
                String depKey = dep.toLowerCase();
                if (!visited.contains(depKey) && nodes.containsKey(depKey)) {
                    if (dfsFindCycle(depKey, visited, inStack, path)) return true;
                } else if (inStack.contains(depKey)) {
                    path.add(dep);
                    return true;
                }
            }
        }

        inStack.remove(current);
        path.remove(path.size() - 1);
        return false;
    }

    // ========================================
    // IMPACT ANALYSIS
    // ========================================

    /**
     * Xác định tất cả plugin sẽ bị ảnh hưởng khi unload/reload một plugin.
     *
     * @param pluginName Tên plugin gốc
     * @return Set các plugin phụ thuộc (trực tiếp và gián tiếp)
     */
    public Set<String> getAffectedPlugins(String pluginName) {
        Set<String> affected = new LinkedHashSet<>();
        collectAffected(pluginName.toLowerCase(), affected);
        affected.remove(pluginName.toLowerCase());
        return affected;
    }

    private void collectAffected(String key, Set<String> affected) {
        if (affected.contains(key)) return;
        affected.add(key);

        PluginNode node = nodes.get(key);
        if (node != null) {
            for (String dependent : node.dependents) {
                collectAffected(dependent.toLowerCase(), affected);
            }
        }
    }

    /**
     * Kiểm tra tất cả hard dependencies đã sẵn sàng (đã có trong đồ thị).
     *
     * @param pluginName Tên plugin cần kiểm tra
     * @return Danh sách dependencies thiếu, hoặc empty nếu đủ
     */
    public List<String> getMissingDependencies(String pluginName) {
        PluginNode node = nodes.get(pluginName.toLowerCase());
        if (node == null) return Collections.emptyList();

        List<String> missing = new ArrayList<>();
        for (String dep : node.hardDepends) {
            if (!nodes.containsKey(dep.toLowerCase())) {
                missing.add(dep);
            }
        }
        return missing;
    }

    // ========================================
    // SEMANTIC VERSIONING
    // ========================================

    /**
     * So sánh 2 phiên bản Semantic Versioning.
     *
     * @return Số âm nếu v1 < v2, 0 nếu bằng, số dương nếu v1 > v2
     */
    public static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.replaceAll("[^0-9.]", "").split("\\.");
        String[] parts2 = v2.replaceAll("[^0-9.]", "").split("\\.");

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int num1 = i < parts1.length ? parseInt(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseInt(parts2[i]) : 0;
            if (num1 != num2) return Integer.compare(num1, num2);
        }
        return 0;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * Kiểm tra tương thích phiên bản (Major version phải bằng nhau).
     */
    public static boolean isCompatible(String required, String actual) {
        String[] req = required.replaceAll("[^0-9.]", "").split("\\.");
        String[] act = actual.replaceAll("[^0-9.]", "").split("\\.");

        // Major version phải bằng nhau
        int reqMajor = req.length > 0 ? parseInt(req[0]) : 0;
        int actMajor = act.length > 0 ? parseInt(act[0]) : 0;

        if (reqMajor != actMajor) return false;

        // Actual version phải >= required
        return compareVersions(actual, required) >= 0;
    }

    // ========================================
    // VISUALIZATION
    // ========================================

    /**
     * Lấy thông tin node plugin.
     */
    public PluginNode getNode(String name) {
        return nodes.get(name.toLowerCase());
    }

    /**
     * Lấy tất cả nodes trong đồ thị.
     */
    public Map<String, PluginNode> getAllNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    /**
     * Xuất đồ thị dạng text (ASCII art).
     */
    public String toTextGraph() {
        StringBuilder sb = new StringBuilder("=== Dependency Graph ===\n");
        for (PluginNode node : nodes.values()) {
            sb.append("  [").append(node.name).append(" v").append(node.version).append("]");
            if (!node.hardDepends.isEmpty()) {
                sb.append(" → depends: ").append(String.join(", ", node.hardDepends));
            }
            if (!node.softDepends.isEmpty()) {
                sb.append(" (soft: ").append(String.join(", ", node.softDepends)).append(")");
            }
            if (!node.dependents.isEmpty()) {
                sb.append(" ← used by: ").append(String.join(", ", node.dependents));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Xuất đồ thị dạng JSON cho Web IDE.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"nodes\":[");
        boolean first = true;
        for (PluginNode node : nodes.values()) {
            if (!first) sb.append(",");
            sb.append("{\"name\":\"").append(node.name)
                    .append("\",\"version\":\"").append(node.version)
                    .append("\",\"hardDepends\":").append(listToJson(node.hardDepends))
                    .append(",\"softDepends\":").append(listToJson(node.softDepends))
                    .append(",\"dependents\":").append(listToJson(new ArrayList<>(node.dependents)))
                    .append("}");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String listToJson(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(list.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
