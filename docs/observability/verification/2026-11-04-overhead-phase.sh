#!/usr/bin/env bash
# phase.sh <label> on|'<kubectl set env args>': restart (on) or apply env changes to the 5 apps, pin the HPAs at 2,
# wait 240 s and for 8 app pods, warm 60 s, load 300 s, measure. Results in $OVERHEAD_OUT (default out/overhead).
# The HPAs are restored to 4/3/3 (k8s/base/app/hpa.yaml) on exit, whatever happens.
set -uo pipefail
D=$(cd "$(dirname "$0")" && pwd); P=2026-11-04-overhead; L=$1; MODE=$2
O=${OVERHEAD_OUT:-$D/out/overhead}; mkdir -p "$O"
APPS=(config-svc eureka-svc ledger-svc conversion-svc gateway-svc)
log() { echo "$(date -u +%FT%TZ) $*"; }
hpa() { for h in conversion-svc:$1 ledger-svc:$2 gateway-svc:$3; do
  kubectl -n ezeebit-app patch hpa "${h%%:*}" --type merge -p "{\"spec\":{\"maxReplicas\":${h##*:}}}" > /dev/null; done; }
trap 'hpa 3 3 4; log "HPAs restored to 3/3/4 (conversion/ledger/gateway)"' EXIT
hpa 2 2 2; log "HPAs pinned at maxReplicas 2"
old=$(kubectl -n ezeebit-app get pods -o name | grep -v wiremock)
for d in "${APPS[@]}"; do
  if [[ $MODE != on ]]; then kubectl -n ezeebit-app set env deploy/$d $MODE; else kubectl -n ezeebit-app rollout restart deploy/$d; fi
done
for d in "${APPS[@]}"; do kubectl -n ezeebit-app rollout status deploy/$d --timeout=300s > /dev/null; log "$d rollout exit=$?"; done
kubectl -n ezeebit-app wait --for=delete $old --timeout=180s > /dev/null 2>&1; log "old pods gone exit=$?"
T=$(date +%s); log "rolled"
"$D/$P-imgcheck.sh"; log "imgcheck exit=$?"
kubectl -n ezeebit-app get deploy -o json | jq -r '.items[]|"\(.metadata.name) \([.spec.template.spec.containers[0].env[]?|select(.name|test("PROFILING|PYROSCOPE_INTERVAL"))|"\(.name)=\(.value)"])"'
while :; do
  now=$(date +%s); r=$(kubectl -n ezeebit-app get hpa -o json | jq -r '[.items[]|select(.status.currentReplicas != .spec.minReplicas)]|length')
  n=$(kubectl -n ezeebit-app get pods --no-headers | grep -v wiremock | awk 'END{print NR}')
  (( now - T >= 240 && r == 0 && n == 8 )) && break; sleep 10
done
log "settled: HPAs at min, 8 app pods, $(( $(date +%s) - T ))s after rollout"
M=$("$D/$P-deposit.sh" | tail -1); log "deposits ok=$([[ $M == *,*,*,*,*,*,*,* ]] && echo 1 || echo 0)"
MERCHANTS=$M node "$D/$P-load.mjs" 60 "warm-$L" > "$O/warm-$L.json" 2>&1; log "warm exit=$?"
MERCHANTS=$M node "$D/$P-load.mjs" 300 "$L" > "$O/run-$L.json" 2>&1; log "load exit=$?"
sleep 20
"$D/$P-measure.sh" "$(jq .startMs "$O/run-$L.json")" "$(jq .endMs "$O/run-$L.json")" "$L" > "$O/measure-$L.txt"
kubectl -n ezeebit-app get events -o json | jq -r --argjson s "$(jq '.startMs/1000|floor' "$O/run-$L.json")" '.items[]|select(.reason=="ScalingReplicaSet" or .reason=="SuccessfulRescale")|select((.lastTimestamp|fromdateiso8601) >= $s)|"scaling event in window: \(.lastTimestamp) \(.message)"'
log "phase $L done"
