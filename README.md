# extsort — memory-bounded external sort (JDK 8, no dependencies)

Sorts UTF-8 tab-separated text by one column while keeping memory usage
under a hard limit. Pure JDK 8 standard library, no build tool required.

## Build

```sh
javac -encoding UTF-8 -d build $(find src -name '*.java')
```

## Usage

```sh
java -cp build com.gsb.extsort.Main sort \
  --input INPUT.tsv --output OUTPUT.tsv --key 2 \
  --max-memory-mb 64 --order asc --bad-line skip --temp-dir /tmp/extsort
```

- `--key` is the 1-based column number; comparison is lexicographic.
- `--order asc|desc`; equal keys always keep their original input order
  (stable sort, original line number is the tie-breaker).
- `--bad-line skip` counts and skips lines without enough columns;
  `--bad-line fail` aborts immediately with the line number and reason,
  and removes the partial output and all temporary files.
- On success the tool prints `peak-bytes: N` and `skipped-bad-lines: N`.

## How the memory limit is enforced

The byte limit is split in two halves:

1. **Spill phase** — input lines are accumulated in a chunk whose
   accounted bytes (per-record estimate) never exceed half the limit;
   full chunks are sorted in memory and written to temp files.
2. **Merge phase** — each open merge stream is accounted a fixed number
   of bytes, so the maximum fan-in is `remaining budget / per-stream
   bytes`. If there are more temp files than the fan-in allows, they are
   merged in batches over multiple passes; never all at once.

`ExternalSort.peakBytes()` exposes the peak accounted bytes, which is
always below `--max-memory-mb`.

## Tests

```sh
./run-tests.sh
```
