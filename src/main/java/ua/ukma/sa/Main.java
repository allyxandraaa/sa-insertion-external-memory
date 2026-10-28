package ua.ukma.sa;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.BufferPool;
import ua.ukma.sa.block.MemoryBudget;
import ua.ukma.sa.buffer.InsertionBuffer;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Config.DATA_DIR);
        blockLayerDemo();
        System.out.println();
        insertionBufferDemo();
    }

    private static void blockLayerDemo() throws IOException {
        System.out.println("=== Block layer ===");
        ByteBuffer block = ByteBuffer.allocate(Config.BLOCK_SIZE);

        try (BlockFile file = BlockFile.create(Config.DATA_DIR.resolve("demo.bin"), Config.BLOCK_SIZE)) {
            file.allocate(5);
            log("allocate 5", file);

            for (int id = 0; id < 5; id++) {
                block.putInt(0, id * 100);
                file.write(id, block);
                log("write block " + id, file);
            }

            for (int id : new int[] {0, 1, 2, 3, 4, 2, 3, 0}) {
                file.read(id, block);
                log("read block " + id + " -> " + block.getInt(0), file);
            }
        }
    }

    private static void insertionBufferDemo() throws IOException {
        System.out.println("=== Insertion buffer ===");
        int blockSize = 64;
        MemoryBudget budget = new MemoryBudget(16L * blockSize);
        String[] names = {
            "Київ", "Київська область", "Kyiv", "Львів", "Lviv", "Харків", "Одеса", "Київ", "Івано-Франківськ"
        };

        try (BlockFile file = BlockFile.create(Config.DATA_DIR.resolve("demo-buffer.bin"), blockSize);
                BufferPool pool = new BufferPool(file, budget, (long) Config.BUFFER_POOL_FRAMES * blockSize)) {
            InsertionBuffer buffer = InsertionBuffer.create(pool, 10L * blockSize);
            System.out.printf("capacity %d bytes, block size %d bytes%n", buffer.capacityBytes(), blockSize);

            for (String name : names) {
                boolean stored = buffer.append(name);
                System.out.printf("append %-18s stored=%b, used=%d bytes, records=%d%n",
                        name, stored, buffer.sizeBytes(), buffer.recordCount());
            }
            log("after appends", file);

            for (String pattern : new String[] {"Київ", "ів", "yiv", "Одеса", "xyz"}) {
                file.stats().reset();
                long[] positions = buffer.findAll(pattern, budget);
                System.out.printf("find %-8s count=%d positions=%s%n",
                        pattern, positions.length, Arrays.toString(positions));
                log("  search cost", file);
            }
        }
    }

    private static void log(String operation, BlockFile file) {
        System.out.printf("%-22s %s%n", operation, file.stats());
    }
}
