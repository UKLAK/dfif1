package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.api.DynamicScript;
import com.javastudio.api.JavaContext;
import com.javastudio.compiler.CompileResult;
import com.javastudio.utils.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class JReplSession implements Listener {

    private final JavaStudioPlugin plugin;
    private final Map<UUID, ReplContext> activeSessions = new ConcurrentHashMap<>();

    private static class ReplContext {
        final List<String> history = new ArrayList<>();
        final List<String> storedDeclarations = new ArrayList<>();
        final StringBuilder multilineBuffer = new StringBuilder();
        int braceDepth = 0;
        int lineCounter = 0;
    }

    @SuppressWarnings("this-escape")
    public JReplSession(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean isInRepl(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public void startRepl(Player player) {
        activeSessions.put(player.getUniqueId(), new ReplContext());
        MessageUtil.send(player, "&6=== CHẾ ĐỘ LẬP TRÌNH TƯƠNG TÁC (JAVA REPL v2) ===");
        MessageUtil.sendRaw(player, "  &7Bạn đang ở trong REPL Mode. Mọi tin nhắn chat sẽ được biên dịch là mã Java!");
        MessageUtil.sendRaw(player, "  &e• &fexit &e/ &fquit &7- Thoát chế độ REPL");
        MessageUtil.sendRaw(player, "  &e• &fvars &7- Xem danh sách các biến trạng thái đang lưu");
        MessageUtil.sendRaw(player, "  &e• &fhistory &7- Xem lại lịch sử các câu lệnh đã chạy");
        MessageUtil.sendRaw(player, "  &e• &fundo &7- Hủy khai báo biến gần nhất");
        MessageUtil.sendRaw(player, "  &e• &fclear &7- Xóa trắng bộ nhớ biến");
        MessageUtil.sendRaw(player, "  &a• Hỗ trợ khối đa dòng: mở ngoặc &f{ &avà đóng &f} &akhi kết thúc khối!");
        MessageUtil.sendRaw(player, "  &bVí dụ: &fint x = 64; &7sau đó dòng tiếp theo: &fgive(\"diamond\", x);");
    }

    public void stopRepl(Player player) {
        if (activeSessions.remove(player.getUniqueId()) != null) {
            MessageUtil.sendSuccess(player, "Đã thoát khỏi chế độ Java REPL!");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ReplContext ctx = activeSessions.get(player.getUniqueId());
        if (ctx == null) return;

        event.setCancelled(true);
        String line = event.getMessage().trim();

        if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
            stopRepl(player);
            return;
        }

        if (line.equalsIgnoreCase("clear")) {
            ctx.history.clear();
            ctx.storedDeclarations.clear();
            ctx.multilineBuffer.setLength(0);
            ctx.braceDepth = 0;
            MessageUtil.sendInfo(player, "Đã xóa toàn bộ bộ đệm biến và trạng thái REPL.");
            return;
        }

        if (line.equalsIgnoreCase("vars")) {
            if (ctx.storedDeclarations.isEmpty()) {
                MessageUtil.sendInfo(player, "Chưa có biến nào được định nghĩa.");
            } else {
                MessageUtil.send(player, "&6=== Danh Sách Biến REPL ===");
                for (String d : ctx.storedDeclarations) {
                    MessageUtil.sendRaw(player, "  &b• &f" + d);
                }
            }
            return;
        }

        if (line.equalsIgnoreCase("history")) {
            if (ctx.history.isEmpty()) {
                MessageUtil.sendInfo(player, "Lịch sử REPL đang trống.");
            } else {
                MessageUtil.send(player, "&6=== Lịch Sử REPL (" + ctx.history.size() + ") ===");
                for (int i = 0; i < ctx.history.size(); i++) {
                    MessageUtil.sendRaw(player, "  &7[" + (i + 1) + "] &f" + ctx.history.get(i));
                }
            }
            return;
        }

        if (line.equalsIgnoreCase("undo")) {
            if (!ctx.storedDeclarations.isEmpty()) {
                String removed = ctx.storedDeclarations.remove(ctx.storedDeclarations.size() - 1);
                MessageUtil.sendSuccess(player, "Đã hủy biến: &e" + removed);
            } else {
                MessageUtil.sendInfo(player, "Không có biến nào để hoàn tác.");
            }
            return;
        }

        int opens = countChars(line, '{');
        int closes = countChars(line, '}');
        ctx.braceDepth += (opens - closes);

        if (ctx.braceDepth > 0) {
            ctx.multilineBuffer.append(" ").append(line);
            MessageUtil.sendRaw(player, "&8[REPL " + ctx.braceDepth + "] &e... | &f" + line);
            return;
        } else if (ctx.multilineBuffer.length() > 0) {
            ctx.multilineBuffer.append(" ").append(line);
            String fullBlock = ctx.multilineBuffer.toString().trim();
            ctx.multilineBuffer.setLength(0);
            ctx.braceDepth = 0;
            line = fullBlock;
        }

        final String codeToExecute = line;
        ctx.history.add(codeToExecute);

        plugin.getServer().getScheduler().runTask(plugin, () -> executeReplLine(player, ctx, codeToExecute));
    }

    private int countChars(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        activeSessions.remove(event.getPlayer().getUniqueId());
    }

    private void executeReplLine(Player player, ReplContext ctx, String line) {
        ctx.lineCounter++;
        String className = "Repl_" + player.getName() + "_" + ctx.lineCounter;
        String fullClassPath = "com.javastudio.runtime." + className;

        boolean isDeclaration = line.matches("^(int|double|float|long|short|byte|boolean|char|String|var|ItemStack|Player|Location|Entity|World)\\s+[a-zA-Z0-9_]+\\s*=.*");

        StringBuilder declarationsBlock = new StringBuilder();
        for (String dec : ctx.storedDeclarations) {
            declarationsBlock.append("        ").append(dec).append("\n");
        }

        String executionLine = line;
        if (!executionLine.contains(";") && !executionLine.contains("{") && !isDeclaration) {
            executionLine = "return String.valueOf(" + executionLine + ");";
        } else if (!executionLine.endsWith(";") && !executionLine.endsWith("}")) {
            executionLine += ";";
        }

        String source = """
                package com.javastudio.runtime;
                import org.bukkit.*;
                import org.bukkit.entity.*;
                import org.bukkit.inventory.*;
                import org.bukkit.potion.*;
                import org.bukkit.util.*;
                import java.util.*;
                import java.util.stream.*;
                import com.javastudio.api.*;

                public class %NAME% extends QuickScript {
                    @Override
                    public Object run() throws Throwable {
                %DECLARATIONS%
                        %LINE%
                        return null;
                    }
                }
                """.replace("%NAME%", className)
                   .replace("%DECLARATIONS%", declarationsBlock.toString())
                   .replace("%LINE%", executionLine);

        CompileResult result = plugin.getCompiler().compileSingleSource(fullClassPath, source);
        if (!result.isSuccess()) {
            MessageUtil.sendRaw(player, "&c✖ Lỗi: " + result.getFormattedErrors().split("\n")[0]);
            return;
        }

        try {
            Class<?> clazz = result.loadClass(fullClassPath);
            DynamicScript script = (DynamicScript) clazz.getDeclaredConstructor().newInstance();
            JavaContext jCtx = new JavaContext(player, plugin, new String[0]);

            Object returned = plugin.getSecuritySandbox().executeWithTimeout(() -> script.execute(jCtx), 3000);
            if (isDeclaration) {
                if (!line.endsWith(";")) line += ";";
                ctx.storedDeclarations.add(line);
                MessageUtil.sendRaw(player, "&b✔ &7[Đã lưu biến]: &e" + line);
            } else if (returned != null) {
                MessageUtil.sendRaw(player, "&a>>> &f" + returned);
            } else {
                MessageUtil.sendRaw(player, "&a✔ &7(Thực thi thành công)");
            }
        } catch (Throwable t) {
            MessageUtil.sendRaw(player, "&c✖ Lỗi thực thi: &f" + t.getMessage());
        }
    }
}
