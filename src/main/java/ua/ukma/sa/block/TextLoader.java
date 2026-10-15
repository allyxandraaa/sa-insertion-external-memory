package ua.ukma.sa.block;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TextLoader {

    private TextLoader() {
    }

    public static ExternalByteArray load(BufferPool pool, MemoryBudget budget, Path source) throws IOException {
        long size = Files.size(source);
        requireSupportedSize(size);
        int chunkBytes = pool.file().blockSize();

        budget.reserve(chunkBytes);
        try {
            ExternalByteArray array = ExternalByteArray.create(pool, size);
            byte[] chunk = new byte[chunkBytes];
            long position = 0;
            try (InputStream in = Files.newInputStream(source)) {
                int read;
                while ((read = in.readNBytes(chunk, 0, chunkBytes)) > 0) {
                    if (read > size - position) {
                        throw new IOException("File grew while loading: " + source);
                    }
                    array.write(position, chunk, read);
                    position += read;
                }
            }
            if (position != size) {
                throw new IOException("File shrank while loading: " + source);
            }
            array.flush();
            return array;
        } finally {
            budget.release(chunkBytes);
        }
    }

    static void requireSupportedSize(long size) {
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Text of " + size + " bytes exceeds " + Integer.MAX_VALUE);
        }
    }
}
