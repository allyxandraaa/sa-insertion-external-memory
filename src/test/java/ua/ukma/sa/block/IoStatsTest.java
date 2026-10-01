package ua.ukma.sa.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IoStatsTest {

    @TempDir
    Path dir;

    @Test
    void totalsSumAllCounters() {
        IoStats stats = new IoStats();
        stats.recordRead(0);
        stats.recordRead(1);
        stats.recordRead(7);
        stats.recordWrite(0);
        stats.recordWrite(5);

        assertEquals(3, stats.reads());
        assertEquals(2, stats.writes());
        assertEquals(5, stats.total());
        assertEquals(2, stats.sequentialReads());
        assertEquals(1, stats.randomReads());
        assertEquals(1, stats.sequentialWrites());
        assertEquals(1, stats.randomWrites());
    }

    @Test
    void readsAndWritesAreTrackedIndependently() {
        IoStats stats = new IoStats();
        stats.recordWrite(0);
        stats.recordWrite(1);
        stats.recordWrite(2);
        stats.recordRead(0);
        stats.recordRead(1);
        stats.recordRead(2);

        assertEquals(3, stats.sequentialWrites());
        assertEquals(3, stats.sequentialReads());
        assertEquals(0, stats.randomReads());
        assertEquals(0, stats.randomWrites());
    }

    @Test
    void resetClearsCountersAndForgetsPreviousBlocks() {
        IoStats stats = new IoStats();
        stats.recordRead(0);
        stats.recordRead(1);
        stats.recordWrite(0);
        stats.recordWrite(9);

        stats.reset();

        assertEquals(0, stats.total());
        stats.recordRead(0);
        stats.recordWrite(5);
        assertEquals(1, stats.sequentialReads());
        assertEquals(0, stats.randomReads());
        assertEquals(0, stats.sequentialWrites());
        assertEquals(1, stats.randomWrites());
    }

    @Test
    void snapshotDoesNotChangeAfterNewRecords() {
        IoStats stats = new IoStats();
        stats.recordRead(0);
        IoStats.Snapshot snapshot = stats.snapshot();

        stats.recordRead(1);
        stats.recordWrite(3);

        assertEquals(1, snapshot.reads());
        assertEquals(0, snapshot.writes());
        assertEquals(2, stats.reads());
    }

    @Test
    void snapshotDifferenceCoversAllFourCounters() {
        IoStats stats = new IoStats();
        stats.recordRead(0);
        stats.recordWrite(0);
        IoStats.Snapshot before = stats.snapshot();

        stats.recordRead(1);
        stats.recordRead(9);
        stats.recordWrite(1);
        stats.recordWrite(2);
        stats.recordWrite(8);
        IoStats.Snapshot cost = stats.snapshot().minus(before);

        assertEquals(1, cost.sequentialReads());
        assertEquals(1, cost.randomReads());
        assertEquals(2, cost.sequentialWrites());
        assertEquals(1, cost.randomWrites());
        assertEquals(2, cost.reads());
        assertEquals(3, cost.writes());
        assertEquals(5, cost.total());
    }

    @Test
    void fileOperationCostIsMeasuredBySnapshots() throws IOException {
        ByteBuffer block = ByteBuffer.allocate(16);
        try (BlockFile file = new BlockFile(dir.resolve("dev.bin"), 16)) {
            file.allocate(20);
            file.write(0, block);

            IoStats.Snapshot before = file.stats().snapshot();
            for (int id = 0; id < 10; id++) {
                file.read(id, block);
            }
            IoStats.Snapshot cost = file.stats().snapshot().minus(before);

            assertEquals(10, cost.reads());
            assertEquals(10, cost.sequentialReads());
            assertEquals(0, cost.writes());
        }
    }
}
