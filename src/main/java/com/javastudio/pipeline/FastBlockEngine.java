package com.javastudio.pipeline;

import com.javastudio.utils.FoliaAutoRegionizer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class FastBlockEngine {

    private static final AtomicLong TOTAL_OFF_HEAP_BYTES = new AtomicLong(0);

    private final Plugin plugin;
    private final Map<String, ArenaSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, CuboidSelection> selections = new ConcurrentHashMap<>();

    private final Map<UUID, Deque<ArenaSnapshot>> undoStacks = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<ArenaSnapshot>> redoStacks = new ConcurrentHashMap<>();

    public FastBlockEngine(Plugin plugin) {
        this.plugin = plugin;
    }

    public void executeInChunkRegion(World world, int chunkX, int chunkZ, Runnable task) {
        FoliaAutoRegionizer.runAtChunk(plugin, world, chunkX, chunkZ, task);
    }

    public CompletableFuture<BatchResult> fillCuboid(World world, Location pos1, Location pos2, Material material) {
        return replaceCuboid(world, pos1, pos2, null, material.createBlockData());
    }

    public CompletableFuture<BatchResult> replaceCuboid(World world, Location pos1, Location pos2,
                                                        Material fromFilter, BlockData targetData) {
        long startNano = System.nanoTime();

        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int minY = Math.max(world.getMinHeight(), Math.min(pos1.getBlockY(), pos2.getBlockY()));
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int maxY = Math.min(world.getMaxHeight() - 1, Math.max(pos1.getBlockY(), pos2.getBlockY()));
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

        AtomicInteger modifiedBlocks = new AtomicInteger(0);

        return FoliaAutoRegionizer.runInCuboid(plugin, world, minX, minY, minZ, maxX, maxY, maxZ,
                (chunk, startX, endX, minY1, maxY1, startZ, endZ) -> {
                    int count = 0;
                    for (int y = minY1; y <= maxY1; y++) {
                        for (int x = startX; x <= endX; x++) {
                            for (int z = startZ; z <= endZ; z++) {
                                Block block = chunk.getBlock(x & 15, y, z & 15);
                                if (fromFilter == null || block.getType() == fromFilter) {
                                    if (!block.getBlockData().matches(targetData)) {
                                        block.setBlockData(targetData, false);
                                        count++;
                                    }
                                }
                            }
                        }
                    }
                    if (count > 0) {
                        modifiedBlocks.addAndGet(count);
                        world.refreshChunk(chunk.getX(), chunk.getZ());
                    }
                }).thenApply(totalChunks -> {
            double elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0;
            return new BatchResult(modifiedBlocks.get(), totalChunks, elapsedMs);
        });
    }

    public CompletableFuture<ArenaSnapshot> createSnapshot(String id, World world, Location pos1, Location pos2) {
        return createArenaSnapshot(id, world, pos1, pos2);
    }

    public CompletableFuture<ArenaSnapshot> createArenaSnapshot(String id, World world, Location pos1, Location pos2) {
        return captureRegionSnapshot(id, world, pos1, pos2, true);
    }

    private CompletableFuture<ArenaSnapshot> captureRegionSnapshot(String id, World world, Location pos1, Location pos2, boolean registerGlobal) {
        CompletableFuture<ArenaSnapshot> future = new CompletableFuture<>();
        long startNano = System.nanoTime();

        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int minY = Math.max(world.getMinHeight(), Math.min(pos1.getBlockY(), pos2.getBlockY()));
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int maxY = Math.min(world.getMaxHeight() - 1, Math.max(pos1.getBlockY(), pos2.getBlockY()));
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

        int minCx = minX >> 4;
        int maxCx = maxX >> 4;
        int minCz = minZ >> 4;
        int maxCz = maxZ >> 4;

        Map<Long, CompressedChunkSlice> chunkSlices = new ConcurrentHashMap<>();
        int totalChunks = (maxCx - minCx + 1) * (maxCz - minCz + 1);
        AtomicInteger remaining = new AtomicInteger(totalChunks);
        AtomicInteger totalBlocksRecorded = new AtomicInteger(0);

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                final int chunkX = cx;
                final int chunkZ = cz;

                int startX = Math.max(minX, chunkX << 4);
                int endX = Math.min(maxX, (chunkX << 4) + 15);
                int startZ = Math.max(minZ, chunkZ << 4);
                int endZ = Math.min(maxZ, (chunkZ << 4) + 15);

                FoliaAutoRegionizer.runAtChunkAsync(plugin, world, chunkX, chunkZ, chunk -> {
                    try {
                        CompressedChunkSlice slice = compressChunkSlice(chunk, startX, endX, minY, maxY, startZ, endZ);
                        chunkSlices.put(chunkKey(chunkX, chunkZ), slice);
                        totalBlocksRecorded.addAndGet(slice.blockCount);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    } finally {
                        if (remaining.decrementAndGet() == 0 && !future.isDone()) {
                            double elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0;
                            ArenaSnapshot snap = new ArenaSnapshot(
                                    id, world.getName(),
                                    minX, minY, minZ, maxX, maxY, maxZ,
                                    totalBlocksRecorded.get(), chunkSlices, elapsedMs
                            );
                            if (registerGlobal) {
                                ArenaSnapshot old = snapshots.put(id.toLowerCase(Locale.ROOT), snap);
                                if (old != null) old.release();
                            }
                            future.complete(snap);
                        }
                    }
                });
            }
        }
        return future;
    }

    public CompletableFuture<BatchResult> restoreSnapshot(String id) {
        ArenaSnapshot snap = snapshots.get(id.toLowerCase(Locale.ROOT));
        if (snap == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Không tồn tại snapshot: " + id));
        }
        return restoreSnapshotDirect(snap);
    }

    public CompletableFuture<BatchResult> restoreSnapshotDirect(ArenaSnapshot snap) {
        World world = Bukkit.getWorld(snap.worldName);
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Thế giới không còn tồn tại: " + snap.worldName));
        }

        CompletableFuture<BatchResult> future = new CompletableFuture<>();
        long startNano = System.nanoTime();
        int totalChunks = snap.chunks.size();
        AtomicInteger remaining = new AtomicInteger(totalChunks);
        AtomicInteger changedBlocks = new AtomicInteger(0);

        for (Map.Entry<Long, CompressedChunkSlice> entry : snap.chunks.entrySet()) {
            long key = entry.getKey();
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            CompressedChunkSlice slice = entry.getValue();

            FoliaAutoRegionizer.runAtChunkAsync(plugin, world, chunkX, chunkZ, chunk -> {
                try {
                    int modified = decompressAndApplySlice(chunk, slice);
                    if (modified > 0) {
                        changedBlocks.addAndGet(modified);
                        world.refreshChunk(chunkX, chunkZ);
                    }
                } catch (Exception e) {
                    future.completeExceptionally(e);
                } finally {
                    if (remaining.decrementAndGet() == 0 && !future.isDone()) {
                        double elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0;
                        future.complete(new BatchResult(changedBlocks.get(), totalChunks, elapsedMs));
                    }
                }
            });
        }
        return future;
    }

    public CompletableFuture<ArenaSnapshot> captureTempSnapshot(String id, World world, Location pos1, Location pos2) {
        return captureRegionSnapshot(id, world, pos1, pos2, false);
    }

    public void pushUndo(UUID playerUuid, ArenaSnapshot snapshot) {
        if (playerUuid == null || snapshot == null) return;
        Deque<ArenaSnapshot> stack = undoStacks.computeIfAbsent(playerUuid, k -> new ArrayDeque<>());
        synchronized (stack) {
            stack.push(snapshot);
            while (stack.size() > 15) {
                ArenaSnapshot evicted = stack.removeLast();
                if (evicted != null) evicted.release();
            }
        }
    }

    public CompletableFuture<BatchResult> undo(UUID playerUuid) {
        Deque<ArenaSnapshot> stack = undoStacks.get(playerUuid);
        if (stack == null) {
            return CompletableFuture.completedFuture(new BatchResult(0, 0, 0));
        }
        ArenaSnapshot prev;
        synchronized (stack) {
            if (stack.isEmpty()) return CompletableFuture.completedFuture(new BatchResult(0, 0, 0));
            prev = stack.pop();
        }
        World world = Bukkit.getWorld(prev.worldName);
        if (world != null) {
            Location p1 = new Location(world, prev.minX, prev.minY, prev.minZ);
            Location p2 = new Location(world, prev.maxX, prev.maxY, prev.maxZ);
            captureRegionSnapshot("redo_" + System.currentTimeMillis(), world, p1, p2, false).thenAccept(redoSnap -> {
                Deque<ArenaSnapshot> rStack = redoStacks.computeIfAbsent(playerUuid, k -> new ArrayDeque<>());
                synchronized (rStack) {
                    rStack.push(redoSnap);
                    while (rStack.size() > 15) {
                        ArenaSnapshot ev = rStack.removeLast();
                        if (ev != null) ev.release();
                    }
                }
            });
        }
        return restoreSnapshotDirect(prev).whenComplete((r, e) -> prev.release());
    }

    public CompletableFuture<BatchResult> redo(UUID playerUuid) {
        Deque<ArenaSnapshot> stack = redoStacks.get(playerUuid);
        if (stack == null) {
            return CompletableFuture.completedFuture(new BatchResult(0, 0, 0));
        }
        ArenaSnapshot next;
        synchronized (stack) {
            if (stack.isEmpty()) return CompletableFuture.completedFuture(new BatchResult(0, 0, 0));
            next = stack.pop();
        }
        return restoreSnapshotDirect(next).whenComplete((r, e) -> next.release());
    }

    public void saveSnapshotToFile(String snapshotId, File file) throws IOException {
        ArenaSnapshot snap = getSnapshot(snapshotId);
        if (snap == null) throw new IllegalArgumentException("Không tìm thấy snapshot: " + snapshotId);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();

        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            dos.writeInt(0x4A424C4B);
            dos.writeInt(1);
            dos.writeUTF(snap.id);
            dos.writeUTF(snap.worldName);
            dos.writeInt(snap.minX);
            dos.writeInt(snap.minY);
            dos.writeInt(snap.minZ);
            dos.writeInt(snap.maxX);
            dos.writeInt(snap.maxY);
            dos.writeInt(snap.maxZ);
            dos.writeInt(snap.totalBlocks);
            dos.writeDouble(snap.captureTimeMs);
            dos.writeInt(snap.chunks.size());

            for (Map.Entry<Long, CompressedChunkSlice> e : snap.chunks.entrySet()) {
                dos.writeLong(e.getKey());
                CompressedChunkSlice s = e.getValue();
                dos.writeInt(s.startX);
                dos.writeInt(s.endX);
                dos.writeInt(s.minY);
                dos.writeInt(s.maxY);
                dos.writeInt(s.startZ);
                dos.writeInt(s.endZ);
                dos.writeInt(s.blockCount);
                String[] palette = s.readPalette();
                dos.writeInt(palette.length);
                for (String p : palette) {
                    dos.writeUTF(p);
                }
                byte[] cBytes = s.getCompressedBytes();
                dos.writeInt(cBytes.length);
                dos.write(cBytes);
            }
        }
    }

    public ArenaSnapshot loadSnapshotFromFile(File file, String overrideId) throws IOException {
        if (!file.exists()) throw new FileNotFoundException("Tệp không tồn tại: " + file.getPath());

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int magic = dis.readInt();
            if (magic != 0x4A424C4B) throw new IOException("Định dạng file FastBlock không hợp lệ!");
            dis.readInt();
            String savedId = dis.readUTF();
            String id = (overrideId != null && !overrideId.isBlank()) ? overrideId : savedId;
            String worldName = dis.readUTF();
            int minX = dis.readInt();
            int minY = dis.readInt();
            int minZ = dis.readInt();
            int maxX = dis.readInt();
            int maxY = dis.readInt();
            int maxZ = dis.readInt();
            int totalBlocks = dis.readInt();
            double captureTimeMs = dis.readDouble();
            int chunkCount = dis.readInt();

            Map<Long, CompressedChunkSlice> chunks = new HashMap<>(chunkCount);
            for (int i = 0; i < chunkCount; i++) {
                long key = dis.readLong();
                int sX = dis.readInt();
                int eX = dis.readInt();
                int mY = dis.readInt();
                int mxY = dis.readInt();
                int sZ = dis.readInt();
                int eZ = dis.readInt();
                int bCount = dis.readInt();
                int palLen = dis.readInt();
                String[] palette = new String[palLen];
                for (int p = 0; p < palLen; p++) {
                    palette[p] = dis.readUTF();
                }
                int dataLen = dis.readInt();
                byte[] data = new byte[dataLen];
                dis.readFully(data);
                chunks.put(key, new CompressedChunkSlice(sX, eX, mY, mxY, sZ, eZ, bCount, palette, data));
            }

            ArenaSnapshot snap = new ArenaSnapshot(id, worldName, minX, minY, minZ, maxX, maxY, maxZ, totalBlocks, chunks, captureTimeMs);
            ArenaSnapshot old = snapshots.put(id.toLowerCase(Locale.ROOT), snap);
            if (old != null) old.release();
            return snap;
        }
    }

    private CompressedChunkSlice compressChunkSlice(Chunk chunk, int startX, int endX,
                                                    int minY, int maxY, int startZ, int endZ) throws Exception {
        List<String> paletteList = new ArrayList<>();
        Map<String, Short> paletteIndexMap = new HashMap<>();

        byte[] compressedData;
        int count = 0;

        try (ByteArrayOutputStream rawBos = new ByteArrayOutputStream()) {
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream deflaterStream = new DeflaterOutputStream(rawBos, deflater);
                 DataOutputStream dos = new DataOutputStream(deflaterStream)) {
                for (int y = minY; y <= maxY; y++) {
                    for (int x = startX; x <= endX; x++) {
                        for (int z = startZ; z <= endZ; z++) {
                            Block block = chunk.getBlock(x & 15, y, z & 15);
                            String dataStr = block.getBlockData().getAsString();

                            Short idx = paletteIndexMap.get(dataStr);
                            if (idx == null) {
                                idx = (short) paletteList.size();
                                paletteList.add(dataStr);
                                paletteIndexMap.put(dataStr, idx);
                            }
                            dos.writeShort(idx);
                            count++;
                        }
                    }
                }
            } finally {
                deflater.end();
            }
            compressedData = rawBos.toByteArray();
        }

        return new CompressedChunkSlice(
                startX, endX, minY, maxY, startZ, endZ,
                count, paletteList.toArray(new String[0]), compressedData
        );
    }

    private int decompressAndApplySlice(Chunk chunk, CompressedChunkSlice slice) throws Exception {
        String[] paletteStrings = slice.readPalette();
        BlockData[] decodedPalette = new BlockData[paletteStrings.length];
        for (int i = 0; i < paletteStrings.length; i++) {
            decodedPalette[i] = Bukkit.createBlockData(paletteStrings[i]);
        }

        int modified = 0;
        try (DataInputStream dis = new DataInputStream(
                new InflaterInputStream(slice.openCompressedStream()))) {

            for (int y = slice.minY; y <= slice.maxY; y++) {
                for (int x = slice.startX; x <= slice.endX; x++) {
                    for (int z = slice.startZ; z <= slice.endZ; z++) {
                        int paletteIdx = dis.readUnsignedShort();
                        BlockData targetData = decodedPalette[paletteIdx];
                        Block block = chunk.getBlock(x & 15, y, z & 15);

                        if (!block.getBlockData().matches(targetData)) {
                            block.setBlockData(targetData, false);
                            modified++;
                        }
                    }
                }
            }
        }
        return modified;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public void setPos1(UUID playerUuid, Location loc) {
        selections.computeIfAbsent(playerUuid, k -> new CuboidSelection()).pos1 = loc.clone();
    }

    public void setPos2(UUID playerUuid, Location loc) {
        selections.computeIfAbsent(playerUuid, k -> new CuboidSelection()).pos2 = loc.clone();
    }

    public CuboidSelection getSelection(UUID playerUuid) {
        return selections.get(playerUuid);
    }

    public Collection<ArenaSnapshot> getAllSnapshots() {
        return snapshots.values();
    }

    public ArenaSnapshot getSnapshot(String id) {
        return snapshots.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean deleteSnapshot(String id) {
        ArenaSnapshot removed = snapshots.remove(id.toLowerCase(Locale.ROOT));
        if (removed != null) {
            removed.release();
            return true;
        }
        return false;
    }

    public long getOffHeapBytesUsed() {
        return Math.max(0L, TOTAL_OFF_HEAP_BYTES.get());
    }

    public void shutdown() {
        for (ArenaSnapshot s : snapshots.values()) {
            s.release();
        }
        snapshots.clear();
        for (Deque<ArenaSnapshot> d : undoStacks.values()) {
            synchronized (d) {
                for (ArenaSnapshot s : d) s.release();
                d.clear();
            }
        }
        undoStacks.clear();
        for (Deque<ArenaSnapshot> d : redoStacks.values()) {
            synchronized (d) {
                for (ArenaSnapshot s : d) s.release();
                d.clear();
            }
        }
        redoStacks.clear();
        selections.clear();
    }

    public static final class CuboidSelection {
        public Location pos1;
        public Location pos2;

        public boolean isComplete() {
            return pos1 != null && pos2 != null
                    && pos1.getWorld() != null && pos1.getWorld().equals(pos2.getWorld());
        }

        public long getVolume() {
            if (!isComplete()) return 0;
            long dx = Math.abs(pos1.getBlockX() - pos2.getBlockX()) + 1L;
            long dy = Math.abs(pos1.getBlockY() - pos2.getBlockY()) + 1L;
            long dz = Math.abs(pos1.getBlockZ() - pos2.getBlockZ()) + 1L;
            return dx * dy * dz;
        }
    }

    public record BatchResult(int modifiedBlocks, int affectedChunks, double elapsedMs) {}

    private static final class DirectByteBufferInputStream extends InputStream {
        private final ByteBuffer buf;

        DirectByteBufferInputStream(ByteBuffer sliceView) {
            this.buf = sliceView;
        }

        @Override
        public int read() {
            if (!buf.hasRemaining()) return -1;
            return buf.get() & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int off, int len) {
            if (!buf.hasRemaining()) return -1;
            int toRead = Math.min(len, buf.remaining());
            buf.get(bytes, off, toRead);
            return toRead;
        }
    }

    public static final class CompressedChunkSlice {
        public final int startX, endX, minY, maxY, startZ, endZ;
        public final int blockCount;
        public final int paletteSize;
        private final int compressedOffset;
        private final int compressedLength;
        private volatile ByteBuffer offHeapSegment;
        private final AtomicBoolean released = new AtomicBoolean(false);

        public CompressedChunkSlice(int startX, int endX, int minY, int maxY, int startZ, int endZ,
                                    int blockCount, String[] palette, byte[] compressedData) {
            this.startX = startX;
            this.endX = endX;
            this.minY = minY;
            this.maxY = maxY;
            this.startZ = startZ;
            this.endZ = endZ;
            this.blockCount = blockCount;
            this.paletteSize = palette.length;

            byte[][] encodedPalette = new byte[palette.length][];
            int paletteByteLen = 4;
            for (int i = 0; i < palette.length; i++) {
                encodedPalette[i] = palette[i].getBytes(StandardCharsets.UTF_8);
                paletteByteLen += 2 + encodedPalette[i].length;
            }

            this.compressedOffset = paletteByteLen;
            this.compressedLength = compressedData.length;
            int totalCapacity = paletteByteLen + compressedData.length;

            ByteBuffer direct = ByteBuffer.allocateDirect(totalCapacity);
            direct.putInt(palette.length);
            for (byte[] entry : encodedPalette) {
                direct.putShort((short) entry.length);
                direct.put(entry);
            }
            direct.put(compressedData);
            direct.flip();

            this.offHeapSegment = direct;
            TOTAL_OFF_HEAP_BYTES.addAndGet(totalCapacity);
        }

        public String[] readPalette() {
            ByteBuffer dup = ensureOpen().duplicate();
            dup.position(0);
            int count = dup.getInt();
            String[] result = new String[count];
            for (int i = 0; i < count; i++) {
                int strLen = dup.getShort() & 0xFFFF;
                byte[] tmp = new byte[strLen];
                dup.get(tmp);
                result[i] = new String(tmp, StandardCharsets.UTF_8);
            }
            return result;
        }

        public InputStream openCompressedStream() {
            ByteBuffer dup = ensureOpen().duplicate();
            dup.position(compressedOffset);
            dup.limit(compressedOffset + compressedLength);
            return new DirectByteBufferInputStream(dup.slice());
        }

        public byte[] getCompressedBytes() {
            ByteBuffer dup = ensureOpen().duplicate();
            dup.position(compressedOffset);
            dup.limit(compressedOffset + compressedLength);
            byte[] copy = new byte[compressedLength];
            dup.get(copy);
            return copy;
        }

        private ByteBuffer ensureOpen() {
            ByteBuffer buf = this.offHeapSegment;
            if (buf == null) {
                throw new IllegalStateException("Snapshot Off-Heap buffer đã được giải phóng.");
            }
            return buf;
        }

        public long getMemoryBytes() {
            ByteBuffer buf = this.offHeapSegment;
            return buf != null ? buf.capacity() : 0L;
        }

        public boolean isOffHeap() {
            ByteBuffer buf = this.offHeapSegment;
            return buf != null && buf.isDirect();
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                ByteBuffer buf = this.offHeapSegment;
                this.offHeapSegment = null;
                if (buf != null) {
                    TOTAL_OFF_HEAP_BYTES.addAndGet(-buf.capacity());
                }
            }
        }
    }

    public static final class ArenaSnapshot {
        public final String id;
        public final String worldName;
        public final int minX, minY, minZ, maxX, maxY, maxZ;
        public final int totalBlocks;
        public final Map<Long, CompressedChunkSlice> chunks;
        public final double captureTimeMs;
        public final long createdAt;

        public ArenaSnapshot(String id, String worldName, int minX, int minY, int minZ,
                             int maxX, int maxY, int maxZ, int totalBlocks,
                             Map<Long, CompressedChunkSlice> chunks, double captureTimeMs) {
            this.id = id;
            this.worldName = worldName;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.totalBlocks = totalBlocks;
            this.chunks = chunks;
            this.captureTimeMs = captureTimeMs;
            this.createdAt = System.currentTimeMillis();
        }

        public double getMemoryUsageKb() {
            long totalBytes = 0;
            for (CompressedChunkSlice slice : chunks.values()) {
                totalBytes += slice.getMemoryBytes();
            }
            return totalBytes / 1024.0;
        }

        public void release() {
            for (CompressedChunkSlice slice : chunks.values()) {
                slice.release();
            }
        }
    }
}
