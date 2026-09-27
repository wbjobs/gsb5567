package com.gsb.extsort;

/**
 * Tracks estimated live bytes owned by the sorter. Every large allocation
 * (record batch, per-way merge buffers, output buffer, IO slack) is reserved
 * before use and released afterwards, so {@link #peak()} is an upper-bound
 * style estimate of the worst concurrent memory footprint of the sort.
 */
final class MemoryLedger {

    private long current;
    private long peak;

    synchronized void reserve(long bytes) {
        current += bytes;
        if (current > peak) {
            peak = current;
        }
    }

    synchronized void release(long bytes) {
        current -= bytes;
        if (current < 0) {
            current = 0;
        }
    }

    synchronized long current() {
        return current;
    }

    synchronized long peak() {
        return peak;
    }
}
