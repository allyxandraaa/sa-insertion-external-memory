package ua.ukma.sa.construction;

import java.io.IOException;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.BlockReader;
import ua.ukma.sa.block.BlockWriter;
import ua.ukma.sa.block.MemoryBudget;

public final class ColumnExtractor {

    private ColumnExtractor() {
    }

    public static void extract(BlockFile source, long records, int recordInts, int column, BlockFile target,
            MemoryBudget budget) throws IOException {
        if (recordInts < 1 || column < 0 || column >= recordInts) {
            throw new IllegalArgumentException("Column " + column + " out of range [0, " + recordInts + ")");
        }
        if (records < 0) {
            throw new IllegalArgumentException("Negative record count " + records);
        }
        if (target.blockCount() != 0) {
            throw new IllegalArgumentException("Target file must be empty, has " + target.blockCount() + " blocks");
        }
        try (BlockReader in = new BlockReader(source, budget, 0, records * recordInts);
                BlockWriter out = new BlockWriter(target, budget, 0)) {
            for (long record = 0; record < records; record++) {
                for (int field = 0; field < recordInts; field++) {
                    int value = in.readInt();
                    if (field == column) {
                        out.writeInt(value);
                    }
                }
            }
        }
    }
}
