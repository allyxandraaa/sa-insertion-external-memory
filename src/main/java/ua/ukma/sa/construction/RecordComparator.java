package ua.ukma.sa.construction;

@FunctionalInterface
public interface RecordComparator {

    int compare(int[] left, int leftOffset, int[] right, int rightOffset);
}
