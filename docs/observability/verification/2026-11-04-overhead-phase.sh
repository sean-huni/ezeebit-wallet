#!/usr/bin/env bash
# phase.sh <label> on|'<kubectl set env args>': restart (on) or apply env changes to the 5 apps, pin the HPAs at 2,
# settle, warm 60 s, load 300 s, measure. Results in $OVERHEAD_OUT (default out/overhead). Fails closed: any step that
# cannot prove its result ends the run non-zero. The HPAs get back the maxReplicas read before pinning, on any exit.
set -uo pipefail
D=$(cd "$(dirname "$0")" && pwd); P=2026-11-04-overhead; L=$1; MODE=$2
O=${OVERHEAD_OUT:-$D/out/overhead}; SETTLE_DEADLINE=${SETTLE_DEADLINE:-900}; SETTLE_MIN=${SETTLE_MIN:-240}; mkdir -p "$O"
APPS=(config-svc eureka-svc ledger-svc conversion-svc gateway-svc); HPAS=(conversion-svc ledger-svc gateway-svc)
log() { echo "$(date -u +%FT%TZ) $*"; }
die() { log "FAIL: $*"; echo "exit=1"; exit 1; }
declare -a DECLARED=()
for h in "${HPAS[@]}"; do
  m=$(kubectl -n ezeebit-app get hpa "$h" -o jsonpath='{.spec.maxReplicas}'); rc=$?
  [[ $rc == 0 && $m =~ ^[0-9]+$ ]] || die "could not read maxReplicas of hpa/$h (exit $rc, value '$m')"
  DECLARED+=("$h:$m")
done
restore() { for e in "${DECLARED[@]}"; do
  kubectl -n ezeebit-app patch hpa "${e%%:*}" --type merge -p "{\"spec\":{\"maxReplicas\":${e##*:}}}" > /dev/null \
    || log "RESTORE FAILED for hpa/${e%%:*} (wanted ${e##*:})"; done; log "HPAs restored to ${DECLARED[*]}"; }
trap restore EXIT
for h in "${HPAS[@]}"; do
  kubectl -n ezeebit-app patch hpa "$h" --type merge -p '{"spec":{"maxReplicas":2}}' > /dev/null || die "pin hpa/$h"
done
log "HPAs pinned at maxReplicas 2 (declared ${DECLARED[*]})"
old=$(kubectl -n ezeebit-app get pods -o name) || die "list pods"
old=$(grep -v wiremock <<< "$old")
for d in "${APPS[@]}"; do
  if [[ $MODE != on ]]; then kubectl -n ezeebit-app set env "deploy/$d" $MODE > /dev/null || die "set env $d"
  else kubectl -n ezeebit-app rollout restart "deploy/$d" > /dev/null || die "rollout restart $d"; fi
done
for d in "${APPS[@]}"; do kubectl -n ezeebit-app rollout status "deploy/$d" --timeout=300s > /dev/null || die "rollout $d"; done
kubectl -n ezeebit-app wait --for=delete $old --timeout=180s > /dev/null 2>&1 || die "old pods still present"
T=$(date +%s); log "rolled"
"$D/$P-imgcheck.sh" || die "image identity"
kubectl -n ezeebit-app get deploy -o json | jq -r '.items[]|"\(.metadata.name) \([.spec.template.spec.containers[0].env[]?|select(.name|test("PROFILING|PYROSCOPE_INTERVAL"))|"\(.name)=\(.value)"])"' \
  || die "read deployment env"
while :; do
  now=$(date +%s); (( now - T < SETTLE_DEADLINE )) || die "not settled within ${SETTLE_DEADLINE}s"
  r=$(kubectl -n ezeebit-app get hpa -o json | jq -r '[.items[]|select(.status.currentReplicas != .spec.minReplicas)]|length')
  n=$(kubectl -n ezeebit-app get pods --no-headers | grep -v wiremock | awk 'END{print NR}')
  [[ $r =~ ^[0-9]+$ && $n =~ ^[0-9]+$ ]] || die "settle check could not read the cluster (r='$r' n='$n')"
  (( now - T >= SETTLE_MIN && r == 0 && n == 8 )) && break; sleep 10
done
log "settled: HPAs at min, 8 app pods, $(( $(date +%s) - T ))s after rollout"
M=$("$D/$P-deposit.sh" | tail -1)
[[ $M =~ ^([0-9a-f-]{36},){7}[0-9a-f-]{36}$ ]] || die "deposits (got '$M')"; log "deposits ok=1"
MERCHANTS=$M node "$D/$P-load.mjs" 60 "warm-$L" > "$O/warm-$L.json" 2>&1 || die "warm-up load"; log "warm exit=0"
MERCHANTS=$M node "$D/$P-load.mjs" 300 "$L" > "$O/run-$L.json" 2>&1 || die "load"; log "load exit=0"
sleep "${MEASURE_DELAY:-20}"
"$D/$P-measure.sh" "$(jq .startMs "$O/run-$L.json")" "$(jq .endMs "$O/run-$L.json")" "$L" > "$O/measure-$L.txt" \
  || die "measure (see $O/measure-$L.txt)"
start=$(jq '.startMs/1000|floor' "$O/run-$L.json") || die "read run start"
ev=$(kubectl -n ezeebit-app get events -o json | jq -r --argjson s "$start" '.items[]|select(.reason=="ScalingReplicaSet" or .reason=="SuccessfulRescale")|select((.lastTimestamp|fromdateiso8601) >= $s)|"\(.lastTimestamp) \(.message)"') \
  || die "could not read scaling events"
[[ -z $ev ]] || die "scaling event in window: $ev"
log "no scaling event in window"
log "phase $L done"; echo "exit=0"
