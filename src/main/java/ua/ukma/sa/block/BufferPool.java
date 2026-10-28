package ua.ukma.sa.block;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BufferPool implements AutoCloseable {

    public static final class Frame {

        private final long blockId;
        private final ByteBuffer buffer;
        private int pinCount;
        private boolean dirty;

        private Frame(long blockId, ByteBuffer buffer) {
            this.blockId = blockId;
            this.buffer = buffer;
        }

        public long blockId() {
            return blockId;
        }

        public ByteBuffer buffer() {
            return buffer;
        }
    }

    private final BlockFile file;
    private final MemoryBudget budget;
    private final int frameCount;
    private final long reservedBytes;
    private final Map<Long, Frame> frames = new LinkedHashMap<>(16, 0.75f, true);
    private long hits;
    private long misses;
    private boolean closed;

    public BufferPool(BlockFile file, MemoryBudget budget, long poolBytes) {
        if (poolBytes % file.blockSize() != 0) {
            throw new IllegalArgumentException(
                    "Pool size " + poolBytes + " is not a multiple of block size " + file.blockSize());
        }
        long count = poolBytes / file.blockSize();
        if (count < 1 || count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Pool of " + poolBytes + " bytes holds " + count + " frames of " + file.blockSize() + " bytes");
        }
        this.file = file;
        this.budget = budget;
        this.frameCount = (int) count;
        this.reservedBytes = count * file.blockSize();
        budget.reserve(reservedBytes);
    }

    public Frame pin(long blockId) throws IOException {
        return pin(blockId, true);
    }

    public Frame pinNew(long blockId) throws IOException {
        return pin(blockId, false);
    }

    private Frame pin(long blockId, boolean readFromDisk) throws IOException {
        if (closed) {
            throw new IllegalStateException("Pool is closed");
        }
        if (blockId < 0 || blockId >= file.blockCount()) {
            throw new IndexOutOfBoundsException("Block " + blockId + " out of range [0, " + file.blockCount() + ")");
        }
        Frame frame = frames.get(blockId);
        if (frame != null) {
            hits++;
            frame.pinCount++;
            frame.buffer.clear();
            return frame;
        }
        misses++;
        ByteBuffer buffer = acquireBuffer();
        if (readFromDisk) {
            file.read(blockId, buffer);
        } else {
            Arrays.fill(buffer.array(), (byte) 0);
            buffer.clear();
        }
        Frame loaded = new Frame(blockId, buffer);
        loaded.pinCount = 1;
        frames.put(blockId, loaded);
        return loaded;
    }

    public void unpin(long blockId, boolean dirty) {
        Frame frame = frames.get(blockId);
        if (frame == null || frame.pinCount == 0) {
            throw new IllegalStateException("Block " + blockId + " is not pinned");
        }
        frame.pinCount--;
        frame.dirty |= dirty;
    }

    public void flush() throws IOException {
        List<Frame> dirtyFrames = new ArrayList<>();
        for (Frame frame : frames.values()) {
            if (frame.dirty) {
                dirtyFrames.add(frame);
            }
        }
        dirtyFrames.sort(Comparator.comparingLong(frame -> frame.blockId));
        for (Frame frame : dirtyFrames) {
            file.write(frame.blockId, frame.buffer);
            frame.dirty = false;
        }
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public int frameCount() {
        return frameCount;
    }

    public BlockFile file() {
        return file;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            flush();
        } finally {
            frames.clear();
            budget.release(reservedBytes);
            closed = true;
        }
    }

    private ByteBuffer acquireBuffer() throws IOException {
        if (frames.size() < frameCount) {
            return ByteBuffer.allocate(file.blockSize());
        }
        Frame victim = null;
        for (Frame frame : frames.values()) {
            if (frame.pinCount == 0) {
                victim = frame;
                break;
            }
        }
        if (victim == null) {
            throw new IllegalStateException("All " + frameCount + " frames are pinned");
        }
        if (victim.dirty) {
            file.write(victim.blockId, victim.buffer);
        }
        frames.remove(victim.blockId);
        return victim.buffer;
    }
}
