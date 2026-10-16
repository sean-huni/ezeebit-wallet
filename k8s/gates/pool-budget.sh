#!/usr/bin/env bash
# Spec 3 connection invariant: sum over database services of HPA maxReplicas x HikariCP pool, plus reserve,
# stays at or below PostgreSQL max_connections. Usage: pool-budget.sh <repository root>
set -uo pipefail
GATE='pool-budget'
ROOT=${1:?usage: pool-budget.sh <repository root>}
cd "$ROOT" || { printf 'FAIL %s: cannot enter %s\n' "$GATE" "$ROOT"; exit 1; }
# shellcheck source=manifests.sh
source "$(dirname "${BASH_SOURCE[0]}")/manifests.sh"

SERVED=services/config-svc/src/main/resources/served
HIKARI_DEFAULT=10
RESERVE=10

# A database service is one whose served configuration has a datasource. The pool size can also arrive as any
# relaxed-binding environment variable; the manifest env is searched for the one spelling Spring documents.
pool_of() {
  local svc=$1 v
  v=$(awk '/maximum-pool-size:/ {sub(/.*maximum-pool-size:[[:space:]]*/, ""); print; exit}' "$SERVED/$svc.yml")
  [[ $v =~ ^\$\{[A-Z_]+:([0-9]+)\}$ ]] && v=${BASH_REMATCH[1]}
  local env
  env=$(awk '/SPRING_DATASOURCE_HIKARI_MAXIMUM_?POOL_?SIZE/ {
    if (match($0, /value:[[:space:]]*["\047]?[0-9]+/)) { s = substr($0, RSTART, RLENGTH); gsub(/[^0-9]/, "", s); print s; exit } }' \
    "k8s/base/app/$svc.yaml" 2>/dev/null)
  [[ -n $env ]] && v=$env
  printf '%s' "${v:-$HIKARI_DEFAULT}"
}

[[ -d $SERVED && -f k8s/base/data/postgres.yaml ]] || { printf 'FAIL %s: %s or k8s/base/data/postgres.yaml missing\n' "$GATE" "$SERVED"; exit 1; }
max_conn=$(awk 'match($0, /max_connections=[0-9]+/) {print substr($0, RSTART + 16, RLENGTH - 16); exit}' k8s/base/data/postgres.yaml)
[[ $max_conn =~ ^[0-9]+$ ]] || { printf 'FAIL %s: no max_connections=N in k8s/base/data/postgres.yaml\n' "$GATE"; exit 1; }
records=$(manifest_records k8s/base); rc=$?
(( rc == 0 )) || { printf 'FAIL %s: reading k8s/base exited %s\n' "$GATE" "$rc"; exit 1; }

dbs=()
for f in "$SERVED"/*.yml; do
  awk '/^[[:space:]]+datasource:/ {found = 1} END {exit !found}' "$f" && dbs+=("$(basename "$f" .yml)")
done
(( ${#dbs[@]} )) || { printf 'FAIL %s: no served configuration declares a datasource\n' "$GATE"; exit 1; }

total=$RESERVE bad=()
for svc in "${dbs[@]}"; do
  max=$(while read -r kind name target _ mx _; do
    [[ $kind == HorizontalPodAutoscaler && $target == "$svc" ]] && printf '%s' "$mx"
  done <<< "$records")
  if [[ -z $max ]]; then
    max=$(while read -r kind name replicas _; do
      [[ $kind == Deployment && $name == "$svc" ]] && printf '%s' "$replicas"
    done <<< "$records")
  fi
  pool=$(pool_of "$svc")
  if [[ ! $max =~ ^[0-9]+$ || ! $pool =~ ^[0-9]+$ ]]; then
    bad+=("$svc: maxReplicas '${max:-missing}' pool '${pool:-missing}' not numbers")
    continue
  fi
  printf '%-15s maxReplicas=%s x pool=%s = %s\n' "$svc" "$max" "$pool" "$((max * pool))"
  total=$((total + max * pool))
done
printf 'reserve=%s; total=%s; postgres max_connections=%s\n' "$RESERVE" "$total" "$max_conn"
(( total <= max_conn )) || bad+=("total $total exceeds max_connections $max_conn")

if (( ${#bad[@]} == 0 )); then
  printf 'PASS %s\n' "$GATE"
  exit 0
fi
printf '  %s\n' "${bad[@]}"
printf 'FAIL %s: %s violation(s)\n' "$GATE" "${#bad[@]}"
exit 1
