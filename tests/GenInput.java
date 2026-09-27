import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

/**
 * Generates a TSV file: {@code <lineNo>\tkey-<n>\t<random payload>}.
 * Column 0 is the original line number (used to verify stability),
 * column 1 is the sort key with heavy duplication.
 *
 * usage: GenInput <output> <numLines> <seed>
 */
public final class GenInput {
    public static void main(String[] args) throws Exception {
        String out = args[0];
        int lines = Integer.parseInt(args[1]);
        long seed = Long.parseLong(args[2]);
        Random random = new Random(seed);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(Paths.get(out)), StandardCharsets.UTF_8));
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            int key = random.nextInt(2000);
            int len = 20 + random.nextInt(60);
            payload.setLength(0);
            for (int j = 0; j < len; j++) {
                payload.append((char) ('a' + random.nextInt(26)));
            }
            w.write(i + "\tkey-" + key + "\t" + payload);
            w.write('\n');
        }
        w.close();
    }
}
