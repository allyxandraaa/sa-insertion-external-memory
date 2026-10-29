package ua.ukma.sa.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import ua.ukma.sa.array.ExternalByteArray;
import ua.ukma.sa.array.ExternalIntArray;

public final class SuffixArrayIndex {

    private final ExternalByteArray text;
    private final ExternalIntArray suffixArray;

    public SuffixArrayIndex(ExternalByteArray text, ExternalIntArray suffixArray) {
        if (text.length() != suffixArray.length()) {
            throw new IllegalArgumentException("Suffix array has " + suffixArray.length()
                    + " entries but the text has " + text.length() + " bytes");
        }
        this.text = text;
        this.suffixArray = suffixArray;
    }

    public long size() {
        return text.length();
    }

    public long suffixStart(long rank) throws IOException {
        long start = suffixArray.get(rank);
        if (start < 0 || start >= text.length()) {
            throw new IOException("Suffix array entry " + start + " at rank " + rank + " is outside the text");
        }
        return start;
    }

    public byte[] suffixPrefix(long rank, int maxLength) throws IOException {
        if (maxLength < 0) {
            throw new IllegalArgumentException("Negative length " + maxLength);
        }
        long start = suffixStart(rank);
        int length = (int) Math.min(maxLength, text.length() - start);
        byte[] prefix = new byte[length];
        text.read(start, prefix, length);
        return prefix;
    }

    public long[] findAll(String pattern) throws IOException {
        return findAll(Normalizer.normalize(pattern, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8));
    }

    public long[] findAll(byte[] pattern) throws IOException {
        if (pattern.length == 0) {
            throw new IllegalArgumentException("Pattern is empty");
        }
        long lo = 0;
        long hi = size();
        long firstGreater = size();
        while (lo < hi) {
            long mid = (lo + hi) / 2;
            int cmp = compareToPattern(mid, pattern);
            if (cmp < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
                if (cmp > 0) {
                    firstGreater = mid;
                }
            }
        }
        long start = lo;
        if (start == size() || compareToPattern(start, pattern) != 0) {
            return new long[0];
        }

        lo = start + 1;
        hi = firstGreater;
        while (lo < hi) {
            long mid = (lo + hi) / 2;
            if (compareToPattern(mid, pattern) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        long end = lo;

        long[] positions = new long[Math.toIntExact(end - start)];
        for (long rank = start; rank < end; rank++) {
            positions[(int) (rank - start)] = suffixStart(rank);
        }
        return positions;
    }

    private int compareToPattern(long rank, byte[] pattern) throws IOException {
        byte[] prefix = suffixPrefix(rank, pattern.length);
        int common = Math.min(prefix.length, pattern.length);
        int cmp = Arrays.compareUnsigned(prefix, 0, common, pattern, 0, common);
        if (cmp != 0 || prefix.length == pattern.length) {
            return cmp;
        }
        return -1;
    }
}
