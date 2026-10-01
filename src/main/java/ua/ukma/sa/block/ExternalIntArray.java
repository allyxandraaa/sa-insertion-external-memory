package ua.ukma.sa.block;

import java.io.IOException;

public final class ExternalIntArray {

    private static final int ELEMENT_BYTES = Integer.BYTES;
    private static final int MAGIC = 0x53414152;
    private static final int LENGTH_OFFSET = Integer.BYTES;
    private static final int HEADER_BYTES = Integer.BYTES + Long.BYTES;
    private static final long HEADER_BLOCK = 0;

    private final BufferPool pool;
    private final long length;
    private final int blockSize;

    private ExternalIntArray(BufferPool pool, long length) {
        this.pool = pool;
        this.length = length;
        this.blockSize = pool.file().blockSize();
    }

    public static ExternalIntArray create(BufferPool pool, long length) throws IOException {
        BlockFile file = pool.file();
        requireSupportedBlockSize(file.blockSize());
        if (length < 0) {
            throw new IllegalArgumentException("Negative length " + length);
        }
        if (file.blockCount() != 0) {
            throw new IllegalArgumentException("File must be empty, has " + file.blockCount() + " blocks");
        }
        file.allocate(Math.toIntExact(1 + dataBlocks(length, file.blockSize())));

        BufferPool.Frame header = pool.pin(HEADER_BLOCK);
        try {
            header.buffer().putInt(0, MAGIC);
            header.buffer().putLong(LENGTH_OFFSET, length);
        } finally {
            pool.unpin(HEADER_BLOCK, true);
        }
        pool.flush();
        return new ExternalIntArray(pool, length);
    }

    public static ExternalIntArray open(BufferPool pool) throws IOException {
        BlockFile file = pool.file();
        requireSupportedBlockSize(file.blockSize());
        if (file.blockCount() == 0) {
            throw new IOException("File is empty, no array header found");
        }
        int magic;
        long length;
        BufferPool.Frame header = pool.pin(HEADER_BLOCK);
        try {
            magic = header.buffer().getInt(0);
            length = header.buffer().getLong(LENGTH_OFFSET);
        } finally {
            pool.unpin(HEADER_BLOCK, false);
        }
        if (magic != MAGIC) {
            throw new IOException("File does not contain an array header");
        }
        if (length < 0 || file.blockCount() != 1 + dataBlocks(length, file.blockSize())) {
            throw new IOException("Array length " + length + " does not match " + file.blockCount() + " blocks");
        }
        return new ExternalIntArray(pool, length);
    }

    public long length() {
        return length;
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

    public void flush() throws IOException {
        pool.flush();
    }

    private long blockOf(long index) {
        return 1 + index * ELEMENT_BYTES / blockSize;
    }

    private int offsetOf(long index) {
        return (int) (index * ELEMENT_BYTES % blockSize);
    }

    private void checkIndex(long index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index " + index + " out of range [0, " + length + ")");
        }
    }

    private static long dataBlocks(long length, int blockSize) {
        long totalBytes = length * ELEMENT_BYTES;
        return (totalBytes + blockSize - 1) / blockSize;
    }

    private static void requireSupportedBlockSize(int blockSize) {
        if (blockSize % ELEMENT_BYTES != 0 || blockSize < HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "Block size " + blockSize + " must be a multiple of 4 and at least " + HEADER_BYTES);
        }
    }
}
