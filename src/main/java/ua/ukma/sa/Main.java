package ua.ukma.sa;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import ua.ukma.sa.array.ExternalByteArray;
import ua.ukma.sa.array.ExternalIntArray;
import ua.ukma.sa.array.TextLoader;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.BufferPool;
import ua.ukma.sa.block.IoStats;
import ua.ukma.sa.block.MemoryBudget;
import ua.ukma.sa.construction.PrefixDoublingBuilder;
import ua.ukma.sa.data.GeoNamesParser;

public final class Main {

    private static final long MIB = 1024 * 1024;
    private static final long STEP_BYTES = 4 * MIB;
    private static final long MAX_BYTES = 32 * MIB;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Config.DATA_DIR);
        Path names = GeoNamesParser.ensureParsed(
                Config.GEONAMES_SOURCE, Config.GEONAMES_TEXT, Config.GEONAMES_COLUMNS);
        Path sample = Config.DATA_DIR.resolve("sample.txt");
        Path workDir = Files.createTempDirectory("sa-construction-");

        System.out.printf("memory: %d blocks of %d bytes%n", Config.MEMORY_BLOCKS, Config.BLOCK_SIZE);
        System.out.printf("%12s %6s %9s %12s %12s %12s %9s%n",
                "text bytes", "iters", "time, s", "reads", "writes", "total", "per byte");
        long available = Files.size(names);
        for (long bytes = STEP_BYTES; bytes <= Math.min(MAX_BYTES, available); bytes += STEP_BYTES) {
            copyPrefix(names, sample, bytes);
            run(sample, workDir);
        }
        Files.deleteIfExists(workDir);
    }

    private static void copyPrefix(Path source, Path target, long bytes) throws IOException {
        try (FileChannel in = FileChannel.open(source);
                FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            long copied = 0;
            while (copied < bytes) {
                copied += in.transferTo(copied, bytes - copied, out);
            }
        }
    }

    private static void run(Path sample, Path workDir) throws IOException {
        MemoryBudget budget = new MemoryBudget(Config.MEMORY_LIMIT_BYTES);
        long poolBytes = (long) Config.CONSTRUCTION_POOL_FRAMES * Config.BLOCK_SIZE;

        try (BlockFile textFile = BlockFile.create(Config.DATA_DIR.resolve("text.bin"), Config.BLOCK_SIZE);
                BlockFile saFile = BlockFile.create(Config.DATA_DIR.resolve("sa.bin"), Config.BLOCK_SIZE);
                BufferPool textPool = new BufferPool(textFile, budget, poolBytes);
                BufferPool saPool = new BufferPool(saFile, budget, poolBytes)) {
            ExternalByteArray text = TextLoader.load(textPool, budget, sample);
            ExternalIntArray suffixArray = ExternalIntArray.create(saPool, text.length());
            textFile.stats().reset();
            saFile.stats().reset();

            long started = System.nanoTime();
            PrefixDoublingBuilder.Result result = PrefixDoublingBuilder.build(text, suffixArray, budget, workDir);
            double seconds = (System.nanoTime() - started) / 1e9;

            IoStats.Snapshot io = result.io().plus(textFile.stats().snapshot()).plus(saFile.stats().snapshot());
            System.out.printf(Locale.ROOT, "%12d %6d %9.1f %12d %12d %12d %9.2f%n", text.length(),
                    result.iterations(), seconds, io.reads(), io.writes(), io.total(),
                    (double) io.total() / text.length());
        }
    }
}
