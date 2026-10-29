package ua.ukma.sa.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.stream.LongStream;
import ua.ukma.sa.array.ExternalByteArray;
import ua.ukma.sa.block.BufferPool;
import ua.ukma.sa.block.IoStats;
import ua.ukma.sa.block.MemoryBudget;
import ua.ukma.sa.matching.KmpMatcher;

public final class InsertionBuffer {

    static final byte TERMINATOR = '\n';

    private final ExternalByteArray bytes;
    private final long capacityBytes;
    private long usedBytes;
    private long recordCount;

    private InsertionBuffer(ExternalByteArray bytes) {
        this.bytes = bytes;
        this.capacityBytes = bytes.length();
    }

    public static InsertionBuffer create(BufferPool pool, long capacityBytes) throws IOException {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacityBytes);
        }
        return new InsertionBuffer(ExternalByteArray.create(pool, capacityBytes));
    }

    public boolean append(String record) throws IOException {
        return append(Normalizer.normalize(record, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8));
    }

    public boolean append(byte[] record) throws IOException {
        requireValid(record, "Record");
        long storedLength = (long) record.length + 1;
        if (storedLength > capacityBytes - usedBytes) {
            return false;
        }
        byte[] stored = Arrays.copyOf(record, record.length + 1);
        stored[record.length] = TERMINATOR;
        bytes.writeAtEnd(usedBytes, stored, stored.length);
        usedBytes += storedLength;
        recordCount++;
        return true;
    }

    public void clear() {
        usedBytes = 0;
        recordCount = 0;
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

    public long[] findAll(String pattern, MemoryBudget budget) throws IOException {
        return findAll(Normalizer.normalize(pattern, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8), budget);
    }

    public long[] findAll(byte[] pattern, MemoryBudget budget) throws IOException {
        requireValid(pattern, "Pattern");
        KmpMatcher matcher = new KmpMatcher(pattern);
        LongStream.Builder found = LongStream.builder();
        long position = 0;
        try (ByteBlockScanner scanner = scanner(budget)) {
            ByteBuffer block;
            while ((block = scanner.next()) != null) {
                for (int i = 0; i < block.limit(); i++, position++) {
                    if (matcher.feed(block.get(i))) {
                        found.add(position - pattern.length + 1);
                    }
                }
            }
        }
        return found.build().toArray();
    }

    private static void requireValid(byte[] value, String what) {
        if (value.length == 0) {
            throw new IllegalArgumentException(what + " is empty");
        }
        for (byte b : value) {
            if (b == TERMINATOR) {
                throw new IllegalArgumentException(what + " contains the line terminator");
            }
        }
    }
}
