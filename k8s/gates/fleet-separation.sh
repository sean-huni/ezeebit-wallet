#!/usr/bin/env bash
# Spec 2a: the two fleets name nothing of each other's. Usage: fleet-separation.sh <repository root>
# scripts/env-guard.sh is the one file allowed to know both fleets, so it is never scanned. This file is scanned
# like any other under k8s/, except its three definition lines below, which must name what they ban.
set -uo pipefail
GATE='fleet-separation'
ROOT=${1:?usage: fleet-separation.sh <repository root>}
cd "$ROOT" || { printf 'FAIL %s: cannot enter %s\n' "$GATE" "$ROOT"; exit 1; }

K8S_BANNED='docker compose|docker-compose|fleet[.]sh|pgdata|kafkadata|lgtmdata'
FLEET_BANNED='kind|kubectl|[.]kube-fleet|kind-ezeebit|ezeebit-control-plane'
HOST_FILES='fleet.sh compose.yaml'
SELF=k8s/gates/fleet-separation.sh

# Prints file:line: text per hit. Only letters and digits extend a word, so `_` and `-` are boundaries:
# a volume name after an underscore and a cluster name after a dash both match.
# Patterns travel through ENVIRON, which keeps backslashes verbatim (awk -v would eat them).
scan() {
  PATTERN="(^|[^A-Za-z0-9])($1)(\$|[^A-Za-z0-9])" SELF=$SELF awk '
    FILENAME == ENVIRON["SELF"] && /^(K8S_BANNED|FLEET_BANNED|HOST_FILES)=/ {next}
    $0 ~ ENVIRON["PATTERN"] {print FILENAME ":" FNR ": " $0}' "${@:2}"
}

host_files=()
for f in $HOST_FILES; do host_files+=("$f"); done
[[ -d k8s && -f ${host_files[0]} && -f ${host_files[1]} ]] || { printf 'FAIL %s: k8s/ or %s missing under %s\n' "$GATE" "$HOST_FILES" "$ROOT"; exit 1; }
k8s_files=()
while IFS= read -r f; do k8s_files+=("$f"); done < <(find k8s -type f | sort)
(( ${#k8s_files[@]} )) || { printf 'FAIL %s: no files under k8s/\n' "$GATE"; exit 1; }

hits_k8s=$(scan "$K8S_BANNED" "${k8s_files[@]}"); rc1=$?
hits_fleet=$(scan "$FLEET_BANNED" "${host_files[@]}"); rc2=$?
(( rc1 == 0 && rc2 == 0 )) || { printf 'FAIL %s: awk exited %s/%s, nothing was scanned\n' "$GATE" "$rc1" "$rc2"; exit 1; }

printf 'scanned: %s files under k8s/ for /%s/; %s for /%s/\n' "${#k8s_files[@]}" "$K8S_BANNED" "$HOST_FILES" "$FLEET_BANNED"
if [[ -z $hits_k8s && -z $hits_fleet ]]; then
  printf 'PASS %s\n' "$GATE"
  exit 0
fi
[[ -n $hits_k8s ]] && printf '%s\n' "$hits_k8s"
[[ -n $hits_fleet ]] && printf '%s\n' "$hits_fleet"
printf 'FAIL %s: a fleet names the other (hits above)\n' "$GATE"
exit 1
