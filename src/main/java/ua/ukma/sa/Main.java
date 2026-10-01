package ua.ukma.sa;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import ua.ukma.sa.block.BlockFile;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Config.DATA_DIR);
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

    private static void log(String operation, BlockFile file) {
        System.out.printf("%-22s %s%n", operation, file.stats());
    }
}
