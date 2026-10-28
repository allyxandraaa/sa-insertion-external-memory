package ua.ukma.sa.matching;

import java.util.Objects;

public final class KmpMatcher {

    private final byte[] pattern;
    private final int[] lps;
    private int matchedLength;

    public KmpMatcher(byte[] pattern) {
        Objects.requireNonNull(pattern);
        if (pattern.length == 0) {
            throw new IllegalArgumentException("Pattern is empty");
        }
        this.pattern = pattern.clone();
        this.lps = buildLps(this.pattern);
    }

    public int patternLength() {
        return pattern.length;
    }

    public boolean feed(byte next) {
        while (matchedLength > 0 && next != pattern[matchedLength]) {
            matchedLength = lps[matchedLength - 1];
        }
        if (next == pattern[matchedLength]) {
            matchedLength++;
        }
        if (matchedLength == pattern.length) {
            matchedLength = lps[matchedLength - 1];
            return true;
        }
        return false;
    }

    public void reset() {
        matchedLength = 0;
    }

    static int[] buildLps(byte[] pattern) {
        int[] lps = new int[pattern.length];
        int prefixLength = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (prefixLength > 0 && pattern[i] != pattern[prefixLength]) {
                prefixLength = lps[prefixLength - 1];
            }
            if (pattern[i] == pattern[prefixLength]) {
                prefixLength++;
            }
            lps[i] = prefixLength;
        }
        return lps;
    }
}
