package com.gsb.extsort;

/**
 * One input line held in memory while filling a sort chunk. Carries the
 * original 0-based line number ({@code seq}) so equal keys can be ordered
 * by their original input position (stable sort).
 */
final class Record {

    /** Rough per-object overhead (object headers, references, String internals). */
    private static final long OVERHEAD_BYTES = 128L;

    private final String key;
    private final String line;
    private final long seq;
    private final long estimatedBytes;

    Record(String key, String line, long seq) {
        this.key = key;
        this.line = line;
        this.seq = seq;
        // char[] data of the two strings plus fixed overhead.
        this.estimatedBytes = OVERHEAD_BYTES + 2L * (key.length() + line.length());
    }

    String key() {
        return key;
    }

    String line() {
        return line;
    }

    long seq() {
        return seq;
    }

    long estimatedBytes() {
        return estimatedBytes;
    }
}
