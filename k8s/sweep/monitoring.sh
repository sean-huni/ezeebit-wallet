#!/usr/bin/env bash
# Spec 6.8: the monitoring survived the sweep. Usage: monitoring.sh begin | mark <label> | end
# begin records the lgtm pod (uid and restart count); mark records Grafana's /api/health code; end compares.
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
CHECK=monitoring
STATE=$SWEEP_DIR/monitoring

lgtm_pod() {
  kctl get pods -n ezeebit-obs -l app.kubernetes.io/name=lgtm \
    -o jsonpath='{range .items[*]}{.metadata.uid} {.status.containerStatuses[0].restartCount}{"\n"}{end}'
}

health() {
  local code rc
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$GRAFANA/api/health"); rc=$?
  (( rc == 0 )) || code=000
  printf '%s %s\n' "$1" "$code" >> "$STATE.health"
  say "grafana /api/health at $1: $code"
  [[ $code == 200 ]]
}

case ${1:-} in
  begin)
    out=$(lgtm_pod); rc=$?
    (( rc == 0 )) || { say "FAIL $CHECK: listing lgtm exited $rc"; exit 1; }
    n=$(printf '%s' "$out" | awk 'NF {n++} END {print n + 0}')
    [[ $n == 1 ]] || { say "FAIL $CHECK: $n lgtm pods, want 1: $out"; exit 1; }
    printf '%s\n' "$out" > "$STATE.pod"
    : > "$STATE.health"
    say "lgtm pod uid restarts at begin: $out"
    health begin || { say "FAIL $CHECK: Grafana not 200 at begin"; exit 1; }
    say "PASS $CHECK-begin"
    ;;
  mark)
    [[ -f $STATE.pod ]] || { say "FAIL $CHECK: mark before begin"; exit 1; }
    health "${2:?mark needs a label}"
    ;;
  end)
    [[ -f $STATE.pod && -f $STATE.health ]] || { say "FAIL $CHECK: end before begin"; exit 1; }
    findings=()
    before=$(cat "$STATE.pod")
    after=$(lgtm_pod); rc=$?
    (( rc == 0 )) || findings+=("listing lgtm at end exited $rc")
    say "lgtm pod uid restarts: begin '$before' end '$after' (want equal)"
    [[ -n $after && $after == "$before" ]] || findings+=("lgtm pod or restart count changed: '$before' -> '$after'")
    health end || true
    total=0 ok=0
    while read -r label code; do
      [[ -n ${label:-} ]] || continue
      total=$((total + 1))
      if [[ $code == 200 ]]; then ok=$((ok + 1)); else findings+=("Grafana answered $code at $label"); fi
    done < "$STATE.health"
    say "grafana 200 at $ok of $total check boundaries"
    (( total >= 2 )) || findings+=("only $total health readings, want begin and end at least")
    verdict "$CHECK" ${findings[@]+"${findings[@]}"}
    ;;
  *) printf 'usage: %s begin | mark <label> | end\n' "$0" >&2; exit 2 ;;
esac
