package ua.ukma.sa.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalByteArrayTest {

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
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalByteArray array = ExternalByteArray.create(pool, 40);
            assertEquals(40, array.length());
            assertEquals(4, file.blockCount());
            assertEquals(4L * B, Files.size(path));
        }
    }

    @Test
    void lengthExactlyFillingBlocksAddsNoExtraBlock() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalByteArray.create(pool, 2 * B);
            assertEquals(3, file.blockCount());
        }
    }

    @Test
    void getReturnsBytesOnBlockBoundaries() throws IOException {
        byte[] data = new byte[40];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (200 + i);
        }
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), B)) {
            ExternalByteArray array = ExternalByteArray.create(pool, data.length);
            array.write(0, data, data.length);
            for (int i = 0; i < data.length; i++) {
                assertEquals(data[i], array.get(i));
            }
        }
    }

    @Test
    void rangeCrossingSeveralBlocksRoundTripsOnSingleFramePool() throws IOException {
        byte[] data = new byte[30];
        new Random(3).nextBytes(data);
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), B)) {
            ExternalByteArray array = ExternalByteArray.create(pool, 50);
            array.write(5, data, data.length);

            byte[] back = new byte[data.length];
            array.read(5, back, back.length);
            assertArrayEquals(data, back);

            byte[] slice = new byte[10];
            array.read(14, slice, 10);
            for (int i = 0; i < 10; i++) {
                assertEquals(data[9 + i], slice[i]);
            }
            assertEquals(0, array.get(4));
            assertEquals(0, array.get(35));
        }
    }

    @Test
    void onlyTheRequestedLengthIsCopied() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalByteArray array = ExternalByteArray.create(pool, 20);
            byte[] src = {1, 2, 3, 9, 9};
            array.write(14, src, 3);

            byte[] dst = {7, 7, 7, 7, 7};
            array.read(14, dst, 3);
            assertArrayEquals(new byte[] {1, 2, 3, 7, 7}, dst);
            assertEquals(0, array.get(13));
            assertEquals(0, array.get(17));
        }
    }

    @Test
    void randomOperationsMatchInMemoryArray() throws IOException {
        int length = 100;
        byte[] reference = new byte[length];
        Random random = new Random(7);
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalByteArray array = ExternalByteArray.create(pool, length);
            for (int op = 0; op < 3000; op++) {
                int offset = random.nextInt(length);
                int len = random.nextInt(Math.min(40, length - offset) + 1);
                if (random.nextBoolean()) {
                    byte[] chunk = new byte[len];
                    random.nextBytes(chunk);
                    System.arraycopy(chunk, 0, reference, offset, len);
                    array.write(offset, chunk, len);
                } else {
                    byte[] chunk = new byte[len];
                    array.read(offset, chunk, len);
                    byte[] expected = new byte[len];
                    System.arraycopy(reference, offset, expected, 0, len);
                    assertArrayEquals(expected, chunk);
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
        byte[] data = new byte[37];
        new Random(11).nextBytes(data);
        try (BlockFile file = BlockFile.create(path, B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalByteArray array = ExternalByteArray.create(pool, data.length);
            array.write(0, data, data.length);
        }
        assertEquals(4L * B, Files.size(path));

        try (BlockFile file = new BlockFile(path, B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalByteArray array = ExternalByteArray.open(pool);
            assertEquals(data.length, array.length());
            byte[] back = new byte[data.length];
            array.read(0, back, back.length);
            assertArrayEquals(data, back);
        }
    }

    @Test
    void emptyArrayHasOnlyHeader() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalByteArray array = ExternalByteArray.create(pool, 0);
            assertEquals(0, array.length());
            assertEquals(1, file.blockCount());
            assertThrows(IndexOutOfBoundsException.class, () -> array.get(0));
            array.read(0, new byte[0], 0);
            array.write(0, new byte[0], 0);
        }
    }

    @Test
    void rangesOutsideArrayOrBuffersAreRejectedWithoutTouchingThePool() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalByteArray array = ExternalByteArray.create(pool, 20);
            byte[] buf = new byte[8];
            long pinsBefore = pool.hits() + pool.misses();

            assertThrows(IndexOutOfBoundsException.class, () -> array.get(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> array.get(20));
            assertThrows(IndexOutOfBoundsException.class, () -> array.read(-1, buf, 4));
            assertThrows(IndexOutOfBoundsException.class, () -> array.read(15, buf, 6));
            assertThrows(IndexOutOfBoundsException.class, () -> array.read(0, buf, 9));
            assertThrows(IndexOutOfBoundsException.class, () -> array.read(0, buf, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> array.write(18, buf, 3));
            assertThrows(IndexOutOfBoundsException.class, () -> array.write(0, buf, 9));

            assertEquals(pinsBefore, pool.hits() + pool.misses());
        }
    }

    @Test
    void createRejectsNonEmptyFileNegativeLengthAndTinyBlocks() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            assertThrows(IllegalArgumentException.class, () -> ExternalByteArray.create(pool, -1));
            file.allocate(1);
            assertThrows(IllegalArgumentException.class, () -> ExternalByteArray.create(pool, 4));
        }
        try (BlockFile file = BlockFile.create(dir.resolve("b.bin"), 8);
                BufferPool pool = new BufferPool(file, budget(), 2 * 8)) {
            assertThrows(IllegalArgumentException.class, () -> ExternalByteArray.create(pool, 4));
        }
    }

    @Test
    void openRejectsEmptyFileAndFileWithoutHeader() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            assertThrows(IOException.class, () -> ExternalByteArray.open(pool));
            file.allocate(2);
            file.write(0, ByteBuffer.allocate(B));
            assertThrows(IOException.class, () -> ExternalByteArray.open(pool));
        }
    }

    @Test
    void openRejectsLengthThatDoesNotMatchBlockCount() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalByteArray.create(pool, 20);
            file.allocate(1);
            assertThrows(IOException.class, () -> ExternalByteArray.open(pool));
        }
    }

    @Test
    void byteAndIntArraysRejectEachOthersFiles() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("bytes.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalByteArray.create(pool, 8);
            assertThrows(IOException.class, () -> ExternalIntArray.open(pool));
        }
        try (BlockFile file = BlockFile.create(dir.resolve("ints.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalIntArray.create(pool, 4);
            assertThrows(IOException.class, () -> ExternalByteArray.open(pool));
        }
    }

    @Test
    void wholeArrayReadTouchesEachDataBlockOnce() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), B)) {
            ExternalByteArray array = ExternalByteArray.create(pool, 40);
            array.write(0, new byte[40], 40);
            array.flush();
            file.stats().reset();
            long missesBefore = pool.misses();

            array.read(0, new byte[40], 40);

            assertEquals(3, pool.misses() - missesBefore);
            assertEquals(3, file.stats().reads());
        }
    }

    @Test
    void bothArrayTypesShareTheCommonBaseType() throws IOException {
        try (BlockFile file = BlockFile.create(dir.resolve("a.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalArray array = ExternalByteArray.create(pool, 5);
            assertEquals(5, array.length());
            array.flush();
        }
        try (BlockFile file = BlockFile.create(dir.resolve("b.bin"), B);
                BufferPool pool = new BufferPool(file, budget(), 2 * B)) {
            ExternalArray array = ExternalIntArray.create(pool, 7);
            assertEquals(7, array.length());
            array.flush();
        }
    }
}
