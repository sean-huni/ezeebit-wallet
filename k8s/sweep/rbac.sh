#!/usr/bin/env bash
# The chaos monkey's least privilege (k8s/base/chaos/rbac.yaml), asked of the API server as its ServiceAccount.
# The allowed arms are the control: a broken query answers "no" to everything. The ServiceAccount is read first because
# a RoleBinding matches the username, so can-i still answers yes for a deleted one (measured 2026-11-06).
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
CHECK=rbac
SA=system:serviceaccount:ezeebit-chaos:chaos-monkey
findings=()

# Each row: <want> <verb> <resource> <namespace>.
ALLOWED=(
  "yes list pods ezeebit-app"
  "yes delete pods ezeebit-app"
  "yes list pods ezeebit-data"
  "yes delete pods ezeebit-data"
)
DENIED=(
  "no delete pods ezeebit-obs"
  "no list pods ezeebit-obs"
  "no get secrets ezeebit-app"
  "no list secrets ezeebit-app"
  "no get secrets ezeebit-data"
  "no get secrets ezeebit-obs"
  "no patch deployments.apps ezeebit-app"
  "no create pods ezeebit-app"
  "no delete pods kube-system"
  "no delete pods ezeebit-chaos"
)

# ask <want> <verb> <resource> <ns>: kubectl prints yes or no and exits 0 or 1; anything else is a finding.
ask() {
  local want=$1 verb=$2 res=$3 ns=$4 out rc got
  out=$(kctl auth can-i "$verb" "$res" -n "$ns" --as="$SA" 2>&1); rc=$?
  got=${out%%$'\n'*}
  say "  can-i $verb $res -n $ns: $got (exit $rc), want $want"
  case "$got:$rc" in
    yes:0|no:1) [[ $got == "$want" ]] || findings+=("$verb $res in $ns is $got, want $want") ;;
    *) findings+=("$verb $res in $ns gave no classifiable answer ('$out', exit $rc)") ;;
  esac
}

out=$(kctl get serviceaccount chaos-monkey -n ezeebit-chaos -o name 2>&1); rc=$?
say "== serviceaccount: $out (exit $rc)"
(( rc == 0 )) || findings+=("ServiceAccount ezeebit-chaos/chaos-monkey not readable (exit $rc): $out")

say "== allowed (control)"
before=${#findings[@]}
for row in "${ALLOWED[@]}"; do read -r w v r n <<< "$row"; ask "$w" "$v" "$r" "$n"; done
if (( ${#findings[@]} > before )); then
  findings+=("denied arms not judged: the allowed control failed")
else
  say "== denied"
  for row in "${DENIED[@]}"; do read -r w v r n <<< "$row"; ask "$w" "$v" "$r" "$n"; done
fi

verdict "$CHECK" ${findings[@]+"${findings[@]}"}
