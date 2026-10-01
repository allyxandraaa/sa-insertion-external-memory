package ua.ukma.sa.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalIntArrayTest {

    private static final int B = 16;

    @TempDir
    Path dir;

    private static MemoryBudget budget() {
        return new MemoryBudget(20 * B);
    }

    @Test
    void createAllocatesHeaderAndDataBlocks() throws IOException {
        Path path = dir.resolve("a.bin");
        try (BlockFile file = BlockFile.create(path, B);
                BufferPool pool = new BufferPool(file, budget(), 3 * B)) {
            ExternalIntArray array = ExternalIntArray.create(pool, 10);
            assertEquals(10, array.length());
            assertEquals(4, file.blockCount());
            assertEquals(4L * B, Files.size(path));
        }
    }

    @Test
    void valuesOnBlockBoundariesAreStoredSeparately() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalIntArray array = ExternalIntArray.create(pool, 9);
            for (int i = 0; i < 9; i++) {
                array.set(i, 1000 + i);
            }
            for (int i = 0; i < 9; i++) {
                assertEquals(1000 + i, array.get(i));
            }
            array.set(3, -1);
            array.set(4, -2);
            assertEquals(1002, array.get(2));
            assertEquals(-1, array.get(3));
            assertEquals(-2, array.get(4));
            assertEquals(1005, array.get(5));
            assertEquals(1008, array.get(8));
        }
    }

    @Test
    void randomOperationsMatchInMemoryArray() throws IOException {
        int length = 50;
        int[] reference = new int[length];
        Random random = new Random(7);
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 3 * B)) {
            ExternalIntArray array = ExternalIntArray.create(pool, length);
            for (int i = 0; i < length; i++) {
                reference[i] = random.nextInt();
                array.set(i, reference[i]);
            }
            for (int op = 0; op < 5000; op++) {
                int i = random.nextInt(length);
                if (random.nextBoolean()) {
                    reference[i] = random.nextInt();
                    array.set(i, reference[i]);
                } else {
                    assertEquals(reference[i], array.get(i));
                }
            }
            for (int i = 0; i < length; i++) {
                assertEquals(reference[i], array.get(i));
            }
        }
    }

    @Test
    void existingArrayCanBeReopenedWithItsLengthAndContent() throws IOException {
        Path path = dir.resolve("a.bin");
        int[] reference = new int[37];
        Random random = new Random(11);
        try (BlockFile file = BlockFile.create(path, B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalIntArray array = ExternalIntArray.create(pool, reference.length);
            for (int i = 0; i < reference.length; i++) {
                reference[i] = random.nextInt();
                array.set(i, reference[i]);
            }
        }
        assertEquals(11L * B, Files.size(path));

        try (BlockFile file = new BlockFile(path, B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalIntArray array = ExternalIntArray.open(pool);
            assertEquals(reference.length, array.length());
            for (int i = 0; i < reference.length; i++) {
                assertEquals(reference[i], array.get(i));
            }
            array.set(5, 12345);
        }

        try (BlockFile file = new BlockFile(path, B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            assertEquals(12345, ExternalIntArray.open(pool).get(5));
        }
    }

    @Test
    void emptyArrayHasOnlyHeader() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalIntArray array = ExternalIntArray.create(pool, 0);
            assertEquals(0, array.length());
            assertEquals(1, file.blockCount());
            assertThrows(IndexOutOfBoundsException.class, () -> array.get(0));
        }
    }

    @Test
    void indexOutsideRangeIsRejected() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalIntArray array = ExternalIntArray.create(pool, 10);
            long pinsBefore = pool.hits() + pool.misses();
            assertThrows(IndexOutOfBoundsException.class, () -> array.get(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> array.get(10));
            assertThrows(IndexOutOfBoundsException.class, () -> array.set(10, 1));
            assertEquals(pinsBefore, pool.hits() + pool.misses());
        }
    }

    @Test
    void createRejectsNonEmptyFileNegativeLengthAndTinyBlocks() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            assertThrows(IllegalArgumentException.class, () -> ExternalIntArray.create(pool, -1));
            file.allocate(1);
            assertThrows(IllegalArgumentException.class, () -> ExternalIntArray.create(pool, 4));
        }
        try (BlockFile file = BlockFile.create(dir.resolve("b.bin"), 8);
                BufferPool pool = new BufferPool(file, budget(), 2 * 8)) {
            assertThrows(IllegalArgumentException.class, () -> ExternalIntArray.create(pool, 4));
        }
    }

    @Test
    void openRejectsEmptyFileAndFileWithoutHeader() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            assertThrows(IOException.class, () -> ExternalIntArray.open(pool));
            file.allocate(2);
            ByteBuffer block = ByteBuffer.allocate(B);
            file.write(0, block);
            assertThrows(IOException.class, () -> ExternalIntArray.open(pool));
        }
    }

    @Test
    void openRejectsLengthThatDoesNotMatchBlockCount() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalIntArray.create(pool, 10);
            file.allocate(1);
            assertThrows(IOException.class, () -> ExternalIntArray.open(pool));
        }
    }

    @Test
    void sequentialScanReadsEachDataBlockOnce() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalIntArray array = ExternalIntArray.create(pool, 20);
            for (int i = 0; i < 20; i++) {
                array.set(i, i);
            }
            array.flush();
            file.stats().reset();
            long hitsBefore = pool.hits();
            long missesBefore = pool.misses();

            for (int i = 0; i < 20; i++) {
                assertEquals(i, array.get(i));
            }
            assertEquals(5, pool.misses() - missesBefore);
            assertEquals(15, pool.hits() - hitsBefore);
            assertEquals(5, file.stats().reads());
        }
    }
}
