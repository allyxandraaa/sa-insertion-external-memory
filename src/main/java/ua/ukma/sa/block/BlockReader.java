package ua.ukma.sa.block;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

public final class BlockReader implements AutoCloseable {

    private static final int ELEMENT_BYTES = Integer.BYTES;

    private final BlockFile file;
    private final MemoryBudget budget;
    private final int blockSize;
    private final ByteBuffer buffer;
    private long nextBlock;
    private int position;
    private long remaining;
    private boolean closed;

    public BlockReader(BlockFile file, MemoryBudget budget, long firstBlock, long count) {
        if (file.blockSize() < ELEMENT_BYTES) {
            throw new IllegalArgumentException(
                    "Block size " + file.blockSize() + " is smaller than an element of " + ELEMENT_BYTES + " bytes");
        }
        if (firstBlock < 0 || firstBlock > file.blockCount()) {
            throw new IllegalArgumentException(
                    "First block " + firstBlock + " out of range [0, " + file.blockCount() + "]");
        }
        if (count < 0) {
            throw new IllegalArgumentException("Negative count " + count);
        }
        this.file = file;
        this.budget = budget;
        this.blockSize = file.blockSize();
        budget.reserve(blockSize);
        this.buffer = ByteBuffer.allocate(blockSize);
        this.nextBlock = firstBlock;
        this.position = blockSize;
        this.remaining = count;
    }

    public boolean hasNext() {
        return remaining > 0;
    }

    public long remaining() {
        return remaining;
    }

    public int readInt() throws IOException {
        prepare();
        int value = buffer.getInt(position);
        position += ELEMENT_BYTES;
        return value;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        budget.release(blockSize);
    }

    private void prepare() throws IOException {
        if (closed) {
            throw new IllegalStateException("Reader is closed");
        }
        if (remaining == 0) {
            throw new NoSuchElementException("No more elements");
        }
        if (position + ELEMENT_BYTES > blockSize) {
            file.read(nextBlock, buffer);
            nextBlock++;
            position = 0;
        }
        remaining--;
    }
}
