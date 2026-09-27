#!/usr/bin/env bash
# Acceptance tests for the memory-bounded external sort tool.
# Requires: JDK 8+ (javac/java on PATH), GNU coreutils, awk.
set -uo pipefail
export LC_ALL=C

cd "$(dirname "$0")"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf 'PASS: %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf 'FAIL: %s\n' "$1"; }

TAB="$(printf '\t')"

echo "==> Compiling src/ with javac"
rm -rf build
mkdir -p build
if javac -encoding UTF-8 -d build $(find src -name '*.java' | sort); then
    pass "javac compiles all sources"
else
    fail "javac compiles all sources"
    echo "aborting: compilation failed"
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

run_sorter() {
    java -cp build com.gsb.extsort.Main sort "$@"
}

# ----------------------------------------------------------------------
echo "==> Test: large input (>> memory cap) sorted correctly and stably"
MEM=8
LIMIT=$((MEM * 1024 * 1024))
BIG="$WORK/big.tsv"
awk 'BEGIN{
    srand(7);
    for (i = 0; i < 1000000; i++) {
        k = int(rand() * 1000);
        printf "%d\tkey%05d\tpayload-%08d-abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ\n", i, k, i;
    }
}' > "$BIG"
BIG_OUT="$WORK/big.out"
BIG_TMP="$WORK/tmp-big"
BIG_LOG="$WORK/big.log"
mkdir -p "$BIG_TMP"
if run_sorter --input "$BIG" --output "$BIG_OUT" --key 2 --max-memory-mb "$MEM" \
        --order asc --bad-line fail --temp-dir "$BIG_TMP" > "$BIG_LOG" 2>&1; then
    pass "large sort exits 0"
else
    fail "large sort exits 0"
fi
if sort -c -t"$TAB" -k2,2 "$BIG_OUT" 2>/dev/null; then
    pass "large output is ordered by key column"
else
    fail "large output is ordered by key column"
fi
sort -t"$TAB" -k2,2 -s "$BIG" > "$WORK/big.ref"
if cmp -s "$BIG_OUT" "$WORK/big.ref"; then
    pass "large output identical to in-memory reference sort (stable)"
else
    fail "large output identical to in-memory reference sort (stable)"
fi
PEAK="$(sed -n 's/^peak-bytes: //p' "$BIG_LOG")"
if [ -n "$PEAK" ] && [ "$PEAK" -lt "$LIMIT" ]; then
    pass "peakBytes ($PEAK) below limit ($LIMIT)"
else
    fail "peakBytes ($PEAK) below limit ($LIMIT)"
fi
if [ -z "$(ls -A "$BIG_TMP")" ]; then
    pass "temp dir empty after successful run"
else
    fail "temp dir empty after successful run"
fi

# ----------------------------------------------------------------------
echo "==> Test: stability with duplicate keys"
STAB="$WORK/stab.tsv"
awk 'BEGIN{for (i = 0; i < 5000; i++) printf "key%02d\trow-%06d\n", i % 7, i}' > "$STAB"
STAB_OUT="$WORK/stab.out"
mkdir -p "$WORK/tmp-stab"
run_sorter --input "$STAB" --output "$STAB_OUT" --key 1 --max-memory-mb 1 \
    --order asc --bad-line fail --temp-dir "$WORK/tmp-stab" >/dev/null 2>&1
awk -F'\t' '$1 == "key03" {print $2}' "$STAB"     > "$WORK/stab.exp"
awk -F'\t' '$1 == "key03" {print $2}' "$STAB_OUT" > "$WORK/stab.act"
if cmp -s "$WORK/stab.exp" "$WORK/stab.act"; then
    pass "equal keys keep original relative order"
else
    fail "equal keys keep original relative order"
fi
SAME="$WORK/same.tsv"
awk 'BEGIN{for (i = 0; i < 2000; i++) printf "same\t%d\n", i}' > "$SAME"
SAME_OUT="$WORK/same.out"
mkdir -p "$WORK/tmp-same"
run_sorter --input "$SAME" --output "$SAME_OUT" --key 1 --max-memory-mb 1 \
    --order asc --bad-line fail --temp-dir "$WORK/tmp-same" >/dev/null 2>&1
if cmp -s "$SAME" "$SAME_OUT"; then
    pass "all-identical keys: output equals input order"
else
    fail "all-identical keys: output equals input order"
fi

# ----------------------------------------------------------------------
echo "==> Test: descending order"
DESC="$WORK/desc.tsv"
printf 'b\t1\na\t2\nc\t3\na\t4\nb\t5\n' > "$DESC"
DESC_OUT="$WORK/desc.out"
mkdir -p "$WORK/tmp-desc"
run_sorter --input "$DESC" --output "$DESC_OUT" --key 1 --max-memory-mb 1 \
    --order desc --bad-line fail --temp-dir "$WORK/tmp-desc" >/dev/null 2>&1
sort -t"$TAB" -k1,1 -r -s "$DESC" > "$WORK/desc.ref"
if cmp -s "$DESC_OUT" "$WORK/desc.ref"; then
    pass "descending order matches reference (stable)"
else
    fail "descending order matches reference (stable)"
fi

# ----------------------------------------------------------------------
echo "==> Test: tiny memory forces multi-pass batched merge"
SMALL_MEM=1
SMALL_LIMIT=$((SMALL_MEM * 1024 * 1024))
MANY="$WORK/many.tsv"
awk 'BEGIN{srand(11); for (i = 0; i < 50000; i++) printf "%d\tkey%05d\tpayload-%d\n", i, int(rand() * 500), i}' > "$MANY"
MANY_OUT="$WORK/many.out"
MANY_TMP="$WORK/tmp-many"
MANY_LOG="$WORK/many.log"
mkdir -p "$MANY_TMP"
run_sorter --input "$MANY" --output "$MANY_OUT" --key 2 --max-memory-mb "$SMALL_MEM" \
    --order asc --bad-line fail --temp-dir "$MANY_TMP" > "$MANY_LOG" 2>&1
sort -t"$TAB" -k2,2 -s "$MANY" > "$WORK/many.ref"
if cmp -s "$MANY_OUT" "$WORK/many.ref"; then
    pass "multi-pass merge output identical to reference"
else
    fail "multi-pass merge output identical to reference"
fi
PEAK="$(sed -n 's/^peak-bytes: //p' "$MANY_LOG")"
if [ -n "$PEAK" ] && [ "$PEAK" -lt "$SMALL_LIMIT" ]; then
    pass "peakBytes ($PEAK) below tiny limit ($SMALL_LIMIT)"
else
    fail "peakBytes ($PEAK) below tiny limit ($SMALL_LIMIT)"
fi
if [ -z "$(ls -A "$MANY_TMP")" ]; then
    pass "temp dir empty after multi-pass merge"
else
    fail "temp dir empty after multi-pass merge"
fi

# ----------------------------------------------------------------------
echo "==> Test: bad-line skip counts and continues"
SKIP_IN="$WORK/skip.tsv"
printf '1\tapple\tA\nGARBAGE\n2\tbanana\tB\n3\tcherry\tC\nALSO_BAD\n' > "$SKIP_IN"
SKIP_OUT="$WORK/skip.out"
SKIP_LOG="$WORK/skip.log"
mkdir -p "$WORK/tmp-skip"
if run_sorter --input "$SKIP_IN" --output "$SKIP_OUT" --key 2 --max-memory-mb 1 \
        --order asc --bad-line skip --temp-dir "$WORK/tmp-skip" > "$SKIP_LOG" 2>&1; then
    pass "skip mode exits 0 despite bad lines"
else
    fail "skip mode exits 0 despite bad lines"
fi
printf '1\tapple\tA\n2\tbanana\tB\n3\tcherry\tC\n' > "$WORK/skip.exp"
if cmp -s "$SKIP_OUT" "$WORK/skip.exp"; then
    pass "skip mode output contains only good lines, sorted"
else
    fail "skip mode output contains only good lines, sorted"
fi
if grep -q '^skipped-bad-lines: 2$' "$SKIP_LOG"; then
    pass "skip mode reports 2 skipped bad lines"
else
    fail "skip mode reports 2 skipped bad lines"
fi

# ----------------------------------------------------------------------
echo "==> Test: bad-line fail aborts with line number and cleans up"
FAIL_IN="$WORK/fail.tsv"
awk 'BEGIN{for (i = 1; i <= 20000; i++) printf "%d\tkey%06d\tpayload\n", i, i}' > "$FAIL_IN"
echo 'BROKEN_LINE' >> "$FAIL_IN"
awk 'BEGIN{for (i = 20001; i <= 20010; i++) printf "%d\tkey%06d\tpayload\n", i, i}' >> "$FAIL_IN"
FAIL_OUT="$WORK/fail.out"
FAIL_TMP="$WORK/tmp-fail"
FAIL_LOG="$WORK/fail.log"
mkdir -p "$FAIL_TMP"
run_sorter --input "$FAIL_IN" --output "$FAIL_OUT" --key 2 --max-memory-mb 1 \
    --order asc --bad-line fail --temp-dir "$FAIL_TMP" > "$FAIL_LOG" 2>&1
STATUS=$?
if [ "$STATUS" -ne 0 ]; then
    pass "fail mode exits non-zero"
else
    fail "fail mode exits non-zero"
fi
if grep -q 'line 20001' "$FAIL_LOG"; then
    pass "fail error message contains bad line number"
else
    fail "fail error message contains bad line number"
fi
if [ ! -e "$FAIL_OUT" ]; then
    pass "no output file left after failure"
else
    fail "no output file left after failure"
fi
if [ -z "$(ls -A "$FAIL_TMP")" ]; then
    pass "temp dir empty after failure"
else
    fail "temp dir empty after failure"
fi

# ----------------------------------------------------------------------
echo "==> Test: empty input produces empty output"
EMPTY_IN="$WORK/empty.tsv"
: > "$EMPTY_IN"
EMPTY_OUT="$WORK/empty.out"
mkdir -p "$WORK/tmp-empty"
if run_sorter --input "$EMPTY_IN" --output "$EMPTY_OUT" --key 1 --max-memory-mb 1 \
        --order asc --bad-line fail --temp-dir "$WORK/tmp-empty" >/dev/null 2>&1 \
        && [ -f "$EMPTY_OUT" ] && [ ! -s "$EMPTY_OUT" ]; then
    pass "empty input yields empty output file"
else
    fail "empty input yields empty output file"
fi

# ----------------------------------------------------------------------
echo
echo "passed: $PASS, failed: $FAIL"
[ "$FAIL" -eq 0 ]
