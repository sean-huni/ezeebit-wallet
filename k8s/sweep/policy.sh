#!/usr/bin/env bash
# Spec 6.5: one denied edge answers nothing and one allowed edge answers, each probed from inside the real pod,
# so a policy that enforces nothing cannot pass. psql classifies the TCP outcome by its own message.
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
CHECK=policy
TARGET=postgres.ezeebit-data
findings=()

# Resolution first, so a DNS failure is never read as a closed port; then one connect with a 5s timeout.
PROBE="getent hosts $TARGET >/dev/null || { echo 'RESULT dns-failed'; exit 0; }
echo \"resolved \$(getent hosts $TARGET)\"
out=\$(PGPASSWORD=sweep-probe psql 'host=$TARGET port=5432 user=sweep-probe dbname=sweep-probe connect_timeout=5' -c 'SELECT 1' 2>&1); rc=\$?
echo \"psql exit=\$rc: \$out\"
case \"\$out\" in
  *'timeout expired'*) echo 'RESULT closed' ;;
  *'Connection refused'*) echo 'RESULT refused' ;;
  *FATAL*) echo 'RESULT open' ;;
  *) [ \$rc = 0 ] && echo 'RESULT open' || echo 'RESULT unknown' ;;
esac"

# probe <service> <want>: runs the probe in a Ready pod of <service>, appends a finding unless RESULT is <want>.
probe() {
  local svc=$1 want=$2 pods pod out rc result
  pods=$(pods_of "$svc"); pod=${pods%%$'\n'*}
  [[ -n $pod ]] || { findings+=("no Ready $svc pod to probe from"); return; }
  out=$(in_pod "$APP_NS" "$pod" "$PROBE" 2>&1); rc=$?
  result=$(awk '$1 == "RESULT" {print $2}' <<< "$out")
  say "== $svc ($pod) -> $TARGET:5432, want $want"
  printf '%s\n' "$out" | awk '{print "  " $0}'
  say "  result=${result:-none} probe exit=$rc"
  case $result in
    closed|refused|open) [[ $result == "$want" ]] || findings+=("$svc -> $TARGET:5432 is $result, want $want") ;;
    *) findings+=("$svc -> $TARGET:5432 gave no classifiable result ('$result', exit $rc)") ;;
  esac
}

# Denied: wiremock has no egress beyond DNS (spec 4). Allowed, the positive control: ledger-svc owns a postgres edge.
probe wiremock closed
probe ledger-svc open

verdict "$CHECK" ${findings[@]+"${findings[@]}"}
