#!/usr/bin/env zsh
# Runs the whole fleet on this host: Postgres, WireMock, Redis and Grafana LGTM in Docker, every service as a
# Java virtual machine (JVM).
# Usage: ./fleet.sh up | down | status
set -u
cd "${0:A:h}"
if [[ -f .env ]]; then
  # The caller's environment wins over .env, as it does for docker compose.
  typeset -A CALLER
  for line in ${(f)"$(env)"}; do CALLER[${line%%=*}]=${line#*=}; done
  set -a; source ./.env; set +a
  for line in ${(k)CALLER}; do export "$line=${CALLER[$line]}"; done
  unset CALLER line
fi

RUN=.fleet
SETTLE=${FLEET_SETTLE_SECONDS:-90}
SERVICES=(config-svc eureka-svc ledger-svc conversion-svc gateway-svc)
CORE_GROUPS=('Control plane:config-svc eureka-svc' 'Business:ledger-svc conversion-svc' 'Edge:gateway-svc')
SUPPORT_GROUPS=('Data stores:postgres redis' 'External stand-ins:wiremock' 'Observability:lgtm')
# Each container with the port it serves inside; the host port is read from compose.yaml.
CONTAINERS=(postgres:5432 wiremock:8080 redis:6379 lgtm:3000)
# Grafana's datasource uids; each health check runs a real query against that backend.
BACKENDS=(prometheus loki tempo pyroscope)
ROW='%-22s %-18s %-5s %-6s %-12s %s\n'
typeset -A PORT
typeset -a BAD
PROJECT= CODE= WHY= ROUTES= ROWS=0 NOCONT=

ours() {
  # A pid file outlives its process, and the pid can be reused: only a JVM of this checkout counts.
  [[ $(ps -o command= -p $2 2>/dev/null) == *"$PWD/services/$1/build/libs/"* ]]
}

live_pid() {
  [[ -f $RUN/$1.pid ]] && ours $1 $(<$RUN/$1.pid) && print $(<$RUN/$1.pid)
}

port_of() {
  # Each service's own application.yml is the only home of its port; fleet.sh ignores SERVER_PORT.
  grep -oE 'SERVER_PORT:[0-9]+' "services/$1/src/main/resources/application.yml" | cut -d: -f2
}

load_ports() {
  # compose.yaml is the only home of the host ports; this reads them resolved against the environment.
  local json key val
  json=$(docker compose config --format json) || { print -u2 "docker compose config failed"; return 1; }
  PROJECT=$(jq -r .name <<< $json) && [[ -n $PROJECT ]] || { print -u2 "jq could not read docker compose config"; return 1; }
  jq -r '.services | to_entries[] | .key as $s | .value.ports[]? | "\($s):\(.target) \(.published)"' <<< $json |
    while read key val; do PORT[$key]=$val; done
  (( ${#PORT} )) || { print -u2 "docker compose config lists no published ports"; return 1; }
}

port_var() {
  # The variable that moves a container's host port, from its '${NAME:-default}:target' mapping.
  awk -v svc=$1 '/^  [a-z0-9-]+:$/ {cur = substr($1, 1, length($1) - 1)}
    cur == svc && match($0, /\$\{[A-Z_]+:-[0-9]+\}:[0-9]/) {s = substr($0, RSTART + 2); sub(/:-.*/, "", s); print s; exit}' compose.yaml
}

probe() {
  # Sets CODE to the HTTP status, or empty when nothing answered, and WHY to what happened in words.
  local rc
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$@"); rc=$?
  case $rc in
    0) WHY="HTTP $CODE"; return 0 ;;
    7) WHY="nothing listening (connection refused)" ;;
    28) WHY="no answer within 2s" ;;
    52) WHY="connection closed without a reply" ;;
    56) WHY="connection reset" ;;
    *) WHY="request failed (curl exit $rc)" ;;
  esac
  CODE=
}

foreign_holder() {
  # Prints who listens on a port, unless it is this compose project or nobody.
  local line pid
  for line in ${(f)"$(docker ps --filter publish=$1 --format '{{.Names}}|{{.Label "com.docker.compose.project"}}' 2>/dev/null)"}; do
    [[ ${line#*|} == $PROJECT ]] && return 1
    print "container ${line%%|*}${${line#*|}:+ (compose project ${line#*|})}"
    return 0
  done
  pid=$(lsof -nP -t -iTCP:$1 -sTCP:LISTEN 2>/dev/null | head -1)
  [[ -n $pid ]] || return 1
  print "process $pid ($(ps -o comm= -p $pid))"
}

routes_once() {
  # A fresh merchant per probe: the limiter charges these, and one fixed id drains its bucket.
  local m=$(uuidgen | tr 'A-Z' 'a-z') gw="http://localhost:$(port_of gateway-svc)/api/v1/merchants" b q
  probe "$gw/$m/balances"; b=${CODE:-$WHY}
  probe "$gw/$m/conversions/$m"; q=${CODE:-$WHY}
  ROUTES="balances answered $b, conversions answered $q"
  [[ $b == 200 && $q == 404 ]]
}

await_healthy() {
  local svc=$1 deadline=$(( SECONDS + 90 ))
  while (( SECONDS < deadline )); do
    probe "http://localhost:$(port_of $svc)/actuator/health"
    [[ $CODE == 200 ]] && { print "  $svc UP on :$(port_of $svc)"; return 0; }
    sleep 1
  done
  print -u2 "  $svc NOT healthy after 90s (${CODE:+/actuator/health answered }${CODE:-$WHY}); see $RUN/$svc.log"
  return 1
}

await_routed() {
  # Healthy is not routable: the gateway learns instances from the registry a few seconds later.
  local deadline=$(( SECONDS + 60 ))
  while (( SECONDS < deadline )); do
    routes_once && { print "  gateway routes to ledger-svc and conversion-svc"; return 0; }
    sleep 1
  done
  print -u2 "  gateway not routing after 60s ($ROUTES)"
  return 1
}

row() {
  printf "$ROW" "    $1" "$2" "$3" "$4" "$5" "$6"
  (( ROWS++ ))
  [[ $5 == (UP|HEALTHY) ]] || BAD+=("$1 $5")
}

core_row() {
  local svc=$1 port=$(port_of $1) pid state detail
  pid=$(live_pid $svc) || pid=-
  probe "http://localhost:$port/actuator/health"
  case $CODE in
    200) state=UP detail="/actuator/health 200" ;;
    '') state=DOWN detail=$WHY ;;
    *) state=DOWN detail="/actuator/health answered $CODE" ;;
  esac
  # Something else answering on a fleet port is not the fleet, however healthy it says it is.
  [[ $state == UP && $pid == - ]] && { state=UNMANAGED; detail+=", but not a JVM fleet.sh started"; }
  [[ $state == DOWN && $pid != - ]] && detail+="; process $pid alive (starting or hung)"
  if [[ $svc == gateway-svc && $state == UP ]]; then
    routes_once && detail="routes to ledger-svc and conversion-svc" || { state=DEGRADED; detail="health 200 but $ROUTES"; }
  fi
  row $svc "Java microservice" $port $pid $state $detail
}

container_row() {
  local name=${1%%:*} st h code state detail port=${PORT[$1]:--} published
  IFS='|' read -r st h code <<< "$(docker compose ps -a --format '{{.State}}|{{.Health}}|{{.ExitCode}}' $name 2>/dev/null)"
  case $st in
    running)
      case $h in
        healthy) state=HEALTHY detail="compose healthcheck passing" ;;
        starting) state=STARTING detail="compose healthcheck not passed yet" ;;
        unhealthy) state=UNHEALTHY detail="compose healthcheck failing: docker compose logs $name" ;;
        *) state=RUNNING detail="no healthcheck to judge it by" ;;
      esac ;;
    created) state="NOT RUNNING" detail="container created, never started" ;;
    exited) state="NOT RUNNING" detail="container exited with code $code" ;;
    '') state="NOT RUNNING" detail=$NOCONT ;;
    *) state=${(U)st} detail="container state $st" ;;
  esac
  if [[ $st == running ]]; then
    published=$(docker compose port $name ${1##*:} 2>/dev/null); published=${published##*:}
    if [[ -n $published && $published != $port ]]; then
      state=MISMATCH detail="published on $published, but the environment now says $port"; port=$published
    fi
  fi
  row $name "Docker container" $port - $state $detail
}

observability_rows() {
  local g=${PORT[lgtm:3000]} o=${PORT[lgtm:4318]} name body msg grafana=0
  probe "http://localhost:$g/api/health"
  if [[ $CODE == 200 ]]; then grafana=1; row grafana "inside lgtm" $g - UP "/api/health 200"
  else row grafana "inside lgtm" $g - DOWN "${CODE:+/api/health answered }${CODE:-$WHY}"; fi
  for name in $BACKENDS; do
    if (( ! grafana )); then row $name "inside lgtm" - - SKIPPED "Grafana not answering, so its datasource check cannot run"; continue; fi
    body=$(curl -s --max-time 5 "http://localhost:$g/api/datasources/uid/$name/health")
    if [[ $(jq -r '.status // empty' <<< $body 2>/dev/null) == OK ]]; then row $name "inside lgtm" - - UP "datasource health OK"
    elif [[ -z $body ]]; then row $name "inside lgtm" - - DOWN "datasource health: no reply within 5s"
    else msg=$(jq -r '.message // empty' <<< $body 2>/dev/null); row $name "inside lgtm" - - DOWN "datasource health: ${msg:-a reply that is not JSON}"; fi
  done
  # An empty export is the cheapest request only a live collector answers with 200.
  probe -X POST -H 'Content-Type: application/json' -d '{}' "http://localhost:$o/v1/metrics"
  if [[ $CODE == 200 ]]; then row otlp-collector "inside lgtm" $o - UP "accepted an empty OTLP export"
  else row otlp-collector "inside lgtm" $o - DOWN "${CODE:+export answered }${CODE:-$WHY}"; fi
}

fleet_status() {
  local group svc
  BAD=() ROWS=0
  docker info > /dev/null 2>&1 && NOCONT="no container" || NOCONT="Docker is not answering"
  printf "$ROW" SERVICE TYPE PORT PID STATE DETAIL
  print "CORE SERVICES (Java microservices launched by fleet.sh)"
  for group in $CORE_GROUPS; do
    print "  ${group%%:*}"
    for svc in ${=group#*:}; do core_row $svc; done
  done
  print "SUPPORTING SERVICES (Docker containers, and the backends inside lgtm)"
  for group in $SUPPORT_GROUPS; do
    print "  ${group%%:*}"
    for svc in ${=group#*:}; do
      container_row ${(M)CONTAINERS:#$svc:*}
      [[ $svc == lgtm ]] && observability_rows
    done
  done
  [[ ${1:-} == rows ]] && return 0
  (( ${#BAD} )) && { print "RESULT: NOT healthy: ${(j:, :)BAD}"; return 1; }
  print "RESULT: healthy, all $ROWS checks passed"
}

await_fleet() {
  # lgtm reports healthy before Loki, Tempo and the collector answer, so one read would bounce a starting stack.
  local deadline=$(( SECONDS + SETTLE )) out
  while (( SECONDS < deadline )); do
    out=$(fleet_status) && { print -r -- $out; return 0; }
    sleep 2
  done
  print -r -- $out
  return 1
}

preflight() {
  # A port held by something else is not fixed by a bounce, so it stops the start with the holder's name.
  local svc entry who var bad=0
  for svc in $SERVICES; do
    who=$(foreign_holder $(port_of $svc)) && { print -u2 "  port $(port_of $svc) ($svc) is held by $who"; bad=1; }
  done
  for entry in ${(k)PORT}; do
    for svc in $SERVICES; do
      [[ ${PORT[$entry]} == $(port_of $svc) ]] && { print -u2 "  port ${PORT[$entry]} is configured for both ${entry%%:*} and $svc"; bad=1; }
    done
    who=$(foreign_holder ${PORT[$entry]}) || continue
    var=$(port_var ${entry%%:*})
    print -u2 "  port ${PORT[$entry]} (${entry%%:*}) is held by $who${var:+; set $var to a free port (.env or the environment)}"
    bad=1
  done
  (( bad )) && print -u2 "cannot start: a port the fleet needs is taken, and a bounce would not free it"
  return $bad
}

start() {
  local svc=$1
  java -jar $PWD/services/$svc/build/libs/$svc-*.jar > "$RUN/$svc.log" 2>&1 &
  print $! > "$RUN/$svc.pid"
}

up_once() {
  # Returns 2 when a bounce cannot help (the build), 1 when a service did not come up healthy.
  # lgtm left running from an earlier run would watch the JVMs boot and page ServiceDown.
  docker compose stop lgtm > /dev/null 2>&1
  print "postgres, wiremock (the exchange-rate service), redis (the gateway rate limiter)"
  docker compose up -d --wait postgres wiremock redis || return 1
  print "build"
  ./gradlew -q bootJar || return 2
  # Control plane first: every other service fetches config and registers on boot.
  start config-svc; await_healthy config-svc || return 1
  start eureka-svc; await_healthy eureka-svc || return 1
  local svc
  for svc in ledger-svc conversion-svc gateway-svc; do start $svc; done
  for svc in ledger-svc conversion-svc gateway-svc; do await_healthy $svc || return 1; done
  await_routed || return 1
  # Last, so neither a slow boot nor a failed up can page: ServiceDown only ever sees a healthy fleet.
  print "lgtm"
  docker compose up -d --wait lgtm || return 1
}

up() {
  mkdir -p $RUN
  load_ports || return 1
  local svc running=0 bounces=1 rc
  for svc in $SERVICES; do live_pid $svc > /dev/null && running=1; done
  if (( running )); then
    # A second start would fail to bind and orphan the running JVMs, so a running fleet is checked instead.
    fleet_status && { print "fleet already up and healthy"; return 0; }
    print "fleet running but not healthy: bouncing the whole stack"
    down || return 1
    bounces=0
  fi
  preflight || return 1
  while true; do
    up_once; rc=$?
    (( rc == 2 )) && return 1
    if (( rc == 0 )); then await_fleet && break; else fleet_status; fi
    if (( ! bounces )); then
      print -u2 "fleet still not healthy after one bounce; left running for diagnosis, stop it with ./fleet.sh down"
      return 1
    fi
    print "fleet not healthy: bouncing the whole stack once"
    bounces=0
    down || return 1
  done
  print "fleet up: gateway http://localhost:$(port_of gateway-svc), registry http://localhost:$(port_of eureka-svc), grafana http://localhost:${PORT[lgtm:3000]}"
}

down() {
  load_ports || return 1
  local svc pid who
  local -a left
  # Monitoring first, so a deliberate stop never pages.
  docker compose stop lgtm
  for svc in ${(Oa)SERVICES}; do
    [[ -f $RUN/$svc.pid ]] || continue
    pid=$(<$RUN/$svc.pid)
    if ! ours $svc $pid; then
      kill -0 $pid 2>/dev/null && print "left $svc's stale pid $pid alone: that process is not a JVM of this checkout"
    elif kill $pid 2>/dev/null; then
      # Graceful shutdown takes a few seconds; returning early leaves the port bound for the next up.
      local deadline=$(( SECONDS + 30 ))
      while kill -0 $pid 2>/dev/null && (( SECONDS < deadline )); do sleep 0.5; done
      if kill -0 $pid 2>/dev/null; then kill -9 $pid; print "killed $svc ($pid): no graceful stop within 30s"
      else print "stopped $svc ($pid)"; fi
    fi
    rm -f $RUN/$svc.pid
  done
  docker compose down
  fleet_status rows
  # Verified rather than assumed: anything still holding a port makes the next up fail.
  for svc in $SERVICES; do
    who=$(foreign_holder $(port_of $svc)) && left+=("port $(port_of $svc) ($svc) still held by $who")
  done
  for svc in ${(f)"$(docker compose ps -a --format '{{.Name}}' 2>/dev/null)"}; do left+=("container $svc still present"); done
  for pid in ${(f)"$(pgrep -f "$PWD/services/[a-z-]+/build/libs/" 2>/dev/null)"}; do left+=("fleet JVM $pid still running: $(ps -o command= -p $pid | cut -c1-120)"); done
  if (( ${#left} )); then
    print -u2 "fleet NOT fully down:"
    print -u2 -l -- "  "${^left}
    return 1
  fi
  print "fleet down: no fleet JVM, container or port left"
}

case ${1:-} in
  up) up ;;
  down) down ;;
  status) load_ports && fleet_status ;;
  *) print -u2 "usage: $0 up|down|status"; exit 2 ;;
esac
