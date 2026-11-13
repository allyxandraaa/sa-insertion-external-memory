package ua.ukma.sa.construction;

import java.io.IOException;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.BlockReader;
import ua.ukma.sa.block.BlockWriter;
import ua.ukma.sa.block.MemoryBudget;

public final class RankAssigner {

    public static final int RECORD_INTS = 2;

    private RankAssigner() {
    }

    public static long assign(BlockFile sortedPairs, long length, BlockFile ranked, MemoryBudget budget)
            throws IOException {
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Length " + length + " out of range [0, " + Integer.MAX_VALUE + "]");
        }
        if (ranked.blockCount() != 0) {
            throw new IllegalArgumentException("Output file must be empty, has " + ranked.blockCount() + " blocks");
        }
        long distinct = 0;
        int previousFirst = 0;
        int previousSecond = 0;
        try (BlockReader in = new BlockReader(sortedPairs, budget, 0, length * RankPairBuilder.RECORD_INTS);
                BlockWriter out = new BlockWriter(ranked, budget, 0)) {
            for (long k = 0; k < length; k++) {
                int position = in.readInt();
                int first = in.readInt();
                int second = in.readInt();
                if (k == 0 || first != previousFirst || second != previousSecond) {
                    distinct++;
                }
                previousFirst = first;
                previousSecond = second;
                out.writeInt(position);
                out.writeInt((int) distinct);
            }
        }
        return distinct;
    }
}
