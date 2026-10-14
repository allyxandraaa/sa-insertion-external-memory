package ua.ukma.sa.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferPoolTest {

    private static final int B = 16;

    @TempDir
    Path dir;

    private BlockFile newFile(int blocks) throws IOException {
        BlockFile file = new BlockFile(dir.resolve("dev.bin"), B);
        file.allocate(blocks);
        ByteBuffer block = ByteBuffer.allocate(B);
        for (int id = 0; id < blocks; id++) {
            block.putInt(0, id);
            file.write(id, block);
        }
        file.stats().reset();
        return file;
    }

    private static void touch(BufferPool pool, long blockId) throws IOException {
        pool.pin(blockId);
        pool.unpin(blockId, false);
    }

    @Test
    void secondPinOfCachedBlockIsAHitAndDoesNotReadDisk() throws IOException {
        try (BlockFile file = newFile(10);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 3 * B)) {
            touch(pool, 3);
            assertEquals(1, pool.misses());
            assertEquals(0, pool.hits());
            assertEquals(1, file.stats().reads());

            touch(pool, 3);
            assertEquals(1, pool.misses());
            assertEquals(1, pool.hits());
            assertEquals(1, file.stats().reads());
        }
    }

    @Test
    void rereadingPinnedBlockDoesNotIncreaseFileCounter() throws IOException {
        try (BlockFile file = newFile(10);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 3 * B)) {
            pool.pin(2);
            pool.pin(2);
            pool.pin(2);
            assertEquals(1, file.stats().reads());
            assertEquals(2, pool.hits());
            pool.unpin(2, false);
            pool.unpin(2, false);
            pool.unpin(2, false);
        }
    }

    @Test
    void pinReturnsBlockContent() throws IOException {
        try (BlockFile file = newFile(10);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 2 * B)) {
            for (int id = 0; id < 10; id++) {
                BufferPool.Frame frame = pool.pin(id);
                assertEquals(id, frame.blockId());
                assertEquals(id, frame.buffer().getInt(0));
                pool.unpin(id, false);
            }
        }
    }

    @Test
    void evictsLeastRecentlyUsedBlock() throws IOException {
        try (BlockFile file = newFile(10);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 3 * B)) {
            touch(pool, 0);
            touch(pool, 1);
            touch(pool, 2);
            touch(pool, 0);
            touch(pool, 3);
            touch(pool, 0);
            touch(pool, 2);
            touch(pool, 3);
            assertEquals(4, pool.hits());
            assertEquals(4, pool.misses());

            touch(pool, 1);
            assertEquals(4, pool.hits());
            assertEquals(5, pool.misses());
        }
    }

    @Test
    void dirtyFrameIsWrittenOnEviction() throws IOException {
        try (BlockFile file = newFile(10);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 2 * B)) {
            BufferPool.Frame frame = pool.pin(0);
            frame.buffer().putInt(0, 111);
            pool.unpin(0, true);
            assertEquals(0, file.stats().writes());

            touch(pool, 1);
            touch(pool, 2);
            assertEquals(1, file.stats().writes());

            ByteBuffer block = ByteBuffer.allocate(B);
            file.read(0, block);
            assertEquals(111, block.getInt(0));
        }
    }

    @Test
    void cleanFrameIsNotWrittenOnEviction() throws IOException {
        try (BlockFile file = newFile(10);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 2 * B)) {
            touch(pool, 0);
            touch(pool, 1);
            touch(pool, 2);
            touch(pool, 3);
            assertEquals(0, file.stats().writes());
        }
    }

    @Test
    void pinnedFramesAreNeverEvicted() throws IOException {
        try (BlockFile file = newFile(10);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 2 * B)) {
            pool.pin(0);
            touch(pool, 1);
            touch(pool, 2);
            assertEquals(3, pool.misses());

            pool.pin(0);
            assertEquals(1, pool.hits());
            pool.pin(2);
            assertThrows(IllegalStateException.class, () -> pool.pin(5));
        }
    }

    @Test
    void flushWritesDirtyFramesInBlockOrderAndOnlyOnce() throws IOException {
        try (BlockFile file = newFile(10);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 4 * B)) {
            for (int id : new int[] {2, 0, 1}) {
                pool.pin(id).buffer().putInt(0, 100 + id);
                pool.unpin(id, true);
            }
            touch(pool, 3);

            pool.flush();
            assertEquals(3, file.stats().writes());
            assertEquals(3, file.stats().sequentialWrites());

            pool.flush();
            assertEquals(3, file.stats().writes());

            ByteBuffer block = ByteBuffer.allocate(B);
            for (int id = 0; id < 3; id++) {
                file.read(id, block);
                assertEquals(100 + id, block.getInt(0));
            }
        }
    }

    @Test
    void closeFlushesDirtyFrames() throws IOException {
        try (BlockFile file = newFile(10)) {
            try (BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 2 * B)) {
                pool.pin(4).buffer().putInt(0, 444);
                pool.unpin(4, true);
            }
            ByteBuffer block = ByteBuffer.allocate(B);
            file.read(4, block);
            assertEquals(444, block.getInt(0));
        }
    }

    @Test
    void unpinWithoutPinThrows() throws IOException {
        try (BlockFile file = newFile(10);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 2 * B)) {
            assertThrows(IllegalStateException.class, () -> pool.unpin(3, false));
            pool.pin(3);
            pool.unpin(3, false);
            assertThrows(IllegalStateException.class, () -> pool.unpin(3, false));
        }
    }

    @Test
    void invalidBlockIsRejectedWithoutSideEffects() throws IOException {
        try (BlockFile file = newFile(10);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 2 * B)) {
            assertThrows(IndexOutOfBoundsException.class, () -> pool.pin(10));
            assertThrows(IndexOutOfBoundsException.class, () -> pool.pin(-1));
            assertEquals(0, pool.misses());
            assertEquals(0, file.stats().total());
        }
    }

    @Test
    void poolReservesAndReleasesMemoryInBudget() throws IOException {
        MemoryBudget budget = new MemoryBudget(6 * B);
        try (BlockFile file = newFile(10)) {
            BufferPool pool = new BufferPool(file, budget, 4 * B);
            assertEquals(4, pool.frameCount());
            assertEquals(4 * B, budget.used());

            assertThrows(MemoryBudgetExceededException.class, () -> new BufferPool(file, budget, 3 * B));
            assertEquals(4 * B, budget.used());

            pool.close();
            assertEquals(0, budget.used());
            pool.close();
            assertEquals(0, budget.used());
            assertThrows(IllegalStateException.class, () -> pool.pin(0));
        }
    }

    @Test
    void poolSmallerThanOneFrameIsRejected() throws IOException {
        try (BlockFile file = newFile(10)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BufferPool(file, new MemoryBudget(10 * B), B - 1));
        }
    }

    @Test
    void poolSizeNotMultipleOfBlockSizeIsRejected() throws IOException {
        MemoryBudget budget = new MemoryBudget(10 * B);
        try (BlockFile file = newFile(10)) {
            assertThrows(IllegalArgumentException.class, () -> new BufferPool(file, budget, 4 * B + 5));
            assertThrows(IllegalArgumentException.class, () -> new BufferPool(file, budget, 2 * B - 1));
            assertEquals(0, budget.used());
        }
    }

    @Test
    void randomOperationsMatchInMemoryModel() throws IOException {
        int blocks = 20;
        int intsPerBlock = B / 4;
        int[] reference = new int[blocks * intsPerBlock];
        for (int id = 0; id < blocks; id++) {
            reference[id * intsPerBlock] = id;
        }
        Random random = new Random(42);

        try (BlockFile file = newFile(blocks);
                BufferPool pool = new BufferPool(file, new MemoryBudget(10 * B), 4 * B)) {
            for (int op = 0; op < 5000; op++) {
                int id = random.nextInt(blocks);
                int slot = random.nextInt(intsPerBlock);
                BufferPool.Frame frame = pool.pin(id);
                if (random.nextBoolean()) {
                    int value = random.nextInt();
                    reference[id * intsPerBlock + slot] = value;
                    frame.buffer().putInt(slot * 4, value);
                    pool.unpin(id, true);
                } else {
                    assertEquals(reference[id * intsPerBlock + slot], frame.buffer().getInt(slot * 4));
                    pool.unpin(id, false);
                }
            }
            assertEquals(5000, pool.hits() + pool.misses());

            pool.flush();
            ByteBuffer block = ByteBuffer.allocate(B);
            for (int id = 0; id < blocks; id++) {
                file.read(id, block);
                for (int slot = 0; slot < intsPerBlock; slot++) {
                    assertEquals(reference[id * intsPerBlock + slot], block.getInt(slot * 4));
                }
            }
        }
    }
}
