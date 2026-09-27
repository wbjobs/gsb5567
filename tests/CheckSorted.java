import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Verifies that a TSV file is ordered by the key column (asc or desc) and
 * that the order is stable: for equal keys the original line number stored
 * in column 0 must be strictly increasing.
 *
 * usage: CheckSorted <file> <keyCol> <asc|desc>
 */
public final class CheckSorted {
    public static void main(String[] args) throws Exception {
        String file = args[0];
        int keyCol = Integer.parseInt(args[1]);
        boolean desc = "desc".equals(args[2]);

        BufferedReader r = new BufferedReader(new InputStreamReader(
                Files.newInputStream(Paths.get(file)), StandardCharsets.UTF_8));
        String line;
        String prevKey = null;
        long prevId = -1;
        long n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            String[] cols = line.split("\t", -1);
            String key = cols[keyCol];
            long id = Long.parseLong(cols[0]);
            if (prevKey != null) {
                int c = key.compareTo(prevKey);
                if (desc) {
                    c = -c;
                }
                if (c < 0) {
                    System.out.println("FAIL: order violated at line " + n);
                    System.exit(1);
                }
                if (c == 0 && id < prevId) {
                    System.out.println("FAIL: stability violated at line " + n);
                    System.exit(1);
                }
            }
            prevKey = key;
            prevId = id;
        }
        r.close();
        System.out.println("OK: " + n + " lines ordered and stable");
    }
}
