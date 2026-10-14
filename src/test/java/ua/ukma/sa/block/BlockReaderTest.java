package ua.ukma.sa.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockReaderTest {

    private static final int B = 16;

    @TempDir
    Path dir;

    private BlockFile newFile(int blockSize) throws IOException {
        return new BlockFile(dir.resolve("dev.bin"), blockSize);
    }

    private static MemoryBudget budget() {
        return new MemoryBudget(10 * B);
    }

    private static void writeInts(BlockFile file, long firstBlock, int... values) throws IOException {
        BlockWriter out = new BlockWriter(file, budget(), firstBlock);
        for (int value : values) {
            out.writeInt(value);
        }
        out.close();
        file.stats().reset();
    }

    @Test
    void roundTripOfIntsCostsOneSequentialReadPerBlock() throws IOException {
        int[] values = new Random(1).ints(100).toArray();
        try (BlockFile file = newFile(B)) {
            writeInts(file, 0, values);

            BlockReader in = new BlockReader(file, budget(), 0, values.length);
            assertEquals(0, file.stats().reads());
            for (int value : values) {
                assertTrue(in.hasNext());
                assertEquals(value, in.readInt());
            }
            assertFalse(in.hasNext());
            in.close();

            assertEquals(25, file.stats().reads());
            assertEquals(25, file.stats().sequentialReads());
            assertEquals(0, file.stats().randomReads());
        }
    }

    @Test
    void readingPastCountIsRejected() throws IOException {
        try (BlockFile file = newFile(B)) {
            writeInts(file, 0, 1, 2, 3, 4);

            BlockReader in = new BlockReader(file, budget(), 0, 2);
            assertEquals(1, in.readInt());
            assertEquals(2, in.readInt());
            assertEquals(0, in.remaining());
            assertThrows(NoSuchElementException.class, in::readInt);
            in.close();
        }
    }

    @Test
    void smallCountDoesNotReadBlocksBeyondIt() throws IOException {
        try (BlockFile file = newFile(B)) {
            writeInts(file, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

            BlockReader in = new BlockReader(file, budget(), 0, 4);
            while (in.hasNext()) {
                in.readInt();
            }
            in.close();

            assertEquals(1, file.stats().reads());
        }
    }

    @Test
    void zeroCountReadsNothing() throws IOException {
        try (BlockFile file = newFile(B)) {
            writeInts(file, 0, 1, 2);

            BlockReader in = new BlockReader(file, budget(), 0, 0);
            assertFalse(in.hasNext());
            in.close();

            assertEquals(0, file.stats().reads());
        }
    }

    @Test
    void paddingIsSkippedWhenElementDoesNotFit() throws IOException {
        try (BlockFile file = newFile(10)) {
            writeInts(file, 0, 11, 22, 33, 44, 55);

            BlockReader in = new BlockReader(file, budget(), 0, 5);
            int[] expected = {11, 22, 33, 44, 55};
            for (int value : expected) {
                assertEquals(value, in.readInt());
            }
            in.close();

            assertEquals(3, file.stats().reads());
        }
    }

    @Test
    void blockSmallerThanElementIsRejected() throws IOException {
        MemoryBudget budget = budget();
        try (BlockFile file = newFile(3)) {
            assertThrows(IllegalArgumentException.class, () -> new BlockReader(file, budget, 0, 0));
            assertEquals(0, budget.used());
        }
    }

    @Test
    void readingStartsAtGivenBlock() throws IOException {
        try (BlockFile file = newFile(B)) {
            writeInts(file, 0, 0, 1, 2, 3, 4, 5, 6, 7);

            BlockReader in = new BlockReader(file, budget(), 1, 4);
            for (int expected = 4; expected < 8; expected++) {
                assertEquals(expected, in.readInt());
            }
            in.close();
        }
    }

    @Test
    void readsLayoutWrittenByHand() throws IOException {
        try (BlockFile file = newFile(B)) {
            file.allocate(1);
            ByteBuffer block = ByteBuffer.allocate(B);
            block.putInt(0, 42);
            block.putInt(4, 43);
            file.write(0, block);

            BlockReader in = new BlockReader(file, budget(), 0, 2);
            assertEquals(42, in.readInt());
            assertEquals(43, in.readInt());
            in.close();
        }
    }

    @Test
    void readerHoldsExactlyOneBlockOfBudgetUntilClosed() throws IOException {
        MemoryBudget budget = budget();
        try (BlockFile file = newFile(B)) {
            BlockReader in = new BlockReader(file, budget, 0, 0);
            assertEquals(B, budget.used());

            in.close();
            assertEquals(0, budget.used());
            in.close();
            assertEquals(0, budget.used());
        }
    }

    @Test
    void readerIsRejectedWhenBudgetCannotFitOneBlock() throws IOException {
        MemoryBudget tight = new MemoryBudget(B - 1);
        try (BlockFile file = newFile(B)) {
            assertThrows(MemoryBudgetExceededException.class, () -> new BlockReader(file, tight, 0, 0));
            assertEquals(0, tight.used());
        }
    }

    @Test
    void readingAfterCloseIsRejected() throws IOException {
        try (BlockFile file = newFile(B)) {
            writeInts(file, 0, 1);

            BlockReader in = new BlockReader(file, budget(), 0, 1);
            in.close();

            assertThrows(IllegalStateException.class, in::readInt);
        }
    }

    @Test
    void invalidArgumentsAreRejected() throws IOException {
        try (BlockFile file = newFile(B)) {
            file.allocate(2);

            assertThrows(IllegalArgumentException.class, () -> new BlockReader(file, budget(), -1, 1));
            assertThrows(IllegalArgumentException.class, () -> new BlockReader(file, budget(), 3, 1));
            assertThrows(IllegalArgumentException.class, () -> new BlockReader(file, budget(), 0, -1));
        }
    }

    @Test
    void countLargerThanDataFailsWhenFileEnds() throws IOException {
        try (BlockFile file = newFile(B)) {
            writeInts(file, 0, 1, 2, 3, 4);

            BlockReader in = new BlockReader(file, budget(), 0, 5);
            for (int i = 0; i < 4; i++) {
                in.readInt();
            }
            assertThrows(IndexOutOfBoundsException.class, in::readInt);
            in.close();
        }
    }
}
