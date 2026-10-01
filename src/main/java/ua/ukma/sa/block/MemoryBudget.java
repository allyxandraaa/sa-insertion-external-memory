package ua.ukma.sa.block;

public final class MemoryBudget {

    private final long limit;
    private long used;

    public MemoryBudget(long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("Negative limit " + limit);
        }
        this.limit = limit;
    }

    public synchronized void reserve(long bytes) {
        requireNonNegative(bytes);
        long available = limit - used;
        if (bytes > available) {
            throw new MemoryBudgetExceededException(bytes, available, limit);
        }
        used += bytes;
    }

    public synchronized void release(long bytes) {
        requireNonNegative(bytes);
        if (bytes > used) {
            throw new IllegalStateException("Cannot release " + bytes + " bytes: only " + used + " reserved");
        }
        used -= bytes;
    }

    public long limit() {
        return limit;
    }

    public synchronized long used() {
        return used;
    }

    public synchronized long available() {
        return limit - used;
    }

    private static void requireNonNegative(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Negative byte count " + bytes);
        }
    }
}
