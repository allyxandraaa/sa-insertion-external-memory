package ua.ukma.sa.block;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class BlockWriter implements AutoCloseable {

    private static final int ELEMENT_BYTES = Integer.BYTES;

    private final BlockFile file;
    private final MemoryBudget budget;
    private final int blockSize;
    private final ByteBuffer buffer;
    private long nextBlock;
    private int used;
    private long count;
    private boolean closed;

    public BlockWriter(BlockFile file, MemoryBudget budget, long firstBlock) {
        if (file.blockSize() < ELEMENT_BYTES) {
            throw new IllegalArgumentException(
                    "Block size " + file.blockSize() + " is smaller than an element of " + ELEMENT_BYTES + " bytes");
        }
        if (firstBlock < 0 || firstBlock > file.blockCount()) {
            throw new IllegalArgumentException(
                    "First block " + firstBlock + " out of range [0, " + file.blockCount() + "]");
        }
        this.file = file;
        this.budget = budget;
        this.blockSize = file.blockSize();
        budget.reserve(blockSize);
        this.buffer = ByteBuffer.allocate(blockSize);
        this.nextBlock = firstBlock;
    }

    public void writeInt(int value) throws IOException {
        prepare();
        buffer.putInt(used, value);
        used += ELEMENT_BYTES;
        count++;
    }

    public long count() {
        return count;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (used > 0) {
                flushBlock();
            }
        } finally {
            budget.release(blockSize);
        }
    }

    private void prepare() throws IOException {
        if (closed) {
            throw new IllegalStateException("Writer is closed");
        }
        if (used + ELEMENT_BYTES > blockSize) {
            flushBlock();
        }
    }

    private void flushBlock() throws IOException {
        if (nextBlock >= file.blockCount()) {
            file.allocate(Math.toIntExact(nextBlock + 1 - file.blockCount()));
        }
        file.write(nextBlock, buffer);
        nextBlock++;
        Arrays.fill(buffer.array(), (byte) 0);
        used = 0;
    }
}
