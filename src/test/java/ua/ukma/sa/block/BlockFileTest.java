package ua.ukma.sa.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockFileTest {

    private static final int B = 16;

    @TempDir
    Path dir;

    @Test
    void readReturnsWhatWasWritten() throws IOException {
        Random random = new Random(42);
        int blocks = 50;
        int[] reference = new int[blocks];
        ByteBuffer block = ByteBuffer.allocate(B);

        try (BlockFile file = new BlockFile(dir.resolve("dev.bin"), B)) {
            file.allocate(blocks);
            for (int op = 0; op < 1000; op++) {
                int id = random.nextInt(blocks);
                if (random.nextBoolean()) {
                    reference[id] = random.nextInt();
                    block.putInt(0, reference[id]);
                    file.write(id, block);
                } else {
                    file.read(id, block);
                    assertEquals(reference[id], block.getInt(0));
                }
            }
        }
    }

    @Test
    void scanFromStartIsSequential() throws IOException {
        ByteBuffer block = ByteBuffer.allocate(B);
        try (BlockFile file = new BlockFile(dir.resolve("dev.bin"), B)) {
            file.allocate(10);
            for (int id = 0; id < 10; id++) {
                file.write(id, block);
            }
            for (int id = 0; id < 10; id++) {
                file.read(id, block);
            }
            assertEquals(10, file.stats().sequentialWrites());
            assertEquals(10, file.stats().sequentialReads());
            assertEquals(0, file.stats().randomWrites());
            assertEquals(0, file.stats().randomReads());
        }
    }

    @Test
    void jumpsAreRandom() throws IOException {
        ByteBuffer block = ByteBuffer.allocate(B);
        try (BlockFile file = new BlockFile(dir.resolve("dev.bin"), B)) {
            file.allocate(30);
            for (int id : new int[] {5, 6, 7, 20, 21, 3}) {
                file.read(id, block);
            }
            assertEquals(3, file.stats().sequentialReads());
            assertEquals(3, file.stats().randomReads());
        }
    }

    @Test
    void allocateReturnsFirstNewBlockAndGrowsFile() throws IOException {
        Path path = dir.resolve("dev.bin");
        try (BlockFile file = new BlockFile(path, B)) {
            assertEquals(0, file.allocate(3));
            assertEquals(3, file.allocate(2));
            assertEquals(5, file.blockCount());
            assertEquals(5L * B, Files.size(path));
        }
    }

    @Test
    void allocatedBlocksAreReadableBeforeFirstWrite() throws IOException {
        ByteBuffer block = ByteBuffer.allocate(B);
        try (BlockFile file = new BlockFile(dir.resolve("dev.bin"), B)) {
            file.allocate(4);
            for (int id = 0; id < 4; id++) {
                file.read(id, block);
            }
        }
    }

    @Test
    void allocateKeepsExistingBlocksIntact() throws IOException {
        ByteBuffer block = ByteBuffer.allocate(B);
        ByteBuffer back = ByteBuffer.allocate(B);
        try (BlockFile file = new BlockFile(dir.resolve("dev.bin"), B)) {
            file.allocate(2);
            Arrays.fill(block.array(), (byte) 0x7F);
            file.write(1, block);

            assertEquals(2, file.allocate(0));
            assertEquals(2, file.blockCount());
            file.read(1, back);
            assertArrayEquals(block.array(), back.array());

            assertEquals(2, file.allocate(3));
            file.read(1, back);
            assertArrayEquals(block.array(), back.array());
        }
    }

    @Test
    void allocateRejectsNegativeCount() throws IOException {
        try (BlockFile file = new BlockFile(dir.resolve("dev.bin"), B)) {
            assertThrows(IllegalArgumentException.class, () -> file.allocate(-1));
            assertEquals(0, file.blockCount());
        }
    }

    @Test
    void readPastEndOfFileThrows() throws IOException {
        Path path = dir.resolve("dev.bin");
        ByteBuffer block = ByteBuffer.allocate(B);
        try (BlockFile file = new BlockFile(path, B)) {
            file.allocate(2);
            try (FileChannel other = FileChannel.open(path, StandardOpenOption.WRITE)) {
                other.truncate(B);
            }
            file.read(0, block);
            assertThrows(EOFException.class, () -> file.read(1, block));
        }
    }

    @Test
    void reopenKeepsContentAndBlockCount() throws IOException {
        Path path = dir.resolve("dev.bin");
        ByteBuffer block = ByteBuffer.allocate(B);
        Arrays.fill(block.array(), (byte) 0x2A);
        try (BlockFile file = new BlockFile(path, B)) {
            file.allocate(3);
            file.write(2, block);
        }
        ByteBuffer back = ByteBuffer.allocate(B);
        try (BlockFile file = new BlockFile(path, B)) {
            assertEquals(3, file.blockCount());
            file.read(2, back);
            assertArrayEquals(block.array(), back.array());
            assertEquals(3, file.allocate(1));
        }
    }

    @Test
    void createDiscardsExistingContent() throws IOException {
        Path path = dir.resolve("dev.bin");
        try (BlockFile file = new BlockFile(path, B)) {
            file.allocate(3);
        }
        try (BlockFile file = BlockFile.create(path, B)) {
            assertEquals(0, file.blockCount());
            assertEquals(0, Files.size(path));
        }
    }

    @Test
    void openRejectsFileThatIsNotWholeBlocks() throws IOException {
        Path path = dir.resolve("dev.bin");
        Files.write(path, new byte[B + 4]);
        assertThrows(IOException.class, () -> new BlockFile(path, B));
        assertThrows(IllegalArgumentException.class, () -> new BlockFile(dir.resolve("other.bin"), 0));
    }

    @Test
    void rejectsBadBlockIdAndBufferSize() throws IOException {
        try (BlockFile file = new BlockFile(dir.resolve("dev.bin"), B)) {
            file.allocate(2);
            assertThrows(IndexOutOfBoundsException.class, () -> file.read(2, ByteBuffer.allocate(B)));
            assertThrows(IllegalArgumentException.class, () -> file.write(0, ByteBuffer.allocate(B + 1)));
        }
    }
}
