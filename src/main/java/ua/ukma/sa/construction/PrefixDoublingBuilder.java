package ua.ukma.sa.construction;

import java.io.IOException;
import java.nio.file.Path;
import ua.ukma.sa.array.ExternalByteArray;
import ua.ukma.sa.array.ExternalIntArray;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.BlockReader;
import ua.ukma.sa.block.IoStats;
import ua.ukma.sa.block.MemoryBudget;

public final class PrefixDoublingBuilder {

    public record Result(int iterations, IoStats.Snapshot io) {
    }

    private static final int PAIR_INTS = RankPairBuilder.RECORD_INTS;

    private static final RecordComparator BY_RANK_PAIR = (left, leftOffset, right, rightOffset) -> {
        int cmp = Integer.compare(left[leftOffset + 1], right[rightOffset + 1]);
        return cmp != 0 ? cmp : Integer.compare(left[leftOffset + 2], right[rightOffset + 2]);
    };

    private static final RecordComparator BY_POSITION =
            (left, leftOffset, right, rightOffset) -> Integer.compare(left[leftOffset], right[rightOffset]);

    private PrefixDoublingBuilder() {
    }

    public static Result build(ExternalByteArray text, ExternalIntArray suffixArray, MemoryBudget budget,
            Path workDir) throws IOException {
        long length = text.length();
        if (length != suffixArray.length()) {
            throw new IllegalArgumentException("Suffix array has " + suffixArray.length()
                    + " entries but the text has " + length + " bytes");
        }
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Text of " + length + " bytes exceeds " + Integer.MAX_VALUE);
        }
        if (length == 0) {
            return new Result(0, new IoStats.Snapshot(0, 0, 0, 0));
        }
        TempFiles temps = new TempFiles(workDir, text.file().blockSize());
        int iterations;
        try {
            iterations = run(text, suffixArray, budget, workDir, temps, length);
        } finally {
            temps.close();
        }
        return new Result(iterations, temps.io());
    }

    private static int run(ExternalByteArray text, ExternalIntArray suffixArray, MemoryBudget budget, Path workDir,
            TempFiles temps, long length) throws IOException {
        BlockFile ranks = temps.create();
        InitialRankBuilder.build(text, ranks, budget);

        long shift = 1;
        int iterations = 0;
        while (true) {
            iterations++;

            BlockFile pairs = temps.create();
            RankPairBuilder.build(ranks, length, shift, pairs, budget);
            temps.release(ranks);

            BlockFile sortedPairs = temps.create();
            temps.add(ExternalSorter.sort(pairs, sortedPairs, length, PAIR_INTS, BY_RANK_PAIR, budget, workDir));
            temps.release(pairs);

            BlockFile ranked = temps.create();
            long distinct = RankAssigner.assign(sortedPairs, length, ranked, budget);
            if (distinct == length) {
                writeSuffixArray(sortedPairs, length, suffixArray, budget);
                temps.release(ranked);
                temps.release(sortedPairs);
                return iterations;
            }
            temps.release(sortedPairs);

            BlockFile sortedRanked = temps.create();
            temps.add(ExternalSorter.sort(ranked, sortedRanked, length, RankAssigner.RECORD_INTS, BY_POSITION,
                    budget, workDir));
            temps.release(ranked);

            ranks = temps.create();
            ColumnExtractor.extract(sortedRanked, length, RankAssigner.RECORD_INTS, 1, ranks, budget);
            temps.release(sortedRanked);

            shift *= 2;
        }
    }

    private static void writeSuffixArray(BlockFile sortedPairs, long length, ExternalIntArray target,
            MemoryBudget budget) throws IOException {
        try (BlockReader in = new BlockReader(sortedPairs, budget, 0, length * PAIR_INTS)) {
            for (long rank = 0; rank < length; rank++) {
                int position = in.readInt();
                in.readInt();
                in.readInt();
                target.set(rank, position);
            }
        }
        target.flush();
    }
}
