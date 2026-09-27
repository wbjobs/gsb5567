package com.gsb.extsort;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Memory-bounded external sort for UTF-8 tab-separated text.
 *
 * Phase 1 (spill): input is read line by line; each line is accounted
 * against a byte budget derived from the configured memory limit. When the
 * current chunk would exceed its share of the budget, the chunk is sorted
 * in memory (by key, then by original line number for stability) and
 * written to a temporary file.
 *
 * Phase 2 (merge): sorted temp files are merged with a k-way heap merge.
 * Every merge input occupies one file handle and is accounted a fixed
 * number of bytes, so the maximum fan-in is derived from the remaining
 * budget. When there are more temp files than the fan-in allows, they are
 * merged in batches over multiple passes instead of opening all of them
 * at once.
 *
 * On any failure (including {@link BadLineException}) all temporary files
 * and the (possibly partial) output file are removed.
 */
public final class ExternalSort {

    public static final String ORDER_ASC = "asc";
    public static final String ORDER_DESC = "desc";
    public static final String BAD_LINE_SKIP = "skip";
    public static final String BAD_LINE_FAIL = "fail";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * Bytes accounted for one open merge input: read buffer plus the
     * in-flight record of that stream held in the merge heap.
     */
    private static final long STREAM_BYTES = 256L * 1024L;
    /** Bytes accounted for the single input reader of the spill phase. */
    private static final long READER_BYTES = 192L * 1024L;
    /** Bytes accounted for one open output writer. */
    private static final long WRITER_BYTES = 192L * 1024L;

    private final Path input;
    private final Path output;
    private final Path tempDir;
    private final int keyColumn; // 1-based
    private final boolean ascending;
    private final boolean skipBadLines;
    private final MemoryBudget budget;
    private final List<Path> tempFiles = new ArrayList<Path>();

    private long skippedBadLines;
    private long tempFileSeq;

    /**
     * @param input         input file (UTF-8, tab-separated)
     * @param output        output file, replaced if it exists
     * @param keyColumn     1-based number of the column to sort by
     * @param maxMemoryBytes hard memory budget; tracked usage stays below it
     * @param order         {@link #ORDER_ASC} or {@link #ORDER_DESC}
     * @param badLine       {@link #BAD_LINE_SKIP} or {@link #BAD_LINE_FAIL}
     * @param tempDir       directory for temporary sorted runs
     */
    public ExternalSort(Path input, Path output, int keyColumn, long maxMemoryBytes,
                        String order, String badLine, Path tempDir) {
        if (keyColumn < 1) {
            throw new IllegalArgumentException("keyColumn must be >= 1");
        }
        if (!ORDER_ASC.equals(order) && !ORDER_DESC.equals(order)) {
            throw new IllegalArgumentException("order must be asc or desc");
        }
        if (!BAD_LINE_SKIP.equals(badLine) && !BAD_LINE_FAIL.equals(badLine)) {
            throw new IllegalArgumentException("badLine must be skip or fail");
        }
        this.input = input;
        this.output = output;
        this.tempDir = tempDir;
        this.keyColumn = keyColumn;
        this.ascending = ORDER_ASC.equals(order);
        this.skipBadLines = BAD_LINE_SKIP.equals(badLine);
        this.budget = new MemoryBudget(maxMemoryBytes);
    }

    /** Peak number of bytes accounted at the same time; always below the configured limit. */
    public long peakBytes() {
        return budget.peakBytes();
    }

    /** Number of bad input lines skipped (only with {@code --bad-line skip}). */
    public long skippedBadLines() {
        return skippedBadLines;
    }

    public void sort() throws IOException {
        boolean success = false;
        try {
            Files.createDirectories(tempDir);
            List<Path> chunks = spillSortedChunks();
            mergeAll(chunks);
            success = true;
        } finally {
            deleteTempFiles();
            if (!success) {
                // Never leave a half-written output behind.
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 1: read input, spill sorted chunks bounded by the budget.
    // ------------------------------------------------------------------

    private List<Path> spillSortedChunks() throws IOException {
        // Half of the budget is reserved for in-chunk records, the other
        // half for the merge phase (stream buffers + writer).
        long chunkBudget = Math.max(STREAM_BYTES, budget.limitBytes() / 2);
        List<Path> chunks = new ArrayList<Path>();
        List<Record> chunk = new ArrayList<Record>();
        long chunkBytes = 0L;
        long seq = 0L;
        long lineNo = 0L;
        budget.acquire(READER_BYTES);
        BufferedReader reader = Files.newBufferedReader(input, UTF8);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String key = extractKey(line, lineNo);
                if (key == null) {
                    continue; // bad line, skipped
                }
                Record record = new Record(key, line, seq++);
                long size = record.estimatedBytes();
                if (chunkBytes + size > chunkBudget && !chunk.isEmpty()) {
                    chunks.add(spill(chunk));
                    budget.release(chunkBytes);
                    chunk = new ArrayList<Record>();
                    chunkBytes = 0L;
                }
                chunk.add(record);
                chunkBytes += size;
                budget.acquire(size);
            }
        } finally {
            reader.close();
            budget.release(READER_BYTES);
        }
        if (!chunk.isEmpty()) {
            chunks.add(spill(chunk));
            budget.release(chunkBytes);
        }
        return chunks;
    }

    /**
     * Returns the key of the line, or {@code null} for a skipped bad line.
     *
     * @throws BadLineException if the key column is missing and bad lines
     *                          must abort the run
     */
    private String extractKey(String line, long lineNo) throws BadLineException {
        int field = 0;
        int start = 0;
        while (true) {
            int tab = line.indexOf('\t', start);
            if (field == keyColumn - 1) {
                return tab < 0 ? line.substring(start) : line.substring(start, tab);
            }
            if (tab < 0) {
                String reason = "expected at least " + keyColumn
                        + " tab-separated column(s) but found " + (field + 1);
                if (skipBadLines) {
                    skippedBadLines++;
                    return null;
                }
                throw new BadLineException(lineNo, reason);
            }
            start = tab + 1;
            field++;
        }
    }

    private Path spill(List<Record> chunk) throws IOException {
        chunk.sort(recordComparator());
        Path file = newTempFile("chunk-");
        budget.acquire(WRITER_BYTES);
        BufferedWriter writer = Files.newBufferedWriter(file, UTF8);
        try {
            for (Record record : chunk) {
                writer.write(Long.toString(record.seq()));
                writer.write('\t');
                writer.write(record.key());
                writer.write('\t');
                writer.write(record.line());
                writer.newLine();
            }
        } finally {
            writer.close();
            budget.release(WRITER_BYTES);
        }
        return file;
    }

    // ------------------------------------------------------------------
    // Phase 2: k-way merge with a budget-bounded fan-in.
    // ------------------------------------------------------------------

    private void mergeAll(List<Path> chunks) throws IOException {
        if (chunks.isEmpty()) {
            writeEmptyOutput();
            return;
        }
        int fanIn = maxFanIn();
        List<Path> level = chunks;
        // Too many runs to open at once: merge them in batches of fanIn.
        while (level.size() > fanIn) {
            List<Path> next = new ArrayList<Path>();
            for (int i = 0; i < level.size(); i += fanIn) {
                List<Path> group = level.subList(i, Math.min(i + fanIn, level.size()));
                if (group.size() == 1) {
                    next.add(group.get(0));
                    continue;
                }
                Path merged = newTempFile("merge-");
                mergeGroup(group, merged);
                deleteAll(group);
                next.add(merged);
            }
            level = next;
        }
        mergeGroup(level, output);
        deleteAll(level);
    }

    /** Maximum number of simultaneously open merge inputs, derived from the budget. */
    private int maxFanIn() {
        long mergeBudget = budget.limitBytes() / 2;
        long usable = mergeBudget - WRITER_BYTES;
        long n = usable / STREAM_BYTES;
        if (n < 2) {
            n = 2; // a merge needs at least two inputs to make progress
        }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    private void mergeGroup(List<Path> inputs, Path dest) throws IOException {
        PriorityQueue<MergeEntry> heap =
                new PriorityQueue<MergeEntry>(Math.max(1, inputs.size()), mergeComparator());
        List<BufferedReader> readers = new ArrayList<BufferedReader>(inputs.size());
        budget.acquire(WRITER_BYTES);
        BufferedWriter writer = Files.newBufferedWriter(dest, UTF8);
        try {
            for (int i = 0; i < inputs.size(); i++) {
                BufferedReader reader = Files.newBufferedReader(inputs.get(i), UTF8);
                readers.add(reader);
                budget.acquire(STREAM_BYTES);
                MergeEntry entry = readEntry(reader, i);
                if (entry != null) {
                    heap.add(entry);
                }
            }
            while (!heap.isEmpty()) {
                MergeEntry entry = heap.poll();
                writer.write(entry.line);
                writer.newLine();
                MergeEntry next = readEntry(readers.get(entry.stream), entry.stream);
                if (next != null) {
                    heap.add(next);
                }
            }
        } finally {
            writer.close();
            for (BufferedReader reader : readers) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
            budget.release(WRITER_BYTES);
            budget.release(STREAM_BYTES * readers.size());
        }
    }

    /** Temp files store "seq \t key \t line" so the merge can compare stably. */
    private static MergeEntry readEntry(BufferedReader reader, int stream) throws IOException {
        String raw = reader.readLine();
        if (raw == null) {
            return null;
        }
        int t1 = raw.indexOf('\t');
        int t2 = raw.indexOf('\t', t1 + 1);
        if (t1 < 0 || t2 < 0) {
            throw new IOException("corrupt temp file record: " + raw);
        }
        long seq = Long.parseLong(raw.substring(0, t1));
        String key = raw.substring(t1 + 1, t2);
        String line = raw.substring(t2 + 1);
        return new MergeEntry(key, line, seq, stream);
    }

    // ------------------------------------------------------------------
    // Comparators: key first, original line number second (stable).
    // ------------------------------------------------------------------

    private Comparator<Record> recordComparator() {
        return new Comparator<Record>() {
            public int compare(Record a, Record b) {
                int c = ascending ? a.key().compareTo(b.key()) : b.key().compareTo(a.key());
                if (c != 0) {
                    return c;
                }
                return Long.compare(a.seq(), b.seq());
            }
        };
    }

    private Comparator<MergeEntry> mergeComparator() {
        return new Comparator<MergeEntry>() {
            public int compare(MergeEntry a, MergeEntry b) {
                int c = ascending ? a.key.compareTo(b.key) : b.key.compareTo(a.key);
                if (c != 0) {
                    return c;
                }
                return Long.compare(a.seq, b.seq);
            }
        };
    }

    // ------------------------------------------------------------------
    // Housekeeping.
    // ------------------------------------------------------------------

    private Path newTempFile(String prefix) {
        String name = prefix + String.format("%06d", tempFileSeq++) + ".tmp";
        Path file = tempDir.resolve(name);
        tempFiles.add(file);
        return file;
    }

    private void writeEmptyOutput() throws IOException {
        budget.acquire(WRITER_BYTES);
        try {
            BufferedWriter writer = Files.newBufferedWriter(output, UTF8);
            writer.close();
        } finally {
            budget.release(WRITER_BYTES);
        }
    }

    private void deleteAll(List<Path> files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private void deleteTempFiles() {
        deleteAll(tempFiles);
        tempFiles.clear();
    }

    private static final class MergeEntry {
        final String key;
        final String line;
        final long seq;
        final int stream;

        MergeEntry(String key, String line, long seq, int stream) {
            this.key = key;
            this.line = line;
            this.seq = seq;
            this.stream = stream;
        }
    }
}
