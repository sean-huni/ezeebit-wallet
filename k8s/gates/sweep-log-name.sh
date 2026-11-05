#!/usr/bin/env bash
# The sweep log's name: the default is <date>-<version>, SWEEP_LABEL replaces the version, and a committed path is refused.
# Usage: sweep-log-name.sh <repository root>
set -uo pipefail
GATE=sweep-log-name
ROOT_ARG=${1:?usage: sweep-log-name.sh <repository root>}
cd "$ROOT_ARG" || { printf 'FAIL %s: cannot enter %s\n' "$GATE" "$ROOT_ARG"; exit 1; }
# shellcheck source=../lib/common.sh
source k8s/lib/common.sh
# A log every checkout carries for good: the 0.3.2 release evidence.
COMMITTED_DAY=2026-11-04 COMMITTED_VER=0.3.2
FREE_DAY=2099-01-01
bad=()

git ls-files --error-unmatch "docs/k8s/verification/$COMMITTED_DAY-$COMMITTED_VER-sweep.log" > /dev/null 2>&1 \
  || { printf 'FAIL %s: the control log %s-%s-sweep.log is not committed\n' "$GATE" "$COMMITTED_DAY" "$COMMITTED_VER"; exit 1; }

expect_path() {
  local want=$1 got rc; shift
  got=$(sweep_log_path "$@" 2> /dev/null); rc=$?
  (( rc == 0 )) && [[ $got == "$want" ]] || bad+=("sweep_log_path $* gave [$got] exit $rc, want [$want] exit 0")
}
expect_refusal() {
  local want=$1 got err rc; shift
  err=$(sweep_log_path "$@" 2>&1 > /dev/null); rc=$?
  got=$(sweep_log_path "$@" 2> /dev/null)
  (( rc == 1 )) && [[ -z $got && $err == *"$want"* ]] || bad+=("sweep_log_path $* gave [$got] exit $rc stderr [$err], want exit 1 naming [$want]")
}

expect_path "docs/k8s/verification/$FREE_DAY-$COMMITTED_VER-sweep.log" "$FREE_DAY" "$COMMITTED_VER"
expect_path "docs/k8s/verification/$FREE_DAY-$COMMITTED_VER-sweep.log" "$FREE_DAY" "$COMMITTED_VER" ''
expect_path "docs/k8s/verification/$FREE_DAY-profiling-sweep.log" "$FREE_DAY" "$COMMITTED_VER" profiling
expect_refusal 'is committed' "$COMMITTED_DAY" "$COMMITTED_VER"
expect_refusal 'is committed' "$COMMITTED_DAY" 9.9.9 "$COMMITTED_VER"
for label in ../x a/b Profiling -x '.hidden' 'a b'; do
  expect_refusal 'SWEEP_LABEL' "$FREE_DAY" "$COMMITTED_VER" "$label"
done

if (( ${#bad[@]} )); then
  for b in "${bad[@]}"; do printf 'FAIL %s: %s\n' "$GATE" "$b"; done
  exit 1
fi
printf 'PASS %s: 3 names and 8 refusals as expected\n' "$GATE"
