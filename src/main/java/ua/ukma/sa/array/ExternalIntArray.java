package ua.ukma.sa.array;

import java.io.IOException;
import ua.ukma.sa.block.BufferPool;

public final class ExternalIntArray extends ExternalArray {

    private static final int ELEMENT_BYTES = Integer.BYTES;
    private static final int MAGIC = 0x53414152; // SAAR

    private ExternalIntArray(BufferPool pool, long length) {
        super(pool, length, ELEMENT_BYTES);
    }

    public static ExternalIntArray create(BufferPool pool, long length) throws IOException {
        initialize(pool, MAGIC, ELEMENT_BYTES, length);
        return new ExternalIntArray(pool, length);
    }

    public static ExternalIntArray open(BufferPool pool) throws IOException {
        return new ExternalIntArray(pool, readLength(pool, MAGIC, ELEMENT_BYTES));
    }

    public int get(long index) throws IOException {
        checkIndex(index);
        long blockId = blockOf(index);
        BufferPool.Frame frame = pool.pin(blockId);
        try {
            return frame.buffer().getInt(offsetOf(index));
        } finally {
            pool.unpin(blockId, false);
        }
    }

    public void set(long index, int value) throws IOException {
        checkIndex(index);
        long blockId = blockOf(index);
        BufferPool.Frame frame = pool.pin(blockId);
        try {
            frame.buffer().putInt(offsetOf(index), value);
        } finally {
            pool.unpin(blockId, true);
        }
    }
}
