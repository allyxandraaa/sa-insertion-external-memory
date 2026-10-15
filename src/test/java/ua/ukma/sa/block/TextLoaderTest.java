package ua.ukma.sa.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextLoaderTest {

    private static final int B = 16;

    @TempDir
    Path dir;

    private Path source(String name, byte[] content) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, content);
        return path;
    }

    private static byte[] readAll(ExternalByteArray array) throws IOException {
        byte[] all = new byte[(int) array.length()];
        array.read(0, all, all.length);
        return all;
    }

    @Test
    void multiByteCharactersAcrossBlockBoundariesSurvive() throws IOException {
        String text = "abcdefghijklmnoК北京end\n";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Path src = source("src.txt", bytes);
        MemoryBudget budget = new MemoryBudget(2 * B);
        try (BlockFile file = BlockFile.create(dir.resolve("text.bin"), B);
                BufferPool pool = new BufferPool(file, budget, B)) {
            ExternalByteArray array = TextLoader.load(pool, budget, src);

            assertEquals(bytes.length, array.length());
            assertEquals((byte) 0xD0, array.get(15));
            assertEquals((byte) 0x9A, array.get(16));
            byte[] back = readAll(array);
            assertArrayEquals(bytes, back);
            assertEquals(text, new String(back, StandardCharsets.UTF_8));
        }
    }

    @Test
    void sizesAroundBlockBoundariesRoundTripOnSingleFramePool() throws IOException {
        Random random = new Random(5);
        for (int size : new int[] {0, 1, B - 1, B, B + 1, 2 * B, 3 * B + 5}) {
            byte[] content = new byte[size];
            random.nextBytes(content);
            Path src = source("src" + size + ".txt", content);
            MemoryBudget budget = new MemoryBudget(2 * B);
            try (BlockFile file = BlockFile.create(dir.resolve("text" + size + ".bin"), B);
                    BufferPool pool = new BufferPool(file, budget, B)) {
                ExternalByteArray array = TextLoader.load(pool, budget, src);

                assertEquals(size, array.length());
                assertEquals(1 + (size + B - 1) / B, file.blockCount());
                assertArrayEquals(content, readAll(array));
            }
        }
    }

    @Test
    void chunkBufferIsReservedOnlyWhileLoading() throws IOException {
        Path src = source("src.txt", new byte[100]);
        MemoryBudget budget = new MemoryBudget(2 * B);
        try (BlockFile file = BlockFile.create(dir.resolve("text.bin"), B);
                BufferPool pool = new BufferPool(file, budget, B)) {
            assertEquals(B, budget.used());

            TextLoader.load(pool, budget, src);

            assertEquals(B, budget.used());
        }
    }

    @Test
    void loadFailsWithoutCreatingAnythingWhenBudgetCannotFitTheChunk() throws IOException {
        Path src = source("src.txt", new byte[100]);
        MemoryBudget budget = new MemoryBudget(B);
        try (BlockFile file = BlockFile.create(dir.resolve("text.bin"), B);
                BufferPool pool = new BufferPool(file, budget, B)) {
            assertThrows(MemoryBudgetExceededException.class, () -> TextLoader.load(pool, budget, src));

            assertEquals(0, file.blockCount());
            assertEquals(B, budget.used());
        }
    }

    @Test
    void chunkIsReleasedWhenTargetFileIsNotEmpty() throws IOException {
        Path src = source("src.txt", new byte[100]);
        MemoryBudget budget = new MemoryBudget(2 * B);
        try (BlockFile file = BlockFile.create(dir.resolve("text.bin"), B);
                BufferPool pool = new BufferPool(file, budget, B)) {
            file.allocate(1);

            assertThrows(IllegalArgumentException.class, () -> TextLoader.load(pool, budget, src));

            assertEquals(B, budget.used());
        }
    }

    @Test
    void missingSourceFileFailsBeforeReservingMemory() throws IOException {
        MemoryBudget budget = new MemoryBudget(2 * B);
        try (BlockFile file = BlockFile.create(dir.resolve("text.bin"), B);
                BufferPool pool = new BufferPool(file, budget, B)) {
            assertThrows(IOException.class, () -> TextLoader.load(pool, budget, dir.resolve("missing.txt")));

            assertEquals(0, file.blockCount());
            assertEquals(B, budget.used());
        }
    }

    @Test
    void loadedTextCanBeReopenedFromDisk() throws IOException {
        byte[] content = new byte[70];
        new Random(9).nextBytes(content);
        Path src = source("src.txt", content);
        Path text = dir.resolve("text.bin");
        MemoryBudget budget = new MemoryBudget(2 * B);
        try (BlockFile file = BlockFile.create(text, B);
                BufferPool pool = new BufferPool(file, budget, B)) {
            TextLoader.load(pool, budget, src);
        }

        try (BlockFile file = new BlockFile(text, B);
                BufferPool pool = new BufferPool(file, new MemoryBudget(2 * B), B)) {
            ExternalByteArray array = ExternalByteArray.open(pool);
            assertEquals(content.length, array.length());
            assertArrayEquals(content, readAll(array));
        }
    }

    @Test
    void textLongerThanIntMaxValueIsRejected() {
        assertDoesNotThrow(() -> TextLoader.requireSupportedSize(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> TextLoader.requireSupportedSize(Integer.MAX_VALUE + 1L));
    }
}
