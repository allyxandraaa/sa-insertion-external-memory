package ua.ukma.sa.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockWriterTest {

    private static final int B = 16;

    @TempDir
    Path dir;

    private BlockFile newFile(int blockSize) throws IOException {
        return new BlockFile(dir.resolve("dev.bin"), blockSize);
    }

    private static MemoryBudget budget() {
        return new MemoryBudget(10 * B);
    }

    @Test
    void intsFillConsecutiveBlocksWithSequentialWrites() throws IOException {
        try (BlockFile file = newFile(B)) {
            BlockWriter out = new BlockWriter(file, budget(), 0);
            for (int i = 0; i < 10; i++) {
                out.writeInt(i);
            }
            assertEquals(10, out.count());
            out.close();

            assertEquals(3, file.blockCount());
            assertEquals(3, file.stats().sequentialWrites());
            assertEquals(0, file.stats().randomWrites());
            assertEquals(0, file.stats().reads());

            ByteBuffer block = ByteBuffer.allocate(B);
            file.read(0, block);
            assertEquals(0, block.getInt(0));
            assertEquals(3, block.getInt(12));
            file.read(2, block);
            assertEquals(8, block.getInt(0));
            assertEquals(9, block.getInt(4));
        }
    }

    @Test
    void closeWritesPartialBlockWithZeroPaddedTail() throws IOException {
        try (BlockFile file = newFile(B)) {
            BlockWriter out = new BlockWriter(file, budget(), 0);
            out.writeInt(77);
            out.close();

            assertEquals(1, file.blockCount());
            ByteBuffer block = ByteBuffer.allocate(B);
            file.read(0, block);
            assertEquals(77, block.getInt(0));
            assertEquals(0, block.getInt(4));
            assertEquals(0, block.getLong(8));
        }
    }

    @Test
    void exactlyFullBlockProducesNoExtraBlock() throws IOException {
        try (BlockFile file = newFile(B)) {
            BlockWriter out = new BlockWriter(file, budget(), 0);
            for (int i = 0; i < 4; i++) {
                out.writeInt(i);
            }
            out.close();

            assertEquals(1, file.blockCount());
            assertEquals(1, file.stats().writes());
        }
    }

    @Test
    void nothingWrittenMeansNothingOnDisk() throws IOException {
        try (BlockFile file = newFile(B)) {
            new BlockWriter(file, budget(), 0).close();

            assertEquals(0, file.blockCount());
            assertEquals(0, file.stats().total());
        }
    }

    @Test
    void elementThatDoesNotFitMovesWholeToNextBlock() throws IOException {
        try (BlockFile file = newFile(10)) {
            BlockWriter out = new BlockWriter(file, budget(), 0);
            out.writeInt(1);
            out.writeInt(2);
            out.writeInt(3);
            out.close();

            assertEquals(2, file.blockCount());
            ByteBuffer block = ByteBuffer.allocate(10);
            file.read(0, block);
            assertEquals(1, block.getInt(0));
            assertEquals(2, block.getInt(4));
            assertEquals(0, block.get(8));
            assertEquals(0, block.get(9));
            file.read(1, block);
            assertEquals(3, block.getInt(0));
        }
    }

    @Test
    void writingStartsAtGivenBlockAndLeavesEarlierBlocksUntouched() throws IOException {
        try (BlockFile file = newFile(B)) {
            file.allocate(2);
            ByteBuffer marker = ByteBuffer.allocate(B);
            marker.putInt(0, 99);
            file.write(1, marker);
            file.stats().reset();

            BlockWriter out = new BlockWriter(file, budget(), 2);
            for (int i = 0; i < 5; i++) {
                out.writeInt(i);
            }
            out.close();

            assertEquals(4, file.blockCount());
            assertEquals(2, file.stats().writes());
            ByteBuffer block = ByteBuffer.allocate(B);
            file.read(1, block);
            assertEquals(99, block.getInt(0));
            file.read(3, block);
            assertEquals(4, block.getInt(0));
        }
    }

    @Test
    void writerHoldsExactlyOneBlockOfBudgetUntilClosed() throws IOException {
        MemoryBudget budget = budget();
        try (BlockFile file = newFile(B)) {
            BlockWriter out = new BlockWriter(file, budget, 0);
            assertEquals(B, budget.used());

            out.close();
            assertEquals(0, budget.used());
            out.close();
            assertEquals(0, budget.used());
        }
    }

    @Test
    void writerIsRejectedWhenBudgetCannotFitOneBlock() throws IOException {
        MemoryBudget tight = new MemoryBudget(B - 1);
        try (BlockFile file = newFile(B)) {
            assertThrows(MemoryBudgetExceededException.class, () -> new BlockWriter(file, tight, 0));
            assertEquals(0, tight.used());
        }
    }

    @Test
    void writingAfterCloseIsRejected() throws IOException {
        try (BlockFile file = newFile(B)) {
            BlockWriter out = new BlockWriter(file, budget(), 0);
            out.close();

            assertThrows(IllegalStateException.class, () -> out.writeInt(1));
        }
    }

    @Test
    void blockSmallerThanElementIsRejected() throws IOException {
        MemoryBudget budget = budget();
        try (BlockFile file = newFile(3)) {
            assertThrows(IllegalArgumentException.class, () -> new BlockWriter(file, budget, 0));
            assertEquals(0, budget.used());
        }
    }

    @Test
    void firstBlockMustBeInsideOrJustPastTheFile() throws IOException {
        try (BlockFile file = newFile(B)) {
            file.allocate(2);

            assertThrows(IllegalArgumentException.class, () -> new BlockWriter(file, budget(), -1));
            assertThrows(IllegalArgumentException.class, () -> new BlockWriter(file, budget(), 3));
            new BlockWriter(file, budget(), 2).close();
        }
    }
}
