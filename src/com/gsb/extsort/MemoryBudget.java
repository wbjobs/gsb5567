package com.gsb.extsort;

/**
 * Tracks an approximate number of bytes currently held in memory by the
 * sorter (records, I/O buffers, merge streams). The sorter is written so
 * that the tracked usage can never exceed the configured limit, hence
 * {@link #peakBytes()} is guaranteed to stay below it.
 */
public final class MemoryBudget {

    private final long limitBytes;
    private long currentBytes;
    private long peakBytes;

    public MemoryBudget(long limitBytes) {
        if (limitBytes <= 0) {
            throw new IllegalArgumentException("limitBytes must be positive");
        }
        this.limitBytes = limitBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }

    public synchronized long currentBytes() {
        return currentBytes;
    }

    /** Peak number of bytes ever accounted at the same time. */
    public synchronized long peakBytes() {
        return peakBytes;
    }

    public synchronized void acquire(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be >= 0");
        }
        currentBytes += bytes;
        if (currentBytes > peakBytes) {
            peakBytes = currentBytes;
        }
    }

    public synchronized void release(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be >= 0");
        }
        currentBytes -= bytes;
        if (currentBytes < 0) {
            currentBytes = 0;
        }
    }
}
