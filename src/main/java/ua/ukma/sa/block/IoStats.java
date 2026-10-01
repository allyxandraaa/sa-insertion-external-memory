package ua.ukma.sa.block;

public final class IoStats {

    public record Snapshot(long sequentialReads, long randomReads, long sequentialWrites, long randomWrites) {

        public long reads() {
            return sequentialReads + randomReads;
        }

        public long writes() {
            return sequentialWrites + randomWrites;
        }

        public long total() {
            return reads() + writes();
        }

        public Snapshot minus(Snapshot other) {
            return new Snapshot(
                    sequentialReads - other.sequentialReads,
                    randomReads - other.randomReads,
                    sequentialWrites - other.sequentialWrites,
                    randomWrites - other.randomWrites);
        }
    }

    private long sequentialReads;
    private long randomReads;
    private long sequentialWrites;
    private long randomWrites;

    private long lastReadBlock = -1;
    private long lastWriteBlock = -1;

    void recordRead(long blockId) {
        if (blockId == lastReadBlock + 1) {
            sequentialReads++;
        } else {
            randomReads++;
        }
        lastReadBlock = blockId;
    }

    void recordWrite(long blockId) {
        if (blockId == lastWriteBlock + 1) {
            sequentialWrites++;
        } else {
            randomWrites++;
        }
        lastWriteBlock = blockId;
    }

    public void reset() {
        sequentialReads = 0;
        randomReads = 0;
        sequentialWrites = 0;
        randomWrites = 0;
        lastReadBlock = -1;
        lastWriteBlock = -1;
    }

    public Snapshot snapshot() {
        return new Snapshot(sequentialReads, randomReads, sequentialWrites, randomWrites);
    }

    public long sequentialReads() {
        return sequentialReads;
    }

    public long randomReads() {
        return randomReads;
    }

    public long sequentialWrites() {
        return sequentialWrites;
    }

    public long randomWrites() {
        return randomWrites;
    }

    public long reads() {
        return sequentialReads + randomReads;
    }

    public long writes() {
        return sequentialWrites + randomWrites;
    }

    public long total() {
        return reads() + writes();
    }

    @Override
    public String toString() {
        return "reads: " + sequentialReads + " seq + " + randomReads + " rand | "
                + "writes: " + sequentialWrites + " seq + " + randomWrites + " rand";
    }
}
