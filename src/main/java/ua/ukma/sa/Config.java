package ua.ukma.sa;

import java.nio.file.Path;
import ua.ukma.sa.data.GeoNamesParser;

public final class Config {

    public static final int BLOCK_SIZE = 4096;
    public static final long POOL_BYTES = 64L * BLOCK_SIZE;
    public static final Path DATA_DIR = Path.of("data");
    public static final Path GEONAMES_SOURCE = DATA_DIR.resolve("allCountries.txt");
    public static final Path GEONAMES_TEXT = DATA_DIR.resolve("allCountries.names.txt");
    public static final int[] GEONAMES_COLUMNS = {GeoNamesParser.NAME, GeoNamesParser.ALTERNATE_NAMES};

    private Config() {
    }
}
