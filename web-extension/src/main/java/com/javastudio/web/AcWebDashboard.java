package com.javastudio.web;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.anticheat.*;
import com.javastudio.utils.PlatformUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

@SuppressWarnings("all")
public final class AcWebDashboard implements WebDashboardService {

    private final JavaPlugin webPlugin;
    private final JavaStudioPlugin plugin;
    private final AntiCheatManager manager;

    private static final Set<String> WEAK_TOKENS = Set.of("", "admin", "admin123", "password", "changeme", "123456", "token");
    private static final int MIN_TOKEN_LENGTH = 16;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_AUTH_FAILURES = 8;
    private static final long LOCKOUT_MS = 5 * 60 * 1000L;

    private HttpServer server;
    private boolean enabled = true;
    private int port = 8085;
    private int actualBoundPort = -1;
    private String bindHost = "127.0.0.1";
    private String singlePortMode = "auto";
    private String publicHost = "";
    private int publicPort = 0;
    private String publicScheme = "http";
    private volatile boolean loopbackMuxOnly = false;
    private String authToken = "";
    private final Map<String, int[]> authFailures = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new java.util.concurrent.ConcurrentHashMap<>();

    public AcWebDashboard(JavaPlugin webPlugin, JavaStudioPlugin plugin, AntiCheatManager manager) {
        this.webPlugin = webPlugin;
        this.plugin = plugin;
        this.manager = manager;
        loadConfig();
    }

    @Override
    public void loadConfig() {
        var extCfg = webPlugin.getConfig();
        var coreCfg = plugin.getConfig();
        boolean coreEnabled = coreCfg.getBoolean("anticheat.web-dashboard.enabled", true);
        this.enabled = extCfg.getBoolean("web-dashboard.enabled", coreEnabled);
        int corePort = coreCfg.getInt("anticheat.web-dashboard.port", 8085);
        this.port = extCfg.getInt("web-dashboard.port", corePort);
        String coreBind = coreCfg.getString("anticheat.web-dashboard.bind-address", "127.0.0.1");
        this.bindHost = extCfg.getString("web-dashboard.bind-address", coreBind);

        String coreSinglePort = coreCfg.getString("anticheat.web-dashboard.single-port", "auto");
        this.singlePortMode = extCfg.getString("web-dashboard.single-port", coreSinglePort);

        String corePubHost = coreCfg.getString("anticheat.web-dashboard.public-host", "");
        this.publicHost = extCfg.getString("web-dashboard.public-host", corePubHost);

        int corePubPort = coreCfg.getInt("anticheat.web-dashboard.public-port", 0);
        this.publicPort = extCfg.getInt("web-dashboard.public-port", corePubPort);

        String coreScheme = coreCfg.getString("anticheat.web-dashboard.public-scheme", "http");
        this.publicScheme = extCfg.getString("web-dashboard.public-scheme", coreScheme);

        String configured = extCfg.getString("web-dashboard.auth-token", "");
        if (configured == null || configured.isBlank()) {
            configured = coreCfg.getString("anticheat.web-dashboard.auth-token", "");
        }
        if (configured == null || configured.length() < MIN_TOKEN_LENGTH || WEAK_TOKENS.contains(configured.trim().toLowerCase(Locale.ROOT))) {
            configured = generateToken();
            webPlugin.getLogger().warning("[WebDashboard] Auth token yeu hoac mac dinh da duoc thay bang token ngau nhien moi.");
            webPlugin.getLogger().warning("[WebDashboard] Token moi (luu tai plugins/JavaStudioWeb/config.yml -> web-dashboard.auth-token): " + configured);
        }
        configured = configured.trim();
        extCfg.set("web-dashboard.auth-token", configured);
        try {
            webPlugin.saveConfig();
        } catch (Throwable ignored) {}
        this.authToken = configured;
    }

    private static String generateToken() {
        byte[] raw = new byte[24];
        new java.security.SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private boolean tokenMatches(String candidate) {
        if (candidate == null || authToken == null || authToken.isEmpty()) return false;
        return java.security.MessageDigest.isEqual(
                candidate.trim().getBytes(StandardCharsets.UTF_8),
                authToken.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && name.equalsIgnoreCase(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private final class SecurityFilter extends com.sun.net.httpserver.Filter {
        private final boolean requireAuth;

        SecurityFilter(boolean requireAuth) {
            this.requireAuth = requireAuth;
        }

        @Override
        public String description() {
            return "JavaStudio WebDashboard Security Filter";
        }

        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            var headers = exchange.getResponseHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Cache-Control", "no-store");

            if (!requireAuth) {
                chain.doFilter(exchange);
                return;
            }

            String rawIp = exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null
                    ? exchange.getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            var mux = plugin.getSinglePortMuxEngine();
            String ip = (mux != null)
                    ? mux.resolveTrustedClientIp(
                            rawIp,
                            exchange.getRequestHeaders().getFirst(com.javastudio.network.SinglePortMuxEngine.HEADER_MUX_SECRET),
                            exchange.getRequestHeaders().getFirst(com.javastudio.network.SinglePortMuxEngine.HEADER_CLIENT_IP))
                    : rawIp;
            long now = System.currentTimeMillis();
            Long until = lockedUntil.get(ip);
            if (until != null && until > now) {
                headers.set("Content-Type", "application/json; charset=UTF-8");
                sendJson(exchange, 429, "{\"success\":false,\"error\":\"Quá nhiều lần xác thực sai. Thử lại sau.\"}");
                return;
            }

            String token = exchange.getRequestHeaders().getFirst("X-Auth-Token");
            if (token == null || token.isBlank()) token = queryParam(exchange, "token");

            if (token == null || token.isBlank()) {
                headers.set("Content-Type", "application/json; charset=UTF-8");
                sendJson(exchange, 401, "{\"success\":false,\"error\":\"Chưa cung cấp mã Auth Token!\"}");
                return;
            }

            if (!tokenMatches(token)) {
                int[] counter = authFailures.computeIfAbsent(ip, k -> new int[1]);
                synchronized (counter) {
                    counter[0]++;
                    if (counter[0] >= MAX_AUTH_FAILURES) {
                        lockedUntil.put(ip, now + LOCKOUT_MS);
                        counter[0] = 0;
                        plugin.getLogger().warning("[WebDashboard] Khóa IP " + ip + " trong 5 phút do xác thực sai liên tục.");
                    }
                }
                headers.set("Content-Type", "application/json; charset=UTF-8");
                sendJson(exchange, 401, "{\"success\":false,\"error\":\"Sai mã Auth Token!\"}");
                return;
            }

            authFailures.remove(ip);
            lockedUntil.remove(ip);
            chain.doFilter(exchange);
        }
    }

    private void route(String path, HttpHandler handler, boolean requireAuth) {
        server.createContext(path, handler).getFilters().add(new SecurityFilter(requireAuth));
    }

    public synchronized void start() {
        if (!enabled) return;
        stop();
        try {
            var mux = plugin.getSinglePortMuxEngine();
            boolean muxAllowed = singlePortMode == null
                    || (!singlePortMode.trim().equalsIgnoreCase("false") && !singlePortMode.trim().equalsIgnoreCase("off"));
            if (muxAllowed && mux != null) {
                mux.start();
            }

            boolean forceLoopback = mux != null && mux.shouldUseLoopbackBackend(singlePortMode);
            if (forceLoopback) {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                loopbackMuxOnly = true;
            } else {
                InetSocketAddress address = (bindHost == null || bindHost.isBlank() || bindHost.equals("*"))
                        ? new InetSocketAddress(port)
                        : new InetSocketAddress(bindHost, port);
                try {
                    server = HttpServer.create(address, 0);
                    loopbackMuxOnly = false;
                } catch (IOException bindErr) {
                    if (muxAllowed && mux != null) {
                        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                        loopbackMuxOnly = true;
                        webPlugin.getLogger().info("[WebDashboard] Cong " + port + " dang duoc su dung -> Da tu dong chuyen sang che do Panel 1-Port (di chung cong Minecraft)!");
                    } else {
                        throw bindErr;
                    }
                }
            }

            actualBoundPort = server.getAddress().getPort();
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            route("/", new DashboardHtmlHandler(), false);
            route("/anticheat", new DashboardHtmlHandler(), false);
            route("/api/status", new ApiStatusHandler(), true);
            route("/api/logs", new ApiLogsHandler(), true);
            route("/api/action", new ApiActionHandler(), true);
            route("/api/reports", new ApiReportsHandler(), true);
            route("/api/reports/action", new ApiReportActionHandler(), true);
            route("/api/replay", new ApiReplayHandler(), true);
            route("/api/system", new ApiSystemHandler(), true);
            route("/api/plugins", new ApiPluginsHandler(), true);
            route("/api/plugins/action", new ApiPluginsHandler(), true);
            route("/api/exec", new ApiExecHandler(), true);
            route("/api/stream/terminal", new ApiTerminalStreamHandler(), true);
            route("/api/ide/tree", new ApiIdeTreeHandler(), true);
            route("/api/ide/file", new ApiIdeFileHandler(), true);
            route("/api/ide/save", new ApiIdeSaveHandler(), true);
            route("/api/ide/run", new ApiIdeRunHandler(), true);
            route("/api/flow/list", new ApiFlowListHandler(), true);
            route("/api/flow/save", new ApiFlowSaveHandler(), true);
            route("/api/flow/run", new ApiFlowRunHandler(), true);
            route("/api/mesh/status", new ApiMeshStatusHandler(), true);
            route("/api/deadlock/status", new ApiDeadlockStatusHandler(), true);
            route("/api/prefetch/status", new ApiPrefetchStatusHandler(), true);
            route("/api/gui/list", new ApiGuiListHandler(), true);
            route("/api/benchmark/status", new ApiBenchmarkStatusHandler(), true);
            route("/api/benchmark/run", new ApiBenchmarkRunHandler(), true);
            route("/api/profiler/flamegraph", new ApiFlameGraphHandler(), true);
            route("/api/ide/autocomplete", new ApiIdeAutocompleteHandler(), true);

            server.start();
            if (loopbackMuxOnly) {
                webPlugin.getLogger().info("[Single-Port] Che do Panel 1-Port dang BAT (cong noi bo co lap 127.0.0.1:" + actualBoundPort + " -> gop chung cong Minecraft :" + Bukkit.getPort() + ")");
                webPlugin.getLogger().info("Link truy cap Web Dashboard: " + getPublicAccessUrl(null));
            } else {
                String shownHost = (bindHost == null || bindHost.isBlank() || bindHost.equals("*")) ? "0.0.0.0" : bindHost;
                webPlugin.getLogger().info("Web Dashboard da khoi dong tai: http://" + shownHost + ":" + actualBoundPort + "/anticheat?token=" + authToken);
                if (muxAllowed && mux != null) {
                    webPlugin.getLogger().info("Ho tro truy cap gop 1 cong (Single-Port) qua cong Minecraft: " + buildSinglePortUrl(null));
                }
                if (shownHost.equals("0.0.0.0")) {
                    webPlugin.getLogger().warning("Dashboard dang mo tren MOI dia chi mang. Hay dung tuong lua hoac dat bind-address: 127.0.0.1.");
                }
            }
        } catch (Throwable t) {
            webPlugin.getLogger().warning("Khong the khoi dong Web Dashboard tren cong " + port + ": " + t.getMessage());
        }
    }

    public synchronized void stop() {
        actualBoundPort = -1;
        loopbackMuxOnly = false;
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

    @Override
    public int getInternalPort() {
        return actualBoundPort > 0 ? actualBoundPort : port;
    }

    @Override
    public boolean isSinglePortActive() {
        if (loopbackMuxOnly) return true;
        if (singlePortMode != null
                && (singlePortMode.trim().equalsIgnoreCase("false") || singlePortMode.trim().equalsIgnoreCase("off"))) {
            return false;
        }
        var mux = plugin.getSinglePortMuxEngine();
        return mux != null && mux.isActive();
    }

    private String buildSinglePortUrl(Player viewer) {
        String prefix = "https".equalsIgnoreCase(publicScheme) ? "https://" : "http://";
        var mux = plugin.getSinglePortMuxEngine();
        String endpoint = (mux != null)
                ? mux.resolvePublicEndpoint(publicHost, publicPort, viewer)
                : ("localhost:" + Bukkit.getPort());
        return prefix + endpoint + "/anticheat?token=" + authToken;
    }

    @Override
    public String getPublicAccessUrl() {
        return getPublicAccessUrl(null);
    }

    @Override
    public String getPublicAccessUrl(Player viewer) {
        String prefix = "https".equalsIgnoreCase(publicScheme) ? "https://" : "http://";
        var mux = plugin.getSinglePortMuxEngine();
        boolean remoteViewer = false;
        if (viewer != null && mux != null) {
            try {
                InetSocketAddress vh = viewer.getVirtualHost();
                if (vh != null) {
                    String clean = com.javastudio.network.SinglePortMuxEngine.sanitizeHost(vh.getHostString());
                    remoteViewer = !com.javastudio.network.SinglePortMuxEngine.isLoopbackOrWildcard(clean);
                }
            } catch (Throwable ignored) {}
        }
        boolean hasConfiguredPublicHost = publicHost != null && !publicHost.isBlank()
                && !com.javastudio.network.SinglePortMuxEngine.isLoopbackOrWildcard(publicHost);
        if (loopbackMuxOnly || ((remoteViewer || hasConfiguredPublicHost) && isSinglePortActive())) {
            return buildSinglePortUrl(viewer);
        }
        return prefix + "localhost:" + getInternalPort() + "/anticheat?token=" + authToken;
    }

    public String getAuthToken() {
        return authToken;
    }

    private boolean isAuthorized(HttpExchange exchange) {
        return isAuthorized(exchange, null);
    }

    private boolean isAuthorized(HttpExchange exchange, String body) {
        String token = (body != null) ? extractJsonField(body, "token") : null;
        if (token == null || token.isBlank()) token = exchange.getRequestHeaders().getFirst("X-Auth-Token");
        if (token == null || token.isBlank()) token = queryParam(exchange, "token");
        return tokenMatches(token);
    }

    private class DashboardHtmlHandler implements HttpHandler {
        private byte[] uncompressedBytes;
        private byte[] gzipBytes;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (uncompressedBytes == null) {
                uncompressedBytes = getDashboardHtml().getBytes(StandardCharsets.UTF_8);
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                     java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
                    gzos.write(uncompressedBytes);
                    gzos.finish();
                    gzipBytes = baos.toByteArray();
                } catch (Throwable t) {
                    gzipBytes = null;
                }
            }

            String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            boolean useGzip = gzipBytes != null && acceptEncoding != null && acceptEncoding.contains("gzip");

            byte[] toSend = useGzip ? gzipBytes : uncompressedBytes;
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            if (useGzip) {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }
            exchange.sendResponseHeaders(200, toSend.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(toSend);
            }
        }
    }

    private class ApiStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"active\":").append(manager.isActive()).append(",");
            sb.append("\"singlePort\":").append(isSinglePortActive()).append(",");
            sb.append("\"mcPort\":").append(Bukkit.getPort()).append(",");
            sb.append("\"totalFlags\":").append(manager.getTotalFlags()).append(",");
            sb.append("\"totalPunishments\":").append(manager.getTotalPunishments()).append(",");
            sb.append("\"nettyDropped\":").append(manager.getNettyDroppedPackets()).append(",");
            sb.append("\"activeAuraBots\":").append(manager.getAuraBotManager() != null ? manager.getAuraBotManager().getActiveBotCount() : 0).append(",");
            sb.append("\"banWaveQueue\":").append(manager.getBanWaveManager() != null ? manager.getBanWaveManager().getQueueSize() : 0).append(",");
            sb.append("\"pendingReports\":").append(manager.getReportManager() != null ? manager.getReportManager().getPendingCount() : 0).append(",");

            double tps = 20.0;
            try {
                double[] t = plugin.getServerController().getTPS();
                if (t != null && t.length > 0) tps = Math.min(20.0, t[0]);
            } catch (Throwable ignored) {}
            sb.append("\"tps\":").append(String.format(Locale.ROOT, "%.2f", tps)).append(",");

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

                double px = 0, py = 0, pz = 0;
                float pyaw = d.lastYaw, ppitch = d.lastPitch;
                try {
                    org.bukkit.Location loc = p.getLocation();
                    px = loc.getX();
                    py = loc.getY();
                    pz = loc.getZ();
                    pyaw = loc.getYaw();
                    ppitch = loc.getPitch();
                } catch (Throwable ignored) {
                    if (d.lastLoc != null) {
                        px = d.lastLoc.getX();
                        py = d.lastLoc.getY();
                        pz = d.lastLoc.getZ();
                    }
                }

                sb.append("{");
                sb.append("\"name\":\"").append(escapeJson(p.getName())).append("\",");
                sb.append("\"uuid\":\"").append(p.getUniqueId()).append("\",");
                sb.append("\"ping\":").append(manager.safePing(p)).append(",");
                sb.append("\"cps\":").append(d.getLiveCps()).append(",");
                sb.append("\"anomaly\":").append(String.format(Locale.ROOT, "%.2f", d.combatAnomalyScore)).append(",");
                sb.append("\"neuralScore\":").append(String.format(Locale.ROOT, "%.2f", d.neuralScore)).append(",");
                sb.append("\"neuralClassification\":\"").append(escapeJson(d.neuralClassification)).append("\",");
                sb.append("\"inBanWave\":").append(d.inBanWave).append(",");
                sb.append("\"yaw\":").append(String.format(Locale.ROOT, "%.1f", pyaw)).append(",");
                sb.append("\"pitch\":").append(String.format(Locale.ROOT, "%.1f", ppitch)).append(",");
                sb.append("\"x\":").append(String.format(Locale.ROOT, "%.1f", px)).append(",");
                sb.append("\"y\":").append(String.format(Locale.ROOT, "%.1f", py)).append(",");
                sb.append("\"z\":").append(String.format(Locale.ROOT, "%.1f", pz)).append(",");
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
                      .append("\",\"frames\":").append(inc.frames.size())
                      .append("}");
                }
            }
            sb.append("],\"banWaveList\":[");
            if (manager.getBanWaveManager() != null) {
                int bIdx = 0;
                for (var b : manager.getBanWaveManager().getQueue()) {
                    if (bIdx++ > 0) sb.append(",");
                    sb.append("{\"name\":\"").append(escapeJson(b.name()))
                      .append("\",\"check\":\"").append(escapeJson(b.check().getDisplayName()))
                      .append("\",\"reason\":\"").append(escapeJson(b.reason()))
                      .append("\",\"totalVl\":").append(String.format(Locale.ROOT, "%.1f", b.vl()))
                      .append(",\"queuedTime\":").append(b.queuedTime())
                      .append("}");
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

    private class ApiReportsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            var repMgr = manager.getReportManager();
            List<ReportManager.ReportEntry> reports = repMgr != null ? repMgr.getReportsHistory() : Collections.emptyList();

            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < reports.size(); i++) {
                ReportManager.ReportEntry r = reports.get(i);
                if (i > 0) sb.append(",");
                sb.append("{");
                sb.append("\"id\":\"").append(escapeJson(r.id)).append("\",");
                sb.append("\"reporter\":\"").append(escapeJson(r.reporterName)).append("\",");
                sb.append("\"target\":\"").append(escapeJson(r.targetName)).append("\",");
                sb.append("\"reason\":\"").append(escapeJson(r.reason)).append("\",");
                sb.append("\"status\":\"").append(r.status.name()).append("\",");
                sb.append("\"handledBy\":\"").append(escapeJson(r.handledBy)).append("\",");
                sb.append("\"resolution\":\"").append(escapeJson(r.resolution)).append("\",");
                sb.append("\"time\":\"").append(escapeJson(r.timestamp)).append("\",");
                sb.append("\"replayId\":\"").append(escapeJson(r.replayId)).append("\"");
                sb.append("}");
            }
            sb.append("]");

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class ApiReportActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = readRequestBody(exchange);
            String token = extractJsonField(body, "token");
            String action = extractJsonField(body, "action");
            String reportId = extractJsonField(body, "id");
            String verdict = extractJsonField(body, "verdict");

            if (!isAuthorized(exchange, body)) {
                sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã xác thực Auth Token!\"}");
                return;
            }

            var repMgr = manager.getReportManager();
            if (repMgr == null) {
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"ReportManager chưa sẵn sàng!\"}");
                return;
            }

            boolean ok = false;
            String msg = "";
            switch (action.toLowerCase(Locale.ROOT)) {
                case "claim" -> {
                    ok = repMgr.claimReport(reportId, "WebAdmin");
                    msg = "Đã tiếp nhận điều tra đơn tố cáo " + reportId;
                }
                case "resolve" -> {
                    ok = repMgr.resolveReport(reportId, "WebAdmin", !verdict.isBlank() ? verdict : "Đã xử lý vi phạm");
                    msg = "Đã giải quyết đơn tố cáo " + reportId;
                }
                case "dismiss" -> {
                    ok = repMgr.dismissReport(reportId, "WebAdmin");
                    msg = "Đã bác bỏ đơn tố cáo " + reportId;
                }
                default -> {
                    sendJson(exchange, 400, "{\"success\":false,\"error\":\"Hành động không hợp lệ!\"}");
                    return;
                }
            }

            if (ok) {
                sendJson(exchange, 200, "{\"success\":true,\"message\":\"" + msg + "\"}");
            } else {
                sendJson(exchange, 404, "{\"success\":false,\"error\":\"Không tìm thấy đơn tố cáo: " + reportId + "\"}");
            }
        }
    }

    private class ApiReplayHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            URI uri = exchange.getRequestURI();
            String query = uri.getQuery();
            String clipId = null;
            if (query != null && query.contains("id=")) {
                for (String param : query.split("&")) {
                    if (param.startsWith("id=")) {
                        clipId = param.substring(3);
                        break;
                    }
                }
            }

            if (clipId == null || clipId.isBlank()) {
                sendJson(exchange, 400, "{\"success\":false,\"error\":\"Thiếu tham số id!\"}");
                return;
            }

            var rm = manager.getReplayManager();
            if (rm == null) {
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"ReplayManager chưa sẵn sàng!\"}");
                return;
            }

            String json = rm.getIncidentJson(clipId);
            if (json == null) {
                sendJson(exchange, 404, "{\"success\":false,\"error\":\"Không tìm thấy clip: " + clipId + "\"}");
                return;
            }

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
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

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = readRequestBody(exchange);
            String token = extractJsonField(body, "token");
            String action = extractJsonField(body, "action");
            String target = extractJsonField(body, "target");

            if (!isAuthorized(exchange, body)) {
                sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã xác thực Auth Token!\"}");
                return;
            }

            if ("execute_banwave".equalsIgnoreCase(action)) {
                PlatformUtil.runTask(plugin, () -> {
                    if (manager.getBanWaveManager() != null) {
                        manager.getBanWaveManager().executeWave(Bukkit.getConsoleSender());
                    }
                });
                sendJson(exchange, 200, "{\"success\":true,\"message\":\"Đã kích hoạt đợt thanh trừng Ban Wave thành công!\"}");
                return;
            }

            if ("clear_banwave".equalsIgnoreCase(action)) {
                if (manager.getBanWaveManager() != null) {
                    manager.getBanWaveManager().clearQueue();
                }
                sendJson(exchange, 200, "{\"success\":true,\"message\":\"Đã làm sạch hàng đợi Ban Wave.\"}");
                return;
            }

            if ("reload".equalsIgnoreCase(action)) {
                PlatformUtil.runTask(plugin, manager::reload);
                sendJson(exchange, 200, "{\"success\":true,\"message\":\"Đã nạp lại cấu hình Anti-Cheat!\"}");
                return;
            }

            Player p = Bukkit.getPlayerExact(target);
            if (p == null) {
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

    private class ApiSystemHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            Runtime rt = Runtime.getRuntime();
            long maxMem = rt.maxMemory() / (1024 * 1024);
            long totalMem = rt.totalMemory() / (1024 * 1024);
            long freeMem = rt.freeMemory() / (1024 * 1024);
            long usedMem = totalMem - freeMem;

            double tps = 20.0;
            try {
                double[] t = plugin.getServerController().getTPS();
                if (t != null && t.length > 0) tps = Math.min(20.0, t[0]);
            } catch (Throwable ignored) {}

            int loadedDynamicPlugins = plugin.getPluginManager() != null ? plugin.getPluginManager().getActivePlugins().size() : 0;
            long packetsChecked = 0;
            long exploitsBlocked = 0;
            long clickFlood = 0;
            long nbtCleaned = 0;
            long commandBombs = 0;
            if (plugin.getExploitGuard() != null) {
                var stats = plugin.getExploitGuard().getStats();
                packetsChecked = stats.getTotalPacketsChecked();
                exploitsBlocked = stats.getPacketsBlocked();
                clickFlood = stats.getClickFloodBlocked();
                nbtCleaned = stats.getOversizedNbtBlocked();
                commandBombs = stats.getCommandBombsBlocked();
            }

            long cveBlocked = 0;
            if (plugin.getPanelCveProtection() != null) {
                cveBlocked = plugin.getPanelCveProtection().getStats().getTotalBlocked();
            }

            String json = String.format(Locale.ROOT,
                    "{\"tps\":%.2f,\"usedMem\":%d,\"totalMem\":%d,\"maxMem\":%d,\"threads\":%d,\"online\":%d,\"dynamicPlugins\":%d," +
                    "\"packetsChecked\":%d,\"exploitsBlocked\":%d,\"clickFlood\":%d,\"nbtCleaned\":%d,\"commandBombs\":%d,\"cveBlocked\":%d," +
                    "\"platform\":\"%s\",\"javaVersion\":\"%s\"}",
                    tps, usedMem, totalMem, maxMem, Thread.activeCount(), Bukkit.getOnlinePlayers().size(),
                    loadedDynamicPlugins, packetsChecked, exploitsBlocked, clickFlood, nbtCleaned, commandBombs, cveBlocked,
                    escapeJson(com.javastudio.utils.PlatformUtil.getPlatformName()),
                    escapeJson(com.javastudio.utils.PlatformUtil.getJavaVersionString()));

            sendJson(exchange, 200, json);
        }
    }

    private class ApiPluginsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readRequestBody(exchange);
                String token = extractJsonField(body, "token");
                String action = extractJsonField(body, "action");
                String pName = extractJsonField(body, "plugin");

                if (!isAuthorized(exchange, body)) {
                    sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã Auth Token!\"}");
                    return;
                }

                if (plugin.getPluginManager() == null) {
                    sendJson(exchange, 500, "{\"success\":false,\"error\":\"PluginManager chưa sẵn sàng!\"}");
                    return;
                }

                StringBuilder log = new StringBuilder();
                boolean ok = false;
                if ("reload".equalsIgnoreCase(action)) {
                    ok = plugin.getPluginManager().reloadPlugin(pName, log);
                } else if ("unload".equalsIgnoreCase(action)) {
                    ok = plugin.getPluginManager().unloadPlugin(pName);
                    if (ok) log.append("Đã dỡ bỏ dynamic plugin ").append(pName);
                    else log.append("Không thể dỡ bỏ dynamic plugin ").append(pName);
                } else if ("load".equalsIgnoreCase(action)) {
                    ok = plugin.getPluginManager().loadPlugin(pName, log);
                }

                sendJson(exchange, ok ? 200 : 400, "{\"success\":" + ok + ",\"message\":\"" + escapeJson(log.toString().isBlank() ? (ok ? "Thành công" : "Thất bại") : log.toString()) + "\"}");
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"plugins\":[");
            if (plugin.getPluginManager() != null) {
                var loaded = plugin.getPluginManager().getActivePlugins().values();
                int idx = 0;
                for (var p : loaded) {
                    if (idx++ > 0) sb.append(",");
                    sb.append("{")
                      .append("\"name\":\"").append(escapeJson(p.getName())).append("\",")
                      .append("\"version\":\"").append(escapeJson(p.getVersion())).append("\",")
                      .append("\"author\":\"").append(escapeJson(p.getAuthor())).append("\",")
                      .append("\"enabled\":").append(p.isEnabled()).append(",")
                      .append("\"description\":\"").append(escapeJson(p.getDescription())).append("\"")
                      .append("}");
                }
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private class ApiExecHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = readRequestBody(exchange);
            String token = extractJsonField(body, "token");
            String code = extractJsonField(body, "code");

            if (!isAuthorized(exchange, body)) {
                sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã Auth Token!\"}");
                return;
            }

            if (code == null || code.isBlank()) {
                sendJson(exchange, 400, "{\"success\":false,\"error\":\"Mã nguồn rỗng!\"}");
                return;
            }

            try {
                String className = "WebExec_" + System.currentTimeMillis();
                String fullPath = "com.javastudio.runtime." + className;
                String scriptSource = """
                        package com.javastudio.runtime;
                        import org.bukkit.*;
                        import org.bukkit.entity.*;
                        import org.bukkit.inventory.*;
                        import com.javastudio.api.*;

                        public class %NAME% extends QuickScript {
                            @Override
                            public Object run() throws Throwable {
                                %CODE%
                                return "Thực thi hoàn tất!";
                            }
                        }
                        """.replace("%NAME%", className).replace("%CODE%", code);

                var cr = plugin.getCompiler().compileSingleSource(fullPath, scriptSource);
                if (!cr.isSuccess()) {
                    sendJson(exchange, 400, "{\"success\":false,\"error\":\"Lỗi cú pháp: " + escapeJson(cr.getFormattedErrors().split("\n")[0]) + "\"}");
                    return;
                }

                Class<?> clazz = cr.loadClass(fullPath);
                com.javastudio.api.DynamicScript script = (com.javastudio.api.DynamicScript) clazz.getDeclaredConstructor().newInstance();
                com.javastudio.api.JavaContext jCtx = new com.javastudio.api.JavaContext(Bukkit.getConsoleSender(), plugin, new String[0]);

                Object res = plugin.getSecuritySandbox().executeWithTimeout(() -> script.execute(jCtx), 3000);
                sendJson(exchange, 200, "{\"success\":true,\"result\":\"" + escapeJson(String.valueOf(res)) + "\"}");
            } catch (Throwable t) {
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"Lỗi thực thi: " + escapeJson(t.getMessage()) + "\"}");
            }
        }
    }

    private class ApiTerminalStreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0);

            OutputStream os = exchange.getResponseBody();
            var logBuffer = plugin.getConsoleLogBuffer();

            if (logBuffer != null) {
                for (var entry : logBuffer.getRecentLogs(40)) {
                    String data = "data: " + formatLogEntryJson(entry) + "\n\n";
                    os.write(data.getBytes(StandardCharsets.UTF_8));
                }
                os.flush();
            }

            java.util.concurrent.BlockingQueue<com.javastudio.control.ConsoleLogBuffer.LogEntry> queue = new java.util.concurrent.LinkedBlockingQueue<>(200);
            java.util.function.Consumer<com.javastudio.control.ConsoleLogBuffer.LogEntry> listener = queue::offer;
            if (logBuffer != null) {
                logBuffer.addLogListener(listener);
            }

            try {
                while (isRunning()) {
                    var entry = queue.poll(15, java.util.concurrent.TimeUnit.SECONDS);
                    if (entry != null) {
                        String data = "data: " + formatLogEntryJson(entry) + "\n\n";
                        os.write(data.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } else {
                        os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                }
            } catch (IOException ignored) {
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                if (logBuffer != null) {
                    logBuffer.removeLogListener(listener);
                }
                try {
                    os.close();
                } catch (Throwable ignored) {}
            }
        }

        private String formatLogEntryJson(com.javastudio.control.ConsoleLogBuffer.LogEntry entry) {
            return "{\"id\":" + entry.id() + ",\"time\":" + entry.timestamp()
                    + ",\"level\":\"" + escapeJson(entry.level()) + "\",\"message\":\"" + escapeJson(entry.message()) + "\"}";
        }
    }


    private java.io.File resolveSafeFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        if (relativePath.contains("..") || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            return null;
        }
        String norm = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!norm.startsWith("projects/") && !norm.startsWith("scripts/") && !norm.startsWith("flows/") && !norm.startsWith("guis/")) {
            return null;
        }
        java.io.File baseDir = plugin.getDataFolder();
        java.io.File target = new java.io.File(baseDir, relativePath);
        try {
            String baseCanonical = baseDir.getCanonicalPath();
            String targetCanonical = target.getCanonicalPath();
            if (!targetCanonical.startsWith(baseCanonical)) {
                return null;
            }
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    private static String extractJsonString(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx == -1) return "";
        int colon = json.indexOf(':', idx + key.length());
        if (colon == -1) return "";
        int startQuote = json.indexOf('\"', colon);
        if (startQuote == -1) return "";

        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '\"' -> sb.append('\"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '\"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private class ApiIdeTreeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            String query = exchange.getRequestURI().getQuery();
            String token = "";
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2 && "token".equalsIgnoreCase(pair[0])) {
                        token = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        break;
                    }
                }
            }
            if (!isAuthorized(exchange)) {
                sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã Auth Token!\"}");
                return;
            }

            java.io.File dataFolder = plugin.getDataFolder();
            java.io.File scriptsDir = new java.io.File(dataFolder, "scripts");
            java.io.File projectsDir = new java.io.File(dataFolder, "projects");

            if (!scriptsDir.exists()) scriptsDir.mkdirs();
            if (!projectsDir.exists()) projectsDir.mkdirs();

            java.io.File sample = new java.io.File(scriptsDir, "QuickSample.java");
            if (!sample.exists()) {
                String sampleContent = """
                        package com.javastudio.runtime;
                        import org.bukkit.*;
                        import org.bukkit.entity.*;
                        import com.javastudio.api.*;

                        public class QuickSample extends QuickScript {
                            @Override
                            public Object run() throws Throwable {
                                broadcast("&a[JavaStudio Web IDE] Chào mừng đến với IDE Minecraft!");
                                sound("levelup", 1.0f, 1.0f);
                                return "Thực thi thành công từ Monaco Web IDE!";
                            }
                        }
                        """;
                try {
                    java.nio.file.Files.writeString(sample.toPath(), sampleContent, StandardCharsets.UTF_8);
                } catch (Throwable ignored) {}
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"success\":true,\"files\":[");
            boolean first = true;

            if (scriptsDir.exists()) {
                first = appendDirTree(sb, scriptsDir, "scripts", first);
            }
            if (projectsDir.exists()) {
                appendDirTree(sb, projectsDir, "projects", first);
            }

            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }

        private boolean appendDirTree(StringBuilder sb, java.io.File dir, String relPath, boolean first) {
            java.io.File[] files = dir.listFiles();
            if (files == null) return first;

            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });

            for (java.io.File f : files) {
                if (f.getName().startsWith(".")) continue;
                if (!first) sb.append(",");
                first = false;

                String subRel = relPath + "/" + f.getName();
                sb.append("{");
                sb.append("\"name\":\"").append(escapeJson(f.getName())).append("\",");
                sb.append("\"path\":\"").append(escapeJson(subRel)).append("\",");
                if (f.isDirectory()) {
                    sb.append("\"type\":\"directory\",");
                    sb.append("\"children\":[");
                    boolean subFirst = true;
                    java.io.File[] subFiles = f.listFiles();
                    if (subFiles != null) {
                        for (java.io.File sf : subFiles) {
                            if (sf.getName().startsWith(".")) continue;
                            if (!subFirst) sb.append(",");
                            subFirst = false;
                            sb.append("{\"name\":\"").append(escapeJson(sf.getName())).append("\",")
                              .append("\"path\":\"").append(escapeJson(subRel + "/" + sf.getName())).append("\",")
                              .append("\"type\":\"").append(sf.isDirectory() ? "directory" : "file").append("\",")
                              .append("\"size\":").append(sf.isDirectory() ? 0 : sf.length())
                              .append("}");
                        }
                    }
                    sb.append("]");
                } else {
                    sb.append("\"type\":\"file\",");
                    sb.append("\"size\":").append(f.length());
                }
                sb.append("}");
            }
            return first;
        }
    }

    private class ApiIdeFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            String query = exchange.getRequestURI().getQuery();
            String path = "";
            String token = "";
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2) {
                        if ("path".equalsIgnoreCase(pair[0])) {
                            path = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        } else if ("token".equalsIgnoreCase(pair[0])) {
                            token = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        }
                    }
                }
            }

            if (!isAuthorized(exchange)) {
                sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã Auth Token!\"}");
                return;
            }

            java.io.File target = resolveSafeFile(path);
            if (target == null || !target.exists() || target.isDirectory()) {
                sendJson(exchange, 404, "{\"success\":false,\"error\":\"Không tìm thấy tập tin hoặc đường dẫn không hợp lệ!\"}");
                return;
            }

            try {
                String content = java.nio.file.Files.readString(target.toPath(), StandardCharsets.UTF_8);
                String json = "{\"success\":true,\"path\":\"" + escapeJson(path) + "\",\"name\":\"" + escapeJson(target.getName()) +
                        "\",\"size\":" + target.length() + ",\"content\":\"" + escapeJson(content) + "\"}";
                sendJson(exchange, 200, json);
            } catch (Throwable t) {
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"Không thể đọc tập tin: " + escapeJson(t.getMessage()) + "\"}");
            }
        }
    }

    private class ApiIdeSaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = readRequestBody(exchange);
            String token = extractJsonField(body, "token");
            String path = extractJsonField(body, "path");
            String content = extractJsonString(body, "content");

            if (!isAuthorized(exchange, body)) {
                sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã Auth Token!\"}");
                return;
            }

            if (path == null || path.isBlank()) {
                sendJson(exchange, 400, "{\"success\":false,\"error\":\"Đường dẫn tập tin không được rỗng!\"}");
                return;
            }

            java.io.File target = resolveSafeFile(path);
            if (target == null) {
                sendJson(exchange, 400, "{\"success\":false,\"error\":\"Đường dẫn không hợp lệ hoặc vi phạm Sandbox boundary!\"}");
                return;
            }

            try {
                if (target.getParentFile() != null) {
                    target.getParentFile().mkdirs();
                }
                java.nio.file.Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);

                if (path.startsWith("projects/") || path.startsWith("projects\\")) {
                    String sub = path.substring("projects/".length());
                    int slash = sub.indexOf('/');
                    if (slash == -1) slash = sub.indexOf('\\');
                    String pluginName = slash != -1 ? sub.substring(0, slash) : sub;

                    if (plugin.getPluginManager() != null && !pluginName.isBlank()) {
                        StringBuilder log = new StringBuilder();
                        boolean reloaded = plugin.getPluginManager().reloadPlugin(pluginName, log);
                        String msg = "Đã lưu " + target.getName() + " và Hot-Reload plugin [" + pluginName + "]: " +
                                (reloaded ? "Thành công!" : "Thất bại: " + log);
                        sendJson(exchange, 200, "{\"success\":true,\"path\":\"" + escapeJson(path) + "\",\"reloaded\":" + reloaded +
                                ",\"message\":\"" + escapeJson(msg) + "\"}");
                        return;
                    }
                }

                if (path.endsWith(".java")) {
                    String dryClassName = "com.javastudio.runtime.IdeSyntaxCheck_" + System.currentTimeMillis();
                    var cr = plugin.getCompiler().compileSingleSource(dryClassName, content);
                    if (!cr.isSuccess()) {
                        String errors = cr.getFormattedErrors();
                        sendJson(exchange, 200, "{\"success\":true,\"path\":\"" + escapeJson(path) +
                                "\",\"compiled\":false,\"compileErrors\":\"" + escapeJson(errors) +
                                "\",\"message\":\"Đã lưu tập tin, nhưng phát hiện lỗi cú pháp!\"}");
                        return;
                    }
                }

                sendJson(exchange, 200, "{\"success\":true,\"path\":\"" + escapeJson(path) + "\",\"compiled\":true,\"message\":\"Đã lưu tập tin và xác thực cú pháp thành công (0 lỗi)!\"}");
            } catch (Throwable t) {
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"Không thể ghi tập tin: " + escapeJson(t.getMessage()) + "\"}");
            }
        }
    }

    private class ApiIdeRunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = readRequestBody(exchange);
            String token = extractJsonField(body, "token");
            String path = extractJsonField(body, "path");
            String rawCode = extractJsonString(body, "content");

            if (!isAuthorized(exchange, body)) {
                sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã Auth Token!\"}");
                return;
            }

            String code = rawCode;
            if ((code == null || code.isBlank()) && path != null && !path.isBlank()) {
                java.io.File target = resolveSafeFile(path);
                if (target != null && target.exists()) {
                    code = java.nio.file.Files.readString(target.toPath(), StandardCharsets.UTF_8);
                }
            }

            if (code == null || code.isBlank()) {
                sendJson(exchange, 400, "{\"success\":false,\"error\":\"Không có mã nguồn để chạy!\"}");
                return;
            }

            try {
                String className = "WebIdeExec_" + System.currentTimeMillis();
                String fullPath = "com.javastudio.runtime." + className;
                String scriptSource;

                if (code.contains("public class") && code.contains("implements DynamicScript")) {
                    scriptSource = code;
                } else if (code.contains("public class") && code.contains("extends QuickScript")) {
                    scriptSource = code;
                } else {
                    scriptSource = """
                            package com.javastudio.runtime;
                            import org.bukkit.*;
                            import org.bukkit.entity.*;
                            import org.bukkit.inventory.*;
                            import com.javastudio.api.*;

                            public class %NAME% extends QuickScript {
                                @Override
                                public Object run() throws Throwable {
                                    %CODE%
                                    return "Hoàn tất kịch bản!";
                                }
                            }
                            """.replace("%NAME%", className).replace("%CODE%", code);
                }

                var cr = plugin.getCompiler().compileSingleSource(fullPath, scriptSource);
                if (!cr.isSuccess()) {
                    sendJson(exchange, 400, "{\"success\":false,\"error\":\"Lỗi cú pháp: " + escapeJson(cr.getFormattedErrors()) + "\"}");
                    return;
                }

                Class<?> clazz = cr.loadClass(fullPath);
                com.javastudio.api.DynamicScript script = (com.javastudio.api.DynamicScript) clazz.getDeclaredConstructor().newInstance();
                com.javastudio.api.JavaContext jCtx = new com.javastudio.api.JavaContext(Bukkit.getConsoleSender(), plugin, new String[0]);

                Object res = plugin.getSecuritySandbox().executeWithTimeout(() -> script.execute(jCtx), 5000);
                sendJson(exchange, 200, "{\"success\":true,\"result\":\"" + escapeJson(String.valueOf(res)) + "\"}");
            } catch (Throwable t) {
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"Lỗi thực thi: " + escapeJson(t.getMessage()) + "\"}");
            }
        }
    }

    private class ApiFlowListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            String json = plugin.getVisualFlowEngine() != null ? plugin.getVisualFlowEngine().exportFlowsJson() : "{\"flows\":[]}";
            sendJson(exchange, 200, json);
        }
    }

    private class ApiFlowSaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = readRequestBody(exchange);
            String token = extractJsonField(body, "token");
            if (!isAuthorized(exchange, body)) {
                sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã Auth Token!\"}");
                return;
            }

            if (plugin.getVisualFlowEngine() == null) {
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"VisualFlowEngine chưa sẵn sàng!\"}");
                return;
            }

            StringBuilder log = new StringBuilder();
            boolean ok = plugin.getVisualFlowEngine().saveFlow(body, log);
            sendJson(exchange, ok ? 200 : 400, "{\"success\":" + ok + ",\"message\":\"" + escapeJson(log.toString()) + "\"}");
        }
    }

    private class ApiFlowRunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = readRequestBody(exchange);
            String token = extractJsonField(body, "token");
            String id = extractJsonField(body, "id");

            if (!isAuthorized(exchange, body)) {
                sendJson(exchange, 403, "{\"success\":false,\"error\":\"Sai mã Auth Token!\"}");
                return;
            }

            if (plugin.getVisualFlowEngine() == null) {
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"VisualFlowEngine chưa sẵn sàng!\"}");
                return;
            }

            com.javastudio.api.JavaContext ctx = new com.javastudio.api.JavaContext(Bukkit.getConsoleSender(), plugin, new String[0]);
            StringBuilder log = new StringBuilder();
            boolean ok = plugin.getVisualFlowEngine().executeFlow(id, ctx, log);
            sendJson(exchange, ok ? 200 : 400, "{\"success\":" + ok + ",\"result\":\"" + escapeJson(log.toString()) + "\"}");
        }
    }

    private class ApiMeshStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            var mesh = plugin.getClusterMeshEngine();
            if (mesh == null) {
                sendJson(exchange, 200, "{\"active\":false,\"peers\":[]}");
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{")
              .append("\"active\":").append(mesh.isRunning()).append(",")
              .append("\"nodeId\":\"").append(escapeJson(mesh.getNodeId())).append("\",")
              .append("\"role\":\"").append(mesh.getCurrentRole()).append("\",")
              .append("\"leaderId\":\"").append(escapeJson(mesh.getLeaderId())).append("\",")
              .append("\"peers\":[");

            int idx = 0;
            for (var p : mesh.getActivePeers()) {
                if (idx++ > 0) sb.append(",");
                sb.append("{")
                  .append("\"id\":\"").append(escapeJson(p.id())).append("\",")
                  .append("\"host\":\"").append(escapeJson(p.host())).append("\",")
                  .append("\"port\":").append(p.port()).append(",")
                  .append("\"online\":").append(p.onlinePlayers()).append(",")
                  .append("\"tps\":").append(String.format(Locale.ROOT, "%.2f", p.tps())).append(",")
                  .append("\"leader\":").append(p.isLeader())
                  .append("}");
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private class ApiDeadlockStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            var dl = plugin.getDeadlockResolverEngine();
            if (dl == null) {
                sendJson(exchange, 200, "{\"active\":false,\"incidents\":[]}");
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"active\":true,\"autoResolve\":").append(dl.isAutoResolve()).append(",\"incidents\":[");
            int idx = 0;
            for (var inc : dl.getIncidents()) {
                if (idx++ > 0) sb.append(",");
                sb.append("{\"id\":\"").append(escapeJson(inc.incidentId())).append("\",")
                  .append("\"timestamp\":").append(inc.timestamp()).append(",")
                  .append("\"threadCount\":").append(inc.threadCount()).append(",")
                  .append("\"resolved\":").append(inc.resolvedSuccessfully()).append("}");
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private class ApiPrefetchStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            var pf = plugin.getChunkPrefetcher();
            if (pf == null) {
                sendJson(exchange, 200, "{\"enabled\":false}");
                return;
            }

            String json = String.format(Locale.ROOT,
                    "{\"enabled\":%b,\"totalPrefetched\":%d,\"cacheHits\":%d,\"skippedTps\":%d,\"activeLoads\":%d,\"trackedPlayers\":%d}",
                    pf.isEnabled(), pf.getTotalPrefetched(), pf.getCacheHits(), pf.getSkippedDueToTps(), pf.getCurrentConcurrentLoads(), pf.getTrackedPlayerCount());
            sendJson(exchange, 200, json);
        }
    }

    private class ApiGuiListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            var eng = plugin.getGuiDesignerEngine();
            if (eng == null) {
                sendJson(exchange, 200, "{\"guis\":[]}");
                return;
            }

            List<String> list = eng.listGuiIds();
            StringBuilder sb = new StringBuilder("{\"guis\":[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJson(list.get(i))).append("\"");
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private class ApiFlameGraphHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            String json = plugin.getProfiler() != null
                    ? plugin.getProfiler().toFlameGraphJson()
                    : "{\"name\":\"Server Root\",\"value\":1000,\"children\":[]}";
            sendJson(exchange, 200, json);
        }
    }

    private class ApiBenchmarkStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            var bm = plugin.getServerBenchmarkEngine();
            if (bm == null) {
                sendJson(exchange, 200, "{\"success\":false,\"running\":false,\"error\":\"Benchmark engine chưa sẵn sàng\"}");
                return;
            }
            boolean running = bm.isBenchmarkRunning();
            String latest = bm.getLatestBenchmarkJson();
            if (latest == null || latest.isBlank()) {
                sendJson(exchange, 200, "{\"success\":true,\"running\":" + running + ",\"hasResult\":false}");
            } else {
                sendJson(exchange, 200, "{\"success\":true,\"running\":" + running + ",\"hasResult\":true,\"result\":" + latest + "}");
            }
        }
    }

    private class ApiBenchmarkRunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            var bm = plugin.getServerBenchmarkEngine();
            if (bm == null) {
                sendJson(exchange, 500, "{\"success\":false,\"error\":\"Benchmark engine chưa sẵn sàng\"}");
                return;
            }
            if (bm.isBenchmarkRunning()) {
                sendJson(exchange, 409, "{\"success\":false,\"error\":\"Một tiến trình kiểm thử benchmark đang chạy!\"}");
                return;
            }
            String quickParam = queryParam(exchange, "quick");
            boolean quick = "true".equalsIgnoreCase(quickParam);
            bm.runBenchmarkAsync(null, quick);
            sendJson(exchange, 200, "{\"success\":true,\"message\":\"Đã khởi chạy kiểm thử benchmark server thành công!\",\"quick\":" + quick + "}");
        }
    }

    private class ApiIdeAutocompleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            String targetClass = queryParam(exchange, "class");
            String prefix = queryParam(exchange, "prefix");
            String lowerPrefix = prefix != null ? prefix.toLowerCase(Locale.ROOT) : "";

            List<Class<?>> inspectClasses = new ArrayList<>();
            if (targetClass != null && !targetClass.isBlank()) {
                try {
                    inspectClasses.add(Class.forName(targetClass.trim(), false, plugin.getClass().getClassLoader()));
                } catch (Throwable ignored) {}
            } else {
                inspectClasses.add(com.javastudio.api.QuickScript.class);
                inspectClasses.add(com.javastudio.api.DynamicPlugin.class);
                inspectClasses.add(org.bukkit.Bukkit.class);
                inspectClasses.add(org.bukkit.entity.Player.class);
                inspectClasses.add(org.bukkit.World.class);
            }

            StringBuilder items = new StringBuilder("[");
            Set<String> seen = new HashSet<>();
            int count = 0;
            for (Class<?> cls : inspectClasses) {
                for (java.lang.reflect.Method m : cls.getMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    String mName = m.getName();
                    if (!lowerPrefix.isEmpty() && !mName.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) continue;
                    StringBuilder sig = new StringBuilder(mName).append("(");
                    java.lang.reflect.Parameter[] params = m.getParameters();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sig.append(", ");
                        sig.append(params[i].getType().getSimpleName()).append(" ").append(params[i].getName());
                    }
                    sig.append(")");
                    String key = cls.getSimpleName() + "#" + sig;
                    if (!seen.add(key)) continue;
                    if (count > 0) items.append(",");
                    items.append("{\"label\":\"").append(escapeJson(sig.toString()))
                            .append("\",\"insertText\":\"").append(escapeJson(mName)).append("(")
                            .append(params.length == 0 ? ")" : "")
                            .append("\",\"detail\":\"").append(escapeJson(cls.getSimpleName() + " -> " + m.getReturnType().getSimpleName()))
                            .append("\"}");
                    if (++count >= 120) break;
                }
                if (count >= 120) break;
            }
            items.append("]");
            sendJson(exchange, 200, "{\"success\":true,\"suggestions\":" + items + "}");
        }
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        int total = 0;
        while ((read = is.read(buf)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new IOException("Request body vượt quá giới hạn " + MAX_BODY_BYTES + " bytes");
            }
            bos.write(buf, 0, read);
        }
        return bos.toString(StandardCharsets.UTF_8);
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


    @SuppressWarnings("all")
    private static final String HTML_PART_1 = """
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
            --purple: #c084fc;
            --text-main: #f1f5f9;
            --text-muted: #94a3b8;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; }
        body { background: var(--bg-base); color: var(--text-main); min-height: 100vh; padding: 24px; }
        .container { max-width: 1440px; margin: 0 auto; display: flex; flex-direction: column; gap: 24px; }

        header { display: flex; justify-content: space-between; align-items: center; padding: 16px 24px; background: var(--bg-card); backdrop-filter: blur(12px); border: 1px solid var(--border); border-radius: 16px; box-shadow: 0 8px 32px rgba(0,0,0,0.4); }
        .brand { display: flex; align-items: center; gap: 14px; }
        .logo-badge { width: 44px; height: 44px; border-radius: 12px; background: linear-gradient(135deg, #0284c7, #38bdf8); display: flex; align-items: center; justify-content: center; font-size: 24px; box-shadow: 0 0 20px var(--primary-glow); }
        .brand h1 { font-size: 20px; font-weight: 700; letter-spacing: -0.5px; }
        .brand span { font-size: 13px; color: var(--text-muted); }
        .auth-bar { display: flex; gap: 12px; align-items: center; }
        .input-token { background: rgba(0,0,0,0.5); border: 1px solid var(--border); border-radius: 8px; padding: 8px 14px; color: var(--text-main); font-size: 13px; outline: none; transition: 0.2s; }
        .input-token:focus { border-color: var(--primary); }

        .nav-tabs { display: flex; gap: 10px; border-bottom: 1px solid var(--border); padding-bottom: 12px; }
        .tab-btn { background: transparent; border: none; color: var(--text-muted); padding: 8px 16px; font-size: 14px; font-weight: 600; cursor: pointer; border-radius: 8px; transition: 0.2s; }
        .tab-btn:hover { color: var(--text-main); background: rgba(255,255,255,0.05); }
        .tab-btn.active { color: var(--primary); background: rgba(56, 189, 248, 0.12); border: 1px solid var(--primary-glow); }

        .grid-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; }
        .card-stat { background: var(--bg-card); backdrop-filter: blur(8px); border: 1px solid var(--border); border-radius: 14px; padding: 20px; position: relative; overflow: hidden; }
        .card-stat::before { content: ''; position: absolute; top: 0; left: 0; width: 4px; height: 100%; background: var(--primary); }
        .card-stat.danger::before { background: var(--danger); }
        .card-stat.warning::before { background: var(--warning); }
        .card-stat.success::before { background: var(--success); }
        .card-stat.purple::before { background: var(--purple); }
        .card-stat .label { font-size: 13px; color: var(--text-muted); font-weight: 500; }
        .card-stat .value { font-size: 32px; font-weight: 700; margin-top: 8px; letter-spacing: -1px; }

        .workspace { display: grid; grid-template-columns: 2fr 1.2fr; gap: 24px; }
        @media (max-width: 1024px) { .workspace { grid-template-columns: 1fr; } }

        .panel { background: var(--bg-card); backdrop-filter: blur(12px); border: 1px solid var(--border); border-radius: 16px; padding: 20px; display: flex; flex-direction: column; gap: 16px; }
        .panel-header { display: flex; justify-content: space-between; align-items: center; }
        .panel-title { font-size: 16px; font-weight: 600; display: flex; align-items: center; gap: 8px; }

        table { width: 100%; border-collapse: collapse; font-size: 13px; }
        th { text-align: left; padding: 12px 14px; color: var(--text-muted); font-weight: 600; border-bottom: 1px solid var(--border); }
        td { padding: 14px; border-bottom: 1px solid rgba(255,255,255,0.03); vertical-align: middle; }
        tr:hover td { background: rgba(255,255,255,0.02); }
        .badge { display: inline-block; padding: 4px 8px; border-radius: 6px; font-size: 11px; font-weight: 600; }
        .badge-clean { background: rgba(16, 185, 129, 0.15); color: var(--success); }
        .badge-warning { background: rgba(245, 158, 11, 0.15); color: var(--warning); }
        .badge-danger { background: rgba(244, 63, 94, 0.15); color: var(--danger); }
        .badge-frozen { background: rgba(56, 189, 248, 0.2); color: var(--primary); border: 1px solid var(--primary); }

        .btn-group { display: flex; gap: 6px; flex-wrap: wrap; }
        .btn { border: none; outline: none; cursor: pointer; padding: 6px 12px; border-radius: 6px; font-size: 12px; font-weight: 600; transition: 0.15s; background: rgba(255,255,255,0.06); color: var(--text-main); }
        .btn:hover { background: rgba(255,255,255,0.15); transform: translateY(-1px); }
        .btn-primary { background: rgba(56, 189, 248, 0.2); color: var(--primary); }
        .btn-primary:hover { background: rgba(56, 189, 248, 0.35); }
        .btn-bot { background: rgba(245, 158, 11, 0.2); color: #fbbf24; }
        .btn-freeze { background: rgba(56, 189, 248, 0.2); color: #38bdf8; }
        .btn-kick { background: rgba(244, 63, 94, 0.2); color: #f43f5e; }
        .btn-success { background: rgba(16, 185, 129, 0.2); color: var(--success); }

        .radar-box { position: relative; width: 100%; aspect-ratio: 1; max-height: 360px; margin: 0 auto; display: flex; align-items: center; justify-content: center; }
        canvas { width: 100%; height: 100%; background: #07090e; border-radius: 12px; border: 1px solid var(--border); }

        .terminal { background: #05070a; border: 1px solid var(--border); border-radius: 10px; padding: 14px; font-family: 'Consolas', monospace; font-size: 12px; height: 260px; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
        .log-line { line-height: 1.5; color: #cbd5e1; }
        .log-line.danger { color: #f43f5e; }
        .log-line.warn { color: #fbbf24; }
        .log-line.info { color: #38bdf8; }

        .modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.8); backdrop-filter: blur(8px); display: none; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
        .modal-overlay.show { display: flex; }
        .modal-card { background: #0d121d; border: 1px solid var(--border); border-radius: 20px; width: 100%; max-width: 860px; box-shadow: 0 20px 60px rgba(0,0,0,0.8); overflow: hidden; display: flex; flex-direction: column; }
        .modal-header { padding: 18px 24px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; background: rgba(255,255,255,0.02); }
        .modal-body { padding: 24px; display: flex; flex-direction: column; gap: 16px; }
        .replay-canvas-wrap { position: relative; width: 100%; height: 380px; background: #05070a; border-radius: 12px; border: 1px solid var(--border); overflow: hidden; }
        .replay-canvas { width: 100%; height: 100%; }
        .replay-controls { display: flex; align-items: center; gap: 14px; background: rgba(255,255,255,0.03); padding: 12px 18px; border-radius: 12px; border: 1px solid var(--border); }
        .scrub-bar { flex: 1; accent-color: var(--primary); cursor: pointer; }
        .replay-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; font-size: 12px; background: rgba(0,0,0,0.3); padding: 12px; border-radius: 10px; }

        .ide-container { display: grid; grid-template-columns: 260px 1fr; gap: 16px; height: calc(100vh - 220px); min-height: 640px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 16px; overflow: hidden; }
        .ide-sidebar { background: rgba(10, 13, 20, 0.7); border-right: 1px solid var(--border); display: flex; flex-direction: column; overflow: hidden; }
        .ide-sidebar-header { padding: 14px 16px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); font-size: 12px; font-weight: 700; color: var(--text-muted); letter-spacing: 0.5px; }
        .ide-sidebar-actions { display: flex; gap: 6px; }
        .btn-icon { background: transparent; border: none; cursor: pointer; color: var(--text-muted); padding: 4px; border-radius: 4px; font-size: 13px; transition: 0.15s; }
        .btn-icon:hover { color: var(--text-main); background: rgba(255,255,255,0.08); }
        .ide-tree { flex: 1; overflow-y: auto; padding: 8px; display: flex; flex-direction: column; gap: 2px; font-family: 'Segoe UI', system-ui, sans-serif; font-size: 13px; }
        .tree-node { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-radius: 6px; cursor: pointer; user-select: none; color: var(--text-muted); transition: 0.15s; }
        .tree-node:hover { background: rgba(255,255,255,0.04); color: var(--text-main); }
        .tree-node.active { background: rgba(56, 189, 248, 0.15); color: var(--primary); font-weight: 600; }
        .tree-folder { font-weight: 600; color: #e2e8f0; }
        .tree-children { margin-left: 16px; display: flex; flex-direction: column; gap: 2px; }
        .ide-main { display: flex; flex-direction: column; overflow: hidden; }
        .ide-toolbar { padding: 10px 18px; display: flex; justify-content: space-between; align-items: center; background: rgba(15, 20, 31, 0.6); border-bottom: 1px solid var(--border); }
        .ide-active-tab { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; color: var(--text-main); }
        .ide-toolbar-actions { display: flex; align-items: center; gap: 10px; }
        .ide-editor-wrapper { flex: 1; position: relative; background: #1e1e1e; min-height: 380px; }
        #monacoEditorContainer { width: 100%; height: 100%; min-height: 380px; }
        .ide-dock { height: 170px; background: #0a0d14; border-top: 1px solid var(--border); display: flex; flex-direction: column; }
        .ide-dock-header { display: flex; justify-content: space-between; align-items: center; padding: 6px 14px; background: rgba(255,255,255,0.02); border-bottom: 1px solid var(--border); font-size: 12px; }
        .ide-dock-tabs { display: flex; gap: 12px; }
        .ide-dock-tab { cursor: pointer; color: var(--text-muted); font-weight: 600; padding: 4px 8px; border-radius: 4px; }
        .ide-dock-tab.active { color: var(--primary); background: rgba(56, 189, 248, 0.1); }
        .ide-dock-content { flex: 1; padding: 10px 14px; overflow-y: auto; font-family: Consolas, monospace; font-size: 12px; }
        .flow-canvas-container { position: relative; width: 100%; height: 500px; background: #070a12; background-image: radial-gradient(rgba(255,255,255,0.08) 1.5px, transparent 1.5px); background-size: 24px 24px; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; user-select: none; }
        .flow-node-card { position: absolute; width: 230px; background: rgba(15, 23, 42, 0.95); border: 1px solid rgba(56, 189, 248, 0.35); border-radius: 8px; box-shadow: 0 8px 24px rgba(0,0,0,0.6); color: #e2e8f0; font-size: 12px; cursor: move; z-index: 10; }
        .flow-node-header { padding: 8px 12px; font-weight: 700; font-size: 12px; border-top-left-radius: 7px; border-top-right-radius: 7px; display: flex; justify-content: space-between; align-items: center; }
        .flow-node-header.trigger { background: linear-gradient(135deg, #7c3aed, #4f46e5); color: white; }
        .flow-node-header.condition { background: linear-gradient(135deg, #d97706, #b45309); color: white; }
        .flow-node-header.action { background: linear-gradient(135deg, #059669, #0d9488); color: white; }
        .flow-node-body { padding: 10px 12px; display: flex; flex-direction: column; gap: 6px; }
        .flow-port { width: 12px; height: 12px; border-radius: 50%; background: #38bdf8; border: 2px solid #0f172a; display: inline-block; cursor: crosshair; }
        .flow-port:hover { transform: scale(1.3); background: #a855f7; }
        @keyframes slideInToast { from { transform: translateY(20px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
        .toast-item { animation: slideInToast 0.25s ease-out; }
    </style>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.45.0/min/vs/loader.min.js"></script>
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
            <input type="password" id="authToken" class="input-token" placeholder="Auth Token (hoặc ?token=...)" value="" autocomplete="off" style="width: 240px;">
            <button class="btn btn-primary" onclick="copyLoginUrl()" title="Sao chép link đăng nhập 1-click">🔗 Copy Link</button>
            <span id="connStatus" style="font-size: 13px; color: var(--warning); font-weight: 600;">● Chưa kết nối</span>
        </div>
    </header>

    <div class="nav-tabs">
        <button class="tab-btn active" onclick="switchTab('tabOverview')">📊 Anti-Cheat</button>
        <button class="tab-btn" onclick="switchTab('tabIde')">⚡ Web IDE</button>
        <button class="tab-btn" onclick="switchTab('tabFlow')">🧩 Visual Flows</button>
        <button class="tab-btn" onclick="switchTab('tabMesh')">🌐 Cluster Mesh</button>
        <button class="tab-btn" onclick="switchTab('tabBenchmark')">⚡ Server Benchmark</button>
        <button class="tab-btn" onclick="switchTab('tabPlugins')">🖥️ Dynamic Plugins</button>
        <button class="tab-btn" onclick="switchTab('tabTerminal')">💻 Web Terminal</button>
        <button class="tab-btn" onclick="switchTab('tabSecurity')">🛡️ Security Sentinel</button>
        <button class="tab-btn" onclick="switchTab('tabReports')">🚨 Đơn Tố Cáo (<span id="tabRepCount">0</span>)</button>
        <button class="tab-btn" onclick="switchTab('tabReplays')">🎬 Replay Hiện Trường (<span id="tabClipCount">0</span>)</button>
        <button class="tab-btn" onclick="switchTab('tabBanWave')">🌊 Ban Wave (<span id="tabWaveCount">0</span>)</button>
    </div>

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
        <div class="card-stat purple">
            <div class="label">Đơn Tố Cáo Đang Chờ</div>
            <div class="value" id="valReports">0</div>
        </div>
        <div class="card-stat">
            <div class="label">AuraBot Đang Bẫy</div>
            <div class="value" id="valBots">0</div>
        </div>
    </div>

    <!-- TAB 1: OVERVIEW -->
    <div id="tabOverview" class="tab-content">
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
    </div>

    <!-- TAB 2: REPORTS -->
    <div id="tabReports" class="tab-content" style="display: none;">
        <div class="panel">
            <div class="panel-header">
                <div class="panel-title">🚨 Danh Sách Đơn Tố Cáo Người Chơi (/report)</div>
                <button class="btn btn-primary" onclick="refreshReports()">🔄 Làm mới</button>
            </div>
            <div style="overflow-x: auto;">
                <table>
                    <thead>
                        <tr>
                            <th>Mã Đơn</th>
                            <th>Người Báo</th>
                            <th>Đối Tượng</th>
                            <th>Lý Do</th>
                            <th>Trạng Thái</th>
                            <th>Thời Gian</th>
                            <th>Replay Clip</th>
                            <th>Thao Tác Quản Trị</th>
                        </tr>
                    </thead>
                    <tbody id="reportsTableBody">
                        <tr><td colspan="8" style="text-align: center; color: var(--text-muted); padding: 30px;">Chưa có đơn tố cáo nào</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <!-- TAB 3: REPLAYS -->
    <div id="tabReplays" class="tab-content" style="display: none;">
        <div class="panel">
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
                            <th>Số Ticks</th>
                            <th>Thời Gian</th>
                            <th>Thao Tác</th>
                        </tr>
                    </thead>
                    <tbody id="replayTableBody">
                        <tr><td colspan="7" style="text-align: center; color: var(--text-muted); padding: 20px;">Chưa có clip vi phạm nào</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <!-- TAB 4: BAN WAVE -->
    <div id="tabBanWave" class="tab-content" style="display: none;">
        <div class="panel">
            <div class="panel-header">
                <div class="panel-title">🌊 Hàng Đợi Thanh Trừng Đồng Loạt (Ban Wave)</div>
                <div class="btn-group">
                    <button class="btn btn-kick" style="font-size: 13px; padding: 8px 16px;" onclick="triggerBanWave()">⚡ Kích Hoạt Ban Wave Ngay</button>
                    <button class="btn" style="font-size: 13px; padding: 8px 16px;" onclick="clearBanWave()">🗑️ Xóa Hàng Đợi</button>
                </div>
            </div>
            <p style="font-size: 13px; color: var(--text-muted);">
                Cơ chế Ban Wave gom các đối tượng sử dụng hack bị phát hiện chắc chắn 100% vào hàng đợi và ban đồng loạt theo đợt, ngăn chặn tác giả cheat kiểm thử để tìm cách né kiểm tra.
            </p>
            <div style="overflow-x: auto; margin-top: 10px;">
                <table>
                    <thead>
                        <tr>
                            <th>Người Chơi</th>
                            <th>Lý Do Vi Phạm</th>
                            <th>Thời Điểm Vào Hàng Đợi</th>
                            <th>Trạng Thái</th>
                        </tr>
                    </thead>
                    <tbody id="banWaveTableBody">
                        <tr><td colspan="4" style="text-align: center; color: var(--text-muted); padding: 20px;">Hàng đợi Ban Wave hiện đang trống</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <!-- TAB 5: DYNAMIC PLUGINS -->
    <div id="tabPlugins" class="tab-content" style="display: none;">
        <div class="panel">
            <div class="panel-header">
                <div class="panel-title">🖥️ Quản Trị Dynamic Plugins & Tài Nguyên Hệ Thống</div>
                <button class="btn btn-primary" onclick="refreshPlugins()">🔄 Làm mới danh sách</button>
            </div>
            <div class="replay-metrics" id="systemMetricsBar" style="margin-bottom: 12px;">
                <div>RAM: <strong id="sysRam" style="color:var(--primary);">0 MB</strong></div>
                <div>Threads: <strong id="sysThreads" style="color:var(--warning);">0</strong></div>
                <div>Nền tảng: <strong id="sysPlatform" style="color:var(--text-main);">Paper</strong></div>
                <div>Java: <strong id="sysJava" style="color:var(--success);">21</strong></div>
            </div>
            <div style="overflow-x: auto;">
                <table>
                    <thead>
                        <tr>
                            <th>Tên Plugin</th>
                            <th>Phiên Bản</th>
                            <th>Tác Giả</th>
                            <th>Trạng Thái</th>
                            <th>Mô Tả</th>
                            <th>Thao Tác</th>
                        </tr>
                    </thead>
                    <tbody id="pluginsTableBody">
                        <tr><td colspan="6" style="text-align: center; color: var(--text-muted); padding: 20px;">Đang tải danh sách plugins...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <!-- TAB 6: LIVE STREAMING TERMINAL -->
    <div id="tabTerminal" class="tab-content" style="display: none;">
        <div class="panel">
            <div class="panel-header">
                <div style="display: flex; align-items: center; gap: 12px;">
                    <div class="panel-title">💻 Live Server Terminal (SSE Stream)</div>
                    <span id="termStreamStatus" style="font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 4px; background: rgba(34,197,94,0.15); color: #4ade80;">🟢 Live Stream Connected</span>
                </div>
                <div style="display: flex; gap: 8px; align-items: center;">
                    <label style="font-size: 12px; color: var(--text-muted); cursor: pointer; display: flex; align-items: center; gap: 4px;">
                        <input type="checkbox" id="termAutoScroll" checked> Tự cuộn
                    </label>
                    <select id="termLevelFilter" class="input-token" style="padding: 4px 8px; font-size: 12px;" onchange="filterTerminalLogs()">
                        <option value="ALL" selected>Mọi Level</option>
                        <option value="INFO">INFO</option>
                        <option value="WARNING">WARNING</option>
                        <option value="SEVERE">SEVERE / ERROR</option>
                    </select>
                    <button class="btn" onclick="clearTerminalView()">🧹 Xóa Màn Hình</button>
                    <button class="btn btn-primary" onclick="reconnectTerminalStream()">🔄 Kết Nối Lại</button>
                </div>
            </div>
            <div id="liveTerminalOutput" style="width: 100%; height: 380px; background: #050810; border: 1px solid var(--border); border-radius: 8px; padding: 12px; font-family: 'Consolas', 'Courier New', monospace; font-size: 12px; line-height: 1.6; overflow-y: auto; color: #cbd5e1; white-space: pre-wrap; word-break: break-all;"></div>
            <div style="margin-top: 14px; display: flex; gap: 8px;">
                <input type="text" id="termCmdInput" class="input-token" style="flex: 1; font-family: 'Consolas', monospace;" placeholder="Nhập mã Java hoặc lệnh thực thi... (VD: heal(); hoặc /jserver gc)" onkeydown="if (event.key === 'Enter') runTerminalCommand()">
                <button class="btn btn-primary" onclick="runTerminalCommand()">⚡ Gửi Thực Thi</button>
            </div>
            <div style="margin-top: 8px; display: flex; gap: 6px; flex-wrap: wrap;">
                <button class="btn" style="padding: 2px 8px; font-size: 11px;" onclick="insertTermCode('heal(); broadcast(&quot;§aServer đã được chữa lành!&quot;);')">Preset: Heal All</button>
                <button class="btn" style="padding: 2px 8px; font-size: 11px;" onclick="insertTermCode('broadcast(&quot;§b[Thông Báo] §fServer bảo trì trong 5 phút!&quot;);')">Preset: Broadcast</button>
                <button class="btn" style="padding: 2px 8px; font-size: 11px;" onclick="insertTermCode('day(); weatherClear();')">Preset: Day & Clear</button>
                <button class="btn" style="padding: 2px 8px; font-size: 11px;" onclick="insertTermCode('System.gc();')">Preset: Force GC</button>
            </div>
        </div>
    </div>

    <!-- TAB 7: SECURITY SENTINEL -->
    <div id="tabSecurity" class="tab-content" style="display: none;">
        <div class="panel">
            <div class="panel-header">
                <div class="panel-title">🛡️ Trung Tâm Phòng Thủ An Ninh Máy Chủ (Security Sentinel)</div>
                <button class="btn btn-primary" onclick="refreshSecurity()">🔄 Làm mới</button>
            </div>
            <div class="grid-stats">
                <div class="card-stat success">
                    <div class="label">Gói Tin Đã Soát Lọc</div>
                    <div class="value" id="secPackets">0</div>
                </div>
                <div class="card-stat danger">
                    <div class="label">Tổng Exploit Bị Chặn</div>
                    <div class="value" id="secViolations">0</div>
                </div>
                <div class="card-stat warning">
                    <div class="label">Click Flood Bị Ngắt</div>
                    <div class="value" id="secClicks">0</div>
                </div>
                <div class="card-stat purple">
                    <div class="label">NBT & Sách Độc Khử Sạch</div>
                    <div class="value" id="secNbt">0</div>
                </div>
                <div class="card-stat danger">
                    <div class="label">CVE Web/Panel Ngăn Chặn</div>
                    <div class="value" id="secCve">0</div>
                </div>
            </div>
            <div style="font-size: 13px; color: var(--text-muted); margin-top: 12px; line-height: 1.6;">
                Tự động bảo vệ máy chủ trước các cuộc tấn công khai thác lỗ hổng Zero-Day: Sách độc (Book Crash), NBT Translation Bomb, Tọa độ vô cực / NaN crash, Packet spam flood, và các lỗ hổng Web Panel Pterodactyl/Crafty (SSTI, LFI, Host Header Spoofing).
            </div>
        </div>
    </div>

<!-- MODAL: WEB REPLAY PLAYER -->
<div id="replayModal" class="modal-overlay">
    <div class="modal-card">
        <div class="modal-header">
            <div>
                <h3 id="modalTitle" style="font-size: 16px; font-weight: 700; color: var(--primary);">🎬 Web Replay Visualizer</h3>
                <span id="modalSubtitle" style="font-size: 12px; color: var(--text-muted);">Quỹ đạo di chuyển và tương tác thực tế</span>
            </div>
            <button class="btn" onclick="closeReplayModal()" style="font-size: 16px; padding: 4px 10px;">✕</button>
        </div>
        <div class="modal-body">
            <div class="replay-canvas-wrap">
                <canvas id="webReplayCanvas" class="replay-canvas" width="800" height="380"></canvas>
            </div>
            <div class="replay-controls">
                <button id="btnPlayPause" class="btn btn-primary" onclick="toggleReplayPlay()">▶️ Phát</button>
                <input type="range" id="replayScrub" class="scrub-bar" min="0" max="100" value="0" oninput="onScrubInput(this.value)">
                <span id="replayTimeDisplay" style="font-family: monospace; font-size: 12px; min-width: 70px;">0/0</span>
                <select id="replaySpeedSelect" class="input-token" style="padding: 4px 8px;" onchange="setReplaySpeed(this.value)">
                    <option value="0.5">0.5x</option>
                    <option value="1.0" selected>1.0x</option>
                    <option value="2.0">2.0x</option>
                </select>
            </div>
            <div class="replay-metrics">
                <div>CPS: <strong id="repMetricCps" style="color: var(--primary);">0</strong></div>
                <div>Tốc Độ: <strong id="repMetricSpeed" style="color: var(--warning);">0.00</strong></div>
                <div>Hành Động: <strong id="repMetricAction" style="color: var(--danger);">MOVE</strong></div>
                <div>Tọa Độ: <strong id="repMetricCoord" style="color: var(--text-muted);">X:0 Y:0 Z:0</strong></div>
                <div>Mặt Đất: <strong id="repMetricGround" style="color: var(--success);">true</strong></div>
            </div>
        </div>
    </div>
</div>
""";

    @SuppressWarnings("all")
    private static final String HTML_PART_2 = """
    <!-- TAB IDE: MONACO CODE EDITOR -->
    <div id="tabIde" class="tab-content" style="display: none;">
        <div class="ide-container">
            <!-- Sidebar: Explorer -->
            <div class="ide-sidebar">
                <div class="ide-sidebar-header">
                    <span>📁 EXPLORER</span>
                    <div class="ide-sidebar-actions">
                        <button class="btn-icon" title="Làm mới cây thư mục" onclick="loadIdeTree()">🔄</button>
                        <button class="btn-icon" title="Tạo script mới" onclick="idePromptNewScript()">➕</button>
                    </div>
                </div>
                <div class="ide-tree" id="ideTree">
                    <div style="padding: 12px; color: var(--text-muted); font-size: 12px;">Đang tải cây thư mục...</div>
                </div>
            </div>

            <!-- Main Editor Area -->
            <div class="ide-main">
                <!-- Top Toolbar -->
                <div class="ide-toolbar">
                    <div class="ide-active-tab">
                        <span class="file-icon">☕</span>
                        <span id="ideActiveFilePath">scripts/QuickSample.java</span>
                        <span id="ideDirtyIndicator" style="display:none; color: var(--warning); font-size: 14px;">●</span>
                    </div>
                    <div class="ide-toolbar-actions">
                        <span id="ideStatusBadge" class="badge badge-clean">Ready</span>
                        <button class="btn btn-primary" onclick="saveCurrentIdeFile()">💾 Lưu & Biên dịch (Ctrl+S)</button>
                        <button class="btn btn-success" onclick="runCurrentIdeScript()">▶ Chạy Script</button>
                        <button class="btn btn-bot" onclick="reloadCurrentIdePlugin()">🔄 Hot-Reload</button>
                    </div>
                </div>

                <!-- Monaco Editor Container -->
                <div class="ide-editor-wrapper">
                    <div id="monacoEditorContainer" style="width: 100%; height: 460px;"></div>
                    <textarea id="fallbackEditor" style="display:none; width: 100%; height: 460px; background: #0f141f; color: #f1f5f9; font-family: Consolas, monospace; font-size: 13px; border: 1px solid var(--border); padding: 12px; box-sizing: border-box; resize: none;"></textarea>
                </div>

                <!-- Bottom Terminal / Diagnostic Dock -->
                <div class="ide-dock">
                    <div class="ide-dock-header">
                        <div class="ide-dock-tabs">
                            <span class="ide-dock-tab active" id="dockTabBuild" onclick="switchDockTab('buildOutput')">🛠️ Trình biên dịch & Chẩn đoán</span>
                            <span class="ide-dock-tab" id="dockTabRun" onclick="switchDockTab('runOutput')">🚀 Kết quả thực thi</span>
                        </div>
                        <button class="btn" style="padding: 2px 8px; font-size: 11px;" onclick="clearIdeDock()">Xóa log</button>
                    </div>
                    <div class="ide-dock-content" id="ideDockContent">
                        <pre id="ideDockText" style="margin: 0; font-family: Consolas, monospace; font-size: 12px; color: #94a3b8; white-space: pre-wrap; word-break: break-all;">Sẵn sàng. Mở tập tin từ cây thư mục bên trái hoặc bấm 'Lưu & Biên dịch' để tự động kiểm tra lỗi cú pháp thời gian thực.</pre>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <!-- TAB FLOW: VISUAL LOGIC FLOWS & NODE EDITOR -->
    <div id="tabFlow" class="tab-content" style="display: none;">
        <div class="panel">
            <div class="panel-header">
                <div style="display: flex; align-items: center; gap: 12px;">
                    <div class="panel-title">🧩 Sơ Đồ Khối Logic & Visual Node Editor</div>
                    <span id="flowActiveBadge" style="font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 4px; background: rgba(56,189,248,0.15); color: var(--primary);">Blueprint Mode</span>
                </div>
                <div style="display: flex; gap: 8px; align-items: center;">
                    <button class="btn btn-primary" onclick="saveVisualFlowToBackend()">💾 Lưu Sơ Đồ Thành Flow</button>
                    <button class="btn" style="background: rgba(34,197,94,0.15); color: #4ade80; border: 1px solid rgba(34,197,94,0.3);" onclick="runCurrentVisualFlow()">⚡ Chạy Thử Kịch Bản</button>
                    <button class="btn" onclick="loadDefaultBlueprintPreset()">📋 Nạp Mẫu Mặc Định</button>
                    <button class="btn" onclick="toggleFlowTable()">📑 Danh Sách Flows</button>
                    <button class="btn" onclick="refreshFlows()">🔄 Làm Mới</button>
                </div>
            </div>

            <div style="display: flex; gap: 12px; margin-bottom: 12px; align-items: center; flex-wrap: wrap;">
                <input type="text" id="vFlowId" class="input-token" style="width: 200px;" placeholder="ID Flow (vd: boss_reward)">
                <input type="text" id="vFlowName" class="input-token" style="flex: 1; min-width: 220px;" placeholder="Tên Kịch Bản Logic (vd: Phần Thưởng Hạ Gục Boss)">
                <div style="display: flex; gap: 6px;">
                    <button class="btn" style="background: rgba(124,58,237,0.2); color: #a78bfa; border: 1px solid rgba(124,58,237,0.4);" onclick="addCanvasNode('trigger')">➕ Trigger</button>
                    <button class="btn" style="background: rgba(217,119,6,0.2); color: #fcd34d; border: 1px solid rgba(217,119,6,0.4);" onclick="addCanvasNode('condition')">➕ Điều Kiện</button>
                    <button class="btn" style="background: rgba(5,150,105,0.2); color: #6ee7b7; border: 1px solid rgba(5,150,105,0.4);" onclick="addCanvasNode('action')">➕ Hành Động</button>
                    <button class="btn" onclick="clearCanvasNodes()">🧹 Dọn Canvas</button>
                </div>
            </div>

            <div id="flowCanvas" class="flow-canvas-container">
                <svg id="flowWiresSvg" style="position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; z-index: 5;">
                    <defs>
                        <marker id="wireArrow" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                            <path d="M 0 1 L 9 5 L 0 9 z" fill="#38bdf8"/>
                        </marker>
                    </defs>
                </svg>
                <div id="flowNodesContainer" style="position: absolute; inset: 0; width: 100%; height: 100%;"></div>
            </div>

            <div id="flowTableWrapper" style="margin-top: 16px; overflow-x: auto; display: none;">
                <div class="panel-title" style="margin-bottom: 8px;">Danh Sách Kịch Bản Đã Biên Dịch Trong Thư Mục /flows</div>
                <table>
                    <thead>
                        <tr>
                            <th>Tên Flow</th>
                            <th>ID Định Danh</th>
                            <th>Trigger Kích Hoạt</th>
                            <th>Điều Kiện</th>
                            <th>Hành Động</th>
                            <th>Thao Tác</th>
                        </tr>
                    </thead>
                    <tbody id="flowTableBody">
                        <tr><td colspan="6" style="text-align: center; color: var(--text-muted); padding: 30px;">Đang tải danh sách Visual Flows...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <!-- TAB MESH: P2P CLUSTER NETWORK -->
    <div id="tabMesh" class="tab-content" style="display: none;">
        <div class="panel">
            <div class="panel-header">
                <div class="panel-title">🌐 Mạng Phân Tán Cụm Máy Chủ P2P (Cluster Mesh)</div>
                <button class="btn btn-primary" onclick="broadcastMeshPrompt()">📡 Phát Sóng Toàn Cụm</button>
            </div>
            <div class="grid-stats" style="margin-bottom: 16px;">
                <div class="card-stat success">
                    <div class="label">Local Node ID</div>
                    <div class="value" id="meshNodeId" style="font-size: 18px;">Loading...</div>
                </div>
                <div class="card-stat purple">
                    <div class="label">Raft Role</div>
                    <div class="value" id="meshRole" style="font-size: 24px;">FOLLOWER</div>
                </div>
                <div class="card-stat warning">
                    <div class="label">Leader Node</div>
                    <div class="value" id="meshLeaderId" style="font-size: 18px;">Election...</div>
                </div>
                <div class="card-stat">
                    <div class="label">Máy Chủ Kết Nối (Peers)</div>
                    <div class="value" id="meshPeerCount">0</div>
                </div>
            </div>
            <div style="overflow-x: auto;">
                <table>
                    <thead>
                        <tr>
                            <th>Vai Trò</th>
                            <th>Node ID</th>
                            <th>Địa Chỉ Mạng (IP:Port)</th>
                            <th>Người Chơi</th>
                            <th>TPS</th>
                            <th>Trạng Thái</th>
                        </tr>
                    </thead>
                    <tbody id="meshTableBody">
                        <tr><td colspan="6" style="text-align: center; color: var(--text-muted); padding: 30px;">Đang dò quét máy chủ trong cụm...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <div id="tabBenchmark" class="tab-content" style="display: none;">
        <div class="panel">
            <div class="panel-header">
                <div class="panel-title">⚡ Kiểm Thử Sức Mạnh Phần Cứng & Chấm Điểm Server (JSPI)</div>
                <div class="btn-group">
                    <button class="btn btn-primary" onclick="runBenchmarkFromWeb(false)">▶ Bắt Đầu Benchmark (Full)</button>
                    <button class="btn btn-warning" onclick="runBenchmarkFromWeb(true)">⚡ Đo Nhanh (Quick)</button>
                    <button class="btn" onclick="refreshBenchmark()">🔄 Tải Lại</button>
                </div>
            </div>
            <div id="bmRunningAlert" style="display: none; padding: 12px 18px; margin-bottom: 16px; border-radius: 8px; background: rgba(56, 189, 248, 0.15); border: 1px solid #38bdf8; color: #38bdf8; font-weight: 600;">
                ⏳ Đang chạy bài kiểm tra benchmark phần cứng toàn diện (CPU, RAM, NVMe, Pipeline)... Vui lòng chờ vài giây!
            </div>
            <div class="grid-stats" style="margin-bottom: 16px;">
                <div class="card-stat success">
                    <div class="label">Điểm JSPI & Xếp Hạng</div>
                    <div style="display: flex; align-items: baseline; gap: 10px; margin-top: 4px;">
                        <span id="bmScore" style="font-size: 32px; font-weight: 800; color: #38bdf8;">--</span>
                        <span id="bmTierBadge" class="badge badge-clean" style="font-size: 14px;">Chưa đo</span>
                    </div>
                </div>
                <div class="card-stat">
                    <div class="label">Khuyến Nghị Online Max</div>
                    <div class="value" id="bmMaxPlayers" style="color: #4ade80;">--</div>
                </div>
                <div class="card-stat warning">
                    <div class="label">Tầm Nhìn Đề Xuất (View / Sim)</div>
                    <div class="value" id="bmViewSim" style="font-size: 20px;">-- / -- chunks</div>
                </div>
                <div class="card-stat purple">
                    <div class="label">Thời Gian Kiểm Thử</div>
                    <div class="value" id="bmDuration">-- ms</div>
                </div>
            </div>
            <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; margin-bottom: 16px;">
                <div style="background: rgba(15,23,42,0.6); border: 1px solid var(--border); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 12px; color: var(--text-muted);">CPU Đơn Nhân (Math)</div>
                    <div style="font-size: 18px; font-weight: 700; color: #e2e8f0; margin-top: 4px;" id="bmCpuMath">-- Mops/s</div>
                </div>
                <div style="background: rgba(15,23,42,0.6); border: 1px solid var(--border); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 12px; color: var(--text-muted);">Crypto Đa Luồng (Virtual)</div>
                    <div style="font-size: 18px; font-weight: 700; color: #e2e8f0; margin-top: 4px;" id="bmCrypto">-- Khash/s</div>
                </div>
                <div style="background: rgba(15,23,42,0.6); border: 1px solid var(--border); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 12px; color: var(--text-muted);">Trình Biên Dịch In-Memory</div>
                    <div style="font-size: 18px; font-weight: 700; color: #e2e8f0; margin-top: 4px;" id="bmCompiler">-- ms</div>
                </div>
                <div style="background: rgba(15,23,42,0.6); border: 1px solid var(--border); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 12px; color: var(--text-muted);">Băng Thông Direct RAM</div>
                    <div style="font-size: 18px; font-weight: 700; color: #e2e8f0; margin-top: 4px;" id="bmRamBw">-- MB/s</div>
                </div>
                <div style="background: rgba(15,23,42,0.6); border: 1px solid var(--border); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 12px; color: var(--text-muted);">Tốc Độ Ghi NVMe / SSD</div>
                    <div style="font-size: 18px; font-weight: 700; color: #e2e8f0; margin-top: 4px;" id="bmDiskIo">-- MB/s</div>
                </div>
                <div style="background: rgba(15,23,42,0.6); border: 1px solid var(--border); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 12px; color: var(--text-muted);">EventPipeline Throughput</div>
                    <div style="font-size: 18px; font-weight: 700; color: #e2e8f0; margin-top: 4px;" id="bmPipeline">-- ops/s</div>
                </div>
                <div style="background: rgba(15,23,42,0.6); border: 1px solid var(--border); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 12px; color: var(--text-muted);">Scheduler Micro-Jitter</div>
                    <div style="font-size: 18px; font-weight: 700; color: #e2e8f0; margin-top: 4px;" id="bmJitter">-- ms</div>
                </div>
            </div>
            <div class="panel-title" style="margin-bottom: 8px; font-size: 14px;">💡 Chẩn Đoán Điểm Nghẽn & Khuyến Nghị Tối Ưu Phần Cứng</div>
            <div id="bmAdviceList" style="display: flex; flex-direction: column; gap: 8px;">
                <div style="color: var(--text-muted); font-size: 13px;">Chưa có dữ liệu benchmark. Hãy nhấn [▶ Bắt Đầu Benchmark] để kiểm tra.</div>
            </div>
        </div>
    </div>
</div>
""";

    @SuppressWarnings("all")
    private static final String HTML_PART_3 = """
<script>
    function showToast(msg, type = 'info') {
        let c = document.getElementById('toastContainer');
        if (!c) {
            c = document.createElement('div');
            c.id = 'toastContainer';
            c.style.cssText = 'position:fixed;bottom:24px;right:24px;z-index:99999;display:flex;flex-direction:column;gap:10px;pointer-events:none;';
            document.body.appendChild(c);
        }
        const t = document.createElement('div');
        t.className = 'toast-item';
        const borderCol = type === 'success' ? '#10b981' : (type === 'error' ? '#f43f5e' : (type === 'warn' ? '#f59e0b' : '#38bdf8'));
        const icon = type === 'success' ? '✔' : (type === 'error' ? '✖' : (type === 'warn' ? '⚠' : 'ℹ'));
        t.style.cssText = 'background:rgba(15,23,42,0.96);backdrop-filter:blur(10px);border-left:4px solid ' + borderCol + ';border:1px solid rgba(255,255,255,0.1);color:#f1f5f9;padding:12px 18px;border-radius:10px;font-size:13px;font-weight:500;box-shadow:0 12px 30px rgba(0,0,0,0.6);display:flex;align-items:center;gap:10px;pointer-events:auto;transition:all 0.3s cubic-bezier(0.4, 0, 0.2, 1);';
        t.innerHTML = '<span style="font-size:16px;">' + icon + '</span> <span>' + escapeHtml(msg) + '</span>';
        c.appendChild(t);
        setTimeout(() => {
            t.style.opacity = '0';
            t.style.transform = 'translateY(12px)';
            setTimeout(() => t.remove(), 300);
        }, 3500);
    }

    function copyLoginUrl() {
        const el = document.getElementById('authToken');
        const token = el ? el.value.trim() : '';
        if (!token) {
            showToast('Chưa có mã token! Hãy kiểm tra config.yml', 'warn');
            return;
        }
        const url = window.location.origin + window.location.pathname + '?token=' + encodeURIComponent(token);
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(url).then(() => {
                showToast('Đã sao chép link đăng nhập 1-click!', 'success');
            }).catch(() => {
                prompt('Sao chép link đăng nhập 1-click:', url);
            });
        } else {
            prompt('Sao chép link đăng nhập 1-click:', url);
        }
    }

    (function () {
        const nativeFetch = window.fetch.bind(window);
        const tokenInput = () => document.getElementById('authToken');
        let rejectedToken = null;

        const initAuthField = () => {
            const el = tokenInput();
            if (!el) return;

            const urlParams = new URLSearchParams(window.location.search);
            const queryToken = urlParams.get('token');
            if (queryToken && queryToken.trim()) {
                el.value = queryToken.trim();
                sessionStorage.setItem('jsAuthToken', el.value);
                localStorage.setItem('jsAuthToken', el.value);
            } else {
                const saved = sessionStorage.getItem('jsAuthToken') || localStorage.getItem('jsAuthToken');
                if (saved && !el.value) el.value = saved;
            }

            const onTokenUpdate = () => {
                const val = el.value.trim();
                sessionStorage.setItem('jsAuthToken', val);
                localStorage.setItem('jsAuthToken', val);
                rejectedToken = null;
                const status = document.getElementById('connStatus');
                if (val.length >= 16) {
                    if (status) {
                        status.style.color = 'var(--primary)';
                        status.innerText = '● Đang kết nối...';
                    }
                    refreshData();
                    refreshLogs();
                } else {
                    if (status) {
                        status.style.color = 'var(--warning)';
                        status.innerText = '● Chưa nhập Token';
                    }
                }
            };

            el.addEventListener('input', onTokenUpdate);
            el.addEventListener('change', onTokenUpdate);

            if (el.value.trim().length < 16) {
                const status = document.getElementById('connStatus');
                if (status) {
                    status.style.color = 'var(--warning)';
                    status.innerText = '● Vui lòng nhập Auth Token';
                }
            }
        };

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', initAuthField);
        } else {
            initAuthField();
        }

        const denied = (msg) => Promise.resolve(new Response(JSON.stringify({ success: false, error: msg }), {
            status: 401, headers: { 'Content-Type': 'application/json' }
        }));

        window.fetch = function (url, opts) {
            opts = opts || {};
            if (typeof url === 'string' && url.startsWith('/')) {
                const el = tokenInput();
                const t = el ? el.value.trim() : '';
                if (!t) return denied('Chưa nhập Auth Token');
                if (t === rejectedToken) return denied('Auth Token không hợp lệ');
                opts.headers = Object.assign({}, opts.headers || {}, { 'X-Auth-Token': t });
                return nativeFetch(url, opts).then(res => {
                    if (res.status === 401) rejectedToken = t;
                    const status = document.getElementById('connStatus');
                    if (status) {
                        status.style.color = res.ok ? 'var(--success)' : (res.status === 429 ? 'var(--danger)' : 'var(--warning)');
                        status.innerText = res.ok ? '● Live Sync (1s)' : (res.status === 429 ? '● IP tạm bị khóa (5p)' : '● Sai Auth Token');
                    }
                    return res;
                });
            }
            return nativeFetch(url, opts);
        };
    })();
    let currentPlayers = [];
    let selectedPlayer = null;

    function switchTab(tabId, btn) {
        document.querySelectorAll('.tab-content').forEach(el => el.style.display = 'none');
        document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        const target = document.getElementById(tabId);
        if (target) target.style.display = 'block';
        if (btn) btn.classList.add('active');
        else if (window.event && window.event.target && window.event.target.classList) {
            window.event.target.classList.add('active');
        }
        if (tabId === 'tabReports') refreshReports();
        if (tabId === 'tabPlugins') refreshPlugins();
        if (tabId === 'tabTerminal') initTerminalStream();
        if (tabId === 'tabSecurity') refreshSecurity();
        if (tabId === 'tabIde') { initMonacoEditor(); loadIdeTree(); }
        if (tabId === 'tabFlow') { initFlowCanvas(); refreshFlows(); }
        if (tabId === 'tabMesh') refreshMesh();
        if (tabId === 'tabBenchmark') refreshBenchmark();
    }

    async function refreshData() {
        if (document.hidden) return;
        const el = document.getElementById('authToken');
        if (!el || el.value.trim().length < 16) return;

        try {
            const res = await fetch('/api/status');
            const data = await res.json();
            if (!data || data.players === undefined) return;

            document.getElementById('valTps').innerText = data.tps;
            document.getElementById('valFlags').innerText = data.totalFlags;
            document.getElementById('valPunishments').innerText = data.totalPunishments;
            document.getElementById('valBots').innerText = data.activeAuraBots;
            document.getElementById('valReports').innerText = data.pendingReports || 0;
            document.getElementById('tabRepCount').innerText = data.pendingReports || 0;
            document.getElementById('tabClipCount').innerText = (data.replays ? data.replays.length : 0);
            document.getElementById('tabWaveCount').innerText = data.banWaveQueue || 0;
            document.getElementById('playerCount').innerText = data.players.length + ' người chơi';

            currentPlayers = data.players;
            renderPlayerTable(data.players);
            renderReplayTable(data.replays);
            renderBanWaveTable(data.banWaveList || data.players.filter(p => p.inBanWave));
            updateRadarSelect(data.players);
            drawRadar();
            drawClusterRadar();
        } catch (e) {
            const status = document.getElementById('connStatus');
            if (status) {
                status.innerText = '○ Mất kết nối';
                status.style.color = 'var(--danger)';
            }
        }
    }

    async function refreshLogs() {
        const el = document.getElementById('authToken');
        if (!el || el.value.trim().length < 16) return;
        try {
            const res = await fetch('/api/logs');
            const logs = await res.json();
            if (!Array.isArray(logs)) return;
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

    async function refreshReports() {
        try {
            const res = await fetch('/api/reports');
            const reports = await res.json();
            if (!Array.isArray(reports)) return;
            renderReportsTable(reports);
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

    async function refreshPlugins() {
        try {
            const resSys = await fetch('/api/system');
            const sys = await resSys.json();
            if (sys && sys.usedMem !== undefined) {
                document.getElementById('sysRam').innerText = `${sys.usedMem} / ${sys.maxMem} MB`;
                document.getElementById('sysThreads').innerText = sys.threads;
                document.getElementById('sysPlatform').innerText = sys.platform;
                document.getElementById('sysJava').innerText = sys.javaVersion;
            }

            const res = await fetch('/api/plugins');
            const data = await res.json();
            const tbody = document.getElementById('pluginsTableBody');
            if (!data.plugins || data.plugins.length === 0) {
                tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--text-muted); padding: 20px;">Không có dynamic plugin nào được nạp</td></tr>';
                return;
            }
            tbody.innerHTML = data.plugins.map(p => {
                let st = p.enabled ? '<span class="badge badge-clean">HOẠT ĐỘNG</span>' : '<span class="badge badge-danger">ĐÃ TẮT</span>';
                return `
                    <tr>
                        <td><strong>${escapeHtml(p.name)}</strong></td>
                        <td>${escapeHtml(p.version)}</td>
                        <td>${escapeHtml(p.author)}</td>
                        <td>${st}</td>
                        <td style="color:var(--text-muted);">${escapeHtml(p.description)}</td>
                        <td>
                            <div class="btn-group">
                                <button class="btn btn-primary" onclick="doPluginAction('reload', '${p.name}')">🔄 Nạp Lại</button>
                                <button class="btn btn-kick" onclick="doPluginAction('unload', '${p.name}')">⏹️ Dừng</button>
                            </div>
                        </td>
                    </tr>
                `;
            }).join('');
        } catch (e) {}
    }

    async function doPluginAction(action, pName) {
        const token = document.getElementById('authToken').value;
        const res = await fetch('/api/plugins/action', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token: token, action: action, plugin: pName })
        });
        const data = await res.json();
        alert(data.message || (data.success ? 'Thành công' : 'Thất bại'));
        refreshPlugins();
    }

    let termEventSource = null;
    let termLogs = [];

    function initTerminalStream() {
        if (termEventSource) return;
        const token = document.getElementById('authToken').value;
        const url = '/api/stream/terminal?token=' + encodeURIComponent(token);
        const st = document.getElementById('termStreamStatus');
        if (st) {
            st.innerText = '🟡 Đang kết nối SSE...';
            st.style.color = '#fbbf24';
            st.style.background = 'rgba(251,191,36,0.15)';
        }

        try {
            termEventSource = new EventSource(url);
            termEventSource.onopen = function () {
                if (st) {
                    st.innerText = '🟢 Live Stream Connected';
                    st.style.color = '#4ade80';
                    st.style.background = 'rgba(34,197,94,0.15)';
                }
            };
            termEventSource.onmessage = function (ev) {
                if (!ev.data) return;
                try {
                    const entry = JSON.parse(ev.data);
                    appendTerminalLog(entry);
                } catch (e) {}
            };
            termEventSource.onerror = function () {
                if (st) {
                    st.innerText = '🔴 Mất kết nối (Đang thử lại...)';
                    st.style.color = '#f87171';
                    st.style.background = 'rgba(239,68,68,0.15)';
                }
            };
        } catch (e) {
            if (st) st.innerText = 'Lỗi SSE: ' + e;
        }
    }

    function appendTerminalLog(entry) {
        termLogs.push(entry);
        if (termLogs.length > 500) termLogs.shift();
        renderSingleTerminalLog(entry);
    }

    function renderSingleTerminalLog(entry) {
        const filter = document.getElementById('termLevelFilter') ? document.getElementById('termLevelFilter').value : 'ALL';
        if (filter !== 'ALL' && entry.level !== filter) return;

        const out = document.getElementById('liveTerminalOutput');
        if (!out) return;

        const timeStr = new Date(entry.time).toLocaleTimeString();
        let color = '#cbd5e1';
        if (entry.level === 'SEVERE' || entry.level === 'ERROR') color = '#f87171';
        else if (entry.level === 'WARNING' || entry.level === 'WARN') color = '#fbbf24';
        else if (entry.level === 'INFO') color = '#38bdf8';
        else if (entry.level === 'DEBUG') color = '#c084fc';

        const line = document.createElement('div');
        line.style.borderBottom = '1px solid rgba(255,255,255,0.03)';
        line.style.padding = '2px 0';
        line.innerHTML = `<span style="color:#64748b;">[${timeStr}]</span> <span style="font-weight:700;color:${color};">[${escapeHtml(entry.level)}]</span> <span>${escapeHtml(entry.message)}</span>`;
        out.appendChild(line);

        const autoScroll = document.getElementById('termAutoScroll');
        if (autoScroll && autoScroll.checked) {
            out.scrollTop = out.scrollHeight;
        }
    }

    function filterTerminalLogs() {
        const out = document.getElementById('liveTerminalOutput');
        if (!out) return;
        out.innerHTML = '';
        termLogs.forEach(renderSingleTerminalLog);
    }

    function clearTerminalView() {
        termLogs = [];
        const out = document.getElementById('liveTerminalOutput');
        if (out) out.innerHTML = '';
    }

    function reconnectTerminalStream() {
        if (termEventSource) {
            termEventSource.close();
            termEventSource = null;
        }
        initTerminalStream();
    }

    function insertTermCode(code) {
        const inp = document.getElementById('termCmdInput');
        if (inp) {
            inp.value = code;
            inp.focus();
        }
    }

    async function runTerminalCommand() {
        const inp = document.getElementById('termCmdInput');
        if (!inp) return;
        const code = inp.value.trim();
        if (!code) return;
        const token = document.getElementById('authToken').value;
        inp.value = '';
        appendTerminalLog({ id: Date.now(), time: Date.now(), level: 'INFO', message: '>>> ' + code });
        try {
            const res = await fetch('/api/exec', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: token, code: code })
            });
            const data = await res.json();
            if (data.success) {
                appendTerminalLog({ id: Date.now(), time: Date.now(), level: 'INFO', message: '<<< [Kết quả] ' + data.result });
            } else {
                appendTerminalLog({ id: Date.now(), time: Date.now(), level: 'SEVERE', message: '<<< [Lỗi] ' + (data.error || 'Thất bại') });
            }
        } catch (e) {
            appendTerminalLog({ id: Date.now(), time: Date.now(), level: 'SEVERE', message: '<<< Lỗi kết nối: ' + e.message });
        }
    }

    function renderReportsTable(reports) {
        const tbody = document.getElementById('reportsTableBody');
        if (!tbody) return;
        if (!reports || reports.length === 0) {
            tbody.innerHTML = '<tr><td colspan="8" style="text-align: center; color: var(--text-muted); padding: 30px;">Không có đơn tố cáo nào</td></tr>';
            return;
        }

        tbody.innerHTML = reports.map(r => {
            let stBadge = '<span class="badge badge-warning">ĐANG CHỜ</span>';
            if (r.status === 'INVESTIGATING') stBadge = `<span class="badge badge-frozen">ĐANG ĐIỀU TRA (${escapeHtml(r.handledBy)})</span>`;
            else if (r.status === 'RESOLVED') stBadge = '<span class="badge badge-clean">ĐÃ GIẢI QUYẾT</span>';
            else if (r.status === 'DISMISSED') stBadge = '<span class="badge" style="background:rgba(255,255,255,0.1); color:#94a3b8;">BÁC BỎ</span>';

            let repBtn = r.replayId !== 'none'
                ? `<button class="btn btn-primary" onclick="openWebReplay('${r.replayId}')">▶️ Xem Web</button>`
                : `<span style="color:var(--text-muted);">Không có</span>`;

            return `
                <tr>
                    <td><code style="color:var(--primary);">${escapeHtml(r.id)}</code></td>
                    <td>${escapeHtml(r.reporter)}</td>
                    <td><strong style="color:#f43f5e;">${escapeHtml(r.target)}</strong></td>
                    <td>${escapeHtml(r.reason)}</td>
                    <td>${stBadge}</td>
                    <td><span style="color:var(--text-muted);">${escapeHtml(r.time)}</span></td>
                    <td>${repBtn}</td>
                    <td>
                        <div class="btn-group">
                            <button class="btn btn-freeze" onclick="doReportAction('claim', '${r.id}')">🔍 Nhận</button>
                            <button class="btn btn-success" onclick="doReportAction('resolve', '${r.id}')">✅ Xong</button>
                            <button class="btn btn-kick" onclick="doReportAction('dismiss', '${r.id}')">❌ Bác</button>
                        </div>
                    </td>
                </tr>
            `;
        }).join('');
    }

    async function refreshSecurity() {
        try {
            const res = await fetch('/api/system');
            const sys = await res.json();
            if (!sys || sys.packetsChecked === undefined) return;
            const setTxt = (id, v) => {
                const el = document.getElementById(id);
                if (el) el.innerText = Number(v || 0).toLocaleString();
            };
            setTxt('secPackets', sys.packetsChecked);
            setTxt('secViolations', sys.exploitsBlocked);
            setTxt('secClicks', sys.clickFlood);
            setTxt('secNbt', sys.nbtCleaned);
            setTxt('secCve', sys.cveBlocked);
        } catch (e) {}
    }

    function renderReplayTable(replays) {
        const tbody = document.getElementById('replayTableBody');
        const countEl = document.getElementById('replayCount');
        if (countEl) countEl.innerText = (replays ? replays.length : 0) + ' clips';
        if (!tbody) return;
        if (!replays || replays.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" style="text-align: center; color: var(--text-muted); padding: 20px;">Chưa có clip vi phạm nào được lưu</td></tr>';
            return;
        }
        tbody.innerHTML = replays.map(r => `
            <tr>
                <td><code style="color: #38bdf8;">${escapeHtml(r.id)}</code></td>
                <td><strong>${escapeHtml(r.name)}</strong></td>
                <td><span style="color: #f43f5e;">${escapeHtml(r.check)}</span></td>
                <td>${r.vl}</td>
                <td>${r.frames || 400}</td>
                <td><span style="color: var(--text-muted);">${escapeHtml(r.time)}</span></td>
                <td>
                    <div class="btn-group">
                        <button class="btn btn-primary" onclick="openWebReplay('${r.id}')">▶️ Xem Web</button>
                        <button class="btn btn-freeze" onclick="alert('Vào game gõ: /jac replay play ${r.id}')">🎮 In-Game</button>
                    </div>
                </td>
            </tr>
        `).join('');
    }

    function renderBanWaveTable(queuedPlayers) {
        const tbody = document.getElementById('banWaveTableBody');
        if (!tbody) return;
        if (!queuedPlayers || queuedPlayers.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" style="text-align: center; color: var(--text-muted); padding: 20px;">Hàng đợi Ban Wave hiện đang trống</td></tr>';
            return;
        }
        tbody.innerHTML = queuedPlayers.map(p => `
            <tr>
                <td><strong style="color:#f43f5e;">${escapeHtml(p.name)}</strong></td>
                <td>${p.check ? escapeHtml(p.check + ' (' + (p.reason || '') + ') - VL ' + p.totalVl) : ('Gian lận cấp độ cao (VL: ' + p.totalVl + ')')}</td>
                <td><span style="color:var(--text-muted);">${p.queuedTime ? new Date(p.queuedTime).toLocaleTimeString() : 'Đã gắn cờ'}</span></td>
                <td><span class="badge badge-danger">CHỜ THANH TRỪNG</span></td>
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

        ctx.strokeStyle = 'rgba(56, 189, 248, 0.15)';
        ctx.lineWidth = 1;
        for (let r = 40; r <= 140; r += 35) {
            ctx.beginPath();
            ctx.arc(cx, cy, r, 0, Math.PI * 2);
            ctx.stroke();
        }

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

        ctx.fillStyle = 'rgba(56, 189, 248, 0.12)';
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.arc(cx, cy, 130, yawRad - 0.6, yawRad + 0.6);
        ctx.closePath();
        ctx.fill();

        ctx.fillStyle = 'rgba(244, 63, 94, 0.08)';
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.arc(cx, cy, 120, yawRad + Math.PI - 0.7, yawRad + Math.PI + 0.7);
        ctx.closePath();
        ctx.fill();

        ctx.fillStyle = '#38bdf8';
        ctx.beginPath();
        ctx.arc(cx, cy, 7, 0, Math.PI * 2);
        ctx.fill();

        ctx.strokeStyle = '#38bdf8';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.lineTo(cx + Math.cos(yawRad) * 45, cy + Math.sin(yawRad) * 45);
        ctx.stroke();

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
""";

    @SuppressWarnings("all")
    private static final String HTML_PART_4 = """
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
                showToast(data.message, 'success');
                refreshData();
                refreshLogs();
            } else {
                showToast('Lỗi: ' + data.error, 'error');
            }
        } catch (e) {
            showToast('Không thể gửi yêu cầu: ' + e.message, 'error');
        }
    }

    async function doReportAction(action, id) {
        const token = document.getElementById('authToken').value.trim();
        let verdict = "";
        if (action === 'resolve') {
            verdict = prompt("Nhập kết luận giải quyết đơn tố cáo:", "Đã xác minh và xử phạt vi phạm");
            if (verdict === null) return;
        }
        try {
            const res = await fetch('/api/reports/action', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token, action, id, verdict })
            });
            const data = await res.json();
            if (data.success) {
                showToast(data.message, 'success');
                refreshReports();
                refreshData();
            } else {
                showToast('Lỗi: ' + data.error, 'error');
            }
        } catch (e) {
            showToast('Không thể gửi yêu cầu: ' + e.message, 'error');
        }
    }

    async function triggerBanWave() {
        if (!confirm("BẠN CÓ CHẮC CHẮN MUỐN KÍCH HOẠT BAN WAVE ĐỒNG LOẠT KHÔNG?")) return;
        const token = document.getElementById('authToken').value.trim();
        try {
            const res = await fetch('/api/action', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token, action: 'execute_banwave' })
            });
            const data = await res.json();
            showToast(data.message || data.error, data.success ? 'success' : 'error');
            refreshData();
        } catch (e) {
            showToast(e.message, 'error');
        }
    }

    async function clearBanWave() {
        if (!confirm("Xóa toàn bộ hàng đợi Ban Wave?")) return;
        const token = document.getElementById('authToken').value.trim();
        try {
            const res = await fetch('/api/action', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token, action: 'clear_banwave' })
            });
            const data = await res.json();
            showToast(data.message || data.error, data.success ? 'success' : 'error');
            refreshData();
        } catch (e) {
            showToast(e.message, 'error');
        }
    }

    let activeReplayData = null;
    let replayTimer = null;
    let replayCurFrame = 0;
    let replayIsPlaying = false;
    let replaySpeed = 1.0;

    async function openWebReplay(clipId) {
        try {
            const res = await fetch('/api/replay?id=' + encodeURIComponent(clipId));
            const data = await res.json();
            if (!data || !data.frames || data.frames.length === 0) {
                alert("Clip không có dữ liệu frames chuyển động!");
                return;
            }
            activeReplayData = data;
            document.getElementById('modalTitle').innerText = `🎬 Web Replay: ${data.playerName} (${data.checkName})`;
            document.getElementById('modalSubtitle').innerText = `Mã clip: ${data.id} • Thế giới: ${data.worldName} • VL: ${data.vl} • ${data.timestamp}`;
            document.getElementById('replayScrub').max = data.frames.length - 1;
            document.getElementById('replayScrub').value = 0;
            replayCurFrame = 0;

            document.getElementById('replayModal').classList.add('show');
            drawWebReplayFrame();
            playWebReplay();
        } catch (e) {
            alert("Không thể tải clip: " + e.message);
        }
    }

    function closeReplayModal() {
        pauseWebReplay();
        document.getElementById('replayModal').classList.remove('show');
    }

    function toggleReplayPlay() {
        if (replayIsPlaying) pauseWebReplay();
        else playWebReplay();
    }

    function playWebReplay() {
        if (!activeReplayData) return;
        replayIsPlaying = true;
        document.getElementById('btnPlayPause').innerText = "⏸️ Tạm Dừng";
        if (replayTimer) clearInterval(replayTimer);

        const interval = Math.max(15, Math.floor(50 / replaySpeed));
        replayTimer = setInterval(() => {
            if (replayCurFrame >= activeReplayData.frames.length - 1) {
                pauseWebReplay();
                return;
            }
            replayCurFrame++;
            document.getElementById('replayScrub').value = replayCurFrame;
            drawWebReplayFrame();
        }, interval);
    }

    function pauseWebReplay() {
        replayIsPlaying = false;
        document.getElementById('btnPlayPause').innerText = "▶️ Phát";
        if (replayTimer) {
            clearInterval(replayTimer);
            replayTimer = null;
        }
    }

    function setReplaySpeed(val) {
        replaySpeed = parseFloat(val);
        if (replayIsPlaying) playWebReplay();
    }

    function onScrubInput(val) {
        replayCurFrame = parseInt(val);
        drawWebReplayFrame();
    }

    function drawWebReplayFrame() {
        if (!activeReplayData || !activeReplayData.frames) return;
        const frames = activeReplayData.frames;
        const cur = frames[replayCurFrame];
        if (!cur) return;

        document.getElementById('replayTimeDisplay').innerText = `${replayCurFrame + 1}/${frames.length}`;
        document.getElementById('repMetricCps').innerText = cur[8];
        document.getElementById('repMetricSpeed').innerText = cur[9].toFixed(2);
        document.getElementById('repMetricAction').innerText = cur[7];
        document.getElementById('repMetricCoord').innerText = `X:${cur[0].toFixed(1)} Y:${cur[1].toFixed(1)} Z:${cur[2].toFixed(1)}`;
        document.getElementById('repMetricGround').innerText = cur[5] ? "Có" : "Không";

        const canvas = document.getElementById('webReplayCanvas');
        const ctx = canvas.getContext('2d');
        const w = canvas.width, h = canvas.height;
        ctx.clearRect(0, 0, w, h);

        let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
        for (const f of frames) {
            minX = Math.min(minX, f[0]); maxX = Math.max(maxX, f[0]);
            minZ = Math.min(minZ, f[2]); maxZ = Math.max(maxZ, f[2]);
        }
        const rangeX = Math.max(10, maxX - minX);
        const rangeZ = Math.max(10, maxZ - minZ);
        const scale = Math.min((w - 100) / rangeX, (h - 100) / rangeZ);
        const offX = (w - rangeX * scale) / 2;
        const offZ = (h - rangeZ * scale) / 2;

        const toScreenX = (x) => offX + (x - minX) * scale;
        const toScreenY = (z) => offZ + (z - minZ) * scale;

        ctx.strokeStyle = "rgba(255, 255, 255, 0.04)";
        ctx.lineWidth = 1;
        for (let x = 0; x < w; x += 40) {
            ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke();
        }
        for (let y = 0; y < h; y += 40) {
            ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
        }

        ctx.strokeStyle = "rgba(56, 189, 248, 0.25)";
        ctx.lineWidth = 2;
        ctx.beginPath();
        for (let i = 0; i < frames.length; i++) {
            const sx = toScreenX(frames[i][0]);
            const sy = toScreenY(frames[i][2]);
            if (i === 0) ctx.moveTo(sx, sy);
            else ctx.lineTo(sx, sy);
        }
        ctx.stroke();

        ctx.strokeStyle = "#38bdf8";
        ctx.lineWidth = 3;
        ctx.beginPath();
        for (let i = 0; i <= replayCurFrame; i++) {
            const sx = toScreenX(frames[i][0]);
            const sy = toScreenY(frames[i][2]);
            if (i === 0) ctx.moveTo(sx, sy);
            else ctx.lineTo(sx, sy);
        }
        ctx.stroke();

        const startX = toScreenX(frames[0][0]);
        const startY = toScreenY(frames[0][2]);
        ctx.fillStyle = "#10b981";
        ctx.beginPath(); ctx.arc(startX, startY, 5, 0, Math.PI * 2); ctx.fill();

        const curX = toScreenX(cur[0]);
        const curY = toScreenY(cur[2]);

        const yawRad = (cur[3] - 90) * (Math.PI / 180);

        ctx.fillStyle = "rgba(56, 189, 248, 0.2)";
        ctx.beginPath();
        ctx.moveTo(curX, curY);
        ctx.arc(curX, curY, 35, yawRad - 0.5, yawRad + 0.5);
        ctx.closePath();
        ctx.fill();

        ctx.fillStyle = cur[7] === 'ATTACK' ? "#f43f5e" : "#38bdf8";
        ctx.beginPath(); ctx.arc(curX, curY, 7, 0, Math.PI * 2); ctx.fill();

        ctx.strokeStyle = "#ffffff";
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(curX, curY);
        ctx.lineTo(curX + Math.cos(yawRad) * 20, curY + Math.sin(yawRad) * 20);
        ctx.stroke();

        ctx.fillStyle = "#ffffff";
        ctx.font = "12px sans-serif";
        ctx.fillText(activeReplayData.playerName, curX + 12, curY + 4);
    }

    function escapeHtml(s) {
        if (!s) return "";
        return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    let monacoLoaded = false;
    let monacoEditorInstance = null;
    let currentIdeFilePath = 'scripts/QuickSample.java';
    let isIdeDirty = false;
    let ideDockBuildLogs = 'Sẵn sàng. Mở tập tin từ cây thư mục bên trái hoặc bấm \\'Lưu & Biên dịch\\' để tự động kiểm tra lỗi cú pháp thời gian thực.';
    let ideDockRunLogs = '';
    let currentDockTab = 'buildOutput';

    function initMonacoEditor() {
        if (monacoLoaded) {
            if (monacoEditorInstance) {
                setTimeout(() => monacoEditorInstance.layout(), 60);
            }
            return;
        }

        if (typeof require !== 'undefined') {
            try {
                require.config({ paths: { 'vs': 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.45.0/min/vs' }});
                require(['vs/editor/editor.main'], function() {
                    monacoEditorInstance = monaco.editor.create(document.getElementById('monacoEditorContainer'), {
                        value: 'JavaStudio Cloud Web IDE (Monaco Engine)\\nĐang kết nối tới server...',
                        language: 'java',
                        theme: 'vs-dark',
                        automaticLayout: true,
                        fontSize: 13,
                        fontFamily: 'Consolas, "Fira Code", monospace',
                        minimap: { enabled: true },
                        scrollBeyondLastLine: false,
                        tabSize: 4,
                        insertSpaces: true
                    });

                    monacoEditorInstance.onDidChangeModelContent(function() {
                        setIdeDirty(true);
                    });

                    monacoEditorInstance.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, function() {
                        saveCurrentIdeFile();
                    });

                    monacoLoaded = true;
                    loadIdeTree();
                    openIdeFile(currentIdeFilePath);
                });
                return;
            } catch (e) {
                console.warn('Monaco require failed:', e);
            }
        }

        document.getElementById('monacoEditorContainer').style.display = 'none';
        const fb = document.getElementById('fallbackEditor');
        fb.style.display = 'block';
        fb.addEventListener('input', () => setIdeDirty(true));
        fb.addEventListener('keydown', (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 's') {
                e.preventDefault();
                saveCurrentIdeFile();
            }
        });
        monacoLoaded = true;
        loadIdeTree();
        openIdeFile(currentIdeFilePath);
    }

    function getIdeEditorContent() {
        if (monacoEditorInstance) {
            return monacoEditorInstance.getValue();
        }
        return document.getElementById('fallbackEditor').value;
    }

    function setIdeEditorContent(val, lang) {
        if (monacoEditorInstance) {
            monacoEditorInstance.setValue(val);
            if (lang && monaco.editor.setModelLanguage) {
                monaco.editor.setModelLanguage(monacoEditorInstance.getModel(), lang);
            }
        } else {
            document.getElementById('fallbackEditor').value = val;
        }
        setIdeDirty(false);
    }

    function setIdeDirty(dirty) {
        isIdeDirty = dirty;
        const ind = document.getElementById('ideDirtyIndicator');
        if (ind) ind.style.display = dirty ? 'inline' : 'none';
    }

    async function loadIdeTree() {
        try {
            const token = document.getElementById('authToken').value.trim();
            const res = await fetch('/api/ide/tree?token=' + encodeURIComponent(token));
            const data = await res.json();
            const treeEl = document.getElementById('ideTree');
            if (!data.files || data.files.length === 0) {
                treeEl.innerHTML = '<div style="padding:12px;color:var(--text-muted);">Không có tập tin nào.</div>';
                return;
            }
            treeEl.innerHTML = renderTreeNodes(data.files);
        } catch (e) {
            console.error('Failed to load IDE tree:', e);
        }
    }

    function renderTreeNodes(nodes) {
        let html = '';
        for (const n of nodes) {
            if (n.type === 'directory') {
                html += `<div class="tree-folder">
                    <div class="tree-node" onclick="toggleFolder(this)">
                        <span>📁</span> <strong>${escapeHtml(n.name)}</strong>
                    </div>
                    <div class="tree-children">
                        ${n.children ? renderTreeNodes(n.children) : ''}
                    </div>
                </div>`;
            } else {
                const icon = n.name.endsWith('.java') ? '☕' : (n.name.endsWith('.yml') ? '⚙️' : '📄');
                const active = n.path === currentIdeFilePath ? 'active' : '';
                html += `<div class="tree-node ${active}" onclick="openIdeFile('${escapeHtml(n.path)}')">
                    <span>${icon}</span> <span>${escapeHtml(n.name)}</span>
                </div>`;
            }
        }
        return html;
    }

    function toggleFolder(el) {
        const children = el.nextElementSibling;
        if (children) {
            children.style.display = children.style.display === 'none' ? 'flex' : 'none';
        }
    }

    async function openIdeFile(path) {
        if (!path) return;
        currentIdeFilePath = path;
        document.getElementById('ideActiveFilePath').innerText = path;
        document.getElementById('ideStatusBadge').className = 'badge badge-warning';
        document.getElementById('ideStatusBadge').innerText = 'Đang tải...';

        try {
            const token = document.getElementById('authToken').value.trim();
            const res = await fetch('/api/ide/file?path=' + encodeURIComponent(path) + '&token=' + encodeURIComponent(token));
            const data = await res.json();
            if (data.success) {
                let lang = 'java';
                if (path.endsWith('.yml') || path.endsWith('.yaml')) lang = 'yaml';
                else if (path.endsWith('.json')) lang = 'json';
                else if (path.endsWith('.md')) lang = 'markdown';
                setIdeEditorContent(data.content, lang);
                document.getElementById('ideStatusBadge').className = 'badge badge-clean';
                document.getElementById('ideStatusBadge').innerText = 'Ready';
            } else {
                alert('Không thể mở tập tin: ' + data.error);
            }
        } catch (e) {
            alert('Lỗi kết nối tải tập tin: ' + e);
        }
        loadIdeTree();
    }

    async function saveCurrentIdeFile() {
        if (!currentIdeFilePath) return;
        const token = document.getElementById('authToken').value;
        const content = getIdeEditorContent();

        document.getElementById('ideStatusBadge').className = 'badge badge-warning';
        document.getElementById('ideStatusBadge').innerText = 'Đang lưu & biên dịch...';

        try {
            const res = await fetch('/api/ide/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: token, path: currentIdeFilePath, content: content })
            });
            const data = await res.json();
            if (data.success) {
                setIdeDirty(false);
                if (data.compiled === false) {
                    document.getElementById('ideStatusBadge').className = 'badge badge-danger';
                    document.getElementById('ideStatusBadge').innerText = 'Lỗi cú pháp';
                    ideDockBuildLogs = `❌ [LỖI BIÊN DỊCH ${new Date().toLocaleTimeString()}]\\n` + data.compileErrors;
                    switchDockTab('buildOutput');
                } else {
                    document.getElementById('ideStatusBadge').className = 'badge badge-clean';
                    document.getElementById('ideStatusBadge').innerText = 'Đã lưu (0 lỗi)';
                    ideDockBuildLogs = `✔ [BIÊN DỊCH THÀNH CÔNG ${new Date().toLocaleTimeString()}]\\n` + (data.message || 'Mã nguồn hợp lệ, không phát hiện lỗi cú pháp.');
                    switchDockTab('buildOutput');
                }
            } else {
                document.getElementById('ideStatusBadge').className = 'badge badge-danger';
                document.getElementById('ideStatusBadge').innerText = 'Lỗi lưu';
                alert('Lỗi: ' + data.error);
            }
        } catch (e) {
            alert('Lỗi kết nối khi lưu: ' + e);
        }
    }

    async function runCurrentIdeScript() {
        const token = document.getElementById('authToken').value;
        const content = getIdeEditorContent();

        switchDockTab('runOutput');
        document.getElementById('ideDockText').innerText = '⏳ Đang khởi chạy kịch bản trên máy chủ...';

        try {
            const res = await fetch('/api/ide/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: token, path: currentIdeFilePath, content: content })
            });
            const data = await res.json();
            if (data.success) {
                ideDockRunLogs = `✔ [KẾT QUẢ THỰC THI ${new Date().toLocaleTimeString()}]\\n` + data.result;
            } else {
                ideDockRunLogs = `❌ [LỖI THỰC THI ${new Date().toLocaleTimeString()}]\\n` + (data.error || 'Thực thi thất bại');
            }
            document.getElementById('ideDockText').innerText = ideDockRunLogs;
        } catch (e) {
            document.getElementById('ideDockText').innerText = 'Lỗi kết nối máy chủ: ' + e;
        }
    }

    async function reloadCurrentIdePlugin() {
        await saveCurrentIdeFile();
    }

    async function idePromptNewScript() {
        const name = prompt('Nhập tên script mới (ví dụ: SuperSword.java):', 'CustomItem.java');
        if (!name) return;
        const cleanName = name.replace(/[^a-zA-Z0-9_.]/g, '');
        const targetPath = 'scripts/' + cleanName;
        const sampleCode = `package com.javastudio.runtime;\\nimport org.bukkit.*;\\nimport org.bukkit.entity.*;\\nimport org.bukkit.inventory.*;\\nimport com.javastudio.api.*;\\nimport com.javastudio.api.item.*;\\n\\npublic class ${cleanName.replace('.java', '')} extends QuickScript {\\n    @Override\\n    public Object run() throws Throwable {\\n        ItemStack item = itemBuilder("NETHERITE_SWORD")\\n            .name("&6&lThanh Gươm Thần Khí")\\n            .maxStackSize(16)\\n            .rarity("EPIC")\\n            .fireResistant(true)\\n            .glintOverride(true)\\n            .build();\\n\\n        if (player != null) {\\n            player.getInventory().addItem(item);\\n            broadcast("&a[JavaStudio] Đã trao &6&lThanh Gươm Thần Khí &acho &e" + player.getName());\\n        }\\n        return "Tạo item thành công!";\\n    }\\n}`;
        currentIdeFilePath = targetPath;
        setIdeEditorContent(sampleCode, 'java');
        await saveCurrentIdeFile();
        loadIdeTree();
    }

    function switchDockTab(tab) {
        currentDockTab = tab;
        document.querySelectorAll('.ide-dock-tab').forEach(t => t.classList.remove('active'));
        if (tab === 'buildOutput') {
            document.getElementById('dockTabBuild').classList.add('active');
            document.getElementById('ideDockText').innerText = ideDockBuildLogs;
        } else {
            document.getElementById('dockTabRun').classList.add('active');
            document.getElementById('ideDockText').innerText = ideDockRunLogs || 'Chưa có kết quả thực thi. Nhấn [▶ Chạy Script] để chạy.';
        }
    }

    function clearIdeDock() {
        if (currentDockTab === 'buildOutput') {
            ideDockBuildLogs = '';
            document.getElementById('ideDockText').innerText = '';
        } else {
            ideDockRunLogs = '';
            document.getElementById('ideDockText').innerText = '';
        }
    }

    async function refreshFlows() {
        try {
            const res = await fetch('/api/flow/list');
            const data = await res.json();
            const tbody = document.getElementById('flowTableBody');
            if (!data.flows || data.flows.length === 0) {
                tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--text-muted); padding: 30px;">Chưa có Visual Flow nào. Nhấn [➕ Tạo Flow Mới] để tạo!</td></tr>';
                return;
            }
            tbody.innerHTML = data.flows.map(f => `
                <tr>
                    <td><strong>${escapeHtml(f.name)}</strong></td>
                    <td><code>${escapeHtml(f.id)}</code></td>
                    <td><span class="badge badge-clean">${escapeHtml(f.trigger)}</span></td>
                    <td>${f.conditions} điều kiện</td>
                    <td>${f.actions} hành động</td>
                    <td>
                        <button class="btn btn-success" onclick="runFlow('${escapeHtml(f.id)}')">▶ Chạy Thử</button>
                    </td>
                </tr>
            `).join('');
        } catch (e) {
            console.error('Lỗi tải flows:', e);
        }
    }

    async function runFlow(id) {
        const token = document.getElementById('authToken').value;
        try {
            const res = await fetch('/api/flow/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: token, id: id })
            });
            const data = await res.json();
            alert(data.success ? '✔ ' + data.result : '❌ Lỗi: ' + data.error);
        } catch (e) {
            alert('Lỗi kết nối: ' + e);
        }
    }

    let vNodes = [];
    let vWires = [];
    let wireConnectingSource = null;
    let canvasInitialized = false;
    let activeDraggingNode = null;
    let dragOffsetX = 0;
    let dragOffsetY = 0;

    function initFlowCanvas() {
        if (canvasInitialized) return;
        canvasInitialized = true;
        const container = document.getElementById('flowCanvas');
        if (!container) return;

        loadDefaultBlueprintPreset();

        container.addEventListener('mousemove', function (e) {
            if (activeDraggingNode) {
                const rect = container.getBoundingClientRect();
                const newX = Math.max(10, Math.min(rect.width - 240, e.clientX - rect.left - dragOffsetX));
                const newY = Math.max(10, Math.min(rect.height - 180, e.clientY - rect.top - dragOffsetY));
                activeDraggingNode.x = newX;
                activeDraggingNode.y = newY;
                const card = document.getElementById('node-card-' + activeDraggingNode.id);
                if (card) {
                    card.style.left = newX + 'px';
                    card.style.top = newY + 'px';
                }
                renderWires();
            }
        });

        document.addEventListener('mouseup', function () {
            activeDraggingNode = null;
        });
    }

    function loadDefaultBlueprintPreset() {
        document.getElementById('vFlowId').value = 'welcome_reward';
        document.getElementById('vFlowName').value = 'Thưởng Chào Mừng & Danh Hiệu';
        vNodes = [
            { id: 'node_1', type: 'trigger', subType: 'on_player_join', title: 'Trigger: Người Chơi Vào Server', params: {}, x: 40, y: 130 },
            { id: 'node_2', type: 'condition', subType: 'chance', title: 'Điều Kiện: Tỷ Lệ (Chance)', params: { percentage: '100' }, x: 320, y: 130 },
            { id: 'node_3', type: 'action', subType: 'broadcast', title: 'Hành Động: Thông Báo', params: { message: '&b[JavaStudio Flow] &aChào mừng chiến binh &e%player% &agia nhập thế giới!' }, x: 620, y: 60 },
            { id: 'node_4', type: 'action', subType: 'play_sound', title: 'Hành Động: Âm Thanh', params: { sound: 'ENTITY_PLAYER_LEVELUP', volume: '1.0', pitch: '1.2' }, x: 620, y: 240 }
        ];
        vWires = [
            { from: 'node_1', to: 'node_2' },
            { from: 'node_2', to: 'node_3' },
            { from: 'node_2', to: 'node_4' }
        ];
        wireConnectingSource = null;
        renderCanvasNodes();
        renderWires();
    }

    function addCanvasNode(type) {
        const id = 'node_' + Math.floor(Math.random() * 100000);
        let subType = 'on_player_join';
        let title = 'Trigger: Người Chơi Vào';
        let params = {};
        const x = 80 + Math.floor(Math.random() * 200);
        const y = 80 + Math.floor(Math.random() * 200);

        if (type === 'trigger') {
            subType = 'on_player_join';
            title = 'Trigger: Sự Kiện';
        } else if (type === 'condition') {
            subType = 'chance';
            title = 'Điều Kiện: Tỷ Lệ';
            params = { percentage: '100' };
        } else if (type === 'action') {
            subType = 'broadcast';
            title = 'Hành Động: Broadcast';
            params = { message: '&a[Flow] Sự kiện kích hoạt!' };
        }

        vNodes.push({ id, type, subType, title, params, x, y });
        renderCanvasNodes();
        renderWires();
    }

    function clearCanvasNodes() {
        vNodes = [];
        vWires = [];
        wireConnectingSource = null;
        renderCanvasNodes();
        renderWires();
    }

    function removeNode(nodeId) {
        vNodes = vNodes.filter(n => n.id !== nodeId);
        vWires = vWires.filter(w => w.from !== nodeId && w.to !== nodeId);
        if (wireConnectingSource === nodeId) wireConnectingSource = null;
        renderCanvasNodes();
        renderWires();
    }

    function renderCanvasNodes() {
        const container = document.getElementById('flowNodesContainer');
        if (!container) return;
        container.innerHTML = '';

        vNodes.forEach(node => {
            const card = document.createElement('div');
            card.id = 'node-card-' + node.id;
            card.className = 'flow-node-card';
            card.style.left = node.x + 'px';
            card.style.top = node.y + 'px';

            let headerClass = 'trigger';
            let icon = '⚡';
            if (node.type === 'condition') { headerClass = 'condition'; icon = '❓'; }
            else if (node.type === 'action') { headerClass = 'action'; icon = '🎯'; }

            let fieldsHtml = '';
            if (node.type === 'trigger') {
                fieldsHtml = `
                    <select class="input-token" style="padding: 4px; font-size: 11px; width: 100%;" onchange="updateNodeSubtype('${node.id}', this.value)">
                        <option value="on_player_join" ${node.subType === 'on_player_join' ? 'selected' : ''}>on_player_join</option>
                        <option value="on_player_quit" ${node.subType === 'on_player_quit' ? 'selected' : ''}>on_player_quit</option>
                        <option value="on_block_break" ${node.subType === 'on_block_break' ? 'selected' : ''}>on_block_break</option>
                        <option value="on_player_death" ${node.subType === 'on_player_death' ? 'selected' : ''}>on_player_death</option>
                        <option value="on_chat" ${node.subType === 'on_chat' ? 'selected' : ''}>on_chat</option>
                    </select>
                `;
            } else if (node.type === 'condition') {
                fieldsHtml = `
                    <select class="input-token" style="padding: 4px; font-size: 11px; width: 100%;" onchange="updateNodeSubtype('${node.id}', this.value)">
                        <option value="chance" ${node.subType === 'chance' ? 'selected' : ''}>chance (%)</option>
                        <option value="is_op" ${node.subType === 'is_op' ? 'selected' : ''}>is_op (OP)</option>
                        <option value="permission" ${node.subType === 'permission' ? 'selected' : ''}>permission</option>
                    </select>
                    <input type="text" class="input-token" style="padding: 4px; font-size: 11px; width: 100%;" placeholder="Giá trị / Phần trăm" value="${escapeHtml(node.params.percentage || node.params.value || '100')}" onchange="updateNodeParam('${node.id}', 'percentage', this.value)">
                `;
            } else if (node.type === 'action') {
                fieldsHtml = `
                    <select class="input-token" style="padding: 4px; font-size: 11px; width: 100%;" onchange="updateNodeSubtype('${node.id}', this.value)">
                        <option value="broadcast" ${node.subType === 'broadcast' ? 'selected' : ''}>broadcast</option>
                        <option value="send_title" ${node.subType === 'send_title' ? 'selected' : ''}>send_title</option>
                        <option value="play_sound" ${node.subType === 'play_sound' ? 'selected' : ''}>play_sound</option>
                        <option value="heal" ${node.subType === 'heal' ? 'selected' : ''}>heal</option>
                        <option value="give_item" ${node.subType === 'give_item' ? 'selected' : ''}>give_item</option>
                    </select>
                    <input type="text" class="input-token" style="padding: 4px; font-size: 11px; width: 100%;" placeholder="Thông điệp / Tên âm thanh" value="${escapeHtml(node.params.message || node.params.sound || node.params.title || '')}" onchange="updateNodeParam('${node.id}', 'message', this.value)">
                `;
            }

            card.innerHTML = `
                <div class="flow-node-header ${headerClass}" id="node-drag-${node.id}">
                    <div style="display:flex; align-items:center; gap:6px;">
                        <span>${icon}</span>
                        <span>${escapeHtml(node.title)}</span>
                    </div>
                    <button style="background:none; border:none; color:rgba(255,255,255,0.7); cursor:pointer; font-size:12px;" onclick="removeNode('${node.id}')">✕</button>
                </div>
                <div class="flow-node-body">
                    ${fieldsHtml}
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-top:4px;">
                        <div>
                            ${node.type !== 'trigger' ? `<span class="flow-port" title="Cổng Vào (Input)" onclick="onPortClick('${node.id}', 'in')" style="background:#22c55e;"></span>` : '<span></span>'}
                        </div>
                        <div>
                            <span class="flow-port" title="Cổng Ra (Output)" onclick="onPortClick('${node.id}', 'out')"></span>
                        </div>
                    </div>
                </div>
            `;

            container.appendChild(card);

            const dragHeader = card.querySelector('#node-drag-' + node.id);
            dragHeader.addEventListener('mousedown', function (e) {
                activeDraggingNode = node;
                const rect = card.getBoundingClientRect();
                dragOffsetX = e.clientX - rect.left;
                dragOffsetY = e.clientY - rect.top;
                e.stopPropagation();
            });
        });
    }

    function onPortClick(nodeId, portType) {
        if (portType === 'out') {
            wireConnectingSource = nodeId;
            const badge = document.getElementById('flowActiveBadge');
            if (badge) badge.innerText = 'Đang nối dây từ ' + nodeId + '... (Click cổng Vào)';
        } else if (portType === 'in' && wireConnectingSource) {
            if (wireConnectingSource !== nodeId) {
                const exists = vWires.some(w => w.from === wireConnectingSource && w.to === nodeId);
                if (!exists) {
                    vWires.push({ from: wireConnectingSource, to: nodeId });
                    renderWires();
                }
            }
            wireConnectingSource = null;
            const badge = document.getElementById('flowActiveBadge');
            if (badge) badge.innerText = 'Blueprint Mode';
        }
    }

    function renderWires() {
        const svg = document.getElementById('flowWiresSvg');
        if (!svg) return;
        const defs = svg.querySelector('defs');
        svg.innerHTML = '';
        if (defs) svg.appendChild(defs);

        const cardWidth = 230;
        const cardHeight = 110;

        vWires.forEach(wire => {
            const fromNode = vNodes.find(n => n.id === wire.from);
            const toNode = vNodes.find(n => n.id === wire.to);
            if (!fromNode || !toNode) return;

            const x1 = fromNode.x + cardWidth - 14;
            const y1 = fromNode.y + cardHeight - 16;
            const x2 = toNode.x + 14;
            const y2 = toNode.y + cardHeight - 16;
            const dx = Math.abs(x2 - x1) * 0.5;

            const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            path.setAttribute('d', `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`);
            path.setAttribute('stroke', '#38bdf8');
            path.setAttribute('stroke-width', '2.5');
            path.setAttribute('fill', 'none');
            path.setAttribute('marker-end', 'url(#wireArrow)');
            svg.appendChild(path);
        });
    }

    function updateNodeSubtype(nodeId, subType) {
        const n = vNodes.find(x => x.id === nodeId);
        if (n) {
            n.subType = subType;
            renderWires();
        }
    }

    function updateNodeParam(nodeId, key, val) {
        const n = vNodes.find(x => x.id === nodeId);
        if (n) {
            n.params[key] = val;
        }
    }

    function toggleFlowTable() {
        const el = document.getElementById('flowTableWrapper');
        if (el) {
            el.style.display = el.style.display === 'none' ? 'block' : 'none';
        }
    }

    async function saveVisualFlowToBackend() {
        const id = document.getElementById('vFlowId').value.trim();
        const name = document.getElementById('vFlowName').value.trim();
        if (!id || !name) {
            alert('Vui lòng nhập ID Flow và Tên Flow trước khi lưu!');
            return;
        }

        const triggerNode = vNodes.find(n => n.type === 'trigger');
        const trigger = triggerNode ? triggerNode.subType : 'on_player_join';

        const conditions = [];
        vNodes.filter(n => n.type === 'condition').forEach(c => {
            conditions.push({
                type: c.subType,
                percentage: c.params.percentage || '100'
            });
        });

        const actions = [];
        vNodes.filter(n => n.type === 'action').forEach(a => {
            const item = { type: a.subType };
            if (a.subType === 'broadcast') item.message = a.params.message || '&aThông báo!';
            else if (a.subType === 'play_sound') { item.sound = a.params.message || 'ENTITY_PLAYER_LEVELUP'; item.volume = '1.0'; item.pitch = '1.0'; }
            else if (a.subType === 'send_title') { item.title = a.params.message || '&6Chào Mừng'; item.subtitle = '&eHoàn thành kịch bản!'; }
            else if (a.subType === 'heal') item.amount = '20';
            else if (a.subType === 'give_item') { item.material = 'DIAMOND'; item.amount = '1'; }
            actions.push(item);
        });

        const token = document.getElementById('authToken').value;
        const payload = {
            token: token,
            id: id.toLowerCase().replace(/[^a-z0-9_]/g, ''),
            name: name,
            trigger: trigger,
            conditions: conditions,
            actions: actions
        };

        try {
            const res = await fetch('/api/flow/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await res.json();
            alert(data.message || (data.success ? 'Đã lưu và nạp nóng kịch bản Flow thành công!' : 'Thất bại!'));
            refreshFlows();
        } catch (e) {
            alert('Lỗi: ' + e);
        }
    }

    async function runCurrentVisualFlow() {
        const id = document.getElementById('vFlowId').value.trim();
        if (!id) {
            alert('Vui lòng nhập ID Flow để chạy thử!');
            return;
        }
        await runFlow(id);
    }

    async function refreshMesh() {
        try {
            const res = await fetch('/api/mesh/status');
            const data = await res.json();
            document.getElementById('meshNodeId').innerText = data.nodeId || 'Offline';
            document.getElementById('meshRole').innerText = data.role || 'FOLLOWER';
            document.getElementById('meshLeaderId').innerText = data.leaderId || 'Election...';
            document.getElementById('meshPeerCount').innerText = (data.peers ? data.peers.length : 0);

            const tbody = document.getElementById('meshTableBody');
            if (!data.peers || data.peers.length === 0) {
                tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--text-muted); padding: 30px;">Node đang hoạt động độc lập (Chưa kết nối cụm mạng ngoài).</td></tr>';
                return;
            }

            tbody.innerHTML = data.peers.map(p => `
                <tr>
                    <td>${p.leader ? '<span class="badge badge-warning">LEADER</span>' : '<span class="badge badge-clean">FOLLOWER</span>'}</td>
                    <td><strong>${escapeHtml(p.id)}</strong></td>
                    <td><code>${escapeHtml(p.host)}:${p.port}</code></td>
                    <td>${p.online} players</td>
                    <td><span class="badge ${p.tps >= 19.0 ? 'badge-clean' : 'badge-danger'}">${p.tps}</span></td>
                    <td><span style="color:var(--success);">● Online</span></td>
                </tr>
            `).join('');
        } catch (e) {
            console.error('Lỗi tải mesh:', e);
        }
    }

    async function broadcastMeshPrompt() {
        const msg = prompt('Nhập thông điệp phát sóng toàn cụm máy chủ P2P:');
        if (!msg) return;
        const token = document.getElementById('authToken').value;
        try {
            const res = await fetch('/api/exec', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: token, code: `mesh().broadcastClusterMessage("${msg.replace(/"/g, '')}"); return "Đã phát sóng tới toàn cụm!";` })
            });
            const data = await res.json();
            alert(data.result || data.error);
        } catch (e) {
            alert('Lỗi: ' + e);
        }
    }

    async function refreshBenchmark() {
        try {
            const res = await fetch('/api/benchmark/status');
            const data = await res.json();
            const alertEl = document.getElementById('bmRunningAlert');
            if (alertEl) alertEl.style.display = data.running ? 'block' : 'none';

            if (!data.hasResult || !data.result) return;
            const r = data.result;

            document.getElementById('bmScore').innerText = r.performanceScore || '0';
            const badge = document.getElementById('bmTierBadge');
            if (badge) {
                badge.innerText = 'Tier ' + (r.tierRating || 'B');
                if (r.tierRating === 'SSS' || r.tierRating === 'SS') badge.className = 'badge badge-clean';
                else if (r.tierRating === 'S' || r.tierRating === 'A') badge.className = 'badge badge-warning';
                else badge.className = 'badge badge-danger';
            }
            document.getElementById('bmMaxPlayers').innerText = (r.recommendedMaxPlayers || 50) + ' người chơi';
            document.getElementById('bmViewSim').innerText = (r.recommendedViewDistance || 10) + ' / ' + (r.recommendedSimulationDistance || 6) + ' chunks';
            document.getElementById('bmDuration').innerText = (r.durationMs || 0) + ' ms';

            document.getElementById('bmCpuMath').innerText = (r.cpuMathMops || 0).toFixed(1) + ' Mops/s';
            document.getElementById('bmCrypto').innerText = (r.multiThreadCryptoKhash || 0).toFixed(1) + ' Khash/s';
            document.getElementById('bmCompiler').innerText = (r.compilerLatencyMs || 0) + ' ms';
            document.getElementById('bmRamBw').innerText = (r.offHeapRamThroughputMb || 0).toFixed(1) + ' MB/s';
            document.getElementById('bmDiskIo').innerText = (r.diskIoThroughputMb || 0).toFixed(1) + ' MB/s';
            document.getElementById('bmPipeline').innerText = (r.eventPipelineThroughputOps || 0).toLocaleString() + ' ops/s';
            document.getElementById('bmJitter').innerText = (r.schedulerJitterMs || 0).toFixed(2) + ' ms';

            const adviceContainer = document.getElementById('bmAdviceList');
            if (adviceContainer && r.advice) {
                adviceContainer.innerHTML = r.advice.map(adv => `
                    <div style="background: rgba(255,255,255,0.03); border-left: 3px solid #38bdf8; border-radius: 4px; padding: 10px 14px; font-size: 13px; color: #cbd5e1;">
                        ${escapeHtml(adv)}
                    </div>
                `).join('');
            }
        } catch (e) {
            console.error('Lỗi tải benchmark:', e);
        }
    }

    async function runBenchmarkFromWeb(quick) {
        const token = document.getElementById('authToken').value;
        const alertEl = document.getElementById('bmRunningAlert');
        if (alertEl) alertEl.style.display = 'block';
        try {
            const res = await fetch('/api/benchmark/run?quick=' + quick, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: token })
            });
            const data = await res.json();
            if (data.success) {
                setTimeout(refreshBenchmark, quick ? 2000 : 5000);
            } else {
                alert('Lỗi: ' + data.error);
                if (alertEl) alertEl.style.display = 'none';
            }
        } catch (e) {
            alert('Lỗi: ' + e);
            if (alertEl) alertEl.style.display = 'none';
        }
    }

    setInterval(refreshData, 1000);
    setInterval(refreshLogs, 2000);
    refreshData();
    refreshLogs();
</script>
</body>
</html>
""";

    private static String getDashboardHtml() {
        return new StringBuilder(HTML_PART_1.length() + HTML_PART_2.length() + HTML_PART_3.length() + HTML_PART_4.length())
                .append(HTML_PART_1)
                .append(HTML_PART_2)
                .append(HTML_PART_3)
                .append(HTML_PART_4)
                .toString();
    }
}
