package ua.ukma.sa;

import java.nio.file.Path;

public final class Config {

    public static final int BLOCK_SIZE = 4096;
    public static final Path DATA_DIR = Path.of("data");

    private Config() {
    }
}
