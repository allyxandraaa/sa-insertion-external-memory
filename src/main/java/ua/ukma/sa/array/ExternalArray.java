package ua.ukma.sa.array;

import java.io.IOException;
import java.util.Objects;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.BufferPool;

public abstract class ExternalArray {

    private static final int LENGTH_OFFSET = Integer.BYTES;
    private static final int HEADER_BYTES = Integer.BYTES + Long.BYTES;
    private static final long HEADER_BLOCK = 0;
    private static final int META_OFFSET = HEADER_BYTES;

    public static final int MIN_BLOCK_WITH_META = META_OFFSET + Long.BYTES;

    final BufferPool pool;
    final int blockSize;
    private final long length;
    private final int elementBytes;

    ExternalArray(BufferPool pool, long length, int elementBytes) {
        this.pool = pool;
        this.length = length;
        this.elementBytes = elementBytes;
        this.blockSize = pool.file().blockSize();
    }

    public final long length() {
        return length;
    }

    public final BlockFile file() {
        return pool.file();
    }

    public final void flush() throws IOException {
        pool.flush();
    }

    public final long readMeta() throws IOException {
        requireMetaFits();
        BufferPool.Frame header = pool.pin(HEADER_BLOCK);
        try {
            return header.buffer().getLong(META_OFFSET);
        } finally {
            pool.unpin(HEADER_BLOCK, false);
        }
    }

    public final void writeMeta(long value) throws IOException {
        requireMetaFits();
        BufferPool.Frame header = pool.pin(HEADER_BLOCK);
        try {
            header.buffer().putLong(META_OFFSET, value);
        } finally {
            pool.unpin(HEADER_BLOCK, true);
        }
    }

    private void requireMetaFits() {
        if (blockSize < MIN_BLOCK_WITH_META) {
            throw new IllegalArgumentException(
                    "Block size " + blockSize + " is too small for a header value, need " + MIN_BLOCK_WITH_META);
        }
    }

    final long blockOf(long index) {
        return 1 + index * elementBytes / blockSize;
    }

    final int offsetOf(long index) {
        return (int) (index * elementBytes % blockSize);
    }

    final void checkIndex(long index) {
        Objects.checkIndex(index, length);
    }

    static void initialize(BufferPool pool, int magic, int elementBytes, long length) throws IOException {
        BlockFile file = pool.file();
        requireSupportedBlockSize(file.blockSize(), elementBytes);
        if (length < 0) {
            throw new IllegalArgumentException("Negative length " + length);
        }
        if (file.blockCount() != 0) {
            throw new IllegalArgumentException("File must be empty, has " + file.blockCount() + " blocks");
        }
        file.allocate(Math.toIntExact(1 + dataBlocks(length, elementBytes, file.blockSize())));

        BufferPool.Frame header = pool.pin(HEADER_BLOCK);
        try {
            header.buffer().putInt(0, magic);
            header.buffer().putLong(LENGTH_OFFSET, length);
        } finally {
            pool.unpin(HEADER_BLOCK, true);
        }
        pool.flush();
    }

    static long readLength(BufferPool pool, int magic, int elementBytes) throws IOException {
        BlockFile file = pool.file();
        requireSupportedBlockSize(file.blockSize(), elementBytes);
        if (file.blockCount() == 0) {
            throw new IOException("File is empty, no array header found");
        }
        int storedMagic;
        long length;
        BufferPool.Frame header = pool.pin(HEADER_BLOCK);
        try {
            storedMagic = header.buffer().getInt(0);
            length = header.buffer().getLong(LENGTH_OFFSET);
        } finally {
            pool.unpin(HEADER_BLOCK, false);
        }
        if (storedMagic != magic) {
            throw new IOException("File does not contain a header of this array type");
        }
        if (length < 0 || file.blockCount() != 1 + dataBlocks(length, elementBytes, file.blockSize())) {
            throw new IOException("Array length " + length + " does not match " + file.blockCount() + " blocks");
        }
        return length;
    }

    private static long dataBlocks(long length, int elementBytes, int blockSize) {
        long totalBytes = length * elementBytes;
        return (totalBytes + blockSize - 1) / blockSize;
    }

    private static void requireSupportedBlockSize(int blockSize, int elementBytes) {
        if (blockSize % elementBytes != 0 || blockSize < HEADER_BYTES) {
            throw new IllegalArgumentException("Block size " + blockSize + " must be a multiple of " + elementBytes
                    + " and at least " + HEADER_BYTES);
        }
    }
}
