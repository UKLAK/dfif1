package com.javastudio.inspector;

import com.javastudio.JavaStudioPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

public class HeapInspector {

    @SuppressWarnings("unused")
    private final JavaStudioPlugin plugin;

    public HeapInspector(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public record PathResult(Object parent, Field targetField, Object value, Class<?> type) {}

    public record MemorySizeReport(long totalBytes, int objectCount, Map<String, Long> topFields) {}

    public PathResult resolve(CommandSender sender, String path) throws Exception {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Đường dẫn thuộc tính không được rỗng");
        }

        String[] parts = path.split("\\.");
        if (parts.length == 0) {
            throw new IllegalArgumentException("Cú pháp đường dẫn không hợp lệ");
        }

        String rootKey = parts[0].trim();
        Object current = null;

        if (rootKey.equalsIgnoreCase("@p")) {
            current = (sender instanceof Player p) ? p : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (current == null) throw new IllegalStateException("Không tìm thấy người chơi nào trực tuyến để phân giải @p");
        } else if (rootKey.equalsIgnoreCase("@s")) {
            current = sender;
        } else if (rootKey.equalsIgnoreCase("@server")) {
            current = Bukkit.getServer();
        } else {
            Plugin targetPlugin = Bukkit.getPluginManager().getPlugin(rootKey);
            if (targetPlugin != null) {
                current = targetPlugin;
            } else {
                try {
                    Class<?> clazz = Class.forName(rootKey);
                    current = clazz;
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Không thể phân giải đối tượng gốc: " + rootKey + " (Không phải @p, @s, @server, tên Plugin hoặc Class)");
                }
            }
        }

        Field lastField = null;
        Object parent = null;

        for (int i = 1; i < parts.length; i++) {
            if (current == null) {
                throw new NullPointerException("Thuộc tính ở bước " + parts[i - 1] + " trả về null (NullPointerException)!");
            }

            String part = parts[i].trim();
            parent = current;

            if (part.endsWith("()")) {
                String methodName = part.substring(0, part.length() - 2);
                Method method = findMethod(current.getClass(), methodName);
                if (method == null) {
                    throw new NoSuchMethodException("Không tìm thấy phương thức: " + methodName + "() trên lớp " + current.getClass().getName());
                }
                method.setAccessible(true);
                current = method.invoke(current);
                lastField = null;
                continue;
            }

            if (part.contains("[") && part.endsWith("]")) {
                int open = part.indexOf('[');
                String fieldName = part.substring(0, open);
                String indexKey = part.substring(open + 1, part.length() - 1);

                if (!fieldName.isEmpty()) {
                    Field field = findField(current.getClass(), fieldName);
                    if (field == null) {
                        throw new NoSuchFieldException("Không tìm thấy trường: " + fieldName + " trên lớp " + current.getClass().getName());
                    }
                    field.setAccessible(true);
                    current = field.get(current);
                    parent = current;
                }

                if (current instanceof Map<?, ?> map) {
                    current = map.get(indexKey);
                    lastField = null;
                } else if (current instanceof List<?> list) {
                    int idx = Integer.parseInt(indexKey);
                    current = list.get(idx);
                    lastField = null;
                } else if (current != null && current.getClass().isArray()) {
                    int idx = Integer.parseInt(indexKey);
                    current = java.lang.reflect.Array.get(current, idx);
                    lastField = null;
                }
                continue;
            }

            Field field = findField(current.getClass(), part);
            if (field == null) {
                String getterName = "get" + Character.toUpperCase(part.charAt(0)) + part.substring(1);
                Method getter = findMethod(current.getClass(), getterName);
                if (getter != null) {
                    getter.setAccessible(true);
                    current = getter.invoke(current);
                    lastField = null;
                    continue;
                }
                throw new NoSuchFieldException("Không tìm thấy trường hoặc getter: " + part + " trên lớp " + current.getClass().getName());
            }

            field.setAccessible(true);
            lastField = field;
            current = field.get(current);
        }

        Class<?> type = current != null ? current.getClass() : (lastField != null ? lastField.getType() : Object.class);
        return new PathResult(parent, lastField, current, type);
    }

    public Object setProperty(CommandSender sender, String path, String rawValue) throws Exception {
        PathResult res = resolve(sender, path);
        if (res.targetField() == null) {
            throw new IllegalStateException("Đích đến của đường dẫn không phải là một Field có thể gán (có thể do gọi phương thức)");
        }
        if (res.parent() == null) {
            throw new IllegalStateException("Không xác định được đối tượng cha chứa trường dữ liệu!");
        }

        Field field = res.targetField();
        field.setAccessible(true);

        try {
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (Throwable ignored) {}

        Object convertedValue = convertStringToType(rawValue, field.getType());
        Object oldValue = field.get(res.parent());
        field.set(res.parent(), convertedValue);

        return oldValue;
    }

    public MemorySizeReport calculateSizeOf(Object root) {
        if (root == null) return new MemorySizeReport(0, 0, Collections.emptyMap());

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Queue<Object> queue = new ArrayDeque<>();
        queue.add(root);
        visited.add(root);

        long totalBytes = 0;
        int count = 0;
        Map<String, Long> fieldSizes = new HashMap<>();

        while (!queue.isEmpty() && count < 30000) {
            Object obj = queue.poll();
            count++;

            Class<?> clazz = obj.getClass();
            if (clazz.isArray()) {
                int length = java.lang.reflect.Array.getLength(obj);
                Class<?> comp = clazz.getComponentType();
                int elemSize = comp.isPrimitive() ? getPrimitiveSize(comp) : 8;
                totalBytes += 16L + ((long) length * elemSize);

                if (!comp.isPrimitive()) {
                    for (int i = 0; i < length; i++) {
                        Object elem = java.lang.reflect.Array.get(obj, i);
                        if (elem != null && visited.add(elem)) {
                            queue.add(elem);
                        }
                    }
                }
            } else {
                totalBytes += 16L;
                for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);

                        Class<?> fType = f.getType();
                        int fSize = fType.isPrimitive() ? getPrimitiveSize(fType) : 8;
                        totalBytes += fSize;

                        if (!fType.isPrimitive()) {
                            try {
                                Object child = f.get(obj);
                                if (child != null) {
                                    long currentFSize = fieldSizes.getOrDefault(f.getName(), 0L);
                                    fieldSizes.put(f.getName(), currentFSize + 8);
                                    if (visited.add(child)) {
                                        queue.add(child);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }

        return new MemorySizeReport(totalBytes, count, fieldSizes);
    }

    private Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equalsIgnoreCase(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
        }
        return null;
    }

    private int getPrimitiveSize(Class<?> prim) {
        if (prim == byte.class || prim == boolean.class) return 1;
        if (prim == short.class || prim == char.class) return 2;
        if (prim == int.class || prim == float.class) return 4;
        if (prim == long.class || prim == double.class) return 8;
        return 8;
    }

    private Object convertStringToType(String str, Class<?> target) {
        if (target == String.class) return str;
        if (target == int.class || target == Integer.class) return Integer.parseInt(str);
        if (target == long.class || target == Long.class) return Long.parseLong(str);
        if (target == double.class || target == Double.class) return Double.parseDouble(str);
        if (target == float.class || target == Float.class) return Float.parseFloat(str);
        if (target == boolean.class || target == Boolean.class) return Boolean.parseBoolean(str);
        if (target == byte.class || target == Byte.class) return Byte.parseByte(str);
        if (target == short.class || target == Short.class) return Short.parseShort(str);
        if (target.isEnum()) {
            for (Object constant : target.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(str)) {
                    return constant;
                }
            }
        }
        return str;
    }
}
