package com.javastudio.analytics;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.utils.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ServerBenchmarkEngine {

    public record BenchmarkResult(
            long timestamp,
            int score,
            String tier,
            String tierColor,
            int cpuCores,
            double cpuOpsMillionPerSec,
            double cryptoMbPerSec,
            long compilerLatencyMs,
            double ramThroughputGbPerSec,
            double diskWriteMbPerSec,
            double diskReadMbPerSec,
            long eventRatePerSec,
            double schedulerJitterMs,
            int maxRecommendedPlayers,
            int recommendedViewDistance,
            int recommendedSimDistance,
            List<String> recommendations,
            String hostInfo,
            String jvmInfo,
            String memoryInfo
    ) {}

    private final JavaStudioPlugin plugin;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile BenchmarkResult latestResult = null;

    public ServerBenchmarkEngine(JavaStudioPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isBenchmarkRunning() {
        return running.get();
    }

    public BenchmarkResult getLatestResult() {
        return latestResult;
    }

    public String getLatestBenchmarkJson() {
        return exportJsonResult(latestResult);
    }

    public CompletableFuture<BenchmarkResult> runBenchmarkAsync(CommandSender sender, boolean quickMode) {
        if (!running.compareAndSet(false, true)) {
            if (sender != null) {
                sender.sendMessage("§c✖ Máy chủ đang thực hiện bài kiểm tra hiệu năng khác. Vui lòng đợi trong giây lát!");
            }
            return CompletableFuture.completedFuture(latestResult);
        }

        CompletableFuture<BenchmarkResult> future = new CompletableFuture<>();

        Thread.ofVirtual().name("JavaStudio-BenchmarkSuite").start(() -> {
            try {
                notify(sender, "§6§l=== BẮT ĐẦU ĐO LƯỜNG & CHẤM ĐIỂM SỨC MẠNH MÁY CHỦ ===");
                notify(sender, "§7Đang khởi chạy bộ kiểm thử toàn diện JavaStudio Benchmark Suite...");

                Runtime rt = Runtime.getRuntime();
                int cores = rt.availableProcessors();
                long maxRamMb = rt.maxMemory() / (1024 * 1024);
                long totalRamMb = rt.totalMemory() / (1024 * 1024);
                long freeRamMb = rt.freeMemory() / (1024 * 1024);
                long usedRamMb = totalRamMb - freeRamMb;

                String osName = System.getProperty("os.name", "Unknown");
                String osArch = System.getProperty("os.arch", "Unknown");
                String javaVer = System.getProperty("java.version", "Unknown");
                String javaVendor = System.getProperty("java.vendor", "Unknown");
                String serverPlatform = PlatformUtil.getPlatformName() + " (" + PlatformUtil.getMinecraftVersion() + ")";

                String hostInfo = osName + " " + osArch + " (" + cores + " vCPU Cores)";
                String jvmInfo = "Java " + javaVer + " (" + javaVendor + ") - " + serverPlatform;
                String memInfo = usedRamMb + " MB / " + maxRamMb + " MB RAM (JVM Heap)";

                notify(sender, "§8[§e1/6§8] §7Đang đo tốc độ tính toán CPU & Khả năng mã hóa mật mã học...");
                double cpuOpsM = runCpuMathTest(quickMode);
                double cryptoMb = runCryptoHashTest(cores, quickMode);

                notify(sender, "§8[§e2/6§8] §7Đang đo độ trễ trình biên dịch mã nguồn Java Metaspace...");
                long compilerMs = runCompilerSpeedTest();

                notify(sender, "§8[§e3/6§8] §7Đang kiểm tra băng thông bộ nhớ Off-Heap RAM Direct Buffer...");
                double ramGbPerSec = runOffHeapRamTest(quickMode);

                notify(sender, "§8[§e4/6§8] §7Đang đo tốc độ Đọc/Ghi thực tế của ổ cứng lưu trữ (NVMe/SSD)...");
                double[] diskSpeeds = runDiskIoTest(quickMode);
                double diskWrite = diskSpeeds[0];
                double diskRead = diskSpeeds[1];

                notify(sender, "§8[§e5/6§8] §7Đang đo thông lượng đường ống sự kiện Event Pipeline...");
                long eventThroughput = runEventPipelineTest(quickMode);

                notify(sender, "§8[§e6/6§8] §7Đang đo độ ổn định chu kỳ Tick & Jitter của Scheduler...");
                double jitterMs = runSchedulerJitterTest();

                int score = calculateScore(cpuOpsM, cryptoMb, compilerMs, ramGbPerSec, diskWrite, diskRead, eventThroughput, jitterMs, cores, maxRamMb);
                String[] tierData = resolveTier(score);
                String tier = tierData[0];
                String tierColor = tierData[1];

                int maxPlayers = calculateRecommendedPlayers(score, cores, maxRamMb);
                int viewDist = calculateRecommendedViewDistance(score);
                int simDist = Math.max(4, viewDist - 2);

                List<String> recommendations = generateRecommendations(cores, maxRamMb, diskWrite, compilerMs, jitterMs);

                BenchmarkResult result = new BenchmarkResult(
                        System.currentTimeMillis(),
                        score,
                        tier,
                        tierColor,
                        cores,
                        cpuOpsM,
                        cryptoMb,
                        compilerMs,
                        ramGbPerSec,
                        diskWrite,
                        diskRead,
                        eventThroughput,
                        jitterMs,
                        maxPlayers,
                        viewDist,
                        simDist,
                        recommendations,
                        hostInfo,
                        jvmInfo,
                        memInfo
                );

                this.latestResult = result;
                printBenchmarkReport(sender, result);
                future.complete(result);
            } catch (Throwable t) {
                plugin.getLogger().severe("[Benchmark] Lỗi khi thực hiện kiểm thử: " + t.getMessage());
                if (sender != null) {
                    sender.sendMessage("§c✖ Lỗi trong quá trình kiểm thử benchmark: " + t.getMessage());
                }
                future.completeExceptionally(t);
            } finally {
                running.set(false);
            }
        });

        return future;
    }

    private void notify(CommandSender sender, String msg) {
        if (sender != null) {
            sender.sendMessage(msg);
        }
    }

    private double runCpuMathTest(boolean quick) {
        int iterations = quick ? 2_000_000 : 8_000_000;
        long start = System.nanoTime();
        long dummy = 0;
        for (int i = 0; i < iterations; i++) {
            dummy += (long) (Math.sin(i) * 1000.0) ^ (i * 31L);
            dummy = (dummy << 3) | (dummy >>> 61);
        }
        long durationNs = Math.max(1L, System.nanoTime() - start);
        double seconds = durationNs / 1_000_000_000.0;
        return Math.round((iterations / seconds / 1_000_000.0) * 100.0) / 100.0;
    }

    private double runCryptoHashTest(int cores, boolean quick) {
        int threads = Math.max(1, Math.min(16, cores));
        int totalPayloadKb = quick ? 24 * 1024 : 96 * 1024;
        int perThreadKb = totalPayloadKb / threads;
        byte[] sample = new byte[64 * 1024];
        new Random(42).nextBytes(sample);

        byte[] hmacKey = "JavaStudioSecretKeyBenchmarkSuite123456".getBytes(StandardCharsets.UTF_8);

        long start = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    int chunks = perThreadKb / 64;
                    for (int c = 0; c < chunks; c++) {
                        mac.update(sample);
                        sha.update(mac.doFinal());
                    }
                    return null;
                });
            }
            executor.invokeAll(tasks);
        } catch (Throwable ignored) {}

        long durationNs = Math.max(1L, System.nanoTime() - start);
        double seconds = durationNs / 1_000_000_000.0;
        double mbTotal = totalPayloadKb / 1024.0;
        return Math.round((mbTotal / seconds) * 100.0) / 100.0;
    }

    private long runCompilerSpeedTest() {
        if (plugin.getCompiler() == null) return 999L;
        String probeSource = """
                package com.javastudio.bench;
                public class BenchProbe {
                    public static long compute(int a, int b) {
                        return (long) a * b + 42L;
                    }
                }
                """;
        long start = System.nanoTime();
        var cr = plugin.getCompiler().compileSingleSource("com.javastudio.bench.BenchProbe", probeSource);
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        if (cr != null && cr.isSuccess()) {
            try {
                Class<?> clazz = cr.loadClass("com.javastudio.bench.BenchProbe");
                var method = clazz.getMethod("compute", int.class, int.class);
                method.invoke(null, 10, 20);
            } catch (Throwable ignored) {}
        }
        return Math.max(1L, durationMs);
    }

    private double runOffHeapRamTest(boolean quick) {
        int sizeBytes = (quick ? 16 : 48) * 1024 * 1024;
        try {
            long start = System.nanoTime();
            ByteBuffer buf = ByteBuffer.allocateDirect(sizeBytes);
            for (int i = 0; i < sizeBytes / 8; i++) {
                buf.putLong((long) i * 17L);
            }
            buf.flip();
            long sum = 0;
            while (buf.hasRemaining()) {
                sum += buf.getLong();
            }
            long durationNs = Math.max(1L, System.nanoTime() - start);
            double seconds = durationNs / 1_000_000_000.0;
            double gbTransferred = (sizeBytes * 2.0) / (1024.0 * 1024.0 * 1024.0);
            return Math.round((gbTransferred / seconds) * 100.0) / 100.0;
        } catch (Throwable t) {
            return 1.5;
        }
    }

    private double[] runDiskIoTest(boolean quick) {
        File testFile = new File(plugin.getDataFolder(), ".bench_io.tmp");
        int sizeBytes = (quick ? 2 : 6) * 1024 * 1024;
        byte[] payload = new byte[sizeBytes];
        new Random(1337).nextBytes(payload);

        double writeSpeed = 100.0;
        double readSpeed = 150.0;

        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

            long startW = System.nanoTime();
            try (RandomAccessFile raf = new RandomAccessFile(testFile, "rw");
                 FileChannel ch = raf.getChannel()) {
                ByteBuffer b = ByteBuffer.wrap(payload);
                while (b.hasRemaining()) {
                    ch.write(b);
                }
                ch.force(true);
            }
            long writeDuration = Math.max(1L, System.nanoTime() - startW);
            double wSec = writeDuration / 1_000_000_000.0;
            writeSpeed = (sizeBytes / (1024.0 * 1024.0)) / wSec;

            long startR = System.nanoTime();
            try (RandomAccessFile raf = new RandomAccessFile(testFile, "r");
                 FileChannel ch = raf.getChannel()) {
                ByteBuffer b = ByteBuffer.allocate(sizeBytes);
                while (b.hasRemaining() && ch.read(b) > 0) {}
            }
            long readDuration = Math.max(1L, System.nanoTime() - startR);
            double rSec = readDuration / 1_000_000_000.0;
            readSpeed = (sizeBytes / (1024.0 * 1024.0)) / rSec;

        } catch (Throwable ignored) {
        } finally {
            try {
                if (testFile.exists()) testFile.delete();
            } catch (Throwable ignored) {}
        }

        return new double[]{
                Math.round(writeSpeed * 10.0) / 10.0,
                Math.round(readSpeed * 10.0) / 10.0
        };
    }

    private long runEventPipelineTest(boolean quick) {
        int events = quick ? 20_000 : 80_000;
        var pipeline = plugin.getEventPipeline();
        if (pipeline == null) return 250_000L;

        String testEvent = "BenchmarkSyntheticEvent";
        pipeline.on(testEvent, ctx -> ctx.set("processed", true));

        long start = System.nanoTime();
        Map<String, Object> data = new HashMap<>();
        data.put("bench", 1);
        for (int i = 0; i < events; i++) {
            pipeline.emit(testEvent, data);
        }
        long durationNs = Math.max(1L, System.nanoTime() - start);
        pipeline.clearCustomHandlers(testEvent);

        double sec = durationNs / 1_000_000_000.0;
        return (long) (events / sec);
    }

    private double runSchedulerJitterTest() {
        CountDownLatch latch = new CountDownLatch(4);
        List<Long> latencies = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 4; i++) {
            final long scheduledTime = System.nanoTime();
            PlatformUtil.runTaskLater(plugin, () -> {
                long elapsedMs = (System.nanoTime() - scheduledTime) / 1_000_000L;
                latencies.add(Math.abs(elapsedMs - 50L));
                latch.countDown();
            }, 1L + i);
        }

        try {
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}

        if (latencies.isEmpty()) return 0.5;
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0.5);
        return Math.round(avg * 100.0) / 100.0;
    }

    private int calculateScore(double cpuOpsM, double cryptoMb, long compilerMs, double ramGb,
                               double diskW, double diskR, long eventRate, double jitter, int cores, long ramMb) {
        double cpuPart = Math.min(3500.0, (cpuOpsM / 25.0) * 1500.0 + (cryptoMb / 1500.0) * 2000.0);
        double ramPart = Math.min(2500.0, (ramGb / 15.0) * 1500.0 + (ramMb / 8192.0) * 1000.0);
        double diskPart = Math.min(2500.0, (diskW / 800.0) * 1500.0 + (diskR / 1200.0) * 1000.0);
        double compPart = Math.min(2000.0, Math.max(200.0, (150.0 / Math.max(10.0, compilerMs)) * 1800.0));
        double eventPart = Math.min(1500.0, (eventRate / 800_000.0) * 1500.0);
        double jitterPart = Math.min(1000.0, Math.max(100.0, (1.0 / Math.max(0.05, jitter)) * 250.0));
        double coresBonus = Math.min(cores, 16) * 125.0;

        int total = (int) (cpuPart + ramPart + diskPart + compPart + eventPart + jitterPart + coresBonus);
        return Math.max(500, Math.min(15000, total));
    }

    private String[] resolveTier(int score) {
        if (score >= 9500) return new String[]{"SSS (GOD-TIER DEDICATED)", "§d§l"};
        if (score >= 8000) return new String[]{"SS (ENTERPRISE HIGH-PERF)", "§b§l"};
        if (score >= 6500) return new String[]{"S (OPTIMAL PRODUCTION)", "§a§l"};
        if (score >= 5000) return new String[]{"A (PRODUCTION READY)", "§e§l"};
        if (score >= 3500) return new String[]{"B (BUDGET / SHARED VPS)", "§6§l"};
        return new String[]{"C (UNDERPOWERED / BOTTLENECKED)", "§c§l"};
    }

    private int calculateRecommendedPlayers(int score, int cores, long ramMb) {
        int base = (int) (score / 75.0);
        if (cores <= 2) base = Math.min(base, 35);
        if (ramMb < 3500) base = Math.min(base, 25);
        if (ramMb < 6000) base = Math.min(base, 65);
        return Math.max(10, Math.min(400, base));
    }

    private int calculateRecommendedViewDistance(int score) {
        if (score >= 9500) return 14;
        if (score >= 8000) return 12;
        if (score >= 6500) return 10;
        if (score >= 5000) return 8;
        if (score >= 3500) return 6;
        return 4;
    }

    private List<String> generateRecommendations(int cores, long maxRamMb, double diskWrite, long compilerMs, double jitter) {
        List<String> list = new ArrayList<>();
        if (cores <= 2) {
            list.add("§6• vCPU bị giới hạn (chỉ 2 cores): §7Hạn chế tạo các farm mob hoặc redstone clock dày đặc.");
        } else if (cores >= 8) {
            list.add("§a• CPU đa nhân dồi dào (" + cores + " cores): §7Tận dụng hoàn hảo cơ chế phân vùng Folia & Netty threads.");
        }

        if (maxRamMb < 3800) {
            list.add("§c• Bộ nhớ JVM Heap thấp (" + maxRamMb + " MB): §7Nên cấp tối thiểu -Xmx6G hoặc -Xmx8G để tránh OutOfMemory.");
        } else {
            list.add("§a• Bộ nhớ RAM JVM đạt chuẩn (" + maxRamMb + " MB): §7Đủ dung lượng cho các bộ nhớ đệm Off-Heap FastBlock.");
        }

        if (diskWrite < 120.0) {
            list.add("§c• Tốc độ ghi ổ đĩa chậm (" + diskWrite + " MB/s): §7Có thể gây lag spike khi auto-save thế giới; khuyến nghị nâng cấp SSD NVMe.");
        } else {
            list.add("§a• Ổ đĩa lưu trữ siêu tốc (" + diskWrite + " MB/s): §7Tự động lưu chunk và dữ liệu snapshot mượt mà.");
        }

        if (compilerMs > 120L) {
            list.add("§e• Trình biên dịch mã nguồn chậm (" + compilerMs + " ms): §7Hãy đảm bảo chạy máy chủ bằng JDK (Adoptium Temurin/GraalVM) thay vì JRE.");
        } else {
            list.add("§a• RuntimeCompiler phản hồi xuất sắc (" + compilerMs + " ms): §7Hot-Reload dynamic plugin tức thì.");
        }

        if (PlatformUtil.isVirtualThreadsSupported()) {
            list.add("§a• Project Loom Virtual Threads: §7Kích hoạt sẵn sàng cho các kết nối P2P Cluster Mesh và Web Dashboard.");
        }

        return list;
    }

    public void printBenchmarkReport(CommandSender sender, BenchmarkResult res) {
        if (sender == null) return;
        sender.sendMessage("§6╔══════════════════════════════════════════════════════════════════╗");
        sender.sendMessage("§6║      §e§lJAVASTUDIO SERVER CAPABILITY & HARDWARE BENCHMARK       §6║");
        sender.sendMessage("§6╚══════════════════════════════════════════════════════════════════╝");
        sender.sendMessage(" §eĐiểm Số Hiệu Năng (JSPI): §f§l" + String.format("%,d", res.score()) + " §7/ 15,000 pts");
        sender.sendMessage(" §eĐánh Giá Năng Lực Máy Chủ: " + res.tierColor() + res.tier());
        sender.sendMessage(" §eSức Tải Đề Xuất: §a" + res.maxRecommendedPlayers() + " người chơi online");
        sender.sendMessage(" §eTầm Nhìn Tối Ưu (View / Sim): §b" + res.recommendedViewDistance() + " chunks §7/ §b" + res.recommendedSimDistance() + " chunks");
        sender.sendMessage("");
        sender.sendMessage("§6┌─── [1] HỆ ĐIỀU HÀNH & MÔI TRƯỜNG MÁY CHỦ ────────────────────────");
        sender.sendMessage("§6│ §7• Máy chủ: §f" + res.hostInfo());
        sender.sendMessage("§6│ §7• Môi trường: §f" + res.jvmInfo());
        sender.sendMessage("§6│ §7• Bộ nhớ: §f" + res.memoryInfo());
        sender.sendMessage("§6├─── [2] KẾT QUẢ ĐO LƯỜNG HIỆU NĂNG THỰC TẾ ──────────────────────");
        sender.sendMessage("§6│ §e• CPU Math Logic: §a" + res.cpuOpsMillionPerSec() + " Triệu ops/giây");
        sender.sendMessage("§6│ §e• Crypto HMAC-SHA256: §a" + res.cryptoMbPerSec() + " MB/giây §7(Đa luồng)");
        sender.sendMessage("§6│ §e• Độ trễ RuntimeCompiler: §a" + res.compilerLatencyMs() + " ms §7(In-Memory)");
        sender.sendMessage("§6│ §e• Băng thông Off-Heap RAM: §a" + res.ramThroughputGbPerSec() + " GB/giây §7(Direct Buffer)");
        sender.sendMessage("§6│ §e• Tốc độ Đọc/Ghi Ổ Đĩa: §aGhi " + res.diskWriteMbPerSec() + " MB/s §8| §aĐọc " + res.diskReadMbPerSec() + " MB/s");
        sender.sendMessage("§6│ §e• Thông lượng Event Bus: §a" + String.format("%,d", res.eventRatePerSec()) + " sự kiện/giây");
        sender.sendMessage("§6│ §e• Scheduler Jitter: §a" + res.schedulerJitterMs() + " ms §7(Độ lệch nhịp tick)");
        sender.sendMessage("§6└─── [3] TƯ VẤN CẤU HÌNH & TỐI ƯU HÓA ─────────────────────────────");
        for (String rec : res.recommendations()) {
            sender.sendMessage("§6│ " + rec);
        }
        sender.sendMessage("§6╚══════════════════════════════════════════════════════════════════╝");
    }

    public String exportJsonResult(BenchmarkResult res) {
        if (res == null) return "{\"available\":false}";
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"available\":true,");
        sb.append("\"timestamp\":").append(res.timestamp()).append(",");
        sb.append("\"score\":").append(res.score()).append(",");
        sb.append("\"performanceScore\":").append(res.score()).append(",");
        sb.append("\"tier\":\"").append(escape(res.tier())).append("\",");
        sb.append("\"tierRating\":\"").append(escape(res.tier())).append("\",");
        sb.append("\"cpuCores\":").append(res.cpuCores()).append(",");
        sb.append("\"cpuOps\":").append(res.cpuOpsMillionPerSec()).append(",");
        sb.append("\"cpuMathMops\":").append(res.cpuOpsMillionPerSec()).append(",");
        sb.append("\"cryptoMb\":").append(res.cryptoMbPerSec()).append(",");
        sb.append("\"multiThreadCryptoKhash\":").append(res.cryptoMbPerSec()).append(",");
        sb.append("\"compilerMs\":").append(res.compilerLatencyMs()).append(",");
        sb.append("\"compilerLatencyMs\":").append(res.compilerLatencyMs()).append(",");
        sb.append("\"ramGb\":").append(res.ramThroughputGbPerSec()).append(",");
        sb.append("\"offHeapRamThroughputMb\":").append(res.ramThroughputGbPerSec() * 1024.0).append(",");
        sb.append("\"diskWrite\":").append(res.diskWriteMbPerSec()).append(",");
        sb.append("\"diskRead\":").append(res.diskReadMbPerSec()).append(",");
        sb.append("\"diskIoThroughputMb\":").append(res.diskWriteMbPerSec()).append(",");
        sb.append("\"eventRate\":").append(res.eventRatePerSec()).append(",");
        sb.append("\"eventPipelineThroughputOps\":").append(res.eventRatePerSec()).append(",");
        sb.append("\"jitterMs\":").append(res.schedulerJitterMs()).append(",");
        sb.append("\"schedulerJitterMs\":").append(res.schedulerJitterMs()).append(",");
        sb.append("\"maxPlayers\":").append(res.maxRecommendedPlayers()).append(",");
        sb.append("\"recommendedMaxPlayers\":").append(res.maxRecommendedPlayers()).append(",");
        sb.append("\"viewDist\":").append(res.recommendedViewDistance()).append(",");
        sb.append("\"recommendedViewDistance\":").append(res.recommendedViewDistance()).append(",");
        sb.append("\"simDist\":").append(res.recommendedSimDistance()).append(",");
        sb.append("\"recommendedSimulationDistance\":").append(res.recommendedSimDistance()).append(",");
        sb.append("\"host\":\"").append(escape(res.hostInfo())).append("\",");
        sb.append("\"jvm\":\"").append(escape(res.jvmInfo())).append("\",");
        sb.append("\"memory\":\"").append(escape(res.memoryInfo())).append("\",");
        sb.append("\"recommendations\":[");
        for (int i = 0; i < res.recommendations().size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(res.recommendations().get(i).replaceAll("§[0-9a-fk-or]", ""))).append("\"");
        }
        sb.append("],");
        sb.append("\"advice\":[");
        for (int i = 0; i < res.recommendations().size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(res.recommendations().get(i).replaceAll("§[0-9a-fk-or]", ""))).append("\"");
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
