#!/usr/bin/env bash
# Spec 6.4: every replica of the three scaled services serves; the rate limit is one bucket across gateway pods;
# a conversion written through one conversion pod reads back through the other.
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
CHECK=replicas
SCALED=(gateway-svc ledger-svc conversion-svc)
REQUESTS=60
SETTLE=60
findings=()

# <pod> <count> per instance of a job, non-actuator requests matching an extra selector.
counts() {
  prom "sum by (instance) (http_server_requests_milliseconds_count{job=\"$1\", uri!~\"/actuator.*\"${2:+, $2}})"
}

# delta_by_pod <job> <before> <after> <pods...>: prints "pod before after delta"; a pod with no series reads 0.
delta_by_pod() {
  local before=$2 after=$3 pod b a
  shift 3
  for pod in "$@"; do
    b=$(awk -v p="$pod" 'index($1, p ":") == 1 {s += $2} END {print s + 0}' <<< "$before")
    a=$(awk -v p="$pod" 'index($1, p ":") == 1 {s += $2} END {print s + 0}' <<< "$after")
    printf '%s %s %s %s\n' "$pod" "$b" "$a" "$(awk -v a="$a" -v b="$b" 'BEGIN {print a - b}')"
  done
}

# await_all_served <label> <selector> <svc...>: polls until every Ready pod of every svc shows a rise, or SETTLE.
# Uses BEFORE_<svc> snapshots taken by the caller.
await_all_served() {
  local label=$1 sel=$2 svc waited=0 short rows pods after
  shift 2
  while :; do
    short=0 rows=""
    for svc in "$@"; do
      pods=$(pods_of "$svc")
      after=$(counts "$svc" "$sel") || return 1
      eval "before=\$BEFORE_${svc//-/_}"
      # shellcheck disable=SC2086,SC2154
      rows+=$(delta_by_pod "$svc" "$before" "$after" $pods | awk -v s="$svc" '{print s, $0}')$'\n'
    done
    short=$(awk 'NF && $5 <= 0 {n++} END {print n + 0}' <<< "$rows")
    (( short == 0 || waited >= SETTLE )) && break
    sleep 5; waited=$((waited + 5))
  done
  say "$label, per pod (service pod before after delta), read ${waited}s after the traffic:"
  printf '%s' "$rows" | awk 'NF {print "  " $0}'
  SERVED_ROWS=$rows
}

snapshot() {
  local sel=$1 svc v
  shift
  for svc in "$@"; do
    # shellcheck disable=SC2034
    v=$(counts "$svc" "$sel") || return 1
    eval "BEFORE_${svc//-/_}=\$v"
  done
}

# 1. Every replica serves.
for svc in "${SCALED[@]}"; do
  n=$(pods_of "$svc" | awk 'NF {n++} END {print n + 0}')
  say "$svc Ready pods: $n (want 2)"
  [[ $n == 2 ]] || findings+=("$svc has $n Ready pods, want 2")
done
snapshot "" "${SCALED[@]}" || { verdict "$CHECK" "Prometheus did not answer the before snapshot"; exit 1; }
bad=0 codes=""
for (( i = 0; i < REQUESTS / 2; i++ )); do
  m=$(uuid)
  call GET "$GATEWAY/api/v1/merchants/$m/balances"; codes+="$CODE "
  [[ $CODE == 200 ]] || bad=$((bad + 1))
  call POST "$GATEWAY/api/v1/merchants/$m/quotes" -d '{"sell":"ZAR","buy":"USDT","sellAmount":"10.00"}'; codes+="$CODE "
  [[ $CODE == 201 ]] || bad=$((bad + 1))
done
say "$REQUESTS requests (balances 200, quotes 201 wanted): $(printf '%s\n' $codes | sort | uniq -c | awk '{printf "%s x%s ", $2, $1}')"
(( bad == 0 )) || findings+=("$bad of $REQUESTS requests answered other than 200/201")
await_all_served "requests served" "" "${SCALED[@]}" || findings+=("Prometheus did not answer after the traffic")
pods_served=$(awk 'NF && $5 > 0 {n++} END {print n + 0}' <<< "${SERVED_ROWS:-}")
say "pods with a rise: $pods_served (want 6)"
[[ $pods_served == 6 ]] || findings+=("$pods_served of 6 scaled pods served the $REQUESTS requests")

# 2. One bucket across gateway pods. Twice the capacity: per-pod buckets would admit up to 2 x capacity, a shared
# one capacity plus refill. The burst must outrun the refill or it cannot tell the two apart.
cap=$(awk '/capacity:/ {gsub(/[^0-9]/, ""); print; exit}' services/config-svc/src/main/resources/served/gateway-svc.yml)
refill=$(awk '/refill-tokens:/ {gsub(/[^0-9]/, ""); print; exit}' services/config-svc/src/main/resources/served/gateway-svc.yml)
period=$(awk '/refill-period:/ {sub(/.*:-?/, ""); sub(/.*:/, ""); gsub(/[^0-9a-z]/, ""); print; exit}' services/config-svc/src/main/resources/served/gateway-svc.yml)
if [[ ! $cap =~ ^[0-9]+$ || ! $refill =~ ^[0-9]+$ || $period != 1s ]]; then
  findings+=("rate limit config unreadable: capacity '$cap' refill '$refill' period '$period' (this check assumes 1s)")
else
  burst=$((cap * 2)) m=$(uuid)
  cfg=$SWEEP_DIR/burst.curl
  : > "$cfg"
  for (( i = 0; i < burst; i++ )); do printf 'url = "%s/api/v1/merchants/%s/balances"\noutput = "/dev/null"\n' "$GATEWAY" "$m" >> "$cfg"; done
  snapshot "" gateway-svc || findings+=("Prometheus did not answer the burst snapshot")
  t0=$(now_ms)
  out=$(curl -s --parallel --parallel-immediate --parallel-max 50 --max-time 15 -w '%{http_code}\n' --config "$cfg"); rc=$?
  t1=$(now_ms)
  admitted=$(awk '$1 == 200 {n++} END {print n + 0}' <<< "$out")
  limited=$(awk '$1 == 429 {n++} END {print n + 0}' <<< "$out")
  other=$(awk 'NF && $1 != 200 && $1 != 429 {n++} END {print n + 0}' <<< "$out")
  elapsed=$((t1 - t0)) secs=$(( (t1 - t0 + 999) / 1000 ))
  bound=$((cap + refill * secs))
  say "burst $burst for one merchant in ${elapsed}ms (curl exit $rc): admitted=$admitted limited=$limited other=$other; bound capacity $cap + $refill x ${secs}s = $bound"
  (( rc == 0 )) || findings+=("burst curl exited $rc")
  (( admitted + limited == burst )) || findings+=("burst answered $admitted+$limited of $burst with 200/429 ($other other)")
  (( bound < burst )) || findings+=("burst took ${elapsed}ms: bound $bound >= $burst, too slow to tell one bucket from two")
  (( admitted <= bound )) || findings+=("admitted $admitted > bound $bound: the gateway pods do not share a bucket")
  (( admitted > 0 )) || findings+=("burst admitted nothing")
  await_all_served "burst" "" gateway-svc || findings+=("Prometheus did not answer after the burst")
  gw_served=$(awk 'NF && $5 > 0 {n++} END {print n + 0}' <<< "${SERVED_ROWS:-}")
  [[ $gw_served == 2 ]] || findings+=("$gw_served of 2 gateway pods recorded the burst")
fi

# 3. Write through one conversion pod, read through both.
m=$(uuid)
if ! deposit "$m" 100.00; then
  findings+=("write-read deposit: $DEPOSIT_OUT")
elif ! convert "$m" "sweep-replicas-$m" 10.00; then
  findings+=("write-read conversion: quote $QUOTE_CODE convert ${CONVERT_CODE:-not sent}: $BODY")
else
  say "wrote conversion $CONVERSION_ID ($CONVERSION_STATUS)"
  read_sel='method="GET", uri=~".*conversions.*"'
  snapshot "$read_sel" conversion-svc || findings+=("Prometheus did not answer the read snapshot")
  okreads=0
  for (( i = 0; i < 10; i++ )); do
    call GET "$GATEWAY/api/v1/merchants/$m/conversions/$CONVERSION_ID"
    id=$(jq -r '.data.conversionId // empty' <<< "$BODY" 2>/dev/null)
    [[ $CODE == 200 && $id == "$CONVERSION_ID" ]] && okreads=$((okreads + 1))
  done
  say "reads of $CONVERSION_ID: $okreads of 10 answered 200 with that id"
  (( okreads == 10 )) || findings+=("$okreads of 10 reads returned the conversion")
  await_all_served "reads" "$read_sel" conversion-svc || findings+=("Prometheus did not answer after the reads")
  rd_served=$(awk 'NF && $5 > 0 {n++} END {print n + 0}' <<< "${SERVED_ROWS:-}")
  [[ $rd_served == 2 ]] || findings+=("$rd_served of 2 conversion pods served the reads")
fi

verdict "$CHECK" ${findings[@]+"${findings[@]}"}
