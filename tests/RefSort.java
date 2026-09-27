import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reference sorter: reads the whole input into memory and sorts it with a
 * stable sort on the key column only. Used as the ground truth that the
 * external sort output is compared against, byte for byte.
 *
 * usage: RefSort <input> <output> <keyCol> <asc|desc>
 */
public final class RefSort {
    public static void main(String[] args) throws Exception {
        String input = args[0];
        String output = args[1];
        final int keyCol = Integer.parseInt(args[2]);
        final boolean desc = "desc".equals(args[3]);

        BufferedReader r = new BufferedReader(new InputStreamReader(
                Files.newInputStream(Paths.get(input)), StandardCharsets.UTF_8));
        List<String[]> rows = new ArrayList<String[]>();
        String line;
        while ((line = r.readLine()) != null) {
            rows.add(new String[]{line, line.split("\t", -1)[keyCol]});
        }
        r.close();

        Collections.sort(rows, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                int c = a[1].compareTo(b[1]);
                return desc ? -c : c;
            }
        });

        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(Paths.get(output)), StandardCharsets.UTF_8));
        for (String[] row : rows) {
            w.write(row[0]);
            w.write('\n');
        }
        w.close();
    }
}
