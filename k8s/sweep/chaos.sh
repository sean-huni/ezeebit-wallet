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
MIN_WINDOW=20
# Quote TTL 15s + posting grace 30s + max transaction time 30s + a 10s sweep, plus margin.
PENDING_WAIT=120
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
# Each kill window [kill, kill + spacing) must carry traffic, or the kill tested nothing.
for (( i = 0; i < ${#kills[@]}; i++ )); do
  k=${kills[$i]} e=$(( kills[i] + SPACING * 1000 ))
  w=$(awk -v a="$k" -v b="$e" '$1 >= a && $1 < b' <<< "$all")
  n=$(awk 'NF {n++} END {print n + 0}' <<< "$w")
  say "window ${VICTIMS[$i]} kill +${SPACING}s: $n requests; $(awk 'NF {c[$3]++} END {for (s in c) printf "%s x%d; ", s, c[s]}' <<< "$w") 4xx: $(awk 'NF && $3 ~ /^4/ {n++} END {print n + 0}' <<< "$w")"
  (( n >= MIN_WINDOW )) || findings+=("only $n requests in the ${VICTIMS[$i]} kill window, want >= $MIN_WINDOW")
done
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

# Cross-store reconciliation for this run's merchants. A unique key cannot show a duplicate, so the audit asks
# whether the two stores agree: each COMPLETED conversion has exactly one POSTED CONVERSION journal keyed by its id
# (ledger changelogs 003 and 005), and each such journal has a COMPLETED conversion. PENDING ones settle first.
in_list="'$(printf '%s' "${merchants[*]}" | sed "s/ /','/g")'"
waited=0 pending=''
while (( waited <= PENDING_WAIT )); do
  pending=$(psql_in conversion "SELECT count(*) FROM conversion WHERE status = 'PENDING' AND merchant_id IN ($in_list)"); rc=$?
  [[ $rc == 0 && $pending == 0 ]] && break
  sleep 5; waited=$((waited + 5))
done
say "PENDING conversions of this run: ${pending:-none} after ${waited}s (want 0 within ${PENDING_WAIT}s)"
if [[ $pending != 0 ]]; then
  left=$(psql_in conversion "SELECT id, deadline FROM conversion WHERE status = 'PENDING' AND merchant_id IN ($in_list)")
  findings+=("unswept PENDING conversions: $(printf '%s' "$left" | tr '\n' ';')")
fi
completed=$(psql_in conversion "SELECT id FROM conversion WHERE status = 'COMPLETED' AND merchant_id IN ($in_list) ORDER BY id"); rc1=$?
by_status=$(psql_in conversion "SELECT status, count(*) FROM conversion WHERE merchant_id IN ($in_list) GROUP BY status ORDER BY status"); rc2=$?
posted=$(psql_in ledger "SELECT idempotency_key, count(*) FROM journal WHERE type = 'CONVERSION' AND status = 'POSTED' AND merchant_id IN ($in_list) GROUP BY idempotency_key ORDER BY idempotency_key"); rc3=$?
by_journal=$(psql_in ledger "SELECT status, count(*) FROM journal WHERE type = 'CONVERSION' AND merchant_id IN ($in_list) GROUP BY status ORDER BY status"); rc4=$?
if (( rc1 || rc2 || rc3 || rc4 )); then
  findings+=("reconciliation reads exited $rc1/$rc2/$rc3/$rc4")
else
  n_completed=$(awk 'NF {n++} END {print n + 0}' <<< "$completed")
  n_posted=$(awk 'NF {n++} END {print n + 0}' <<< "$posted")
  multi=$(awk 'NF && $2 != 1 {printf "%s x%s; ", $1, $2}' <<< "$posted")
  posted_ids=$(awk 'NF {print $1}' <<< "$posted")
  no_journal=$(comm -23 <(printf '%s\n' "$completed" | awk NF) <(printf '%s\n' "$posted_ids" | awk NF) | awk 'NF {n++} END {print n + 0}')
  no_conversion=$(comm -13 <(printf '%s\n' "$completed" | awk NF) <(printf '%s\n' "$posted_ids" | awk NF) | awk 'NF {n++} END {print n + 0}')
  say "conversion-svc by status: $(printf '%s' "$by_status" | tr '\n' ';')"
  say "ledger CONVERSION journals by status: $(printf '%s' "$by_journal" | tr '\n' ';')"
  say "COMPLETED conversions: $n_completed; POSTED CONVERSION journals: $n_posted; COMPLETED without a journal: $no_journal; journals without a COMPLETED conversion: $no_conversion; journals posted more than once: ${multi:-none}"
  (( n_completed > 0 )) || findings+=("no COMPLETED conversion to reconcile")
  (( no_journal == 0 )) || findings+=("$no_journal COMPLETED conversion(s) have no POSTED journal")
  (( no_conversion == 0 )) || findings+=("$no_conversion POSTED journal(s) have no COMPLETED conversion")
  [[ -z $multi ]] || findings+=("conversions posted more than once: $multi")
fi
sent=$(awk '$2 == "convert" {n++} END {print n + 0}' <<< "$all")
mine=$(psql_in conversion "SELECT count(*) FROM conversion WHERE idempotency_key LIKE 'chaos-$run-%'"); rc=$?
created=$(awk '$2 == "convert" && $3 ~ /^20[12]$/ {n++} END {print n + 0}' <<< "$all")
say "this run: $sent convert requests, $created answered 201/202, ${mine:-none} conversion rows (want answered <= rows <= requests)"
[[ $rc == 0 && $mine =~ ^[0-9]+$ ]] && (( created <= mine && mine <= sent )) || findings+=("rows '$mine' outside [$created, $sent]")

verdict "$CHECK" ${findings[@]+"${findings[@]}"}
