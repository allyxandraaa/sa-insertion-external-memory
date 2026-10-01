package ua.ukma.sa.block;

public final class MemoryBudgetExceededException extends RuntimeException {

    public MemoryBudgetExceededException(long requested, long available, long limit) {
        super("Cannot reserve " + requested + " bytes: " + available + " available of " + limit);
    }
}
