#!/usr/bin/env bash
# Virtual threads are on exactly when a JVM pod's CPU limit is above 1 core: one core gives one carrier, and one
# pinned virtual thread then stalls every request. Usage: virtual-threads.sh <repository root>
set -uo pipefail
GATE='virtual-threads'
ROOT=${1:?usage: virtual-threads.sh <repository root>}
cd "$ROOT" || { printf 'FAIL %s: cannot enter %s\n' "$GATE" "$ROOT"; exit 1; }

SERVED_DIR=services/config-svc/src/main/resources/served
KEY=spring.threads.virtual.enabled

# yaml_get <file> <dotted.key>: the scalar at that block-style path, or nothing. House style: two-space indent.
yaml_get() {
  [[ -f $1 ]] || return 0
  awk -v want="$2" '
    /^[[:space:]]*(#|$)/ { next }
    {
      match($0, /^ */); depth = RLENGTH / 2
      line = substr($0, RLENGTH + 1)
      if (!match(line, /^[A-Za-z0-9_.-]+:/)) next
      key = substr(line, 1, RLENGTH - 1); val = substr(line, RLENGTH + 1)
      sub(/[[:space:]]+#.*$/, "", val); gsub(/^[[:space:]]+|[[:space:]]+$|["\047]/, "", val)
      path[depth] = key; p = path[0]
      for (i = 1; i <= depth; i++) p = p "." path[i]
      if (p == want && val != "") { print val; exit }
    }' "$1"
}

# millicores <quantity>: "2" -> 2000, "1500m" -> 1500, "1.5" -> 1500, anything else -> nothing.
millicores() {
  [[ $1 =~ ^([0-9]+)m$ ]] && { printf '%s' "${BASH_REMATCH[1]}"; return; }
  [[ $1 =~ ^([0-9]+)$ ]] && { printf '%s' "$(( BASH_REMATCH[1] * 1000 ))"; return; }
  [[ $1 =~ ^([0-9]+)\.([0-9])$ ]] && printf '%s' "$(( BASH_REMATCH[1] * 1000 + BASH_REMATCH[2] * 100 ))"
}

# enabled_for <service> <manifest>: the effective value, highest precedence first: the manifest env, then (for a
# config client) the served per-service and shared files, which outrank the file that imports them, then its own file.
enabled_for() {
  local svc=$1 manifest=$2 own="services/$1/src/main/resources/application.yml" v
  v=$(awk '/name:[[:space:]]*SPRING_THREADS_VIRTUAL_ENABLED/ {
        if (!match($0, /value:/)) getline
        if (match($0, /value:[[:space:]]*["\047]?[A-Za-z]+/)) { s = substr($0, RSTART, RLENGTH); sub(/value:[[:space:]]*["\047]?/, "", s); print s; exit } }' "$manifest")
  if [[ -z $v ]] && grep -qE 'import:.*configserver' "$own" 2>/dev/null; then
    v=$(yaml_get "$SERVED_DIR/$svc.yml" "$KEY")
    [[ -z $v ]] && v=$(yaml_get "$SERVED_DIR/application.yml" "$KEY")
  fi
  [[ -z $v ]] && v=$(yaml_get "$own" "$KEY")
  printf '%s' "${v:-false}"
}

[[ -f $SERVED_DIR/application.yml ]] || { printf 'FAIL %s: %s/application.yml missing\n' "$GATE" "$SERVED_DIR"; exit 1; }
bad=() seen=0
for manifest in k8s/base/app/*.yaml; do
  grep -qE "image:[[:space:]]*[\"']?ezeebit/" "$manifest" || continue
  svc=$(basename "$manifest" .yaml)
  [[ -d services/$svc ]] || { bad+=("$svc: manifest has an ezeebit image but services/$svc does not exist"); continue; }
  seen=$((seen + 1))
  cpu=$(awk '/limits:/ { if (match($0, /cpu:[[:space:]]*["\047]?[0-9.]+m?/)) { s = substr($0, RSTART, RLENGTH); sub(/cpu:[[:space:]]*["\047]?/, "", s); print s; exit }; inl = 1; next }
             inl && /cpu:/ { s = $0; sub(/.*cpu:[[:space:]]*["\047]?/, "", s); gsub(/["\047[:space:]]/, "", s); print s; exit }' "$manifest")
  mc=$(millicores "$cpu")
  [[ -n $mc ]] || { bad+=("$svc: no readable CPU limit ('${cpu:-missing}') in $manifest"); continue; }
  on=$(enabled_for "$svc" "$manifest")
  printf '%-15s cpu limit=%-5s %s=%s\n' "$svc" "$cpu" "$KEY" "$on"
  if (( mc > 1000 )) && [[ $on != true ]]; then bad+=("$svc: CPU limit $cpu is above 1 core but virtual threads are '$on' (want true)"); fi
  if (( mc <= 1000 )) && [[ $on == true ]]; then bad+=("$svc: CPU limit $cpu gives one carrier, so virtual threads must be off"); fi
done
(( seen )) || bad+=("no JVM Deployment (image ezeebit/*) found under k8s/base/app")

if (( ${#bad[@]} == 0 )); then
  printf 'PASS %s (%s JVM services)\n' "$GATE" "$seen"
  exit 0
fi
printf '  %s\n' "${bad[@]}"
printf 'FAIL %s: %s violation(s)\n' "$GATE" "${#bad[@]}"
exit 1
