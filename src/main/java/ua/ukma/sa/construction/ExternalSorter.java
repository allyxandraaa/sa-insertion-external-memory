package ua.ukma.sa.construction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import ua.ukma.sa.block.BlockFile;
import ua.ukma.sa.block.BlockReader;
import ua.ukma.sa.block.BlockWriter;
import ua.ukma.sa.block.IoStats;
import ua.ukma.sa.block.MemoryBudget;

public final class ExternalSorter {

    private static final int MAX_RUN_INTS = 1 << 29;

    private record Run(long firstBlock, long records) {
    }

    private static final class Head {

        private final int index;
        private final BlockReader reader;
        private final int[] record;

        private Head(int index, BlockReader reader, int recordInts) {
            this.index = index;
            this.reader = reader;
            this.record = new int[recordInts];
        }

        private void load() throws IOException {
            for (int k = 0; k < record.length; k++) {
                record[k] = reader.readInt();
            }
        }
    }

    private final int recordInts;
    private final RecordComparator comparator;
    private final MemoryBudget budget;
    private final int blockSize;
    private final TempFiles temps;

    private ExternalSorter(int recordInts, RecordComparator comparator, MemoryBudget budget, int blockSize,
            Path workDir) {
        this.recordInts = recordInts;
        this.comparator = comparator;
        this.budget = budget;
        this.blockSize = blockSize;
        this.temps = new TempFiles(workDir, blockSize);
    }

    public static IoStats.Snapshot sort(BlockFile input, BlockFile output, long records, int recordInts,
            RecordComparator comparator, MemoryBudget budget, Path workDir) throws IOException {
        if (recordInts < 1) {
            throw new IllegalArgumentException("Record must have at least one int: " + recordInts);
        }
        if (records < 0) {
            throw new IllegalArgumentException("Negative record count " + records);
        }
        if (input.blockSize() != output.blockSize()) {
            throw new IllegalArgumentException(
                    "Block sizes differ: " + input.blockSize() + " and " + output.blockSize());
        }
        if (output.blockCount() != 0) {
            throw new IllegalArgumentException("Output file must be empty, has " + output.blockCount() + " blocks");
        }
        if (records == 0) {
            return new IoStats.Snapshot(0, 0, 0, 0);
        }
        int blockSize = input.blockSize();
        long recordBytes = (long) recordInts * Integer.BYTES;
        long available = budget.available();
        if (available / blockSize < 3 || available - 2L * blockSize < recordBytes) {
            throw new IllegalArgumentException("Memory budget of " + available + " bytes is too small to sort");
        }
        return new ExternalSorter(recordInts, comparator, budget, blockSize, workDir).run(input, output, records);
    }

    private IoStats.Snapshot run(BlockFile input, BlockFile output, long records) throws IOException {
        try {
            long recordBytes = (long) recordInts * Integer.BYTES;
            long fitting = (budget.available() - 2L * blockSize) / recordBytes;
            int capacity = (int) Math.min(Math.min(records, fitting), MAX_RUN_INTS / recordInts);
            BlockFile runFile = capacity >= records ? output : temps.create();
            List<Run> runs = formRuns(input, records, capacity, runFile);
            if (runFile != output) {
                mergeRuns(runs, runFile, output);
            }
        } finally {
            temps.close();
        }
        return temps.io();
    }

    private List<Run> formRuns(BlockFile input, long records, int capacity, BlockFile destination)
            throws IOException {
        long arrayBytes = (long) capacity * recordInts * Integer.BYTES;
        budget.reserve(arrayBytes);
        try {
            int[] data = new int[capacity * recordInts];
            List<Run> runs = new ArrayList<>();
            try (BlockReader in = new BlockReader(input, budget, 0, records * recordInts)) {
                long left = records;
                while (left > 0) {
                    int chunk = (int) Math.min(left, capacity);
                    int ints = chunk * recordInts;
                    for (int k = 0; k < ints; k++) {
                        data[k] = in.readInt();
                    }
                    heapSort(data, chunk);
                    long firstBlock = destination.blockCount();
                    try (BlockWriter out = new BlockWriter(destination, budget, firstBlock)) {
                        for (int k = 0; k < ints; k++) {
                            out.writeInt(data[k]);
                        }
                    }
                    runs.add(new Run(firstBlock, chunk));
                    left -= chunk;
                }
            }
            return runs;
        } finally {
            budget.release(arrayBytes);
        }
    }

    private void mergeRuns(List<Run> runs, BlockFile source, BlockFile output) throws IOException {
        int fanIn = (int) Math.min(Integer.MAX_VALUE, budget.available() / blockSize - 1);
        BlockFile from = source;
        List<Run> current = runs;
        while (current.size() > fanIn) {
            BlockFile to = temps.create();
            List<Run> merged = new ArrayList<>();
            for (int start = 0; start < current.size(); start += fanIn) {
                List<Run> group = current.subList(start, Math.min(start + fanIn, current.size()));
                long firstBlock = to.blockCount();
                long total = mergeGroup(from, group, to, firstBlock);
                merged.add(new Run(firstBlock, total));
            }
            temps.release(from);
            from = to;
            current = merged;
        }
        mergeGroup(from, current, output, 0);
        temps.release(from);
    }

    private long mergeGroup(BlockFile from, List<Run> group, BlockFile to, long firstBlock) throws IOException {
        List<BlockReader> readers = new ArrayList<>();
        try (BlockWriter out = new BlockWriter(to, budget, firstBlock)) {
            PriorityQueue<Head> heap = new PriorityQueue<>((left, right) -> {
                int cmp = comparator.compare(left.record, 0, right.record, 0);
                return cmp != 0 ? cmp : Integer.compare(left.index, right.index);
            });
            for (int index = 0; index < group.size(); index++) {
                Run run = group.get(index);
                BlockReader reader = new BlockReader(from, budget, run.firstBlock(), run.records() * recordInts);
                readers.add(reader);
                Head head = new Head(index, reader, recordInts);
                head.load();
                heap.add(head);
            }
            long total = 0;
            while (!heap.isEmpty()) {
                Head head = heap.poll();
                for (int value : head.record) {
                    out.writeInt(value);
                }
                total++;
                if (head.reader.hasNext()) {
                    head.load();
                    heap.add(head);
                }
            }
            return total;
        } finally {
            for (BlockReader reader : readers) {
                reader.close();
            }
        }
    }

    private void heapSort(int[] data, int count) {
        for (int root = count / 2 - 1; root >= 0; root--) {
            siftDown(data, root, count);
        }
        for (int end = count - 1; end > 0; end--) {
            swap(data, 0, end);
            siftDown(data, 0, end);
        }
    }

    private void siftDown(int[] data, int start, int size) {
        int root = start;
        while (true) {
            int child = 2 * root + 1;
            if (child >= size) {
                return;
            }
            if (child + 1 < size
                    && comparator.compare(data, (child + 1) * recordInts, data, child * recordInts) > 0) {
                child++;
            }
            if (comparator.compare(data, root * recordInts, data, child * recordInts) >= 0) {
                return;
            }
            swap(data, root, child);
            root = child;
        }
    }

    private void swap(int[] data, int a, int b) {
        int aOffset = a * recordInts;
        int bOffset = b * recordInts;
        for (int k = 0; k < recordInts; k++) {
            int tmp = data[aOffset + k];
            data[aOffset + k] = data[bOffset + k];
            data[bOffset + k] = tmp;
        }
    }
}
