package ua.ukma.sa;

import java.nio.file.Path;
import ua.ukma.sa.data.GeoNamesParser;

public final class Config {

    public static final int BLOCK_SIZE = 4096;

    public static final int MEMORY_BLOCKS = 256;
    public static final long MEMORY_LIMIT_BYTES = bytes(MEMORY_BLOCKS);

    public static final int BUFFER_POOL_FRAMES = 1;
    public static final int SCANNER_BLOCKS = 1;

    private static final int POOL_BLOCKS = MEMORY_BLOCKS - BUFFER_POOL_FRAMES - SCANNER_BLOCKS;
    public static final int TEXT_POOL_FRAMES = POOL_BLOCKS / 2;
    public static final int SA_POOL_FRAMES = POOL_BLOCKS - TEXT_POOL_FRAMES;

    public static final Path DATA_DIR = Path.of("data");
    public static final Path GEONAMES_SOURCE = DATA_DIR.resolve("allCountries.txt");
    public static final Path GEONAMES_TEXT = DATA_DIR.resolve("allCountries.names.txt");
    public static final int[] GEONAMES_COLUMNS = {GeoNamesParser.NAME, GeoNamesParser.ALTERNATE_NAMES};

    private Config() {
    }

    public static long bytes(int blocks) {
        return (long) blocks * BLOCK_SIZE;
    }
}
