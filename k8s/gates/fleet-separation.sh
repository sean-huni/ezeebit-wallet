#!/usr/bin/env bash
# Spec 2a: the two fleets name nothing of each other's. Usage: fleet-separation.sh <repository root>
# scripts/env-guard.sh is the one file allowed to know both fleets, so it is never scanned; this gate holds the patterns.
set -uo pipefail
GATE='fleet-separation'
ROOT=${1:?usage: fleet-separation.sh <repository root>}
cd "$ROOT" || { printf 'FAIL %s: cannot enter %s\n' "$GATE" "$ROOT"; exit 1; }

K8S_BANNED='docker compose|docker-compose|fleet\.sh|pgdata|kafkadata|lgtmdata'
FLEET_BANNED='kind|kubectl|\.kube-fleet'
ALLOW_MARK='# gate:allow-fleet-mention'

# Prints file:line: text for each word-bounded hit; a line carrying the allow mark is skipped.
scan() {
  local pattern=$1
  shift
  awk -v p="(^|[^A-Za-z0-9_-])($pattern)(\$|[^A-Za-z0-9_-])" -v allow="$ALLOW_MARK" \
    'index($0, allow) == 0 && $0 ~ p {print FILENAME ":" FNR ": " $0}' "$@"
}

[[ -d k8s && -f fleet.sh && -f compose.yaml ]] || { printf 'FAIL %s: k8s/, fleet.sh or compose.yaml missing under %s\n' "$GATE" "$ROOT"; exit 1; }
k8s_files=()
while IFS= read -r f; do
  [[ $f == k8s/gates/fleet-separation.sh ]] || k8s_files+=("$f")
done < <(find k8s -type f | sort)
(( ${#k8s_files[@]} )) || { printf 'FAIL %s: no files under k8s/\n' "$GATE"; exit 1; }

hits_k8s=$(scan "$K8S_BANNED" "${k8s_files[@]}"); rc1=$?
hits_fleet=$(scan "$FLEET_BANNED" fleet.sh compose.yaml); rc2=$?
(( rc1 == 0 && rc2 == 0 )) || { printf 'FAIL %s: awk exited %s/%s, nothing was scanned\n' "$GATE" "$rc1" "$rc2"; exit 1; }

printf 'scanned: %s files under k8s/ for /%s/; fleet.sh, compose.yaml for /%s/\n' "${#k8s_files[@]}" "$K8S_BANNED" "$FLEET_BANNED"
if [[ -z $hits_k8s && -z $hits_fleet ]]; then
  printf 'PASS %s\n' "$GATE"
  exit 0
fi
[[ -n $hits_k8s ]] && printf '%s\n' "$hits_k8s"
[[ -n $hits_fleet ]] && printf '%s\n' "$hits_fleet"
printf 'FAIL %s: a fleet names the other (hits above)\n' "$GATE"
exit 1
