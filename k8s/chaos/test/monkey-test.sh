#!/usr/bin/env bash
# Runs k8s/chaos/monkey.sh against a strict kubectl stub, so no case needs a cluster. Each case asserts the exact exit
# code and one phrase of its output. Usage: bash k8s/chaos/test/monkey-test.sh
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
MONKEY=$HERE/../monkey.sh
SB=$(mktemp -d "${TMPDIR:-/tmp}/monkey-test.XXXXXX") || exit 1
trap 'rm -rf -- "$SB"' EXIT
export PATH="$HERE/stubs:$PATH"
export STUB_LOG=$SB/calls
PASS=0 TOTAL=0 RC='' OUT=''

reset() {
  unset KUBERNETES_SERVICE_HOST KCTL_CONTEXT STUB_LIST_APP STUB_LIST_DATA STUB_LIST_FAIL STUB_PODS STUB_DELETE_NOTFOUND
  : > "$STUB_LOG"
}

monkey() {
  bash "$MONKEY" "$@" > "$SB/out" 2>&1; RC=$?
  OUT=$(cat "$SB/out")
}

expect() {
  local name=$1 rc=$2 phrase=$3
  TOTAL=$((TOTAL + 1))
  if [[ $RC == "$rc" && $OUT == *"$phrase"* ]]; then
    PASS=$((PASS + 1)); printf 'ok    %s\n' "$name"
  else
    printf 'FAIL  %s: want exit %s and "%s", got exit %s: %s\n' "$name" "$rc" "$phrase" "$RC" "${OUT//$'\n'/ | }"
  fi
}

expect_no_delete() {
  local name=$1 calls
  TOTAL=$((TOTAL + 1))
  calls=$(cat "$STUB_LOG")
  if [[ $calls != *"delete pod"* ]]; then
    PASS=$((PASS + 1)); printf 'ok    %s\n' "$name"
  else
    printf 'FAIL  %s: a delete was issued: %s\n' "$name" "${calls//$'\n'/ | }"
  fi
}

reset
export STUB_LIST_APP=Deployment/ledger-svc STUB_PODS='ledger-svc ledger-svc-a'
monkey --victims 1
expect "happy path: one kill" 0 $'KILL ezeebit-app/ledger-svc-a service=ledger-svc\nKILLED 1'

reset
export STUB_LIST_APP=Deployment/ledger-svc STUB_PODS='ledger-svc ledger-svc-a' STUB_LIST_FAIL=ezeebit-data
monkey --victims 1
expect "one namespace cannot be listed: refused" 2 'listing services in ezeebit-data exited 1'
expect_no_delete "one namespace cannot be listed: nothing killed"

reset
monkey --victims 1
expect "no service at all: KILLED 0" 1 'KILLED 0'

reset
export STUB_LIST_APP=Deployment/ledger-svc STUB_PODS='ledger-svc'
monkey --victims 1
expect "service with no Running pod: a miss" 1 'no Running pod of ezeebit-app/Deployment/ledger-svc'
expect "service with no Running pod: KILLED 0" 1 'KILLED 0'

reset
export STUB_LIST_DATA=StatefulSet/kafka STUB_PODS='kafka kafka-0'
monkey --victims 2
expect "fewer kills than asked: exit 1" 1 $'KILL ezeebit-data/kafka-0 service=kafka'
expect "fewer kills than asked: the second draw finds no pod" 1 'no Running pod of ezeebit-data/StatefulSet/kafka'
expect "fewer kills than asked: total counted" 1 'KILLED 1'

reset
export STUB_LIST_APP=Deployment/ledger-svc STUB_PODS='ledger-svc ledger-svc-a' STUB_DELETE_NOTFOUND=ledger-svc-a
monkey --victims 1
expect "delete answers NotFound: not counted" 1 'KILLED 0'
expect "delete answers NotFound: reported" 1 'not found'

reset
export STUB_LIST_APP=Deployment/ledger-svc STUB_PODS='ledger-svc ledger-svc-a'
monkey --namespace ezeebit-obs
expect "ezeebit-obs: refused" 2 "namespace 'ezeebit-obs' is outside"
expect_no_delete "ezeebit-obs: nothing killed"

reset
export KCTL_CONTEXT=docker-desktop
monkey --victims 1
expect "another context: refused" 2 'context docker-desktop is not kind-ezeebit'

printf 'PASS %s/%s\n' "$PASS" "$TOTAL"
(( PASS == TOTAL ))
