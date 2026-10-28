package ua.ukma.sa.array;

import java.io.IOException;
import java.util.Objects;
import ua.ukma.sa.block.BufferPool;

public final class ExternalByteArray extends ExternalArray {

    private static final int ELEMENT_BYTES = Byte.BYTES;
    private static final int MAGIC = 0x53414259; // SABY

    private ExternalByteArray(BufferPool pool, long length) {
        super(pool, length, ELEMENT_BYTES);
    }

    public static ExternalByteArray create(BufferPool pool, long length) throws IOException {
        initialize(pool, MAGIC, ELEMENT_BYTES, length);
        return new ExternalByteArray(pool, length);
    }

    public static ExternalByteArray open(BufferPool pool) throws IOException {
        return new ExternalByteArray(pool, readLength(pool, MAGIC, ELEMENT_BYTES));
    }

    public byte get(long index) throws IOException {
        checkIndex(index);
        long blockId = blockOf(index);
        BufferPool.Frame frame = pool.pin(blockId);
        try {
            return frame.buffer().get(offsetOf(index));
        } finally {
            pool.unpin(blockId, false);
        }
    }

    public void read(long offset, byte[] dst, int len) throws IOException {
        Objects.checkFromIndexSize(offset, len, length());
        Objects.checkFromIndexSize(0, len, dst.length);
        long position = offset;
        int done = 0;
        while (done < len) {
            long blockId = blockOf(position);
            int inBlock = offsetOf(position);
            int chunk = Math.min(len - done, blockSize - inBlock);
            BufferPool.Frame frame = pool.pin(blockId);
            try {
                frame.buffer().get(inBlock, dst, done, chunk);
            } finally {
                pool.unpin(blockId, false);
            }
            position += chunk;
            done += chunk;
        }
    }

    public void write(long offset, byte[] src, int len) throws IOException {
        Objects.checkFromIndexSize(offset, len, length());
        Objects.checkFromIndexSize(0, len, src.length);
        long position = offset;
        int done = 0;
        while (done < len) {
            long blockId = blockOf(position);
            int inBlock = offsetOf(position);
            int chunk = Math.min(len - done, blockSize - inBlock);
            BufferPool.Frame frame = pool.pin(blockId);
            try {
                frame.buffer().put(inBlock, src, done, chunk);
            } finally {
                pool.unpin(blockId, true);
            }
            position += chunk;
            done += chunk;
        }
    }

    public void writeAtEnd(long end, byte[] src, int len) throws IOException {
        Objects.checkFromIndexSize(end, len, length());
        Objects.checkFromIndexSize(0, len, src.length);
        long usedDataBlocks = (end + blockSize - 1) / blockSize;
        long firstEmptyBlock = 1 + usedDataBlocks;
        long position = end;
        int done = 0;
        while (done < len) {
            long blockId = blockOf(position);
            int inBlock = offsetOf(position);
            int chunk = Math.min(len - done, blockSize - inBlock);
            BufferPool.Frame frame = blockId >= firstEmptyBlock ? pool.pinNew(blockId) : pool.pin(blockId);
            try {
                frame.buffer().put(inBlock, src, done, chunk);
            } finally {
                pool.unpin(blockId, true);
            }
            position += chunk;
            done += chunk;
        }
    }
}
