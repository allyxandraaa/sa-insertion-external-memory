package ua.ukma.sa.construction;

import java.io.IOException;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.BlockReader;
import ua.ukma.sa.block.BlockWriter;
import ua.ukma.sa.block.MemoryBudget;

public final class RankPairBuilder {

    public static final int RECORD_INTS = 3;

    private RankPairBuilder() {
    }

    public static void build(BlockFile ranks, long length, long shift, BlockFile pairs, MemoryBudget budget)
            throws IOException {
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Length " + length + " out of range [0, " + Integer.MAX_VALUE + "]");
        }
        if (shift < 1) {
            throw new IllegalArgumentException("Shift must be positive: " + shift);
        }
        if (pairs.blockCount() != 0) {
            throw new IllegalArgumentException("Pair file must be empty, has " + pairs.blockCount() + " blocks");
        }

        long secondCount = Math.max(0, length - shift);
        try (BlockReader first = new BlockReader(ranks, budget, 0, length);
                BlockReader second = BlockReader.atElement(ranks, budget, Math.min(shift, length), secondCount);
                BlockWriter out = new BlockWriter(pairs, budget, 0)) {
            for (long position = 0; position < length; position++) {
                int own = first.readInt();
                int next = second.hasNext() ? second.readInt() : 0;
                out.writeInt((int) position);
                out.writeInt(own);
                out.writeInt(next);
            }
        }
    }
}
