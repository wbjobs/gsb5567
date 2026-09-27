#!/usr/bin/env bash
# Acceptance tests for the external sort tool (JDK 8, standard library only).
set -euo pipefail
cd "$(dirname "$0")"

BUILD=build
TBUILD=build-tests
WORK=test-out
MAIN=com.gsb.extsort.Main

rm -rf "$BUILD" "$TBUILD" "$WORK"
mkdir -p "$BUILD" "$TBUILD" "$WORK"

echo "== compile (JDK 8 source level) =="
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d "$BUILD" $(find src -name '*.java')
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$BUILD" -d "$TBUILD" $(find tests -name '*.java')

FAILED=0

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILED=1; }

# expect_true <description> <command...>
expect_true() {
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi
}

dir_empty() {
    [ -d "$1" ] && [ -z "$(find "$1" -mindepth 1 2>/dev/null)" ]
}

echo "== T1: small input, asc, single run =="
d="$WORK/t1"; mkdir -p "$d/tmp"
java -cp "$TBUILD" GenInput "$d/in.tsv" 2000 7
java -cp "$BUILD" $MAIN sort --input "$d/in.tsv" --output "$d/out.tsv" --key 1 \
    --max-memory-mb 1 --order asc --bad-line fail --temp-dir "$d/tmp" > "$d/log.txt"
java -cp "$TBUILD" RefSort "$d/in.tsv" "$d/ref.tsv" 1 asc
expect_true "T1 output identical to in-memory reference" cmp -s "$d/out.tsv" "$d/ref.tsv"
expect_true "T1 output ordered and stable" java -cp "$TBUILD" CheckSorted "$d/out.tsv" 1 asc
expect_true "T1 temp dir empty after run" dir_empty "$d/tmp"

echo "== T2: small input, desc =="
d="$WORK/t2"; mkdir -p "$d/tmp"
java -cp "$TBUILD" GenInput "$d/in.tsv" 3000 11
java -cp "$BUILD" $MAIN sort --input "$d/in.tsv" --output "$d/out.tsv" --key 1 \
    --max-memory-mb 1 --order desc --bad-line fail --temp-dir "$d/tmp" > "$d/log.txt"
java -cp "$TBUILD" RefSort "$d/in.tsv" "$d/ref.tsv" 1 desc
expect_true "T2 desc output identical to reference" cmp -s "$d/out.tsv" "$d/ref.tsv"
expect_true "T2 desc output ordered and stable" java -cp "$TBUILD" CheckSorted "$d/out.tsv" 1 desc

echo "== T3: input far larger than memory limit (multi-round merge) =="
d="$WORK/t3"; mkdir -p "$d/tmp"
java -cp "$TBUILD" GenInput "$d/in.tsv" 500000 123
input_bytes=$(wc -c < "$d/in.tsv" | tr -d ' ')
echo "   input size: ${input_bytes} bytes, limit: 4 MiB"
java -Xmx1g -cp "$TBUILD" RefSort "$d/in.tsv" "$d/ref.tsv" 1 asc
java -cp "$BUILD" $MAIN sort --input "$d/in.tsv" --output "$d/out.tsv" --key 1 \
    --max-memory-mb 4 --order asc --bad-line fail --temp-dir "$d/tmp" > "$d/log.txt"
expect_true "T3 output identical to in-memory reference" cmp -s "$d/out.tsv" "$d/ref.tsv"
expect_true "T3 output ordered and stable" java -cp "$TBUILD" CheckSorted "$d/out.tsv" 1 asc
expect_true "T3 temp dir empty after run" dir_empty "$d/tmp"
peak=$(grep '^peak-bytes: ' "$d/log.txt" | awk '{print $2}' || true)
if [ -n "$peak" ] && [ "$peak" -lt $((4 * 1024 * 1024)) ]; then
    pass "T3 CLI peak-bytes ($peak) < 4 MiB limit"
else
    fail "T3 CLI peak-bytes ($peak) >= 4 MiB limit"
fi

echo "== T4: peakBytes() assertion via API =="
d="$WORK/t4"; mkdir -p "$d/tmp"
expect_true "T4 peakBytes() < limit on large input" \
    java -cp "$TBUILD:$BUILD" PeakCheck "$WORK/t3/in.tsv" "$d/out.tsv" "$d/tmp" 1 4
expect_true "T4 temp dir empty after API run" dir_empty "$d/tmp"

echo "== T5: bad lines with --bad-line skip =="
d="$WORK/t5"; mkdir -p "$d/tmp"
for i in $(seq 1 100); do
    case "$i" in
        10|50|99) echo "GARBAGE" ;;
        *) printf '%s\tkey-%s\tpayload%s\n' "$i" "$((i % 7))" "$i" ;;
    esac
done > "$d/in.tsv"
java -cp "$BUILD" $MAIN sort --input "$d/in.tsv" --output "$d/out.tsv" --key 1 \
    --max-memory-mb 1 --order asc --bad-line skip --temp-dir "$d/tmp" > "$d/log.txt"
lines=$(wc -l < "$d/out.tsv" | tr -d ' ')
[ "$lines" -eq 97 ] && pass "T5 skip wrote 97 good lines" || fail "T5 expected 97 lines, got $lines"
expect_true "T5 skip counted 3 bad lines" grep -q '^bad-lines-skipped: 3$' "$d/log.txt"
expect_true "T5 output ordered and stable" java -cp "$TBUILD" CheckSorted "$d/out.tsv" 1 asc
expect_true "T5 temp dir empty after run" dir_empty "$d/tmp"

echo "== T6: bad line with --bad-line fail =="
d="$WORK/t6"; mkdir -p "$d/tmp"
for i in $(seq 1 100); do
    if [ "$i" -eq 42 ]; then
        echo "BROKEN"
    else
        printf '%s\tkey-%s\tpayload%s\n' "$i" "$((i % 5))" "$i"
    fi
done > "$d/in.tsv"
rc=0
java -cp "$BUILD" $MAIN sort --input "$d/in.tsv" --output "$d/out.tsv" --key 1 \
    --max-memory-mb 1 --order asc --bad-line fail --temp-dir "$d/tmp" \
    > "$d/log.txt" 2> "$d/err.txt" || rc=$?
[ "$rc" -ne 0 ] && pass "T6 fail exits non-zero" || fail "T6 expected non-zero exit"
expect_true "T6 error message contains line number 42" grep -q 'line 42' "$d/err.txt"
[ ! -e "$d/out.tsv" ] && pass "T6 output file absent after failure" || fail "T6 output file left behind"
expect_true "T6 temp dir empty after failure" dir_empty "$d/tmp"

echo "== T7: failure late in a large input (temp runs already flushed) =="
d="$WORK/t7"; mkdir -p "$d/tmp"
java -cp "$TBUILD" GenInput "$d/in.tsv" 50000 55
echo "BROKEN" >> "$d/in.tsv"
rc=0
java -cp "$BUILD" $MAIN sort --input "$d/in.tsv" --output "$d/out.tsv" --key 1 \
    --max-memory-mb 1 --order asc --bad-line fail --temp-dir "$d/tmp" \
    > "$d/log.txt" 2> "$d/err.txt" || rc=$?
[ "$rc" -ne 0 ] && pass "T7 fail exits non-zero" || fail "T7 expected non-zero exit"
expect_true "T7 error message contains line number 50001" grep -q 'line 50001' "$d/err.txt"
[ ! -e "$d/out.tsv" ] && pass "T7 output file absent after failure" || fail "T7 output file left behind"
expect_true "T7 temp dir empty after failure" dir_empty "$d/tmp"

echo
if [ "$FAILED" -ne 0 ]; then
    echo "TESTS FAILED"
    exit 1
fi
echo "ALL TESTS PASSED"
