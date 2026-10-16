#!/usr/bin/env bash
# Runs scripts/env-guard.sh against stubbed docker, ps and lsof in a sandbox checkout, so no case needs Docker, a
# fleet or a cluster. Each case asserts the exact exit code and one phrase of stderr. Usage: bash scripts/test/env-guard-test.sh
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REPO=$(cd "$HERE/../.." && pwd -P)
SB=$(mktemp -d "${TMPDIR:-/tmp}/env-guard-test.XXXXXX") || exit 1
SB=$(cd "$SB" && pwd -P)
trap 'rm -rf -- "$SB"' EXIT
mkdir -p "$SB/scripts"
cp "$REPO/scripts/env-guard.sh" "$SB/scripts/env-guard.sh" 2> /dev/null
ln -s "$REPO/services" "$SB/services"
ln -s "$REPO/compose.yaml" "$SB/compose.yaml"
ln -s "$REPO/k8s" "$SB/k8s"
G=$SB/scripts/env-guard.sh
LEASE=$SB/.env-lease
PROJECT=$(basename "$SB" | tr '[:upper:]' '[:lower:]')
export PATH="$HERE/stubs:$PATH"
unset COMPOSE_PROJECT_NAME LEDGER_GRPC_PORT POSTGRES_PORT RATES_PORT REDIS_PORT KAFKA_PORT

KIND_UP='ezeebit-control-plane|running|||ezeebit|127.0.0.1:8080->30080/tcp, 127.0.0.1:3000->30300/tcp'
KIND_DOWN='ezeebit-control-plane|exited|||ezeebit|'
PASS=0 TOTAL=0 RC='' ERR=''

reset() {
  rm -rf -- "$LEASE" "$LEASE.reclaim" "$SB/.fleet"
  unset STUB_CONTAINERS STUB_DOCKER_DOWN STUB_LISTENERS STUB_PIDS STUB_PS_FAIL STUB_LSOF_ERROR
}

guard() {
  bash "$G" "$@" > "$SB/out" 2> "$SB/err"; RC=$?
  ERR=$(cat "$SB/err")
}

expect() {
  local name=$1 rc=$2 phrase=$3
  TOTAL=$((TOTAL + 1))
  if [[ $RC == "$rc" && $ERR == *"$phrase"* ]]; then
    PASS=$((PASS + 1)); printf 'ok    %s\n' "$name"
  else
    printf 'FAIL  %s: want exit %s and "%s", got exit %s: %s\n' "$name" "$rc" "$phrase" "$RC" "${ERR//$'\n'/ | }"
  fi
}

expect_true() {
  local name=$1
  shift
  TOTAL=$((TOTAL + 1))
  if "$@"; then PASS=$((PASS + 1)); printf 'ok    %s\n' "$name"; else printf 'FAIL  %s\n' "$name"; fi
}

# shellcheck disable=SC2329 # called through expect_true
lease_owner_is() { [[ -f $LEASE/owner && $(cat "$LEASE/owner") == "$1" ]]; }
# shellcheck disable=SC2329 # called through expect_true
no_lease() { [[ ! -e $LEASE ]]; }
# shellcheck disable=SC2329 # called through expect_true
no_reclaim_lock() { [[ ! -e $LEASE.reclaim ]]; }
# shellcheck disable=SC2329 # called through expect_true
not_in_err() { [[ $ERR != *"$1"* ]]; }

dead_pid() {
  local p
  sleep 0 & p=$!
  wait "$p"
  printf '%s\n' "$p"
}

fake_lease() {
  local owner=$1 pid=$2
  mkdir -p "$LEASE"
  printf '%s\n' "$pid" > "$LEASE/pid"
  /bin/ps -o lstart= -p "$pid" > "$LEASE/started" 2> /dev/null
  printf '2026-10-16T00:00:00Z\n' > "$LEASE/since"
  printf '%s\n' "$owner" > "$LEASE/owner"
}

reset; STUB_CONTAINERS="postgres|running|other|$SB|||127.0.0.1:5432->5432/tcp"; export STUB_CONTAINERS
guard acquire k8s
expect "k8s refused while compose containers run (working_dir label)" 1 "compose"
expect_true "a refused acquire drops the lease it took" no_lease

reset; STUB_CONTAINERS="redis|running|$PROJECT|/elsewhere|||"; export STUB_CONTAINERS
guard acquire k8s
expect "k8s refused while compose containers run (project label)" 1 "redis"

reset; mkdir -p "$SB/.fleet"; printf '99901\n' > "$SB/.fleet/config-svc.pid"
STUB_PIDS="99901 java -jar $SB/services/config-svc/build/libs/config-svc-0.0.1.jar"; export STUB_PIDS
guard acquire k8s
expect "k8s refused while a fleet JVM lives with no containers" 1 "config-svc"

reset; mkdir -p "$SB/.fleet"; printf '99902\n' > "$SB/.fleet/ledger-svc.pid"
STUB_PIDS="99902 /usr/bin/vim notes.txt"; export STUB_PIDS
guard acquire k8s
expect "a pid file naming a foreign process is not a fleet JVM" 0 "lease held by k8s"

reset; sleep 30 & live=$!
mkdir -p "$SB/.fleet"; printf '%s\n' "$live" > "$SB/.fleet/gateway-svc.pid"; STUB_PS_FAIL="$live"; export STUB_PS_FAIL
guard acquire k8s
kill "$live" 2> /dev/null; wait "$live" 2> /dev/null
expect "ps failing on a live fleet pid fails closed" 2 "ps could not read live pid"
expect_true "a blind JVM probe drops the lease it took" no_lease

reset; mkdir -p "$SB/.fleet"; dead=$(dead_pid); printf '%s\n' "$dead" > "$SB/.fleet/gateway-svc.pid"; STUB_PS_FAIL="$dead"; export STUB_PS_FAIL
guard acquire k8s
expect "ps failing on a dead fleet pid is no JVM" 0 "lease held by k8s"

reset; STUB_LSOF_ERROR=1; export STUB_LSOF_ERROR
guard acquire k8s
expect "lsof failing with stderr fails closed" 2 "lsof on port"

reset; STUB_LISTENERS="8080=4242"; STUB_PIDS="4242 java -jar somewhere.jar"; export STUB_LISTENERS STUB_PIDS
guard acquire k8s
expect "k8s refused while a foreign listener holds 8080" 1 "port 8080 is held by process 4242 (java)"
expect "a foreign holder of 8080 is named foreign" 1 "not part of the compose fleet"
expect_true "a foreign holder is never reported as the compose fleet running" not_in_err "the compose fleet still runs"

reset; STUB_LISTENERS="3000=4245"; STUB_PIDS="4245 node server.js"; export STUB_LISTENERS STUB_PIDS
guard acquire k8s
expect "k8s refused while a foreign listener holds 3000" 1 "port 3000 is held by process 4245 (node)"

reset; STUB_LISTENERS="5432=4243 8081=4244 9081=4246"; export STUB_LISTENERS
guard acquire k8s
expect "foreign holders of ports kind does not bind do not block k8s" 0 "lease held by k8s"

reset; STUB_CONTAINERS="otel|running|elsewhere|/elsewhere||127.0.0.1:2999-3001->2999-3001/tcp"
STUB_LISTENERS="3000=67846"; STUB_PIDS="67846 com.docker.backend"; export STUB_CONTAINERS STUB_LISTENERS STUB_PIDS
guard acquire k8s
expect "a port inside a published range names its container, not the Docker backend" 1 "3000 is held by container otel"

reset; STUB_CONTAINERS="gw|running|$PROJECT|/elsewhere|||127.0.0.1:8080->8080/tcp"; export STUB_CONTAINERS
guard acquire k8s
expect "a compose container on 8080 is the compose fleet" 1 "the compose fleet still runs: compose containers running: gw"
expect_true "a compose container on 8080 is not named foreign" not_in_err "not part of the compose fleet"

reset; mkdir -p "$SB/.fleet"; printf '99903\n' > "$SB/.fleet/gateway-svc.pid"
STUB_PIDS="99903 java -jar $SB/services/gateway-svc/build/libs/gateway-svc-0.0.1.jar"; STUB_LISTENERS="8080=99903"; export STUB_PIDS STUB_LISTENERS
guard acquire k8s
expect "a fleet JVM on 8080 is the compose fleet" 1 "fleet JVM gateway-svc (pid 99903)"
expect_true "a fleet JVM on 8080 is not named foreign" not_in_err "not part of the compose fleet"

reset; fake_lease compose "$(dead_pid)"; STUB_LISTENERS="8080=4247"; STUB_PIDS="4247 nginx"; export STUB_LISTENERS STUB_PIDS
guard acquire k8s
expect "a foreign 8080 holder is not the stale compose lease still running" 1 "not part of the compose fleet"
expect_true "a foreign holder does not keep a stale compose lease alive" not_in_err "holds the lease and still runs"

reset; STUB_CONTAINERS="$KIND_UP"; export STUB_CONTAINERS
guard acquire k8s
expect "k8s acquire ignores its own kind node on 8080 and 3000" 0 "lease held by k8s"

reset; STUB_CONTAINERS="$KIND_UP"; export STUB_CONTAINERS
guard acquire compose
expect "compose refused while the kind node runs" 1 "kind"

reset; STUB_CONTAINERS="$KIND_DOWN"; export STUB_CONTAINERS
guard acquire compose
expect "compose refused while the kind node is stopped" 1 "stopped"

reset; fake_lease k8s "$(dead_pid)"
guard acquire compose
expect "a stale k8s lease is reclaimed by compose" 0 "stale"
expect_true "the reclaimed lease names compose" lease_owner_is compose
expect_true "a finished reclaim removes its lock" no_reclaim_lock

reset; fake_lease k8s "$(dead_pid)"; mkdir "$LEASE.reclaim"
guard acquire compose
expect "a stale lease another acquire is reclaiming is left to it" 1 "another acquire is reclaiming"
expect_true "the lease being reclaimed by another is untouched" lease_owner_is k8s
expect_true "another acquire's reclaim lock is untouched" test -d "$LEASE.reclaim"

reset; fake_lease k8s "$(dead_pid)"; STUB_CONTAINERS="$KIND_UP"; export STUB_CONTAINERS
guard acquire compose
expect "a k8s lease whose kind node runs refuses compose" 1 "kind"
expect_true "the live lease is left with k8s" lease_owner_is k8s

reset; fake_lease k8s "$$"
guard acquire compose
expect "a k8s lease whose up is still running refuses compose" 1 "still running"
expect_true "the in-progress lease is left with k8s" lease_owner_is k8s

reset
guard acquire compose
guard acquire compose
expect "the same owner acquiring again is idempotent" 0 "lease held by compose"

reset; fake_lease nobody "$(dead_pid)"
guard acquire compose
expect "a lease held by an unknown owner fails closed" 2 "unknown owner"
expect "the unknown-owner refusal says how to recover" 2 "rm -r $LEASE"

reset; STUB_DOCKER_DOWN=1; export STUB_DOCKER_DOWN
guard acquire k8s
expect "Docker unreachable fails closed" 2 "docker"
expect_true "a blind acquire drops the lease it took" no_lease

reset
guard acquire compose
guard release k8s
expect "release by the wrong owner is refused" 0 "left alone"
expect_true "the wrong owner's release leaves the lease" lease_owner_is compose
guard release compose
expect "release by the owner" 0 "released"
expect_true "the owner's release removes the lease" no_lease

reset
guard state
expect "state reads every fact" 0 ""
TOTAL=$((TOTAL + 1))
if [[ $(cat "$SB/out") == $'lease none\ncompose absent\njvms 0\nports none\nkind absent' ]]; then
  PASS=$((PASS + 1)); printf 'ok    state prints one line per fact\n'
else
  printf 'FAIL  state prints one line per fact: %s\n' "$(tr '\n' '|' < "$SB/out")"
fi

for round in 1 2 3; do
  reset
  ( bash "$G" acquire compose > /dev/null 2>&1; printf '%s\n' "$?" > "$SB/rc1"; sleep 1 ) &
  ( bash "$G" acquire k8s > /dev/null 2>&1; printf '%s\n' "$?" > "$SB/rc2"; sleep 1 ) &
  wait
  pair=$(sort "$SB/rc1" "$SB/rc2" 2> /dev/null | tr '\n' ' ')
  TOTAL=$((TOTAL + 1))
  if [[ $pair == "0 1 " ]]; then
    PASS=$((PASS + 1)); printf 'ok    concurrent acquires, round %s: one 0, one 1\n' "$round"
  else
    printf 'FAIL  concurrent acquires, round %s: exits %s\n' "$round" "$pair"
  fi
done

if (( PASS == TOTAL )); then printf 'PASS %s/%s\n' "$PASS" "$TOTAL"; exit 0; fi
printf 'FAIL %s/%s passed\n' "$PASS" "$TOTAL"
exit 1
