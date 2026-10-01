package ua.ukma.sa.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MemoryBudgetTest {

    @Test
    void reserveAndReleaseTrackUsage() {
        MemoryBudget budget = new MemoryBudget(64);
        assertEquals(64, budget.limit());
        assertEquals(0, budget.used());
        assertEquals(64, budget.available());

        budget.reserve(16);
        budget.reserve(32);
        assertEquals(48, budget.used());
        assertEquals(16, budget.available());

        budget.release(16);
        assertEquals(32, budget.used());
        assertEquals(32, budget.available());
    }

    @Test
    void reservingExactlyTheLimitIsAllowed() {
        MemoryBudget budget = new MemoryBudget(64);
        budget.reserve(64);
        assertEquals(0, budget.available());
    }

    @Test
    void exceedingTheLimitThrowsAndLeavesStateUnchanged() {
        MemoryBudget budget = new MemoryBudget(64);
        budget.reserve(48);

        assertThrows(MemoryBudgetExceededException.class, () -> budget.reserve(17));

        assertEquals(48, budget.used());
        assertEquals(16, budget.available());
        budget.reserve(16);
    }

    @Test
    void releasedMemoryCanBeReservedAgain() {
        MemoryBudget budget = new MemoryBudget(32);
        budget.reserve(32);
        assertThrows(MemoryBudgetExceededException.class, () -> budget.reserve(1));

        budget.release(16);
        budget.reserve(16);
        assertEquals(32, budget.used());
    }

    @Test
    void releasingMoreThanReservedThrows() {
        MemoryBudget budget = new MemoryBudget(64);
        budget.reserve(16);

        assertThrows(IllegalStateException.class, () -> budget.release(17));
        assertEquals(16, budget.used());
    }

    @Test
    void rejectsNegativeArguments() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryBudget(-1));
        MemoryBudget budget = new MemoryBudget(64);
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.release(-1));
    }

    @Test
    void zeroLimitAllowsOnlyZeroBytes() {
        MemoryBudget budget = new MemoryBudget(0);
        budget.reserve(0);
        assertThrows(MemoryBudgetExceededException.class, () -> budget.reserve(1));
    }

    @Test
    void hugeRequestDoesNotOverflow() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        budget.reserve(Long.MAX_VALUE - 1);
        assertThrows(MemoryBudgetExceededException.class, () -> budget.reserve(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE - 1, budget.used());
    }
}
