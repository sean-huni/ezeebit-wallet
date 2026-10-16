#!/usr/bin/env bash
# Spec 3: the singletons stay single and unscaled; the scaled three start at 2 under their HPA bounds.
# Usage: replicas.sh <repository root>
set -uo pipefail
GATE=replicas
ROOT=${1:?usage: replicas.sh <repository root>}
cd "$ROOT" || { printf 'FAIL %s: cannot enter %s\n' "$GATE" "$ROOT"; exit 1; }
# shellcheck source=manifests.sh
source "$(dirname "${BASH_SOURCE[0]}")/manifests.sh"

SINGLETONS=(config-svc eureka-svc)
# service min max cpu%
SCALED=("gateway-svc 2 4 70" "ledger-svc 2 3 70" "conversion-svc 2 3 70")

records=$(manifest_records k8s/base); rc=$?
(( rc == 0 )) || { printf 'FAIL %s: reading k8s/base exited %s\n' "$GATE" "$rc"; exit 1; }
bad=()

replicas_of() {
  local kind name replicas
  while read -r kind name replicas _; do
    [[ $kind == Deployment && $name == "$1" ]] && { printf '%s' "$replicas"; return; }
  done <<< "$records"
}

# Prints "min max cpu" of every HPA targeting $1, one line each.
hpas_of() {
  local kind name target min max cpu
  while read -r kind name target min max cpu; do
    [[ $kind == HorizontalPodAutoscaler && $target == "$1" ]] && printf '%s %s %s\n' "$min" "$max" "$cpu"
  done <<< "$records"
}

for svc in "${SINGLETONS[@]}"; do
  r=$(replicas_of "$svc"); h=$(hpas_of "$svc")
  printf '%-15s replicas=%s hpas=%s (want replicas=1 hpas=0)\n' "$svc" "${r:-none}" "$(printf '%s' "$h" | awk 'NF {n++} END {print n + 0}')"
  [[ $r == 1 ]] || bad+=("$svc replicas ${r:-missing}, want 1")
  [[ -z $h ]] || bad+=("$svc is targeted by an HPA ($h)")
done

for row in "${SCALED[@]}"; do
  read -r svc want_min want_max want_cpu <<< "$row"
  r=$(replicas_of "$svc"); h=$(hpas_of "$svc")
  n=$(printf '%s' "$h" | awk 'NF {n++} END {print n + 0}')
  printf '%-15s replicas=%s hpa=[%s] (want replicas=2 hpa=[%s %s %s])\n' "$svc" "${r:-none}" "$h" "$want_min" "$want_max" "$want_cpu"
  [[ $r == 2 ]] || bad+=("$svc replicas ${r:-missing}, want 2")
  if [[ $n != 1 ]]; then
    bad+=("$svc has $n HPAs, want 1")
  elif [[ $h != "$want_min $want_max $want_cpu" ]]; then
    bad+=("$svc HPA min/max/cpu '$h', want '$want_min $want_max $want_cpu'")
  fi
done

if (( ${#bad[@]} == 0 )); then
  printf 'PASS %s\n' "$GATE"
  exit 0
fi
printf '  %s\n' "${bad[@]}"
printf 'FAIL %s: %s violation(s)\n' "$GATE" "${#bad[@]}"
exit 1
