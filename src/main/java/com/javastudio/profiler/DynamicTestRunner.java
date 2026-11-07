package com.javastudio.profiler;

import com.javastudio.api.DynamicPlugin;
import com.javastudio.api.Test;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class DynamicTestRunner {

    public record TestResult(String methodName, String description, boolean passed, double elapsedMs, String error) {}

    public static List<TestResult> runTests(DynamicPlugin plugin) {
        List<TestResult> results = new ArrayList<>();
        if (plugin == null) return results;

        Class<?> clazz = plugin.getClass();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Test.class)) {
                Test ann = m.getAnnotation(Test.class);
                String desc = ann.description().isEmpty() ? m.getName() : ann.description();
                m.setAccessible(true);

                long start = System.nanoTime();
                boolean passed = false;
                String errorMsg = null;

                try {
                    m.invoke(plugin);
                    passed = true;
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    errorMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                }

                long elapsed = System.nanoTime() - start;
                results.add(new TestResult(m.getName(), desc, passed, elapsed / 1_000_000.0, errorMsg));
            }
        }

        return results;
    }

    public static void runAndReport(CommandSender sender, DynamicPlugin plugin) {
        if (plugin == null) {
            MessageUtil.sendError(sender, "Plugin không tồn tại hoặc chưa được nạp!");
            return;
        }

        MessageUtil.send(sender, "&6=== CHẠY UNIT TEST: &b" + plugin.getName() + " &6===");
        List<TestResult> results = runTests(plugin);

        if (results.isEmpty()) {
            MessageUtil.sendInfo(sender, "Không tìm thấy hàm kiểm thử @Test nào trong plugin &e" + plugin.getName());
            return;
        }

        int passedCount = 0;
        for (TestResult r : results) {
            if (r.passed()) {
                passedCount++;
                MessageUtil.sendRaw(sender, String.format("  &a✔ [PASS] &f%s &7(%.2fms) &8- %s", r.methodName(), r.elapsedMs(), r.description()));
            } else {
                MessageUtil.sendRaw(sender, String.format("  &c✖ [FAIL] &f%s &7(%.2fms) &cError: %s", r.methodName(), r.elapsedMs(), r.error()));
            }
        }

        MessageUtil.sendRaw(sender, "&8--------------------------------------------------");
        if (passedCount == results.size()) {
            MessageUtil.sendSuccess(sender, String.format("Toàn bộ bài test thành công! (%d/%d passed)", passedCount, results.size()));
        } else {
            MessageUtil.sendError(sender, String.format("Có bài test thất bại! (%d/%d passed)", passedCount, results.size()));
        }
    }

    public static TestResult simulateEvent(DynamicPlugin plugin, org.bukkit.event.Event event) {
        if (plugin == null || event == null) {
            return new TestResult("simulateEvent", "Mock Event", false, 0, "Plugin hoặc Event null");
        }

        long start = System.nanoTime();
        boolean handled = false;
        String error = null;

        Class<?> eventClass = event.getClass();
        for (Method m : plugin.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(org.bukkit.event.EventHandler.class)) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0].isAssignableFrom(eventClass)) {
                    m.setAccessible(true);
                    try {
                        m.invoke(plugin, event);
                        handled = true;
                    } catch (Throwable t) {
                        Throwable cause = t.getCause() != null ? t.getCause() : t;
                        error = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                        break;
                    }
                }
            }
        }

        long elapsed = System.nanoTime() - start;
        return new TestResult(
                eventClass.getSimpleName(),
                "Mock Event Simulation",
                handled && error == null,
                elapsed / 1_000_000.0,
                error != null ? error : (handled ? null : "Không tìm thấy @EventHandler nào phù hợp")
        );
    }
}
