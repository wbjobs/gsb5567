package com.gsb.extsort;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command line entry point.
 *
 * <pre>
 * java -cp &lt;classes&gt; com.gsb.extsort.Main sort \
 *   --input PATH --output PATH --key N --max-memory-mb M \
 *   --order asc|desc --bad-line skip|fail --temp-dir DIR
 * </pre>
 *
 * On success prints "peak-bytes: N" and "skipped-bad-lines: N" to stdout.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int status;
        try {
            status = run(args);
        } catch (BadLineException e) {
            System.err.println("error: " + e.getMessage());
            status = 3;
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            printUsage();
            status = 2;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            status = 1;
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    private static int run(String[] args) throws IOException {
        if (args.length == 0 || !"sort".equals(args[0])) {
            printUsage();
            return 2;
        }
        Map<String, String> opts = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i++) {
            String name = args[i];
            if (!name.startsWith("--") || name.length() == 2 || i + 1 >= args.length) {
                throw new IllegalArgumentException("invalid argument: " + name);
            }
            opts.put(name.substring(2), args[++i]);
        }

        String input = required(opts, "input");
        String output = required(opts, "output");
        int key = (int) parseLong(required(opts, "key"), "key");
        long maxMemoryMb = parseLong(required(opts, "max-memory-mb"), "max-memory-mb");
        String order = required(opts, "order");
        String badLine = required(opts, "bad-line");
        String tempDir = required(opts, "temp-dir");

        if (key < 1) {
            throw new IllegalArgumentException("--key must be >= 1 (1-based column number)");
        }
        if (maxMemoryMb < 1) {
            throw new IllegalArgumentException("--max-memory-mb must be >= 1");
        }

        ExternalSort sorter = new ExternalSort(
                Paths.get(input), Paths.get(output), key,
                maxMemoryMb * 1024L * 1024L, order, badLine, Paths.get(tempDir));
        sorter.sort();
        System.out.println("peak-bytes: " + sorter.peakBytes());
        System.out.println("skipped-bad-lines: " + sorter.skippedBadLines());
        return 0;
    }

    private static String required(Map<String, String> opts, String name) {
        String value = opts.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing required option --" + name);
        }
        return value;
    }

    private static long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be an integer, got: " + value);
        }
    }

    private static void printUsage() {
        System.err.println("usage: java -cp <classes> com.gsb.extsort.Main sort"
                + " --input PATH --output PATH --key N --max-memory-mb M"
                + " --order asc|desc --bad-line skip|fail --temp-dir DIR");
    }
}
