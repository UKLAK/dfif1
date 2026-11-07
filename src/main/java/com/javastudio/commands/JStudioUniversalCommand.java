package com.javastudio.commands;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class JStudioUniversalCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ALL_MODULES = List.of(
            "exec", "plugin", "script", "studio", "control", "network", "exploit",
            "ac", "hud", "spy", "security", "diag", "ai", "packet", "pack",
            "license", "trace", "chunk", "bot", "heap", "bridge", "2fa",
            "mesh", "flow", "prefetch", "gui", "git", "mock", "db", "bench", "help"
    );

    private final JavaStudioPlugin plugin;

    public JStudioUniversalCommand(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) {
            MessageUtil.send(sender, "&cBạn không có quyền thực hiện lệnh này!");
            return true;
        }

        if (!plugin.getSettingsManager().isUniversalCommandEnabled()) {
            MessageUtil.send(sender, "&cLệnh gom nhóm toàn cục (/js) đang bị TẮT trong cài đặt! Dùng /javastudio settings để bật.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendUniversalHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String targetCommandName = resolveTargetCommand(sub);

        if (targetCommandName == null) {
            sendUniversalHelp(sender);
            return true;
        }

        var targetCmd = plugin.getCommand(targetCommandName);
        if (targetCmd != null && targetCmd.getExecutor() != null) {
            String[] forwardedArgs = Arrays.copyOfRange(args, 1, args.length);
            return targetCmd.getExecutor().onCommand(sender, targetCmd, targetCommandName, forwardedArgs);
        } else {
            MessageUtil.send(sender, "&cKhông tìm thấy bộ xử lý lệnh cho phân hệ: " + sub);
            return true;
        }
    }

    private String resolveTargetCommand(String sub) {
        return switch (sub) {
            case "exec", "java", "run" -> "jexec";
            case "plugin", "p" -> "jplugin";
            case "script", "scripts" -> "jscript";
            case "studio", "settings", "modules", "config" -> "javastudio";
            case "control", "server", "pm" -> "jcontrol";
            case "network", "net" -> "jnetwork";
            case "exploit", "fixer" -> "jexploit";
            case "ac", "anticheat" -> "jac";
            case "hud", "tpsbar" -> "jhud";
            case "spy", "eventspy" -> "jspy";
            case "security", "sec", "sentinel" -> "jsecurity";
            case "2fa", "totp" -> "j2fa";
            case "diag", "doctor", "telemetry" -> "jdiag";
            case "ai", "copilot" -> "jai";
            case "packet", "pkt", "sniffer" -> "jpacket";
            case "pack", "rp" -> "jpack";
            case "license", "drm" -> "jlicense";
            case "trace", "breaker" -> "jtrace";
            case "chunk", "arena", "fastblock" -> "jchunk";
            case "bot", "bots" -> "jbot";
            case "heap", "mem" -> "jheap";
            case "bridge", "vscode" -> "jbridge";
            case "mesh", "raft" -> "jmesh";
            case "flow", "blueprint" -> "jflow";
            case "prefetch" -> "jprefetch";
            case "gui", "designer" -> "jgui";
            case "git", "snapshot", "rollback" -> "jgit";
            case "mock", "test" -> "jmock";
            case "db", "sql" -> "jdb";
            case "bench", "benchmark", "servertest", "testserver" -> "jbench";
            default -> null;
        };
    }

    private void sendUniversalHelp(CommandSender sender) {
        MessageUtil.send(sender, "&6╔══════════════════════════════════════════════════════════╗");
        MessageUtil.send(sender, "&6║   &eJavaStudio Universal CLI &7(/js <phân_hệ> <tham_số...>)   &6║");
        MessageUtil.send(sender, "&6╚══════════════════════════════════════════════════════════╝");
        MessageUtil.send(sender, "&b▸ Runtime & Dev: &fexec, plugin, script, ai, git, mock, db, bridge");
        MessageUtil.send(sender, "&a▸ World & Visual: &fchunk, prefetch, gui, flow, pack, bot, hud");
        MessageUtil.send(sender, "&c▸ Security & AC: &fac, security, 2fa, exploit, license, packet");
        MessageUtil.send(sender, "&d▸ Ops & Cluster: &fstudio, control, trace, heap, diag, spy, mesh, network");
        MessageUtil.send(sender, "&7Ví dụ: &e/js exec heal() &8| &e/js chunk list &8| &e/js mesh kv list &8| &e/js studio settings");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("javastudio.admin") && !sender.isOp()) return List.of();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> res = new ArrayList<>();
            for (String m : ALL_MODULES) {
                if (m.startsWith(prefix)) res.add(m);
            }
            return res;
        }

        if (args.length >= 2) {
            String targetCommandName = resolveTargetCommand(args[0].toLowerCase(Locale.ROOT));
            if (targetCommandName != null) {
                var targetCmd = plugin.getCommand(targetCommandName);
                if (targetCmd != null && targetCmd.getTabCompleter() != null) {
                    String[] forwardedArgs = Arrays.copyOfRange(args, 1, args.length);
                    return targetCmd.getTabCompleter().onTabComplete(sender, targetCmd, targetCommandName, forwardedArgs);
                }
            }
        }

        return List.of();
    }
}
