package com.javastudio.network;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.anticheat.AntiCheatManager;
import com.javastudio.anticheat.WebDashboardService;
import com.javastudio.pack.ResourcePackHost;
import com.javastudio.utils.PlatformUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

public final class SinglePortMuxEngine implements Listener {

    public static final String ACCEPTOR_HANDLER_NAME = "javastudio_singleport_acceptor";
    public static final String INJECTOR_HANDLER_NAME = "javastudio_singleport_injector";
    public static final String MUX_HANDLER_NAME = "javastudio_http_mux";
    public static final String HEADER_MUX_SECRET = "X-JS-Mux-Secret";
    public static final String HEADER_CLIENT_IP = "X-JS-Client-IP";

    private static final int MAX_HTTP_HEADER_BYTES = 16384;
    private static final byte[] EOF_MARKER = new byte[0];
    private static final byte[][] HTTP_PREFIXES = new byte[][]{
            "GET ".getBytes(StandardCharsets.US_ASCII),
            "POST ".getBytes(StandardCharsets.US_ASCII),
            "HEAD ".getBytes(StandardCharsets.US_ASCII),
            "OPTIONS ".getBytes(StandardCharsets.US_ASCII),
            "PUT ".getBytes(StandardCharsets.US_ASCII),
            "DELETE ".getBytes(StandardCharsets.US_ASCII)
    };

    public record PackMuxQuery(String method, String clientIp, String ifNoneMatch) {}

    private final JavaStudioPlugin plugin;
    private final String muxSecret;
    private final Set<Channel> hookedServerChannels = ConcurrentHashMap.newKeySet();
    private final Set<Channel> activeHttpChannels = ConcurrentHashMap.newKeySet();
    private final ServerAcceptorHook acceptorHook = new ServerAcceptorHook();
    private final AtomicLong totalHttpRequests = new AtomicLong(0);
    private final AtomicLong totalPackRequests = new AtomicLong(0);

    private volatile List<?> serverConnectionsList;
    private volatile boolean running = false;
    private volatile boolean listenerRegistered = false;
    private volatile String learnedHost = "";
    private volatile int learnedPort = 0;

    private IntSupplier customWebPortSupplier;
    private java.util.function.Function<PackMuxQuery, ResourcePackHost.PackHttpResponse> customPackHandler;

    public SinglePortMuxEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
        this.muxSecret = generateRandomHex(24);
    }

    private static String generateRandomHex(int byteLen) {
        byte[] raw = new byte[byteLen];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(byteLen * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static boolean envPresent(String key) {
        try {
            String v = System.getenv(key);
            return v != null && !v.isBlank();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isPanelEnvironment() {
        if (envPresent("P_SERVER_UUID") || envPresent("P_SERVER_LOCATION") || envPresent("PTERODACTYL_PTDL")) {
            return true;
        }
        if (envPresent("PUFFER_DAEMON") || envPresent("CRAFTY_HOST") || envPresent("KUBERNETES_SERVICE_HOST")) {
            return true;
        }
        if (envPresent("SERVER_PORT") && envPresent("SERVER_MEMORY")) {
            return true;
        }
        try {
            if (new File("/.dockerenv").exists() || new File("/run/.containerenv").exists()) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static String detectEnvironmentLabel() {
        if (envPresent("P_SERVER_UUID") || envPresent("P_SERVER_LOCATION") || envPresent("PTERODACTYL_PTDL")) {
            return "Pterodactyl / Pelican Panel";
        }
        if (envPresent("CRAFTY_HOST")) {
            return "Crafty Controller Panel";
        }
        if (envPresent("PUFFER_DAEMON")) {
            return "PufferPanel";
        }
        if (envPresent("KUBERNETES_SERVICE_HOST")) {
            return "Kubernetes Container";
        }
        try {
            if (new File("/.dockerenv").exists() || new File("/run/.containerenv").exists()
                    || (envPresent("SERVER_PORT") && envPresent("SERVER_MEMORY"))) {
                return "Docker / Hosting Panel";
            }
        } catch (Throwable ignored) {}
        return "Máy chủ độc lập / PC";
    }

    public boolean isConfiguredEnabled() {
        if (plugin == null) return true;
        String webMode = plugin.getConfig().getString("anticheat.web-dashboard.single-port", "auto");
        String packMode = plugin.getConfig().getString("resource-pack.single-port", "auto");
        return !isDisabledFlag(webMode) || !isDisabledFlag(packMode);
    }

    private static boolean isDisabledFlag(String val) {
        if (val == null) return false;
        String v = val.trim().toLowerCase(Locale.ROOT);
        return v.equals("false") || v.equals("off") || v.equals("0") || v.equals("disabled");
    }

    public boolean shouldUseLoopbackBackend(String modeSetting) {
        if (modeSetting == null || modeSetting.isBlank()) {
            return isPanelEnvironment();
        }
        String m = modeSetting.trim().toLowerCase(Locale.ROOT);
        if (m.equals("true") || m.equals("on") || m.equals("always") || m.equals("1") || m.equals("panel")) {
            return true;
        }
        if (m.equals("false") || m.equals("off") || m.equals("0") || m.equals("disabled")) {
            return false;
        }
        return isPanelEnvironment();
    }

    public void setTestHooks(
            IntSupplier webPortSupplier,
            java.util.function.Function<PackMuxQuery, ResourcePackHost.PackHttpResponse> packHandler
    ) {
        this.customWebPortSupplier = webPortSupplier;
        this.customPackHandler = packHandler;
        this.running = true;
    }

    public synchronized void start() {
        if (running) {
            hookMinecraftServerChannels();
            return;
        }
        if (!isConfiguredEnabled()) {
            return;
        }
        running = true;

        if (plugin != null && !listenerRegistered) {
            try {
                Bukkit.getPluginManager().registerEvents(this, plugin);
                listenerRegistered = true;
            } catch (Throwable ignored) {}
        }

        int hookedNow = hookMinecraftServerChannels();
        if (hookedNow > 0 && plugin != null) {
            logStartupBanner();
        }

        if (plugin != null) {
            PlatformUtil.runTaskLater(plugin, () -> {
                if (!running) return;
                int added = hookMinecraftServerChannels();
                if (added > 0 && hookedServerChannels.size() == added) {
                    logStartupBanner();
                }
            }, 1L);

            PlatformUtil.runTaskLater(plugin, () -> {
                if (!running) return;
                int added = hookMinecraftServerChannels();
                if (added > 0 && hookedServerChannels.size() == added) {
                    logStartupBanner();
                }
            }, 20L);
        }
    }

    private void logStartupBanner() {
        if (plugin == null) return;
        int mcPort = Bukkit.getPort();
        String envLabel = detectEnvironmentLabel();
        plugin.getLogger().info("[SinglePortMux] Đã kích hoạt gộp cổng HTTP + Minecraft trực tiếp trên cổng :" + mcPort + " (" + envLabel + ")");
    }

    public synchronized void stop() {
        running = false;
        for (Channel serverCh : hookedServerChannels) {
            try {
                ChannelPipeline p = serverCh.pipeline();
                if (p.get(ACCEPTOR_HANDLER_NAME) != null) {
                    p.remove(ACCEPTOR_HANDLER_NAME);
                }
            } catch (Throwable ignored) {}
        }
        hookedServerChannels.clear();

        for (Channel httpCh : activeHttpChannels) {
            closeSafe(httpCh);
        }
        activeHttpChannels.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        observePlayerAddress(event.getPlayer());
    }

    public void observePlayerAddress(Player player) {
        if (player == null) return;
        try {
            InetSocketAddress vhost = player.getVirtualHost();
            if (vhost != null) {
                String clean = sanitizeHost(vhost.getHostString());
                int port = vhost.getPort();
                if (!isLoopbackOrWildcard(clean)) {
                    this.learnedHost = clean;
                    if (port > 0 && port <= 65535) {
                        this.learnedPort = port;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void observeHttpHostHeader(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) return;
        String trimmed = hostHeader.trim();
        int parsedPort = 0;
        if (!trimmed.startsWith("[")) {
            int colon = trimmed.lastIndexOf(':');
            if (colon > 0 && trimmed.indexOf(':') == colon) {
                try {
                    parsedPort = Integer.parseInt(trimmed.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        String clean = sanitizeHost(trimmed);
        if (!isLoopbackOrWildcard(clean)) {
            this.learnedHost = clean;
            if (parsedPort > 0 && parsedPort <= 65535) {
                this.learnedPort = parsedPort;
            }
        }
    }

    public static String sanitizeHost(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw;
        int nul = s.indexOf('\0');
        if (nul >= 0) {
            s = s.substring(0, nul);
        }
        s = s.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close > 1) {
                s = s.substring(1, close);
            } else {
                return "";
            }
        } else {
            int colon = s.indexOf(':');
            if (colon > 0 && s.indexOf(':', colon + 1) < 0) {
                s = s.substring(0, colon);
            }
        }
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || s.length() > 253 || s.contains("..")) {
            return "";
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == ':';
            if (!ok) return "";
        }
        return s;
    }

    public static boolean isLoopbackOrWildcard(String host) {
        if (host == null || host.isBlank()) return true;
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("0.0.0.0")
                || h.equals("127.0.0.1")
                || h.equals("localhost")
                || h.equals("::")
                || h.equals("::1")
                || h.equals("0:0:0:0:0:0:0:0")
                || h.equals("0:0:0:0:0:0:0:1");
    }

    public int detectExternalPort(int configuredPublicPort) {
        if (configuredPublicPort > 0 && configuredPublicPort <= 65535) {
            return configuredPublicPort;
        }
        if (learnedPort > 0 && learnedPort <= 65535) {
            return learnedPort;
        }
        for (String envKey : new String[]{"P_SERVER_ALLOCATION_PORT", "SERVER_PORT"}) {
            try {
                String v = System.getenv(envKey);
                if (v != null && !v.isBlank()) {
                    int p = Integer.parseInt(v.trim());
                    if (p > 0 && p <= 65535) return p;
                }
            } catch (Throwable ignored) {}
        }
        try {
            int bp = Bukkit.getPort();
            if (bp > 0 && bp <= 65535) return bp;
        } catch (Throwable ignored) {}
        return 25565;
    }

    public String resolvePublicEndpoint(String configuredHost, int configuredPort, Player viewer) {
        String cfgClean = sanitizeHost(configuredHost);
        if (!isLoopbackOrWildcard(cfgClean)) {
            return formatHostPort(cfgClean, detectExternalPort(configuredPort));
        }

        if (viewer != null) {
            observePlayerAddress(viewer);
            try {
                InetSocketAddress vh = viewer.getVirtualHost();
                if (vh != null) {
                    String vhHost = sanitizeHost(vh.getHostString());
                    if (!isLoopbackOrWildcard(vhHost)) {
                        int port = configuredPort > 0 ? configuredPort : (vh.getPort() > 0 ? vh.getPort() : detectExternalPort(0));
                        return formatHostPort(vhHost, port);
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (isLoopbackOrWildcard(learnedHost)) {
            try {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    observePlayerAddress(online);
                    if (!isLoopbackOrWildcard(learnedHost)) break;
                }
            } catch (Throwable ignored) {}
        }

        if (!isLoopbackOrWildcard(learnedHost)) {
            return formatHostPort(learnedHost, detectExternalPort(configuredPort));
        }

        for (String envKey : new String[]{"SERVER_HOSTNAME", "P_SERVER_HOSTNAME", "P_SERVER_ALLOCATION_IP", "SERVER_IP"}) {
            try {
                String envVal = sanitizeHost(System.getenv(envKey));
                if (!isLoopbackOrWildcard(envVal)) {
                    return formatHostPort(envVal, detectExternalPort(configuredPort));
                }
            } catch (Throwable ignored) {}
        }

        try {
            String bukkitIp = sanitizeHost(Bukkit.getIp());
            if (!isLoopbackOrWildcard(bukkitIp)) {
                return formatHostPort(bukkitIp, detectExternalPort(configuredPort));
            }
        } catch (Throwable ignored) {}

        int extPort = detectExternalPort(configuredPort);
        if (isPanelEnvironment()) {
            return "<IP-Panel>:" + extPort;
        }
        return "localhost:" + extPort;
    }

    private static String formatHostPort(String host, int port) {
        String wrapped = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return wrapped + ":" + port;
    }

    public String getMuxSecret() {
        return muxSecret;
    }

    public String resolveTrustedClientIp(String socketIp, String secretHeader, String clientIpHeader) {
        if (secretHeader != null && clientIpHeader != null && !clientIpHeader.isBlank()) {
            byte[] candidate = secretHeader.trim().getBytes(StandardCharsets.UTF_8);
            byte[] expected = muxSecret.getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(candidate, expected)) {
                return clientIpHeader.trim();
            }
        }
        return socketIp != null ? socketIp : "unknown";
    }

    public boolean isActive() {
        return running && !hookedServerChannels.isEmpty();
    }

    public int getHookedChannelCount() {
        return hookedServerChannels.size();
    }

    public long getTotalHttpRequests() {
        return totalHttpRequests.get();
    }

    public long getTotalPackRequests() {
        return totalPackRequests.get();
    }

    public boolean attachToServerChannel(Channel serverChannel) {
        if (serverChannel == null) return false;
        try {
            ChannelPipeline pipeline = serverChannel.pipeline();
            if (pipeline.get(ACCEPTOR_HANDLER_NAME) == null) {
                pipeline.addFirst(ACCEPTOR_HANDLER_NAME, acceptorHook);
            }
            return hookedServerChannels.add(serverChannel);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int hookMinecraftServerChannels() {
        if (plugin == null) return 0;
        int newlyHooked = 0;
        try {
            Object craftServer = Bukkit.getServer();
            if (craftServer == null) return 0;
            Method getServerMethod = findZeroArgMethod(craftServer.getClass(), "getServer");
            if (getServerMethod == null) return 0;
            Object mcServer = getServerMethod.invoke(craftServer);
            if (mcServer == null) return 0;

            Object serverConnection = findServerConnection(mcServer);
            if (serverConnection == null) return 0;

            Class<?> scClass = serverConnection.getClass();
            while (scClass != null && scClass != Object.class) {
                for (Field f : scClass.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object rawList = f.get(serverConnection);
                    if (!(rawList instanceof List<?> list)) continue;

                    String genericHint = extractListElementTypeName(f);
                    boolean isChannelFutureList = genericHint.contains("ChannelFuture");
                    boolean isConnectionList = genericHint.contains("Connection") || genericHint.contains("NetworkManager");

                    if (!isChannelFutureList && !isConnectionList) {
                        synchronized (list) {
                            if (!list.isEmpty()) {
                                Object first = list.get(0);
                                if (first instanceof ChannelFuture) {
                                    isChannelFutureList = true;
                                } else if (first instanceof ChannelHandler) {
                                    isConnectionList = true;
                                }
                            }
                        }
                    }

                    if (isConnectionList) {
                        this.serverConnectionsList = list;
                    } else if (isChannelFutureList) {
                        synchronized (list) {
                            for (Object elem : list) {
                                if (elem instanceof ChannelFuture cf && cf.channel() != null) {
                                    if (attachToServerChannel(cf.channel())) {
                                        newlyHooked++;
                                    }
                                }
                            }
                        }
                    }
                }
                scClass = scClass.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return newlyHooked;
    }

    private static String extractListElementTypeName(Field f) {
        try {
            Type gt = f.getGenericType();
            if (gt instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length == 1 && args[0] != null) {
                    return args[0].getTypeName();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static Object findServerConnection(Object mcServer) {
        Class<?> c = mcServer.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                String typeName = f.getType().getSimpleName();
                if (typeName.contains("ServerConnection")) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(mcServer);
                        if (val != null) return val;
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        c = mcServer.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains("ServerConnection")) {
                    try {
                        m.setAccessible(true);
                        Object val = m.invoke(mcServer);
                        if (val != null) return val;
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Method findZeroArgMethod(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private void detachFromMinecraftConnections(Channel child) {
        try {
            ChannelHandler packetHandler = child.pipeline().get("packet_handler");
            List<?> conns = this.serverConnectionsList;
            if (packetHandler != null && conns != null) {
                synchronized (conns) {
                    conns.remove(packetHandler);
                }
            }
        } catch (Throwable ignored) {}
    }

    private int resolveWebInternalPort() {
        if (customWebPortSupplier != null) {
            return customWebPortSupplier.getAsInt();
        }
        if (plugin == null) return -1;
        String webMode = plugin.getConfig().getString("anticheat.web-dashboard.single-port", "auto");
        if (isDisabledFlag(webMode)) return -1;
        AntiCheatManager ac = plugin.getAntiCheatManager();
        if (ac == null) return -1;
        WebDashboardService web = ac.getWebDashboard();
        if (web == null || !web.isRunning()) return -1;
        return web.getInternalPort();
    }

    private ResourcePackHost.PackHttpResponse resolvePackDownload(String method, String clientIp, String ifNoneMatch) {
        if (customPackHandler != null) {
            return customPackHandler.apply(new PackMuxQuery(method, clientIp, ifNoneMatch));
        }
        if (plugin == null) return null;
        String packMode = plugin.getConfig().getString("resource-pack.single-port", "auto");
        if (isDisabledFlag(packMode)) return null;
        if (plugin.getSettingsManager() != null && !plugin.getSettingsManager().isResourcePackEnabled()) {
            return null;
        }
        ResourcePackHost host = plugin.getResourcePackHost();
        if (host == null) return null;
        return host.prepareDirectDownload(method, clientIp, ifNoneMatch);
    }

    @ChannelHandler.Sharable
    private final class ServerAcceptorHook extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (running && msg instanceof Channel child) {
                try {
                    child.pipeline().addFirst(INJECTOR_HANDLER_NAME, new ChildPipelineInjector());
                } catch (Throwable ignored) {}
            }
            super.channelRead(ctx, msg);
        }
    }

    private final class ChildPipelineInjector extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            super.channelRegistered(ctx);
            try {
                ChannelPipeline p = ctx.pipeline();
                if (p.get(INJECTOR_HANDLER_NAME) != null) {
                    p.remove(INJECTOR_HANDLER_NAME);
                }
                if (running && p.get(MUX_HANDLER_NAME) == null) {
                    p.addFirst(MUX_HANDLER_NAME, new HttpMultiplexHandler());
                }
            } catch (Throwable ignored) {}
        }
    }

    private final class HttpMultiplexHandler extends ChannelInboundHandlerAdapter {
        private ByteBuf prefixBuf;
        private ByteBuf headerBuf;
        private boolean httpHijacked = false;
        private boolean headersDispatched = false;
        private LinkedBlockingQueue<byte[]> outboundBodyQueue;
        private volatile Socket loopbackSocket;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private ScheduledFuture<?> headerTimeoutFuture;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof ByteBuf buf)) {
                ctx.fireChannelRead(msg);
                return;
            }

            if (headersDispatched) {
                try {
                    int n = buf.readableBytes();
                    if (n > 0 && outboundBodyQueue != null && !closed.get()) {
                        byte[] chunk = new byte[n];
                        buf.readBytes(chunk);
                        if (!outboundBodyQueue.offer(chunk, 5, TimeUnit.SECONDS)) {
                            closeRelay(ctx.channel());
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(buf);
                }
                return;
            }

            if (!httpHijacked) {
                if (prefixBuf == null) {
                    int readable = buf.readableBytes();
                    if (readable == 0) {
                        ctx.fireChannelRead(msg);
                        return;
                    }
                    int matchState = matchHttpPrefix(buf);
                    if (matchState < 0) {
                        ctx.pipeline().remove(this);
                        ctx.fireChannelRead(msg);
                        return;
                    }
                    if (matchState == 0) {
                        prefixBuf = ctx.alloc().buffer(16);
                        prefixBuf.writeBytes(buf);
                        ReferenceCountUtil.release(buf);
                        return;
                    }
                    hijackForHttp(ctx);
                    consumeHeaderBytes(ctx, buf);
                    return;
                } else {
                    prefixBuf.writeBytes(buf);
                    ReferenceCountUtil.release(buf);
                    int matchState = matchHttpPrefix(prefixBuf);
                    if (matchState < 0) {
                        ByteBuf forward = prefixBuf;
                        prefixBuf = null;
                        ctx.pipeline().remove(this);
                        ctx.fireChannelRead(forward);
                        return;
                    }
                    if (matchState == 0) {
                        return;
                    }
                    ByteBuf accumulated = prefixBuf;
                    prefixBuf = null;
                    hijackForHttp(ctx);
                    consumeHeaderBytes(ctx, accumulated);
                    return;
                }
            }

            consumeHeaderBytes(ctx, buf);
        }

        private void hijackForHttp(ChannelHandlerContext ctx) {
            httpHijacked = true;
            Channel ch = ctx.channel();
            activeHttpChannels.add(ch);
            detachFromMinecraftConnections(ch);

            ChannelPipeline pipeline = ctx.pipeline();
            while (pipeline.last() != null && pipeline.last() != this) {
                try {
                    pipeline.removeLast();
                } catch (Throwable ignored) {
                    break;
                }
            }

            headerTimeoutFuture = ctx.executor().schedule(() -> {
                if (!headersDispatched && ch.isOpen()) {
                    closeRelay(ch);
                }
            }, 8, TimeUnit.SECONDS);
        }

        private void consumeHeaderBytes(ChannelHandlerContext ctx, ByteBuf chunk) {
            try {
                if (headerBuf == null) {
                    headerBuf = ctx.alloc().buffer(Math.min(MAX_HTTP_HEADER_BYTES, Math.max(512, chunk.readableBytes())));
                }
                if (headerBuf.readableBytes() + chunk.readableBytes() > MAX_HTTP_HEADER_BYTES) {
                    sendSimpleHttpAndClose(ctx, "431 Request Header Fields Too Large", "text/plain; charset=UTF-8",
                            "Request header too large".getBytes(StandardCharsets.UTF_8), null, null);
                    return;
                }
                headerBuf.writeBytes(chunk);
            } finally {
                ReferenceCountUtil.release(chunk);
            }

            int headerLen = findHeaderBlockLength(headerBuf);
            if (headerLen <= 0) {
                return;
            }

            headersDispatched = true;
            cancelHeaderTimeout();

            byte[] rawHeaderBytes = new byte[headerLen];
            headerBuf.readBytes(rawHeaderBytes);
            byte[] trailingBody = null;
            if (headerBuf.readableBytes() > 0) {
                trailingBody = new byte[headerBuf.readableBytes()];
                headerBuf.readBytes(trailingBody);
            }
            headerBuf.release();
            headerBuf = null;

            dispatchHttpRequest(ctx, rawHeaderBytes, trailingBody);
        }

        private void dispatchHttpRequest(ChannelHandlerContext ctx, byte[] rawHeaderBytes, byte[] trailingBody) {
            totalHttpRequests.incrementAndGet();
            String headerText = new String(rawHeaderBytes, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r?\n");
            if (lines.length == 0 || lines[0].isBlank()) {
                sendSimpleHttpAndClose(ctx, "400 Bad Request", "text/plain; charset=UTF-8",
                        "Bad Request".getBytes(StandardCharsets.UTF_8), null, null);
                return;
            }

            String[] reqParts = lines[0].split(" ", 3);
            if (reqParts.length < 2) {
                sendSimpleHttpAndClose(ctx, "400 Bad Request", "text/plain; charset=UTF-8",
                        "Bad Request".getBytes(StandardCharsets.UTF_8), null, null);
                return;
            }

            String method = reqParts[0].trim();
            String rawTarget = reqParts[1].trim();
            int qIdx = rawTarget.indexOf('?');
            String pathOnly = qIdx >= 0 ? rawTarget.substring(0, qIdx) : rawTarget;

            String hostHeader = null;
            String ifNoneMatch = null;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Host")) {
                    hostHeader = val;
                } else if (name.equalsIgnoreCase("If-None-Match")) {
                    ifNoneMatch = val;
                }
            }
            if (hostHeader != null) {
                observeHttpHostHeader(hostHeader);
            }

            String clientIp = extractRemoteIp(ctx.channel().remoteAddress());

            if (pathOnly.equals("/pack.zip") || pathOnly.equals("/pack/download")) {
                totalPackRequests.incrementAndGet();
                handlePackMux(ctx, method, clientIp, ifNoneMatch);
                return;
            }

            int backendPort = resolveWebInternalPort();
            if (backendPort <= 0) {
                byte[] msgBytes = "JavaStudio-Web chua duoc cai dat hoac dang tat.".getBytes(StandardCharsets.UTF_8);
                sendSimpleHttpAndClose(ctx, "503 Service Unavailable", "text/plain; charset=UTF-8", msgBytes, null, null);
                return;
            }

            byte[] rewrittenHeaders = rewriteHeadersForLoopback(lines, clientIp);
            relayToLoopback(ctx, backendPort, rewrittenHeaders, trailingBody);
        }

        private void handlePackMux(ChannelHandlerContext ctx, String method, String clientIp, String ifNoneMatch) {
            ResourcePackHost.PackHttpResponse resp = resolvePackDownload(method, clientIp, ifNoneMatch);
            if (resp == null || resp.status() == 404) {
                sendSimpleHttpAndClose(ctx, "404 Not Found", "text/plain; charset=UTF-8", new byte[0], null, null);
                return;
            }
            if (resp.status() == 405) {
                sendSimpleHttpAndClose(ctx, "405 Method Not Allowed", "text/plain; charset=UTF-8", new byte[0], null, null);
                return;
            }
            if (resp.status() == 429) {
                sendSimpleHttpAndClose(ctx, "429 Too Many Requests", "text/plain; charset=UTF-8", new byte[0], null, null);
                return;
            }
            if (resp.status() == 304) {
                sendPackHeadersAndBody(ctx, "304 Not Modified", 0, null, resp.etag(), resp.sha1());
                return;
            }
            sendPackHeadersAndBody(ctx, "200 OK", resp.contentLength(), resp.body(), resp.etag(), resp.sha1());
        }

        private void relayToLoopback(ChannelHandlerContext ctx, int backendPort, byte[] rewrittenHeaders, byte[] trailingBody) {
            Channel inbound = ctx.channel();
            LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(512);
            this.outboundBodyQueue = queue;
            queue.offer(rewrittenHeaders);
            if (trailingBody != null && trailingBody.length > 0) {
                queue.offer(trailingBody);
            }

            Thread.ofVirtual().name("JSMux-Relay").start(() -> {
                Socket sock = new Socket();
                try {
                    sock.setTcpNoDelay(true);
                    sock.connect(new InetSocketAddress("127.0.0.1", backendPort), 3000);
                    this.loopbackSocket = sock;
                    if (closed.get() || !inbound.isOpen()) {
                        closeSocketSafe(sock);
                        return;
                    }

                    OutputStream backendOut = sock.getOutputStream();
                    Thread.ofVirtual().name("JSMux-Writer").start(() -> {
                        try {
                            while (!closed.get() && !sock.isClosed()) {
                                byte[] data = queue.poll(30, TimeUnit.SECONDS);
                                if (data == null) continue;
                                if (data == EOF_MARKER) break;
                                backendOut.write(data);
                                if (queue.isEmpty()) {
                                    backendOut.flush();
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    });

                    InputStream backendIn = sock.getInputStream();
                    byte[] readBuf = new byte[8192];
                    int n;
                    while ((n = backendIn.read(readBuf)) != -1) {
                        if (!inbound.isActive()) break;
                        int spins = 0;
                        while (!inbound.isWritable() && inbound.isActive() && spins++ < 200) {
                            Thread.sleep(5);
                        }
                        ByteBuf outBuf = inbound.alloc().buffer(n);
                        outBuf.writeBytes(readBuf, 0, n);
                        inbound.writeAndFlush(outBuf);
                    }
                    if (inbound.isActive()) {
                        inbound.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                    }
                } catch (Throwable connectOrIoErr) {
                    if (this.loopbackSocket == null && inbound.isActive()) {
                        byte[] err = "502 Bad Gateway".getBytes(StandardCharsets.UTF_8);
                        inbound.eventLoop().execute(() ->
                                sendSimpleHttpAndClose(ctx, "502 Bad Gateway", "text/plain; charset=UTF-8", err, null, null));
                    } else {
                        closeRelay(inbound);
                    }
                } finally {
                    closed.set(true);
                    queue.offer(EOF_MARKER);
                    closeSocketSafe(sock);
                }
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            closeRelay(ctx.channel());
            super.channelInactive(ctx);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
            if (!httpHijacked) {
                releaseBuffers();
            }
            super.handlerRemoved(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            closeRelay(ctx.channel());
        }

        private void cancelHeaderTimeout() {
            ScheduledFuture<?> f = this.headerTimeoutFuture;
            this.headerTimeoutFuture = null;
            if (f != null) {
                f.cancel(false);
            }
        }

        private void releaseBuffers() {
            if (prefixBuf != null) {
                ReferenceCountUtil.release(prefixBuf);
                prefixBuf = null;
            }
            if (headerBuf != null) {
                ReferenceCountUtil.release(headerBuf);
                headerBuf = null;
            }
        }

        private void closeRelay(Channel ch) {
            closed.set(true);
            cancelHeaderTimeout();
            releaseBuffers();
            LinkedBlockingQueue<byte[]> q = this.outboundBodyQueue;
            if (q != null) {
                q.offer(EOF_MARKER);
            }
            closeSocketSafe(this.loopbackSocket);
            this.loopbackSocket = null;
            closeSafe(ch);
            activeHttpChannels.remove(ch);
        }
    }

    private byte[] rewriteHeadersForLoopback(String[] lines, String clientIp) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(lines[0]).append("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String keyLower = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if (keyLower.equals("x-js-mux-secret")
                        || keyLower.equals("x-js-client-ip")
                        || keyLower.equals("connection")) {
                    continue;
                }
            }
            sb.append(line).append("\r\n");
        }
        sb.append("Connection: close\r\n");
        sb.append(HEADER_MUX_SECRET).append(": ").append(muxSecret).append("\r\n");
        sb.append(HEADER_CLIENT_IP).append(": ").append(clientIp).append("\r\n");
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int matchHttpPrefix(ByteBuf buf) {
        int readable = buf.readableBytes();
        int ri = buf.readerIndex();
        byte b0 = buf.getByte(ri);
        if (b0 != 'G' && b0 != 'P' && b0 != 'H' && b0 != 'O' && b0 != 'D') {
            return -1;
        }
        boolean anyPartial = false;
        for (byte[] prefix : HTTP_PREFIXES) {
            int checkLen = Math.min(readable, prefix.length);
            boolean matches = true;
            for (int i = 0; i < checkLen; i++) {
                if (buf.getByte(ri + i) != prefix[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                if (readable >= prefix.length) {
                    return 1;
                }
                anyPartial = true;
            }
        }
        return anyPartial ? 0 : -1;
    }

    private static int findHeaderBlockLength(ByteBuf buf) {
        int start = buf.readerIndex();
        int end = buf.writerIndex();
        for (int i = start; i < end - 1; i++) {
            byte c = buf.getByte(i);
            if (c == '\r' && i + 3 < end
                    && buf.getByte(i + 1) == '\n'
                    && buf.getByte(i + 2) == '\r'
                    && buf.getByte(i + 3) == '\n') {
                return (i - start) + 4;
            }
            if (c == '\n' && buf.getByte(i + 1) == '\n') {
                return (i - start) + 2;
            }
        }
        return -1;
    }

    private static String extractRemoteIp(SocketAddress remote) {
        if (remote instanceof InetSocketAddress isa) {
            if (isa.getAddress() != null) {
                return isa.getAddress().getHostAddress();
            }
            return isa.getHostString();
        }
        return "unknown";
    }

    private static void sendSimpleHttpAndClose(
            ChannelHandlerContext ctx,
            String status,
            String contentType,
            byte[] body,
            String etag,
            String sha1
    ) {
        byte[] payload = body != null ? body : new byte[0];
        StringBuilder hdr = new StringBuilder(256);
        hdr.append("HTTP/1.1 ").append(status).append("\r\n");
        hdr.append("Content-Type: ").append(contentType).append("\r\n");
        hdr.append("Content-Length: ").append(payload.length).append("\r\n");
        hdr.append("X-Content-Type-Options: nosniff\r\n");
        if (etag != null && !etag.isEmpty()) {
            hdr.append("ETag: ").append(etag).append("\r\n");
        }
        if (sha1 != null && !sha1.isEmpty()) {
            hdr.append("X-Pack-SHA1: ").append(sha1).append("\r\n");
        }
        hdr.append("Connection: close\r\n\r\n");
        byte[] hdrBytes = hdr.toString().getBytes(StandardCharsets.US_ASCII);
        ByteBuf out = ctx.alloc().buffer(hdrBytes.length + payload.length);
        out.writeBytes(hdrBytes);
        if (payload.length > 0) {
            out.writeBytes(payload);
        }
        ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
    }

    private static void sendPackHeadersAndBody(
            ChannelHandlerContext ctx,
            String status,
            int contentLength,
            byte[] body,
            String etag,
            String sha1
    ) {
        StringBuilder hdr = new StringBuilder(320);
        hdr.append("HTTP/1.1 ").append(status).append("\r\n");
        hdr.append("Content-Type: application/zip\r\n");
        hdr.append("Content-Disposition: attachment; filename=\"javastudio-pack.zip\"\r\n");
        hdr.append("Content-Length: ").append(Math.max(0, contentLength)).append("\r\n");
        if (etag != null && !etag.isEmpty()) {
            hdr.append("ETag: ").append(etag).append("\r\n");
        }
        if (sha1 != null && !sha1.isEmpty()) {
            hdr.append("X-Pack-SHA1: ").append(sha1).append("\r\n");
        }
        hdr.append("X-Content-Type-Options: nosniff\r\n");
        hdr.append("Cache-Control: public, max-age=60\r\n");
        hdr.append("Connection: close\r\n\r\n");

        byte[] hdrBytes = hdr.toString().getBytes(StandardCharsets.US_ASCII);
        int bodyLen = body != null ? body.length : 0;
        ByteBuf out = ctx.alloc().buffer(hdrBytes.length + bodyLen);
        out.writeBytes(hdrBytes);
        if (bodyLen > 0) {
            out.writeBytes(body);
        }
        ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
    }

    private static void closeSocketSafe(Socket s) {
        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (Throwable ignored) {}
        }
    }

    private static void closeSafe(Channel ch) {
        if (ch != null && ch.isOpen()) {
            try {
                ch.close();
            } catch (Throwable ignored) {}
        }
    }
}
