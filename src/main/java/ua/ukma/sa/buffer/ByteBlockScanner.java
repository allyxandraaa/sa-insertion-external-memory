package ua.ukma.sa.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import ua.ukma.sa.array.ExternalByteArray;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.MemoryBudget;

public final class ByteBlockScanner implements AutoCloseable {

    private final BlockFile file;
    private final MemoryBudget budget;
    private final int blockSize;
    private final ByteBuffer buffer;
    private long nextBlock = 1;
    private long remaining;
    private boolean closed;

    ByteBlockScanner(ExternalByteArray array, MemoryBudget budget, long length) throws IOException {
        if (length < 0 || length > array.length()) {
            throw new IllegalArgumentException("Scan length " + length + " out of range [0, " + array.length() + "]");
        }
        array.flush();
        this.file = array.file();
        this.budget = budget;
        this.blockSize = file.blockSize();
        this.remaining = length;
        budget.reserve(blockSize);
        this.buffer = ByteBuffer.allocate(blockSize);
    }

    public ByteBuffer next() throws IOException {
        if (closed) {
            throw new IllegalStateException("Scanner is closed");
        }
        if (remaining == 0) {
            return null;
        }
        file.read(nextBlock, buffer);
        nextBlock++;
        int valid = (int) Math.min(blockSize, remaining);
        remaining -= valid;
        buffer.limit(valid);
        return buffer;
    }

    public long remaining() {
        return remaining;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        budget.release(blockSize);
    }
}
