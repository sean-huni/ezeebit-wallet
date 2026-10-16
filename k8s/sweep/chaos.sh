#!/usr/bin/env bash
# Spec 6.6: traffic runs while one pod of each scaled service is killed; afterwards no duplicate posting or
# conversion exists, and no merchant-facing failure lands later than the R1 budget after the latest kill.
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
CHECK=chaos
VICTIMS=(gateway-svc ledger-svc conversion-svc)
BUDGET_MS=20000
WARMUP=10
SPACING=30
TAIL=30
LOOPS=3
# Unpaced, three loops drew 2712 rate-limit 429s and drove the HPAs up: traffic under the limit tests the kills.
PACE=0.25
findings=()
run=$(date +%s)
traffic=$SWEEP_DIR/chaos-traffic
stop=$SWEEP_DIR/chaos-stop
rm -f -- "$traffic".* "$stop"

# One loop per merchant: quote then convert, each with a fresh idempotency key. Line: <epoch ms> <op> <status>.
loop() {
  local m=$1 out=$2 n=0
  while [[ ! -f $stop ]]; do
    n=$((n + 1))
    convert "$m" "chaos-$run-$m-$n" 1.00
    printf '%s quote %s\n' "$(now_ms)" "$QUOTE_CODE" >> "$out"
    [[ -n $CONVERT_CODE ]] && printf '%s convert %s %s\n' "$(now_ms)" "$CONVERT_CODE" "${CONVERSION_STATUS:--}" >> "$out"
    sleep "$PACE"
  done
}

merchants=()
for (( i = 0; i < LOOPS; i++ )); do
  m=$(uuid)
  deposit "$m" 100000.00 || { verdict "$CHECK" "deposit for $m: $DEPOSIT_OUT"; exit 1; }
  merchants+=("$m")
done
say "merchants: ${merchants[*]}"

pids=()
for (( i = 0; i < LOOPS; i++ )); do
  loop "${merchants[$i]}" "$traffic.$i" &
  pids+=($!)
done
sleep "$WARMUP"

kills=() killed=0
for svc in "${VICTIMS[@]}"; do
  t=$(now_ms)
  out=$(bash k8s/chaos/monkey.sh --service "$svc" 2>&1); rc=$?
  n=$(awk '/^KILL / {n++} END {print n + 0}' <<< "$out")
  say "t=$t monkey --service $svc exit=$rc: $(printf '%s' "$out" | tr '\n' ' ')"
  (( rc == 0 && n == 1 )) || findings+=("monkey --service $svc exited $rc with $n KILL lines, want 0 and 1")
  killed=$((killed + n))
  kills+=("$t")
  sleep "$SPACING"
done
sleep $((TAIL > SPACING ? TAIL - SPACING : 0))
touch "$stop"
for p in "${pids[@]}"; do wait "$p"; done
say "KILL lines: $killed (want ${#VICTIMS[@]})"
(( killed == ${#VICTIMS[@]} )) || findings+=("$killed kills happened, ${#VICTIMS[@]} were asked for")

# Every failure (5xx, or 000 when nothing answered) with its delay after the latest kill at or before it.
all=$(cat "$traffic".* | sort -n)
total=$(awk 'NF {n++} END {print n + 0}' <<< "$all")
say "requests: $total; by op and status: $(awk 'NF {c[$2 " " $3]++} END {for (k in c) printf "%s x%d; ", k, c[k]}' <<< "$all")"
last_kill=${kills[${#kills[@]}-1]}
first_kill=${kills[0]}
late=$(awk -v k="${kills[*]}" -v budget="$BUDGET_MS" '
  BEGIN { n = split(k, ks, " ") }
  NF && ($3 == "000" || $3 ~ /^5/) {
    ref = ""; for (i = 1; i <= n; i++) if (ks[i] <= $1) ref = ks[i]
    if (ref == "") { print "before any kill: " $0; next }
    d = $1 - ref; printf "%s +%dms after kill at %s\n", $0, d, ref
  }' <<< "$all")
failures=$(awk 'NF {n++} END {print n + 0}' <<< "$late")
say "merchant failures (5xx or no answer): $failures"
[[ -n $late ]] && printf '%s\n' "$late" | awk '{print "  " $0}'
over=$(awk -v budget="$BUDGET_MS" '/before any kill/ {n++; next} {d = $0; sub(/.* \+/, "", d); sub(/ms.*/, "", d); if (d + 0 > budget) n++} END {print n + 0}' <<< "$late")
(( over == 0 )) || findings+=("$over failure(s) later than ${BUDGET_MS}ms after a kill, or before any kill")
before_ok=$(awk -v t="$first_kill" '$1 < t && $2 == "convert" && $3 == 201 {n++} END {print n + 0}' <<< "$all")
after_ok=$(awk -v t="$((last_kill + BUDGET_MS))" '$1 > t && $2 == "convert" && $3 == 201 {n++} END {print n + 0}' <<< "$all")
say "conversions 201 before the first kill: $before_ok; after the last kill + ${BUDGET_MS}ms: $after_ok (want both > 0)"
(( before_ok > 0 )) || findings+=("no conversion completed before the first kill: the traffic did not run")
(( after_ok > 0 )) || findings+=("no conversion completed after the last kill's budget: the fleet did not recover")

for svc in "${VICTIMS[@]}"; do
  kctl rollout status "deployment/$svc" -n "$APP_NS" --timeout=180s > /dev/null; rc=$?
  ready=$(kctl get deployment "$svc" -n "$APP_NS" -o jsonpath='{.status.readyReplicas}/{.spec.replicas}')
  say "$svc after the kills: ready $ready (rollout exit $rc)"
  (( rc == 0 )) || findings+=("$svc did not recover: ready $ready")
done

# Duplicate audit over the whole store, keyed as the changelogs' unique identities are.
row=$(psql_in ledger "SELECT count(*), count(DISTINCT (merchant_id, idempotency_key)) FROM journal WHERE type = 'CONVERSION'"); rc=$?
say "ledger journal CONVERSION: count, distinct (merchant_id, idempotency_key) = ${row:-none} (psql exit $rc)"
read -r c d <<< "$row"
[[ $rc == 0 && $c =~ ^[0-9]+$ && $c -gt 0 && $c == "$d" ]] || findings+=("ledger CONVERSION postings '$row', want count = distinct > 0")
row=$(psql_in conversion "SELECT count(*), count(DISTINCT (merchant_id, idempotency_key)) FROM conversion"); rc=$?
say "conversion: count, distinct (merchant_id, idempotency_key) = ${row:-none} (psql exit $rc)"
read -r c d <<< "$row"
[[ $rc == 0 && $c =~ ^[0-9]+$ && $c -gt 0 && $c == "$d" ]] || findings+=("conversions '$row', want count = distinct > 0")
sent=$(awk '$2 == "convert" {n++} END {print n + 0}' <<< "$all")
mine=$(psql_in conversion "SELECT count(*) FROM conversion WHERE idempotency_key LIKE 'chaos-$run-%'"); rc=$?
created=$(awk '$2 == "convert" && $3 == 201 {n++} END {print n + 0}' <<< "$all")
say "this run: $sent convert requests, $created answered 201, ${mine:-none} conversion rows (want 201s <= rows <= requests)"
[[ $rc == 0 && $mine =~ ^[0-9]+$ ]] && (( created <= mine && mine <= sent )) || findings+=("rows '$mine' outside [$created, $sent]")

verdict "$CHECK" ${findings[@]+"${findings[@]}"}
