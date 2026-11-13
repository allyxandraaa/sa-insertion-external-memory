package ua.ukma.sa.construction;

import java.io.IOException;
import ua.ukma.sa.array.ExternalByteArray;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.BlockWriter;
import ua.ukma.sa.block.MemoryBudget;

public final class InitialRankBuilder {

    private InitialRankBuilder() {
    }

    public static void build(ExternalByteArray text, BlockFile ranks, MemoryBudget budget) throws IOException {
        if (ranks.blockCount() != 0) {
            throw new IllegalArgumentException("Rank file must be empty, has " + ranks.blockCount() + " blocks");
        }
        int chunkBytes = ranks.blockSize();
        budget.reserve(chunkBytes);
        try (BlockWriter out = new BlockWriter(ranks, budget, 0)) {
            byte[] chunk = new byte[chunkBytes];
            long length = text.length();
            for (long position = 0; position < length; position += chunkBytes) {
                int size = (int) Math.min(chunkBytes, length - position);
                text.read(position, chunk, size);
                for (int k = 0; k < size; k++) {
                    out.writeInt(Byte.toUnsignedInt(chunk[k]) + 1);
                }
            }
        } finally {
            budget.release(chunkBytes);
        }
    }
}
