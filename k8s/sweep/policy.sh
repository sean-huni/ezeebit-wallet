#!/usr/bin/env bash
# Spec 6.5: postgres's INGRESS rule refuses a client whose own egress is open, and admits ledger-svc. The denied arm runs
# in a probe pod in `default` (no NetworkPolicy there), whose positive control must reach kube-dns over TCP first.
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
CHECK=policy
TARGET=postgres.ezeebit-data
PROBE_NS=default
PROBE_POD="sweep-policy-$(date +%s)-$RANDOM"
CONTROL=kube-dns.kube-system
findings=()

cleanup() { kctl delete pod "$PROBE_POD" -n "$PROBE_NS" --ignore-not-found --wait=false > /dev/null 2>&1; }
trap cleanup EXIT
trap 'exit 1' INT TERM

# Resolution first, so a DNS failure is never read as a closed port; then one connect with a 5s timeout.
PG_PROBE="getent hosts $TARGET >/dev/null || { echo 'RESULT pg dns-failed'; exit 0; }
echo \"resolved \$(getent hosts $TARGET)\"
out=\$(PGPASSWORD=sweep-probe psql 'host=$TARGET port=5432 user=sweep-probe dbname=sweep-probe connect_timeout=5' -c 'SELECT 1' 2>&1); rc=\$?
echo \"psql exit=\$rc: \$out\"
case \"\$out\" in
  *'timeout expired'*) echo 'RESULT pg closed' ;;
  *'Connection refused'*) echo 'RESULT pg refused' ;;
  *FATAL*) echo 'RESULT pg open' ;;
  *) [ \$rc = 0 ] && echo 'RESULT pg open' || echo 'RESULT pg unknown' ;;
esac"

# A TCP connect to kube-dns: kube-system has no NetworkPolicy, so a probe that cannot reach it is broken, not denied.
# busybox nc prints nothing on a failed connect (measured), so only open is classified; anything else fails the arm.
CONTROL_PROBE="getent hosts $CONTROL >/dev/null || { echo 'RESULT control dns-failed'; exit 0; }
out=\$(nc -z -w 5 $CONTROL 53 2>&1); rc=\$?
echo \"nc $CONTROL:53 exit=\$rc: \$out\"
[ \$rc = 0 ] && echo 'RESULT control open' || echo 'RESULT control not-open'"

# judge <arm> <result> <want> <exit>: fail-closed, anything but the three classified outcomes is a finding.
judge() {
  case $2 in
    closed|refused|open) [[ $2 == "$3" ]] || findings+=("$1 is $2, want $3") ;;
    *) findings+=("$1 gave no classifiable result ('$2', exit $4)") ;;
  esac
}

result_of() { awk -v k="$1" '$1 == "RESULT" && $2 == k {print $3}' <<< "$2"; }

# Allowed arm: from inside a real ledger-svc pod, which postgres's ingress admits.
allowed() {
  local pods pod out rc result
  pods=$(pods_of ledger-svc); pod=${pods%%$'\n'*}
  [[ -n $pod ]] || { findings+=("no Ready ledger-svc pod to probe from"); return; }
  out=$(in_pod "$APP_NS" "$pod" "$PG_PROBE" 2>&1); rc=$?
  result=$(result_of pg "$out")
  say "== allowed: ledger-svc ($pod) -> $TARGET:5432, want open"
  printf '%s\n' "$out" | awk '{print "  " $0}'
  say "  result=${result:-none} probe exit=$rc"
  judge "allowed: ledger-svc -> $TARGET:5432" "${result:-none}" open "$rc"
}

# Denied arm: a pod no workload selects, in a namespace with no NetworkPolicy, so only postgres's ingress can stop it.
denied() {
  local policies out rc phase waited=0 control pg
  policies=$(kctl get networkpolicy -n "$PROBE_NS" -o name 2>&1); rc=$?
  (( rc == 0 )) || { findings+=("cannot list NetworkPolicies in $PROBE_NS (exit $rc): $policies"); return; }
  [[ -z $policies ]] || { findings+=("$PROBE_NS has NetworkPolicies, so its egress is not known open: $policies"); return; }
  out=$(kctl run "$PROBE_POD" -n "$PROBE_NS" --image="$PROBE_IMAGE" --image-pull-policy=Never --restart=Never \
    --labels=ezeebit.io/probe=policy --command -- sh -c "$CONTROL_PROBE; $PG_PROBE" 2>&1); rc=$?
  (( rc == 0 )) || { findings+=("kubectl run $PROBE_POD exited $rc: $out"); return; }
  while (( waited < 90 )); do
    phase=$(kctl get pod "$PROBE_POD" -n "$PROBE_NS" -o jsonpath='{.status.phase}' 2>/dev/null)
    [[ $phase == Succeeded || $phase == Failed ]] && break
    sleep 1; waited=$((waited + 1))
  done
  [[ $phase == Succeeded ]] || { findings+=("probe pod $PROBE_POD ended '${phase:-unknown}' after ${waited}s"); return; }
  out=$(kctl logs "$PROBE_POD" -n "$PROBE_NS" 2>&1); rc=$?
  (( rc == 0 )) || { findings+=("logs of $PROBE_POD exited $rc: $out"); return; }
  control=$(result_of control "$out"); pg=$(result_of pg "$out"); rc=0
  say "== denied: $PROBE_NS/$PROBE_POD -> $TARGET:5432, want closed; control -> $CONTROL:53, want open"
  printf '%s\n' "$out" | awk '{print "  " $0}'
  say "  control=${control:-none} denied=${pg:-none}"
  judge "control: $PROBE_NS probe -> $CONTROL:53" "${control:-none}" open "$rc"
  [[ $control == open ]] || { findings+=("denied arm not judged: the probe's control failed"); return; }
  judge "denied: $PROBE_NS probe -> $TARGET:5432" "${pg:-none}" closed "$rc"
}

allowed
denied

verdict "$CHECK" ${findings[@]+"${findings[@]}"}
