package com.javastudio.anticheat;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.PlatformUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Hướng 2: Web Dashboard & Live Radar cho Anti-Cheat.
 * Tích hợp sẵn máy chủ HTTP siêu nhẹ (chuẩn JDK com.sun.net.httpserver), chạy độc lập không phụ thuộc thư viện ngoài.
 * Cung cấp giao diện Web Dark-mode / Glassmorphism tuyệt đẹp, xem trực tiếp TPS, MSPT, CPS, Ping, VL,
 * Radar 2D/3D góc nhìn người chơi, bảng điều khiển xử lý nhanh (Freeze, Bot, Kick, Reset) và live log stream.
 */
@SuppressWarnings("all")
public final class AcWebDashboard {

    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    private HttpServer server;
    private boolean enabled = true;
    private int port = 8085;
    private String authToken = "admin123";

    public AcWebDashboard(JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        loadConfig();
    }

    public void loadConfig() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("anticheat.web-dashboard.enabled", true);
        this.port = cfg.getInt("anticheat.web-dashboard.port", 8085);
        this.authToken = cfg.getString("anticheat.web-dashboard.auth-token", "admin123");
    }

    public synchronized void start() {
        if (!enabled) return;
        stop();
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            server.createContext("/", new DashboardHtmlHandler());
            server.createContext("/anticheat", new DashboardHtmlHandler());
            server.createContext("/api/status", new ApiStatusHandler());
            server.createContext("/api/logs", new ApiLogsHandler());
            server.createContext("/api/action", new ApiActionHandler());

            server.start();
            plugin.getLogger().info("[AntiCheat] Web Dashboard đã khởi động tại: http://localhost:" + port + "/anticheat");
        } catch (Throwable t) {
            plugin.getLogger().warning("[AntiCheat] Không thể khởi động Web Dashboard trên cổng " + port + ": " + t.getMessage());
        }
    }

    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop(1);
            } catch (Throwable ignored) {}
            server = null;
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public int getPort() {
        return port;
    }

    public String getAuthToken() {
        return authToken;
    }

    // ============================================================
    //  HTTP HANDLERS
    // ============================================================

    private class DashboardHtmlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = DASHBOARD_HTML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class ApiStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"active\":").append(manager.isActive()).append(",");
            sb.append("\"totalFlags\":").append(manager.getTotalFlags()).append(",");
            sb.append("\"totalPunishments\":").append(manager.getTotalPunishments()).append(",");
            sb.append("\"activeAuraBots\":").append(manager.getAuraBotManager() != null ? manager.getAuraBotManager().getActiveBotCount() : 0).append(",");
            sb.append("\"banWaveQueue\":").append(manager.getBanWaveManager() != null ? manager.getBanWaveManager().getQueueSize() : 0).append(",");

            double tps = 20.0;
            try {
                double[] t = plugin.getServerController().getTPS();
                if (t != null && t.length > 0) tps = Math.min(20.0, t[0]);
            } catch (Throwable ignored) {}
            sb.append("\"tps\":").append(String.format(Locale.ROOT, "%.2f", tps)).append(",");

            // Danh sách người chơi online
            sb.append("\"players\":[");
            Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            int idx = 0;
            for (Player p : online) {
                PlayerData d = manager.peek(p.getUniqueId());
                if (d == null) continue;
                if (idx++ > 0) sb.append(",");

                double movVl = 0, comVl = 0, worVl = 0;
                for (CheckType t : CheckType.values()) {
                    double val = d.vl[t.ordinal()];
                    if (t.getCategory() == CheckType.Category.MOVEMENT) movVl += val;
                    else if (t.getCategory() == CheckType.Category.COMBAT) comVl += val;
                    else worVl += val;
                }

                sb.append("{");
                sb.append("\"name\":\"").append(escapeJson(p.getName())).append("\",");
                sb.append("\"uuid\":\"").append(p.getUniqueId()).append("\",");
                sb.append("\"ping\":").append(manager.safePing(p)).append(",");
                sb.append("\"cps\":").append(d.clicks.size()).append(",");
                sb.append("\"anomaly\":").append(String.format(Locale.ROOT, "%.2f", d.combatAnomalyScore)).append(",");
                sb.append("\"inBanWave\":").append(d.inBanWave).append(",");
                sb.append("\"yaw\":").append(String.format(Locale.ROOT, "%.1f", p.getLocation().getYaw())).append(",");
                sb.append("\"pitch\":").append(String.format(Locale.ROOT, "%.1f", p.getLocation().getPitch())).append(",");
                sb.append("\"x\":").append(String.format(Locale.ROOT, "%.1f", p.getLocation().getX())).append(",");
                sb.append("\"y\":").append(String.format(Locale.ROOT, "%.1f", p.getLocation().getY())).append(",");
                sb.append("\"z\":").append(String.format(Locale.ROOT, "%.1f", p.getLocation().getZ())).append(",");
                sb.append("\"movementVl\":").append(String.format(Locale.ROOT, "%.1f", movVl)).append(",");
                sb.append("\"combatVl\":").append(String.format(Locale.ROOT, "%.1f", comVl)).append(",");
                sb.append("\"worldVl\":").append(String.format(Locale.ROOT, "%.1f", worVl)).append(",");
                sb.append("\"totalVl\":").append(String.format(Locale.ROOT, "%.1f", d.getTotalVl())).append(",");
                sb.append("\"totalFlags\":").append(d.totalFlags).append(",");
                sb.append("\"isFrozen\":").append(d.isFrozen).append(",");
                sb.append("\"hasAuraBot\":").append(d.activeAuraBotUuid != null).append(",");
                sb.append("\"cluster\":\"").append(d.clusterGroup != null ? d.clusterGroup.name() : "LEGIT_CASUAL").append("\",");
                sb.append("\"clusterCpsStdDev\":").append(String.format(Locale.ROOT, "%.1f", d.clusterCpsStdDev)).append(",");
                sb.append("\"clusterKurtosis\":").append(String.format(Locale.ROOT, "%.2f", d.clusterKurtosis)).append(",");
                sb.append("\"clusterHitbox\":").append(String.format(Locale.ROOT, "%.2f", d.clusterHitboxProximity)).append(",");
                sb.append("\"clusterReaction\":").append(String.format(Locale.ROOT, "%.0f", d.clusterReactionLatency)).append(",");
                sb.append("\"clusterJitter\":").append(String.format(Locale.ROOT, "%.1f", d.clusterPingJitter)).append(",");
                sb.append("\"clusterSmoothness\":").append(String.format(Locale.ROOT, "%.2f", d.clusterSmoothness)).append(",");
                sb.append("\"reportCount\":").append(d.reportCount);
                sb.append("}");
            }
            sb.append("],\"replays\":[");
            if (manager.getReplayManager() != null) {
                var incidents = manager.getReplayManager().getIncidents();
                for (int r = 0; r < incidents.size(); r++) {
                    var inc = incidents.get(r);
                    if (r > 0) sb.append(",");
                    sb.append("{\"id\":\"").append(inc.id)
                      .append("\",\"name\":\"").append(escapeJson(inc.playerName))
                      .append("\",\"check\":\"").append(escapeJson(inc.checkName))
                      .append("\",\"vl\":").append(String.format(Locale.ROOT, "%.1f", inc.vl))
                      .append(",\"time\":\"").append(escapeJson(inc.timestamp))
                      .append("\"}");
                }
            }
            sb.append("]}");

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class ApiLogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            List<String> logs = manager.getRecentLog(50);
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < logs.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJson(logs.get(i))).append("\"");
            }
            sb.append("]");

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class ApiActionHandler implements HttpHandler {
        @SuppressWarnings("deprecation")
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int read;
            while ((read = is.read(buf)) != -1) {
                bos.write(buf, 0, read);
            }
            String body = bos.toString(StandardCharsets.UTF_8);

            // Simple parser for token, action, target
            String token = extractJsonField(body, "token");
            String action = extractJsonField(body, "action");
            String target = extractJsonField(body, "target");

            if (!authToken.equals(token)) {
                sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã xác thực Auth Token!\"}");
                return;
            }

            Player p = Bukkit.getPlayerExact(target);
            if (p == null && !"reload".equalsIgnoreCase(action)) {
                sendJson(exchange, 404, "{\"success\":false,\"error\":\"Không tìm thấy người chơi!\"}");
                return;
            }

            switch (action.toLowerCase(Locale.ROOT)) {
                case "freeze" -> {
                    PlatformUtil.runTask(plugin, () -> manager.freezePlayer(Bukkit.getConsoleSender(), p));
                    sendJson(exchange, 200, "{\"success\":true,\"message\":\"Đã đóng băng " + target + "\"}");
                }
                case "unfreeze" -> {
                    PlatformUtil.runTask(plugin, () -> manager.unfreezePlayer(Bukkit.getConsoleSender(), p));
                    sendJson(exchange, 200, "{\"success\":true,\"message\":\"Đã mở khóa " + target + "\"}");
                }
                case "aurabot" -> {
                    PlatformUtil.runTask(plugin, () -> {
                        if (manager.getAuraBotManager() != null) {
                            manager.getAuraBotManager().spawnAuraBot(p, 160);
                        }
                    });
                    sendJson(exchange, 200, "{\"success\":true,\"message\":\"Đã thả AuraBot bẫy " + target + "\"}");
                }
                case "reset" -> {
                    PlayerData d = manager.peek(p.getUniqueId());
                    if (d != null) d.resetViolations();
                    sendJson(exchange, 200, "{\"success\":true,\"message\":\"Đã xóa toàn bộ điểm vi phạm của " + target + "\"}");
                }
                case "kick" -> {
                    PlatformUtil.runAtEntity(plugin, p, () -> p.kickPlayer("§c[AntiCheat] Bạn đã bị Kick bởi quản trị viên qua Web Dashboard!"));
                    sendJson(exchange, 200, "{\"success\":true,\"message\":\"Đã Kick " + target + "\"}");
                }
                default -> sendJson(exchange, 400, "{\"success\":false,\"error\":\"Hành động không hợp lệ!\"}");
            }
        }
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx == -1) return "";
        int colon = json.indexOf(':', idx + key.length());
        if (colon == -1) return "";
        int startQuote = json.indexOf('\"', colon);
        if (startQuote == -1) return "";
        int endQuote = json.indexOf('\"', startQuote + 1);
        if (endQuote == -1) return "";
        return json.substring(startQuote + 1, endQuote);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    // ============================================================
    //  GIAO DIỆN WEB HIỆN ĐẠI (HTML/CSS/JS NHÚNG)
    // ============================================================

    @SuppressWarnings("all")
    private static final String DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>JavaStudio Anti-Cheat V2 Dashboard</title>
    <style>
        :root {
            --bg-base: #0a0d14;
            --bg-card: rgba(18, 24, 38, 0.75);
            --border: rgba(255, 255, 255, 0.08);
            --border-hover: rgba(56, 189, 248, 0.3);
            --primary: #38bdf8;
            --primary-glow: rgba(56, 189, 248, 0.25);
            --danger: #f43f5e;
            --warning: #f59e0b;
            --success: #10b981;
            --text-main: #f1f5f9;
            --text-muted: #94a3b8;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; }
        body { background: var(--bg-base); color: var(--text-main); min-height: 100vh; padding: 24px; }
        .container { max-width: 1400px; margin: 0 auto; display: flex; flex-direction: column; gap: 24px; }

        /* Header */
        header { display: flex; justify-content: space-between; align-items: center; padding: 16px 24px; background: var(--bg-card); backdrop-filter: blur(12px); border: 1px solid var(--border); border-radius: 16px; box-shadow: 0 8px 32px rgba(0,0,0,0.4); }
        .brand { display: flex; align-items: center; gap: 14px; }
        .logo-badge { width: 42px; height: 42px; border-radius: 10px; background: linear-gradient(135deg, #0284c7, #38bdf8); display: flex; align-items: center; justify-content: center; font-size: 22px; font-weight: bold; box-shadow: 0 0 20px var(--primary-glow); }
        .brand h1 { font-size: 20px; font-weight: 700; letter-spacing: -0.5px; }
        .brand span { font-size: 13px; color: var(--text-muted); }
        .auth-bar { display: flex; gap: 10px; align-items: center; }
        .input-token { background: rgba(0,0,0,0.4); border: 1px solid var(--border); border-radius: 8px; padding: 8px 14px; color: var(--text-main); font-size: 13px; outline: none; transition: 0.2s; }
        .input-token:focus { border-color: var(--primary); }

        /* Metric Grid */
        .grid-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
        .card-stat { background: var(--bg-card); backdrop-filter: blur(8px); border: 1px solid var(--border); border-radius: 14px; padding: 20px; position: relative; overflow: hidden; }
        .card-stat::before { content: ''; position: absolute; top: 0; left: 0; width: 4px; height: 100%; background: var(--primary); }
        .card-stat.danger::before { background: var(--danger); }
        .card-stat.warning::before { background: var(--warning); }
        .card-stat.success::before { background: var(--success); }
        .card-stat .label { font-size: 13px; color: var(--text-muted); font-weight: 500; }
        .card-stat .value { font-size: 32px; font-weight: 700; margin-top: 8px; letter-spacing: -1px; }

        /* Main Workspace */
        .workspace { display: grid; grid-template-columns: 2fr 1.2fr; gap: 24px; }
        @media (max-width: 1024px) { .workspace { grid-template-columns: 1fr; } }

        .panel { background: var(--bg-card); backdrop-filter: blur(12px); border: 1px solid var(--border); border-radius: 16px; padding: 20px; display: flex; flex-direction: column; gap: 16px; }
        .panel-header { display: flex; justify-content: space-between; align-items: center; }
        .panel-title { font-size: 16px; font-weight: 600; display: flex; align-items: center; gap: 8px; }

        /* Player Table */
        table { width: 100%; border-collapse: collapse; font-size: 13px; }
        th { text-align: left; padding: 12px 14px; color: var(--text-muted); font-weight: 600; border-bottom: 1px solid var(--border); }
        td { padding: 14px; border-bottom: 1px solid rgba(255,255,255,0.03); vertical-align: middle; }
        tr:hover td { background: rgba(255,255,255,0.02); }
        .badge { display: inline-block; padding: 4px 8px; border-radius: 6px; font-size: 11px; font-weight: 600; }
        .badge-clean { background: rgba(16, 185, 129, 0.15); color: var(--success); }
        .badge-warning { background: rgba(245, 158, 11, 0.15); color: var(--warning); }
        .badge-danger { background: rgba(244, 63, 94, 0.15); color: var(--danger); }
        .badge-frozen { background: rgba(56, 189, 248, 0.2); color: var(--primary); border: 1px solid var(--primary); }

        .btn-group { display: flex; gap: 6px; }
        .btn { border: none; outline: none; cursor: pointer; padding: 6px 10px; border-radius: 6px; font-size: 11px; font-weight: 600; transition: 0.15s; background: rgba(255,255,255,0.06); color: var(--text-main); }
        .btn:hover { background: rgba(255,255,255,0.15); transform: translateY(-1px); }
        .btn-bot { background: rgba(245, 158, 11, 0.2); color: #fbbf24; }
        .btn-freeze { background: rgba(56, 189, 248, 0.2); color: #38bdf8; }
        .btn-kick { background: rgba(244, 63, 94, 0.2); color: #f43f5e; }

        /* Radar & Compass Canvas */
        .radar-box { position: relative; width: 100%; aspect-ratio: 1; max-height: 380px; margin: 0 auto; display: flex; align-items: center; justify-content: center; }
        canvas { width: 100%; height: 100%; background: #07090e; border-radius: 12px; border: 1px solid var(--border); }

        /* Terminal Console */
        .terminal { background: #05070a; border: 1px solid var(--border); border-radius: 10px; padding: 14px; font-family: 'Consolas', monospace; font-size: 12px; height: 260px; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
        .log-line { line-height: 1.5; color: #cbd5e1; }
        .log-line.danger { color: #f43f5e; }
        .log-line.warn { color: #fbbf24; }
        .log-line.info { color: #38bdf8; }
    </style>
</head>
<body>
<div class="container">
    <header>
        <div class="brand">
            <div class="logo-badge">🛡️</div>
            <div>
                <h1>JavaStudio Anti-Cheat V2</h1>
                <span>Trung tâm Điều Hành & Giám Sát Thời Gian Thực</span>
            </div>
        </div>
        <div class="auth-bar">
            <input type="password" id="authToken" class="input-token" placeholder="Auth Token (mặc định admin123)" value="admin123">
            <span id="connStatus" style="font-size: 12px; color: var(--success);">● Live Sync (1s)</span>
        </div>
    </header>

    <div class="grid-stats">
        <div class="card-stat success">
            <div class="label">Server TPS</div>
            <div class="value" id="valTps">20.0</div>
        </div>
        <div class="card-stat danger">
            <div class="label">Tổng Cảnh Báo (Flags)</div>
            <div class="value" id="valFlags">0</div>
        </div>
        <div class="card-stat warning">
            <div class="label">Tổng Xử Phạt (Bans/Kicks)</div>
            <div class="value" id="valPunishments">0</div>
        </div>
        <div class="card-stat">
            <div class="label">AuraBot Đang Bẫy</div>
            <div class="value" id="valBots">0</div>
        </div>
    </div>

    <div class="workspace">
        <div class="panel">
            <div class="panel-header">
                <div class="panel-title">👥 Danh Sách Nghi Phạm & Người Chơi Online</div>
                <div id="playerCount" style="font-size: 13px; color: var(--text-muted);">0 người chơi</div>
            </div>
            <div style="overflow-x: auto;">
                <table>
                    <thead>
                        <tr>
                            <th>Tên</th>
                            <th>Ping</th>
                            <th>CPS</th>
                            <th>Hành Vi</th>
                            <th>Di chuyển</th>
                            <th>Chiến đấu</th>
                            <th>Thế giới</th>
                            <th>Trạng thái</th>
                            <th>Hành động</th>
                        </tr>
                    </thead>
                    <tbody id="playerTableBody">
                        <tr><td colspan="9" style="text-align: center; color: var(--text-muted); padding: 30px;">Chưa có dữ liệu người chơi</td></tr>
                    </tbody>
                </table>
            </div>

            <div class="panel-header" style="margin-top: 10px;">
                <div class="panel-title">📜 Nhật Ký Vi Phạm Trực Tiếp (Live Stream)</div>
            </div>
            <div class="terminal" id="logTerminal"></div>
        </div>

        <div class="panel">
            <div class="panel-header">
                <div class="panel-title">🎯 3D Blind-Spot Radar (Góc Nhìn Nghi Phạm)</div>
                <select id="radarTargetSelect" class="input-token" style="padding: 4px 8px; font-size: 12px;">
                    <option value="">Chọn người chơi...</option>
                </select>
            </div>
            <div class="radar-box">
                <canvas id="radarCanvas" width="400" height="340"></canvas>
            </div>
            <div style="font-size: 11px; color: var(--text-muted); text-align: center;">
                Vùng màu đỏ: Điểm mù 180° sau lưng • Chấm vàng: AuraBot Decoy • Mũi tên: Hướng nhìn FOV
            </div>

            <div class="panel-header" style="margin-top: 16px;">
                <div class="panel-title">🕸️ 6-Axis Behavior Radar (Hồ Sơ Đa Biến)</div>
            </div>
            <div class="radar-box" style="max-height: 240px;">
                <canvas id="clusterCanvas" width="360" height="240"></canvas>
            </div>
            <div id="clusterText" style="font-size: 11px; color: var(--text-muted); text-align: center;">
                Nhóm hành vi: <strong id="clusterGroupName" style="color: var(--primary);">CASUAL</strong>
            </div>
        </div>
    </div>

    <!-- Replays & Evidence -->
    <div class="panel" style="margin-top: 10px;">
        <div class="panel-header">
            <div class="panel-title">🎬 Bằng Chứng & Replay Clips Vi Phạm Đã Lưu</div>
            <div id="replayCount" style="font-size: 13px; color: var(--text-muted);">0 clips</div>
        </div>
        <div style="overflow-x: auto;">
            <table>
                <thead>
                    <tr>
                        <th>Mã Clip</th>
                        <th>Người Chơi</th>
                        <th>Loại Hack</th>
                        <th>Điểm VL</th>
                        <th>Thời Gian</th>
                        <th>Lệnh Chiếu In-Game</th>
                    </tr>
                </thead>
                <tbody id="replayTableBody">
                    <tr><td colspan="6" style="text-align: center; color: var(--text-muted); padding: 20px;">Chưa có clip vi phạm nào</td></tr>
                </tbody>
            </table>
        </div>
    </div>
</div>

<script>
    let currentPlayers = [];
    let selectedPlayer = null;

    async function refreshData() {
        try {
            const res = await fetch('/api/status');
            const data = await res.json();

            document.getElementById('valTps').innerText = data.tps;
            document.getElementById('valFlags').innerText = data.totalFlags;
            document.getElementById('valPunishments').innerText = data.totalPunishments;
            document.getElementById('valBots').innerText = data.activeAuraBots;
            document.getElementById('playerCount').innerText = data.players.length + ' người chơi';

            currentPlayers = data.players;
            renderPlayerTable(data.players);
            renderReplayTable(data.replays);
            updateRadarSelect(data.players);
            drawRadar();
            drawClusterRadar();
        } catch (e) {
            document.getElementById('connStatus').innerText = '○ Mất kết nối';
            document.getElementById('connStatus').style.color = 'var(--danger)';
        }
    }

    async function refreshLogs() {
        try {
            const res = await fetch('/api/logs');
            const logs = await res.json();
            const term = document.getElementById('logTerminal');
            term.innerHTML = logs.map(l => {
                let cls = 'log-line';
                if (l.includes('PUNISH') || l.includes('100% Aura')) cls += ' danger';
                else if (l.includes('KillAura') || l.includes('Speed') || l.includes('Fly')) cls += ' warn';
                return `<div class="${cls}">${escapeHtml(l)}</div>`;
            }).join('');
            term.scrollTop = term.scrollHeight;
        } catch (e) {}
    }

    function renderPlayerTable(players) {
        const tbody = document.getElementById('playerTableBody');
        if (!players || players.length === 0) {
            tbody.innerHTML = '<tr><td colspan="9" style="text-align: center; color: var(--text-muted); padding: 30px;">Không có người chơi online</td></tr>';
            return;
        }

        tbody.innerHTML = players.map(p => {
            let badge = '<span class="badge badge-clean">SẠCH</span>';
            if (p.isFrozen) badge = '<span class="badge badge-frozen">ĐÓNG BĂNG</span>';
            else if (p.totalVl >= 10) badge = '<span class="badge badge-danger">NGUY CƠ CAO</span>';
            else if (p.totalVl >= 4) badge = '<span class="badge badge-warning">NGHI VẤN</span>';

            let clusterBg = 'rgba(16, 185, 129, 0.15); color: #10b981;';
            if (p.cluster === 'DEFINITE_CHEAT') clusterBg = 'rgba(244, 63, 94, 0.2); color: #f43f5e;';
            else if (p.cluster === 'SUSPICIOUS') clusterBg = 'rgba(245, 158, 11, 0.2); color: #f59e0b;';
            else if (p.cluster === 'LEGIT_PRO') clusterBg = 'rgba(168, 85, 247, 0.2); color: #c084fc;';

            let repBadge = p.reportCount > 0 ? `<span title="Bị tố cáo ${p.reportCount} lần" style="color:#f43f5e; font-weight:bold; margin-left:4px;">🚨${p.reportCount}</span>` : '';

            return `
                <tr>
                    <td><strong>${escapeHtml(p.name)}</strong>${repBadge}</td>
                    <td>${p.ping}ms</td>
                    <td>${p.cps}</td>
                    <td><span class="badge" style="background: ${clusterBg}">${p.cluster || 'CASUAL'}</span></td>
                    <td><span style="color: #38bdf8;">${p.movementVl}</span></td>
                    <td><span style="color: #f43f5e;">${p.combatVl}</span></td>
                    <td><span style="color: #fbbf24;">${p.worldVl}</span></td>
                    <td>${badge}</td>
                    <td>
                        <div class="btn-group">
                            <button class="btn btn-bot" title="Thả AuraBot thử bẫy" onclick="doAction('aurabot', '${p.name}')">🎯 Bot</button>
                            ${p.isFrozen
                                ? `<button class="btn btn-freeze" onclick="doAction('unfreeze', '${p.name}')">🔓 Mở</button>`
                                : `<button class="btn btn-freeze" onclick="doAction('freeze', '${p.name}')">❄️ Freeze</button>`
                            }
                            <button class="btn" title="Reset điểm phạt" onclick="doAction('reset', '${p.name}')">🔄</button>
                            <button class="btn btn-kick" title="Kick ngay" onclick="doAction('kick', '${p.name}')">⚡ Kick</button>
                        </div>
                    </td>
                </tr>
            `;
        }).join('');
    }

    function renderReplayTable(replays) {
        const tbody = document.getElementById('replayTableBody');
        const countEl = document.getElementById('replayCount');
        if (countEl) countEl.innerText = (replays ? replays.length : 0) + ' clips';
        if (!tbody) return;
        if (!replays || replays.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--text-muted); padding: 20px;">Chưa có clip vi phạm nào được lưu</td></tr>';
            return;
        }
        tbody.innerHTML = replays.map(r => `
            <tr>
                <td><code style="color: #38bdf8;">${escapeHtml(r.id)}</code></td>
                <td><strong>${escapeHtml(r.name)}</strong></td>
                <td><span style="color: #f43f5e;">${escapeHtml(r.check)}</span></td>
                <td>${r.vl}</td>
                <td><span style="color: var(--text-muted);">${escapeHtml(r.time)}</span></td>
                <td>
                    <button class="btn btn-freeze" onclick="alert('Vào game gõ lệnh: /jac replay play ${r.id}')">🎬 /jac replay play ${r.id}</button>
                </td>
            </tr>
        `).join('');
    }

    function updateRadarSelect(players) {
        const sel = document.getElementById('radarTargetSelect');
        const curVal = sel.value;
        sel.innerHTML = '<option value="">Chọn người chơi theo dõi...</option>' +
            players.map(p => `<option value="${p.name}" ${p.name === curVal ? 'selected' : ''}>${p.name} (VL ${p.totalVl})</option>`).join('');
        if (curVal) selectedPlayer = players.find(p => p.name === curVal);
        else if (players.length > 0 && !selectedPlayer) {
            selectedPlayer = players[0];
            sel.value = selectedPlayer.name;
        }
    }

    document.getElementById('radarTargetSelect').addEventListener('change', (e) => {
        selectedPlayer = currentPlayers.find(p => p.name === e.target.value);
        drawRadar();
        drawClusterRadar();
    });

    function drawRadar() {
        const canvas = document.getElementById('radarCanvas');
        const ctx = canvas.getContext('2d');
        const w = canvas.width, h = canvas.height;
        const cx = w / 2, cy = h / 2;

        ctx.clearRect(0, 0, w, h);

        // Vòng tròn Radar
        ctx.strokeStyle = 'rgba(56, 189, 248, 0.15)';
        ctx.lineWidth = 1;
        for (let r = 40; r <= 140; r += 35) {
            ctx.beginPath();
            ctx.arc(cx, cy, r, 0, Math.PI * 2);
            ctx.stroke();
        }

        // Trục tọa độ
        ctx.beginPath();
        ctx.moveTo(cx, 10); ctx.lineTo(cx, h - 10);
        ctx.moveTo(10, cy); ctx.lineTo(w - 10, cy);
        ctx.stroke();

        if (!selectedPlayer) {
            ctx.fillStyle = '#64748b';
            ctx.font = '14px sans-serif';
            ctx.textAlign = 'center';
            ctx.fillText('Chọn người chơi để quan sát góc nhìn', cx, cy);
            return;
        }

        const yawRad = (selectedPlayer.yaw - 90) * (Math.PI / 180);

        // Vùng hình nón FOV (Phía trước 70 độ)
        ctx.fillStyle = 'rgba(56, 189, 248, 0.12)';
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.arc(cx, cy, 130, yawRad - 0.6, yawRad + 0.6);
        ctx.closePath();
        ctx.fill();

        // Vùng điểm mù (Blind Spot 180 độ phía sau)
        ctx.fillStyle = 'rgba(244, 63, 94, 0.08)';
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.arc(cx, cy, 120, yawRad + Math.PI - 0.7, yawRad + Math.PI + 0.7);
        ctx.closePath();
        ctx.fill();

        // Người chơi (tâm radar)
        ctx.fillStyle = '#38bdf8';
        ctx.beginPath();
        ctx.arc(cx, cy, 7, 0, Math.PI * 2);
        ctx.fill();

        // Mũi tên chỉ hướng nhìn
        ctx.strokeStyle = '#38bdf8';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.lineTo(cx + Math.cos(yawRad) * 45, cy + Math.sin(yawRad) * 45);
        ctx.stroke();

        // Vẽ AuraBot decoy nếu đang bẫy
        if (selectedPlayer.hasAuraBot) {
            const botAngle = yawRad + Math.PI;
            const bx = cx + Math.cos(botAngle) * 75;
            const by = cy + Math.sin(botAngle) * 75;

            ctx.fillStyle = '#fbbf24';
            ctx.beginPath();
            ctx.arc(bx, by, 6, 0, Math.PI * 2);
            ctx.fill();

            ctx.fillStyle = '#fbbf24';
            ctx.font = '11px sans-serif';
            ctx.textAlign = 'center';
            ctx.fillText('AuraBot (Decoy)', bx, by - 10);
        }
    }

    function drawClusterRadar() {
        const canvas = document.getElementById('clusterCanvas');
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        const w = canvas.width, h = canvas.height;
        const cx = w / 2, cy = h / 2;
        ctx.clearRect(0, 0, w, h);

        const labels = ['CPS Var', 'Kurtosis', 'Hitbox', 'Latency', 'Jitter', 'Smooth'];
        const numAxes = 6;
        const maxR = 80;

        ctx.strokeStyle = 'rgba(255, 255, 255, 0.08)';
        ctx.lineWidth = 1;
        for (let r = 25; r <= maxR; r += 25) {
            ctx.beginPath();
            for (let i = 0; i < numAxes; i++) {
                const angle = (i * Math.PI * 2 / numAxes) - Math.PI / 2;
                const x = cx + Math.cos(angle) * r;
                const y = cy + Math.sin(angle) * r;
                if (i === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            }
            ctx.closePath();
            ctx.stroke();
        }

        ctx.fillStyle = '#94a3b8';
        ctx.font = '10px sans-serif';
        ctx.textAlign = 'center';
        for (let i = 0; i < numAxes; i++) {
            const angle = (i * Math.PI * 2 / numAxes) - Math.PI / 2;
            const x = cx + Math.cos(angle) * maxR;
            const y = cy + Math.sin(angle) * maxR;
            ctx.beginPath();
            ctx.moveTo(cx, cy);
            ctx.lineTo(x, y);
            ctx.stroke();

            const lx = cx + Math.cos(angle) * (maxR + 18);
            const ly = cy + Math.sin(angle) * (maxR + 18) + 3;
            ctx.fillText(labels[i], lx, ly);
        }

        if (!selectedPlayer) return;

        const v0 = Math.min(1.0, (selectedPlayer.clusterCpsStdDev || 20) / 40.0);
        const v1 = Math.min(1.0, (selectedPlayer.clusterKurtosis || 3.0) / 8.0);
        const v2 = Math.min(1.0, selectedPlayer.clusterHitbox || 0.5);
        const v3 = Math.min(1.0, (selectedPlayer.clusterReaction || 150) / 300.0);
        const v4 = Math.min(1.0, (selectedPlayer.clusterJitter || 5) / 30.0);
        const v5 = Math.min(1.0, selectedPlayer.clusterSmoothness || 0.8);
        const values = [v0, v1, v2, v3, v4, v5];

        ctx.fillStyle = 'rgba(56, 189, 248, 0.25)';
        ctx.strokeStyle = '#38bdf8';
        ctx.lineWidth = 2;
        ctx.beginPath();
        for (let i = 0; i < numAxes; i++) {
            const angle = (i * Math.PI * 2 / numAxes) - Math.PI / 2;
            const r = Math.max(10, values[i] * maxR);
            const x = cx + Math.cos(angle) * r;
            const y = cy + Math.sin(angle) * r;
            if (i === 0) ctx.moveTo(x, y);
            else ctx.lineTo(x, y);
        }
        ctx.closePath();
        ctx.fill();
        ctx.stroke();

        const grpEl = document.getElementById('clusterGroupName');
        if (grpEl) {
            grpEl.innerText = selectedPlayer.cluster || 'LEGIT_CASUAL';
            if (selectedPlayer.cluster === 'DEFINITE_CHEAT') grpEl.style.color = '#f43f5e';
            else if (selectedPlayer.cluster === 'SUSPICIOUS') grpEl.style.color = '#f59e0b';
            else if (selectedPlayer.cluster === 'LEGIT_PRO') grpEl.style.color = '#c084fc';
            else grpEl.style.color = '#10b981';
        }
    }

    async function doAction(action, target) {
        const token = document.getElementById('authToken').value.trim();
        try {
            const res = await fetch('/api/action', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token, action, target })
            });
            const data = await res.json();
            if (data.success) {
                alert(data.message);
                refreshData();
                refreshLogs();
            } else {
                alert('Lỗi: ' + data.error);
            }
        } catch (e) {
            alert('Không thể gửi yêu cầu: ' + e.message);
        }
    }

    function escapeHtml(s) {
        return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    setInterval(refreshData, 1000);
    setInterval(refreshLogs, 2000);
    refreshData();
    refreshLogs();
</script>
</body>
</html>
""";
}
