#!/usr/bin/env zsh
# Sweep test of fleet.sh: every outcome of up, down and status, each asserted on its exit code and its words.
# Run from anywhere: zsh docs/fleet/verification/2026-10-13-fleet-sweep.zsh
# About 22 minutes: it starts the fleet seven times. Leaves the fleet down. Needs ports 8081 and 16399 free.
set -u
cd "${0:A:h}/../../.."
# 6379 is held by another project's Redis on the machine this was written on.
export REDIS_PORT=${REDIS_PORT:-6390}
# Its own compose project, so its containers and volumes (case 16 deletes them) are never the developer's.
export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-ezeebit-wallet-sweep}

PASS=0 FAIL=0 OUT= RC=
LEDGER=$(grep -oE 'SERVER_PORT:[0-9]+' services/ledger-svc/src/main/resources/application.yml | cut -d: -f2)
SQUAT=16399

run() { print "\$ $*"; OUT=$("$@" 2>&1); RC=$?; print -r -- "$OUT" | sed 's/^/  | /'; print "  | exit=$RC"; }
ok() { print "  PASS  $1"; PASS=$(( PASS + 1 )); }
ko() { print "  FAIL  $1"; FAIL=$(( FAIL + 1 )); }
want_rc() { if [[ $RC == $1 ]]; then ok "exit $1"; else ko "exit $RC, wanted $1"; fi; }
want() { if [[ $OUT == *"$1"* ]]; then ok "says: $1"; else ko "never says: $1"; fi; }
never() { if [[ $OUT != *"$1"* ]]; then ok "never says: $1"; else ko "says: $1"; fi; }
want_line() {  # one line holding all the given words, e.g. a row's name and its state
  local l w hit
  for l in ${(f)OUT}; do hit=1; for w in "$@"; do [[ $l == *"$w"* ]] || hit=0; done; (( hit )) && { ok "a line holds: $*"; return; }; done
  ko "no line holds: $*"
}
lines_with() {  # lines_with <n> <text>: the text appears on exactly n lines
  local l n=0
  for l in ${(f)OUT}; do [[ $l == *"$2"* ]] && n=$(( n + 1 )); done
  if (( n == $1 )); then ok "$1 line(s) say: $2"; else ko "$n line(s) say: $2, wanted $1"; fi
}
no_000() {
  local l
  for l in ${(f)OUT}; do [[ $l =~ '(^|[^0-9])000([^0-9]|$)' ]] && { ko "bare 000 on: $l"; return; }; done
  ok "no bare 000 anywhere"
}
pids() { print -r -- ${(o)$(cat .fleet/*.pid(N) 2>/dev/null)}; }
listener() { lsof -nP -t -iTCP:$1 -sTCP:LISTEN 2>/dev/null; }
case_() { print "\n=== CASE $1: $2"; }

# The log is evidence for one commit only, so a modified fleet.sh or sweep refuses to run.
if [[ -n $(git status --porcelain -- fleet.sh docs/fleet/verification/2026-10-13-fleet-sweep.zsh) ]]; then
  print -u2 "fleet.sh or this sweep has uncommitted changes; commit them first so the log names what ran"; exit 2
fi
print "fleet.sh sweep, $(date -u '+%Y-%m-%dT%H:%M:%SZ'), commit $(git rev-parse --short HEAD), fleet.sh blob $(git hash-object fleet.sh), REDIS_PORT=$REDIS_PORT"
case_ 0 "setup: whatever is running stops (not asserted)"
run ./fleet.sh down

case_ 1 "cold up"
run ./fleet.sh up
want_rc 0; want "RESULT: healthy"; want "fleet up:"; never "bouncing"; no_000

case_ 2 "up on a healthy fleet changes nothing"
before=$(pids)
run ./fleet.sh up
want_rc 0; want "fleet already up and healthy"; never "bouncing"
if [[ $(pids) == $before && -n $before ]]; then ok "same PIDs ($before)"; else ko "PIDs moved: '$before' to '$(pids)'"; fi

case_ 3 "status on a healthy fleet: grouped, typed, every row in words"
run ./fleet.sh status
want_rc 0
for w in "CORE SERVICES" "SUPPORTING SERVICES" "Control plane" "Business" "Edge" "Data stores" "External stand-ins" "Observability"; do want "$w"; done
for s in config-svc eureka-svc ledger-svc conversion-svc gateway-svc; do want_line "$s" "Java microservice" UP; done
for s in postgres redis wiremock lgtm; do want_line "$s" "Docker container" HEALTHY; done
for s in grafana prometheus loki tempo pyroscope otlp-collector; do want_line "$s" "inside lgtm" UP; done
want "RESULT: healthy, all 15 checks passed"; no_000

case_ 4 "a supporting container stops: up bounces the whole stack once"
docker compose stop wiremock > /dev/null 2>&1
before=$(pids)
run ./fleet.sh up
want_rc 0; want_line wiremock "NOT RUNNING" "exited"; lines_with 1 "bouncing"; want "RESULT: healthy"
if [[ $(pids) != $before && -n $(pids) ]]; then ok "new PIDs"; else ko "PIDs unchanged: $before"; fi

case_ 5 "a core JVM dies: up bounces the whole stack once"
# Slack is live on the dev fleet and a page from this case is accepted (Sean, 2026-10-13).
kill -9 $(<.fleet/ledger-svc.pid)
sleep 1
run ./fleet.sh up
want_rc 0; want_line ledger-svc DOWN "nothing listening"; lines_with 1 "bouncing"; want "RESULT: healthy"

case_ 6 "down stops everything and proves it"
run ./fleet.sh down
want_rc 0; want "fleet down: no fleet JVM, container or port left"; no_000
if [[ -z $(docker compose ps -aq) ]]; then ok "docker compose ps -a lists nothing"; else ko "containers left: $(docker compose ps -aq)"; fi
held=$(listener $LEDGER)
if [[ -z $held ]]; then ok "nothing listens on $LEDGER"; else ko "pid $held listens on $LEDGER"; fi

case_ 7 "status on a stopped fleet: exit 1, every row in words"
run ./fleet.sh status
want_rc 1; want "RESULT: NOT healthy"; no_000
want_line ledger-svc DOWN "nothing listening"; want_line postgres "NOT RUNNING" "no container"; want_line prometheus SKIPPED "Grafana not answering"

case_ 8 "a foreign process on a core port: down fails naming it, up refuses before starting anything"
python3 -m http.server $LEDGER --bind 127.0.0.1 > /dev/null 2>&1 &
squatter=$!
for i in {1..20}; do [[ -n $(listener $LEDGER) ]] && break; sleep 0.25; done
if [[ -n $(listener $LEDGER) ]]; then ok "control: lsof sees the squatter on $LEDGER"; else ko "control: squatter never listened"; fi
run ./fleet.sh down
want_rc 1; want "fleet NOT fully down"; want "port $LEDGER (ledger-svc) still held by process $squatter"
run ./fleet.sh up
want_rc 1; want "port $LEDGER (ledger-svc) is held by process $squatter"; want "cannot start"; never "build"; never "bouncing"
if [[ -z $(docker compose ps -aq) ]]; then ok "no container was started"; else ko "containers started: $(docker compose ps -aq)"; fi
kill $squatter; wait $squatter 2> /dev/null

case_ 9 "a foreign container on a supporting port: up refuses, names it and the variable to change"
image=$(docker compose config --format json | jq -r .services.redis.image)
docker run -d --rm --name fleet-sweep-squatter -p 127.0.0.1:$SQUAT:6379 $image > /dev/null
run env REDIS_PORT=$SQUAT ./fleet.sh up
want_rc 1; want "port $SQUAT (redis) is held by container fleet-sweep-squatter"; want "set REDIS_PORT"; never "bouncing"
docker rm -f fleet-sweep-squatter > /dev/null

case_ 10 "a supporting service never gets healthy: exactly one bounce, then exit 1"
shim=$(mktemp -d)
# Stands in for docker: reports wiremock unhealthy for ever and passes every other call to the real binary.
print -r -- "#!/bin/zsh
[[ \$* == *'compose ps'*'{{.Health}}'*wiremock ]] && { print 'running|unhealthy|0'; exit 0; }
exec $(whence -p docker) \"\$@\"" > $shim/docker
chmod +x $shim/docker
run env PATH=$shim:$PATH FLEET_SETTLE_SECONDS=20 ./fleet.sh up
want_rc 1; lines_with 1 "bouncing the whole stack once"; want "still not healthy after one bounce"; want_line wiremock UNHEALTHY
rm -rf $shim

case_ 11 "cleanup: down after the failed up"
run ./fleet.sh down
want_rc 0; want "fleet down: no fleet JVM, container or port left"

case_ 12 "a fresh start fails once and recovers: exactly one bounce, then exit 0"
shim=$(mktemp -d); touch $shim/fail
# wiremock reads unhealthy until the bounce's docker compose down, then the real answer comes back.
print -r -- "#!/bin/zsh
[[ \$* == *'compose down'* ]] && rm -f $shim/fail
[[ -f $shim/fail && \$* == *'compose ps'*'{{.Health}}'*wiremock ]] && { print 'running|unhealthy|0'; exit 0; }
exec $(whence -p docker) \"\$@\"" > $shim/docker
chmod +x $shim/docker
run env PATH=$shim:$PATH FLEET_SETTLE_SECONDS=20 ./fleet.sh up
want_rc 0; lines_with 1 "bouncing the whole stack once"; want_line wiremock UNHEALTHY; want "RESULT: healthy"; never "after one bounce"
rm -r $shim

case_ 13 "a JVM that ignores SIGTERM: down kills it after 30 s and still verifies"
run ./fleet.sh down
want_rc 0
zsh -c 'trap "" TERM; while :; do sleep 1; done' $PWD/services/config-svc/build/libs/sweep-ignores-term.jar &
stubborn=$!
mkdir -p .fleet; print $stubborn > .fleet/config-svc.pid
run ./fleet.sh down
want_rc 0; want "killed config-svc ($stubborn): no graceful stop within 30s"; want "fleet down: no fleet JVM, container or port left"
if kill -0 $stubborn 2> /dev/null; then ko "pid $stubborn survived"; kill -9 $stubborn; else ok "pid $stubborn is gone"; fi

case_ 14 "a stale pid file naming another program's process: down and status leave it alone"
sleep 300 &
bystander=$!
print $bystander > .fleet/ledger-svc.pid
run ./fleet.sh down
want_rc 0; want "left ledger-svc's stale pid $bystander alone"
if kill -0 $bystander 2> /dev/null; then ok "bystander $bystander still alive"; else ko "bystander $bystander was killed"; fi
print $bystander > .fleet/ledger-svc.pid
run ./fleet.sh status
want_line ledger-svc "$LEDGER  -      DOWN" "nothing listening"; never "$bystander  UP"
kill $bystander; print -n > .fleet/ledger-svc.pid

case_ 15 "a failed build never bounces"
run env JAVA_HOME=/nonexistent ./fleet.sh up
want_rc 1; want "build"; never "bouncing"
run ./fleet.sh down
want_rc 0

case_ 16 "purge releases every container, the network and both data volumes"
run ./fleet.sh up
want_rc 0
vols=$(docker volume ls -q --filter label=com.docker.compose.project=$COMPOSE_PROJECT_NAME)
if [[ -n $vols ]]; then ok "control: volumes exist before purge (${(f)vols})"; else ko "control: no volumes before purge"; fi
run ./fleet.sh purge
want_rc 0; want "fleet down: no fleet JVM, container or port left"; want "removing volumes:"; want "fleet purged"
if [[ -z $(docker volume ls -q --filter label=com.docker.compose.project=$COMPOSE_PROJECT_NAME) ]]; then ok "no volume of $COMPOSE_PROJECT_NAME left"; else ko "volumes left"; fi
if [[ -z $(docker network ls -q --filter label=com.docker.compose.project=$COMPOSE_PROJECT_NAME) ]]; then ok "no network of $COMPOSE_PROJECT_NAME left"; else ko "network left"; fi
if [[ ! -d .fleet ]]; then ok ".fleet/ removed"; else ko ".fleet/ still present"; fi

print "\nSWEEP: $PASS passed, $FAIL failed"
(( FAIL == 0 ))
