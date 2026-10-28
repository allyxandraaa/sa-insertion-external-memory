package ua.ukma.sa.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import ua.ukma.sa.array.ExternalArray;
import ua.ukma.sa.array.ExternalByteArray;
import ua.ukma.sa.block.BufferPool;
import ua.ukma.sa.block.IoStats;
import ua.ukma.sa.block.MemoryBudget;

public final class InsertionBuffer {

    static final byte TERMINATOR = '\n';

    private final ExternalByteArray bytes;
    private final long capacityBytes;
    private long usedBytes;
    private long recordCount;

    private InsertionBuffer(ExternalByteArray bytes, long usedBytes, long recordCount) {
        this.bytes = bytes;
        this.capacityBytes = bytes.length();
        this.usedBytes = usedBytes;
        this.recordCount = recordCount;
    }

    public static InsertionBuffer create(BufferPool pool, long capacityBytes) throws IOException {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacityBytes);
        }
        requireBlockSize(pool);
        ExternalByteArray bytes = ExternalByteArray.create(pool, capacityBytes);
        bytes.writeMeta(0);
        return new InsertionBuffer(bytes, 0, 0);
    }

    public static InsertionBuffer open(BufferPool pool, MemoryBudget budget) throws IOException {
        requireBlockSize(pool);
        ExternalByteArray bytes = ExternalByteArray.open(pool);
        long used = bytes.readMeta();
        if (used < 0 || used > bytes.length()) {
            throw new IOException("Stored size " + used + " is outside [0, " + bytes.length() + "]");
        }
        long records = 0;
        int last = -1;
        try (ByteBlockScanner scanner = new ByteBlockScanner(bytes, budget, used)) {
            ByteBuffer block;
            while ((block = scanner.next()) != null) {
                for (int i = 0; i < block.limit(); i++) {
                    last = block.get(i);
                    if (last == TERMINATOR) {
                        records++;
                    }
                }
            }
        }
        if (used > 0 && last != TERMINATOR) {
            throw new IOException("Insertion buffer does not end with a record terminator");
        }
        return new InsertionBuffer(bytes, used, records);
    }

    public boolean append(String record) throws IOException {
        return append(Normalizer.normalize(record, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8));
    }

    public boolean append(byte[] record) throws IOException {
        if (record.length == 0) {
            throw new IllegalArgumentException("Empty record");
        }
        for (byte b : record) {
            if (b == TERMINATOR) {
                throw new IllegalArgumentException("Record contains the line terminator");
            }
        }
        long storedLength = (long) record.length + 1;
        if (storedLength > capacityBytes - usedBytes) {
            return false;
        }
        byte[] stored = Arrays.copyOf(record, record.length + 1);
        stored[record.length] = TERMINATOR;
        bytes.writeAtEnd(usedBytes, stored, stored.length);
        usedBytes += storedLength;
        recordCount++;
        bytes.writeMeta(usedBytes);
        return true;
    }

    public long sizeBytes() {
        return usedBytes;
    }

    public long capacityBytes() {
        return capacityBytes;
    }

    public long recordCount() {
        return recordCount;
    }

    public void flush() throws IOException {
        bytes.flush();
    }

    public IoStats stats() {
        return bytes.file().stats();
    }

    public ByteBlockScanner scanner(MemoryBudget budget) throws IOException {
        return new ByteBlockScanner(bytes, budget, usedBytes);
    }

    private static void requireBlockSize(BufferPool pool) {
        int blockSize = pool.file().blockSize();
        if (blockSize < ExternalArray.MIN_BLOCK_WITH_META) {
            throw new IllegalArgumentException(
                    "Block size " + blockSize + " is smaller than " + ExternalArray.MIN_BLOCK_WITH_META);
        }
    }
}
