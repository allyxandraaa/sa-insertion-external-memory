package ua.ukma.sa.construction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.IoStats;

final class TempFiles {

    private final Path directory;
    private final int blockSize;
    private final Map<BlockFile, Path> files = new LinkedHashMap<>();
    private IoStats.Snapshot io = new IoStats.Snapshot(0, 0, 0, 0);

    TempFiles(Path directory, int blockSize) {
        this.directory = directory;
        this.blockSize = blockSize;
    }

    BlockFile create() throws IOException {
        Path path = Files.createTempFile(directory, "construction-", ".tmp");
        try {
            BlockFile file = BlockFile.create(path, blockSize);
            files.put(file, path);
            return file;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    void release(BlockFile file) throws IOException {
        Path path = files.remove(file);
        if (path == null) {
            throw new IllegalArgumentException("File is not owned by this workspace");
        }
        io = io.plus(file.stats().snapshot());
        try {
            file.close();
        } finally {
            Files.deleteIfExists(path);
        }
    }

    void add(IoStats.Snapshot extra) {
        io = io.plus(extra);
    }

    IoStats.Snapshot io() {
        return io;
    }

    void close() throws IOException {
        IOException failure = null;
        for (BlockFile file : new ArrayList<>(files.keySet())) {
            try {
                release(file);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
