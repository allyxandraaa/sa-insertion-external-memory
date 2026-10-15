package ua.ukma.sa.data;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GeoNamesParser {

    public static final int NAME = 1;
    public static final int ALTERNATE_NAMES = 3;

    private static final int FIELD_COUNT = 19;

    public record Result(long rowsRead, long rowsSkipped, long linesWritten, long bytesWritten) {
    }

    private GeoNamesParser() {
    }

    public static Path ensureParsed(Path source, Path target, int[] columns) throws IOException {
        if (!Files.exists(target)) {
            parse(source, columns, target);
        }
        return target;
    }

    public static Result parse(Path source, int[] columns, Path target) throws IOException {
        requireColumns(columns);
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve(target.getFileName() + ".part");

        long rowsRead = 0;
        long rowsSkipped = 0;
        long linesWritten = 0;
        try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(open(source), StandardCharsets.UTF_8.newDecoder()), 1 << 16);
                BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                rowsRead++;
                String[] fields = line.split("\t", -1);
                if (fields.length != FIELD_COUNT) {
                    rowsSkipped++;
                    continue;
                }
                Set<String> values = new LinkedHashSet<>();
                for (int column : columns) {
                    if (column == ALTERNATE_NAMES) {
                        for (String part : fields[column].split(",")) {
                            add(values, part);
                        }
                    } else {
                        add(values, fields[column]);
                    }
                }
                for (String value : values) {
                    out.write(value);
                    out.write('\n');
                    linesWritten++;
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        long bytesWritten = Files.size(temp);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return new Result(rowsRead, rowsSkipped, linesWritten, bytesWritten);
    }

    private static void add(Set<String> values, String value) {
        if (!value.isEmpty()) {
            values.add(Normalizer.normalize(value, Normalizer.Form.NFC));
        }
    }

    private static void requireColumns(int[] columns) {
        if (columns.length == 0) {
            throw new IllegalArgumentException("No columns requested");
        }
        for (int column : columns) {
            if (column < 0 || column >= FIELD_COUNT) {
                throw new IllegalArgumentException("Column " + column + " out of range [0, " + FIELD_COUNT + ")");
            }
        }
    }

    private static InputStream open(Path source) throws IOException {
        InputStream file = Files.newInputStream(source);
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return file;
        }
        ZipInputStream zip = new ZipInputStream(file);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && name.endsWith(".txt") && !name.endsWith("readme.txt")) {
                    return zip;
                }
            }
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
        zip.close();
        throw new IOException("No data file found in archive " + source);
    }
}
