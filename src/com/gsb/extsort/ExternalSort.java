package com.gsb.extsort;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Memory-bounded external sort for UTF-8 tab-separated text.
 *
 * Phase 1 reads the input in batches whose estimated size never exceeds a
 * budget derived from {@code maxMemoryBytes}, sorts each batch in memory by
 * (key, original line number) and flushes it to a temporary run file.
 *
 * Phase 2 merges the run files. The fan-in (number of simultaneously open
 * run files) is derived from the same memory budget; when there are more
 * runs than the fan-in allows, runs are merged in multiple rounds so that at
 * most {@code fanIn} file handles are open at any time.
 *
 * All sizeable buffers are accounted in a {@link MemoryLedger}; the worst
 * concurrent reservation is exposed via {@link #peakBytes()} and is
 * guaranteed by construction to stay below {@code maxMemoryBytes}.
 */
public final class ExternalSort {

    public enum Order { ASC, DESC }

    public enum BadLinePolicy { SKIP, FAIL }

    /** Fixed reservation covering stream/decoder buffers outside the explicit buffers. */
    private static final long IO_SLACK_BYTES = 256L * 1024L;
    /** Smallest per-way buffer we are willing to merge with. */
    private static final long MIN_WAY_BUFFER_BYTES = 16L * 1024L;
    /** Hard cap on simultaneously open run files, independent of the budget. */
    private static final int MAX_FAN_IN = 16;
    /** Conservative per-record object overhead estimate (record + two strings + list slot). */
    private static final long RECORD_OVERHEAD_BYTES = 192L;
    /** Conservative per-line overhead estimate for merge-side buffered lines. */
    private static final long LINE_OVERHEAD_BYTES = 64L;

    private final Path input;
    private final Path output;
    private final Path tempDir;
    private final int keyIndex;
    private final long maxMemoryBytes;
    private final Order order;
    private final BadLinePolicy badLinePolicy;

    private final MemoryLedger ledger = new MemoryLedger();
    private final List<Path> tempFiles = new ArrayList<Path>();

    private long runBudgetBytes;
    private long wayBufferBytes;
    private long outBufferBytes;
    private int fanIn;

    private long recordCount;
    private long badLineCount;

    public ExternalSort(Path input, Path output, Path tempDir, int keyIndex,
                        long maxMemoryBytes, Order order, BadLinePolicy badLinePolicy) {
        if (input == null || output == null || tempDir == null) {
            throw new IllegalArgumentException("input/output/tempDir must not be null");
        }
        if (keyIndex < 0) {
            throw new IllegalArgumentException("key index must be >= 0");
        }
        if (maxMemoryBytes < 1024L * 1024L) {
            throw new IllegalArgumentException("max memory must be at least 1 MiB");
        }
        this.input = input;
        this.output = output;
        this.tempDir = tempDir;
        this.keyIndex = keyIndex;
        this.maxMemoryBytes = maxMemoryBytes;
        this.order = order == null ? Order.ASC : order;
        this.badLinePolicy = badLinePolicy == null ? BadLinePolicy.FAIL : badLinePolicy;
        computeBudget();
    }

    /** Peak estimated live bytes used by the sorter so far. */
    public long peakBytes() {
        return ledger.peak();
    }

    public long recordCount() {
        return recordCount;
    }

    public long badLineCount() {
        return badLineCount;
    }

    /** Maximum number of run files opened simultaneously during merging. */
    public int fanIn() {
        return fanIn;
    }

    /** Maximum estimated bytes held by a single in-memory batch. */
    public long runBudgetBytes() {
        return runBudgetBytes;
    }

    private void computeBudget() {
        long usable = maxMemoryBytes - IO_SLACK_BYTES;
        runBudgetBytes = usable / 2;
        long mergeBudget = usable - runBudgetBytes;
        outBufferBytes = Math.min(1024L * 1024L, Math.max(8192L, mergeBudget / 8));
        long wayBudget = mergeBudget - outBufferBytes;
        fanIn = (int) Math.min(MAX_FAN_IN, Math.max(2L, wayBudget / MIN_WAY_BUFFER_BYTES));
        wayBufferBytes = wayBudget / fanIn;
        // By construction every phase reserves at most IO_SLACK + max(run, merge)
        // which is strictly below maxMemoryBytes.
    }

    public void sort() throws IOException {
        ledger.reserve(IO_SLACK_BYTES);
        try {
            Files.createDirectories(tempDir);
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<Path> runs = buildRuns();
            mergeAll(runs);
        } catch (IOException e) {
            deleteQuietly(output);
            throw e;
        } catch (RuntimeException e) {
            deleteQuietly(output);
            throw e;
        } finally {
            for (Path p : tempFiles) {
                deleteQuietly(p);
            }
            ledger.release(IO_SLACK_BYTES);
        }
    }

    // ------------------------------------------------------------------
    // Phase 1: read input in bounded batches, sort and flush run files.
    // ------------------------------------------------------------------

    private List<Path> buildRuns() throws IOException {
        List<Path> runs = new ArrayList<Path>();
        BufferedReader reader = newReader(input, 8192);
        try {
            List<Record> batch = new ArrayList<Record>();
            long batchBytes = 0;
            String line;
            long lineNo = 0;
            long seq = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String key = extractKey(line);
                if (key == null) {
                    handleBadLine(lineNo, "expected at least " + (keyIndex + 1)
                            + " tab-separated column(s), key column " + keyIndex + " is missing");
                    continue;
                }
                Record record = new Record(key, line, seq++);
                long est = record.estimatedBytes();
                if (!batch.isEmpty() && batchBytes + est > runBudgetBytes) {
                    runs.add(flushRun(batch));
                    batch.clear();
                    ledger.release(batchBytes);
                    batchBytes = 0;
                }
                ledger.reserve(est);
                batch.add(record);
                batchBytes += est;
                recordCount++;
            }
            if (!batch.isEmpty()) {
                runs.add(flushRun(batch));
                ledger.release(batchBytes);
            }
        } finally {
            reader.close();
        }
        return runs;
    }

    private void handleBadLine(long lineNo, String reason) {
        if (badLinePolicy == BadLinePolicy.FAIL) {
            throw new BadLineException(lineNo, reason);
        }
        badLineCount++;
    }

    private Path flushRun(List<Record> batch) throws IOException {
        Collections.sort(batch, recordComparator());
        Path file = Files.createTempFile(tempDir, "run-", ".tmp");
        tempFiles.add(file);
        BufferedWriter writer = newWriter(file, 8192);
        try {
            for (Record record : batch) {
                writer.write(record.line);
                writer.write('\n');
            }
        } finally {
            writer.close();
        }
        return file;
    }

    private Comparator<Record> recordComparator() {
        return new Comparator<Record>() {
            @Override
            public int compare(Record a, Record b) {
                int c = a.key.compareTo(b.key);
                if (order == Order.DESC) {
                    c = -c;
                }
                if (c != 0) {
                    return c;
                }
                // Stability: original input order breaks key ties.
                return Long.compare(a.seq, b.seq);
            }
        };
    }

    // ------------------------------------------------------------------
    // Phase 2: bounded fan-in multi-way merge, in rounds if necessary.
    // ------------------------------------------------------------------

    private void mergeAll(List<Path> runs) throws IOException {
        List<Path> current = new ArrayList<Path>(runs);
        while (current.size() > fanIn) {
            List<Path> next = new ArrayList<Path>();
            for (int i = 0; i < current.size(); i += fanIn) {
                int end = Math.min(i + fanIn, current.size());
                if (end - i == 1) {
                    next.add(current.get(i));
                    continue;
                }
                List<Path> group = new ArrayList<Path>(current.subList(i, end));
                Path merged = Files.createTempFile(tempDir, "merge-", ".tmp");
                tempFiles.add(merged);
                mergeRuns(group, merged);
                for (Path p : group) {
                    deleteQuietly(p);
                }
                next.add(merged);
            }
            current = next;
        }
        if (current.isEmpty()) {
            BufferedWriter writer = newWriter(output, 8192);
            writer.close();
        } else {
            mergeRuns(current, output);
        }
    }

    private void mergeRuns(List<Path> files, Path dest) throws IOException {
        final Comparator<MergeHead> headOrder = new Comparator<MergeHead>() {
            @Override
            public int compare(MergeHead a, MergeHead b) {
                int c = a.key.compareTo(b.key);
                if (order == Order.DESC) {
                    c = -c;
                }
                if (c != 0) {
                    return c;
                }
                // Runs were produced in input order, so a lower run index
                // always holds the earlier original line numbers.
                return a.runIndex - b.runIndex;
            }
        };
        List<RunReader> readers = new ArrayList<RunReader>();
        ledger.reserve(outBufferBytes);
        BufferedWriter out = newWriter(dest, (int) Math.max(4096L, outBufferBytes / 2));
        try {
            PriorityQueue<MergeHead> heap =
                    new PriorityQueue<MergeHead>(Math.max(1, files.size()), headOrder);
            for (int i = 0; i < files.size(); i++) {
                RunReader reader = new RunReader(files.get(i));
                ledger.reserve(wayBufferBytes);
                readers.add(reader);
                String line = reader.next();
                if (line != null) {
                    heap.add(new MergeHead(extractKeyChecked(line), line, i, reader));
                }
            }
            while (!heap.isEmpty()) {
                MergeHead head = heap.poll();
                out.write(head.line);
                out.write('\n');
                String next = head.reader.next();
                if (next != null) {
                    heap.add(new MergeHead(extractKeyChecked(next), next, head.runIndex, head.reader));
                }
            }
        } finally {
            try {
                out.close();
            } finally {
                ledger.release(outBufferBytes);
                for (RunReader reader : readers) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                        // closing readers on a best-effort basis
                    }
                    ledger.release(wayBufferBytes);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Returns the key column value, or {@code null} if the line has too few columns. */
    private String extractKey(String line) {
        int start = 0;
        for (int i = 0; i < keyIndex; i++) {
            int tab = line.indexOf('\t', start);
            if (tab < 0) {
                return null;
            }
            start = tab + 1;
        }
        int end = line.indexOf('\t', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    /** Key extraction for lines already validated during the run-building phase. */
    private String extractKeyChecked(String line) {
        String key = extractKey(line);
        return key == null ? "" : key;
    }

    private BufferedReader newReader(Path path, int charBuffer) throws IOException {
        return new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8),
                charBuffer);
    }

    private BufferedWriter newWriter(Path path, int charBuffer) throws IOException {
        return new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8),
                charBuffer);
    }

    private static long lineEstimate(String line) {
        return LINE_OVERHEAD_BYTES + 2L * line.length();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private static final class Record {
        final String key;
        final String line;
        final long seq;

        Record(String key, String line, long seq) {
            this.key = key;
            this.line = line;
            this.seq = seq;
        }

        long estimatedBytes() {
            return RECORD_OVERHEAD_BYTES + 2L * (key.length() + line.length());
        }
    }

    private static final class MergeHead {
        final String key;
        final String line;
        final int runIndex;
        final RunReader reader;

        MergeHead(String key, String line, int runIndex, RunReader reader) {
            this.key = key;
            this.line = line;
            this.runIndex = runIndex;
            this.reader = reader;
        }
    }

    /**
     * Buffered reader over one run file. Never buffers more than
     * wayBufferBytes estimated bytes (a single line larger than the buffer
     * is still allowed through), and the reservation for that buffer is
     * held in the ledger for the reader's whole lifetime.
     */
    private final class RunReader {
        private final BufferedReader reader;
        private final ArrayDeque<String> buffer = new ArrayDeque<String>();
        private long bufferedBytes;
        private String pending;

        RunReader(Path path) throws IOException {
            this.reader = newReader(path, 2048);
        }

        String next() throws IOException {
            if (buffer.isEmpty()) {
                refill();
            }
            if (buffer.isEmpty()) {
                return null;
            }
            String line = buffer.poll();
            bufferedBytes -= lineEstimate(line);
            return line;
        }

        private void refill() throws IOException {
            if (pending != null) {
                buffer.add(pending);
                bufferedBytes += lineEstimate(pending);
                pending = null;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                long est = lineEstimate(line);
                if (bufferedBytes + est > wayBufferBytes) {
                    pending = line;
                    return;
                }
                buffer.add(line);
                bufferedBytes += est;
            }
        }

        void close() throws IOException {
            reader.close();
        }
    }
}
