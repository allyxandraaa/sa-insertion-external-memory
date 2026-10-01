package ua.ukma.sa.block;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class BlockFile implements AutoCloseable {

    private final FileChannel channel;
    private final int blockSize;
    private long blockCount;
    private final IoStats stats = new IoStats();

    public BlockFile(Path path, int blockSize) throws IOException {
        this(path, blockSize, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private BlockFile(Path path, int blockSize, OpenOption... options) throws IOException {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("Block size must be positive: " + blockSize);
        }
        this.blockSize = blockSize;
        FileChannel opened = FileChannel.open(path, options);
        try {
            long size = opened.size();
            if (size % blockSize != 0) {
                throw new IOException("File size " + size + " is not a multiple of block size " + blockSize);
            }
            this.blockCount = size / blockSize;
        } catch (IOException | RuntimeException e) {
            opened.close();
            throw e;
        }
        this.channel = opened;
    }

    public static BlockFile create(Path path, int blockSize) throws IOException {
        return new BlockFile(path, blockSize,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    public int blockSize() {
        return blockSize;
    }

    public long blockCount() {
        return blockCount;
    }

    public IoStats stats() {
        return stats;
    }

    public long allocate(int count) throws IOException {
        if (count < 0) {
            throw new IllegalArgumentException("Negative block count " + count);
        }
        long first = blockCount;
        if (count > 0) {
            ByteBuffer last = ByteBuffer.allocate(1);
            long end = (first + count) * blockSize;
            while (last.hasRemaining()) {
                channel.write(last, end - 1 + last.position());
            }
            blockCount = first + count;
        }
        return first;
    }

    public void read(long blockId, ByteBuffer block) throws IOException {
        check(blockId, block);
        block.clear();
        while (block.hasRemaining()) {
            int n = channel.read(block, blockId * blockSize + block.position());
            if (n < 0) {
                throw new EOFException("Unexpected end of file while reading block " + blockId);
            }
        }
        block.clear();
        stats.recordRead(blockId);
    }

    public void write(long blockId, ByteBuffer block) throws IOException {
        check(blockId, block);
        block.clear();
        while (block.hasRemaining()) {
            channel.write(block, blockId * blockSize + block.position());
        }
        block.clear();
        stats.recordWrite(blockId);
    }

    private void check(long blockId, ByteBuffer block) {
        if (blockId < 0 || blockId >= blockCount) {
            throw new IndexOutOfBoundsException("Block " + blockId + " out of range [0, " + blockCount + ")");
        }
        if (block.capacity() != blockSize) {
            throw new IllegalArgumentException("Buffer size " + block.capacity() + " != block size " + blockSize);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
