package com.gsb.extsort;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Command line entry point:
 *
 * java -cp &lt;classes&gt; com.gsb.extsort.Main sort
 *     --input &lt;path&gt; --output &lt;path&gt; --key &lt;0-based column&gt;
 *     --max-memory-mb &lt;mb&gt; [--order asc|desc] [--bad-line skip|fail]
 *     --temp-dir &lt;dir&gt;
 *
 * Exit codes: 0 success, 1 bad input line with --bad-line fail,
 * 2 usage or IO error.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) {
        if (args.length < 1 || !"sort".equals(args[0])) {
            usage();
            return 2;
        }
        Map<String, String> opts = new HashMap<String, String>();
        for (int i = 1; i < args.length; i++) {
            String name = args[i];
            if (!name.startsWith("--") || i + 1 >= args.length) {
                usage();
                return 2;
            }
            opts.put(name.substring(2), args[++i]);
        }
        String input = opts.get("input");
        String output = opts.get("output");
        String tempDir = opts.get("temp-dir");
        String keyText = opts.get("key");
        String memText = opts.get("max-memory-mb");
        if (input == null || output == null || tempDir == null
                || keyText == null || memText == null) {
            usage();
            return 2;
        }
        int key;
        long maxMemoryMb;
        try {
            key = Integer.parseInt(keyText);
            maxMemoryMb = Long.parseLong(memText);
        } catch (NumberFormatException e) {
            System.err.println("error: --key and --max-memory-mb must be integers");
            return 2;
        }
        String orderText = opts.containsKey("order") ? opts.get("order") : "asc";
        if (!"asc".equals(orderText) && !"desc".equals(orderText)) {
            System.err.println("error: --order must be asc or desc");
            return 2;
        }
        String badLineText = opts.containsKey("bad-line") ? opts.get("bad-line") : "fail";
        if (!"skip".equals(badLineText) && !"fail".equals(badLineText)) {
            System.err.println("error: --bad-line must be skip or fail");
            return 2;
        }
        ExternalSort.Order order =
                "desc".equals(orderText) ? ExternalSort.Order.DESC : ExternalSort.Order.ASC;
        ExternalSort.BadLinePolicy policy = "skip".equals(badLineText)
                ? ExternalSort.BadLinePolicy.SKIP : ExternalSort.BadLinePolicy.FAIL;

        ExternalSort sorter;
        try {
            sorter = new ExternalSort(Paths.get(input), Paths.get(output), Paths.get(tempDir),
                    key, maxMemoryMb * 1024L * 1024L, order, policy);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
        try {
            sorter.sort();
        } catch (BadLineException e) {
            System.err.println("error: bad " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
        System.out.println("records: " + sorter.recordCount());
        System.out.println("bad-lines-skipped: " + sorter.badLineCount());
        System.out.println("peak-bytes: " + sorter.peakBytes());
        return 0;
    }

    private static void usage() {
        System.err.println("usage: java -cp <classes> com.gsb.extsort.Main sort"
                + " --input <path> --output <path> --key <0-based-column>"
                + " --max-memory-mb <mb> [--order asc|desc] [--bad-line skip|fail]"
                + " --temp-dir <dir>");
    }
}
