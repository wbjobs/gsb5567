import com.gsb.extsort.ExternalSort;

import java.nio.file.Paths;

/**
 * Runs the external sort programmatically and asserts that the reported
 * peakBytes() stays strictly below the configured memory limit.
 *
 * usage: PeakCheck <input> <output> <tempDir> <keyCol> <maxMemoryMb>
 */
public final class PeakCheck {
    public static void main(String[] args) throws Exception {
        String input = args[0];
        String output = args[1];
        String tempDir = args[2];
        int keyCol = Integer.parseInt(args[3]);
        long mb = Long.parseLong(args[4]);
        long limit = mb * 1024L * 1024L;

        ExternalSort sorter = new ExternalSort(
                Paths.get(input), Paths.get(output), Paths.get(tempDir),
                keyCol, limit, ExternalSort.Order.ASC, ExternalSort.BadLinePolicy.FAIL);
        sorter.sort();
        long peak = sorter.peakBytes();
        System.out.println("peak-bytes=" + peak + " limit=" + limit
                + " fanIn=" + sorter.fanIn() + " runBudget=" + sorter.runBudgetBytes());
        if (peak <= 0) {
            System.out.println("FAIL: peakBytes() was not tracked");
            System.exit(1);
        }
        if (peak >= limit) {
            System.out.println("FAIL: peak " + peak + " >= limit " + limit);
            System.exit(1);
        }
        System.out.println("OK: peak " + peak + " < limit " + limit);
    }
}
