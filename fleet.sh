#!/usr/bin/env zsh
# Runs the whole fleet on this host: Postgres, WireMock, Redis, Kafka and Grafana LGTM in Docker, every service as a
# Java virtual machine (JVM).
# Usage: ./fleet.sh up | down | purge | status   (purge also deletes the data volumes)
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
SUPPORT_GROUPS=('Data stores:postgres redis' 'External stand-ins:wiremock' 'Messaging:kafka' 'Observability:lgtm')
# Each container with the port it serves inside; the host port is read from compose.yaml.
CONTAINERS=(postgres:5432 wiremock:8080 redis:6379 kafka:9092 lgtm:3000)
# Grafana's datasource uids; each health check runs a real query against that backend.
BACKENDS=(prometheus loki tempo pyroscope)
ROW='%-22s %-18s %-5s %-6s %-12s %s\n'
typeset -A PORT
typeset -a BAD
PROJECT= CODE= WHY= BODY= ROUTES= ROWS=0 NOCONT=

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

curl_why() {
  # curl's exit code $1 for a request allowed $2 seconds, in words.
  case $1 in
    7) print "nothing listening (connection refused)" ;;
    28) print "no answer within $2s" ;;
    52) print "connection closed without a reply" ;;
    56) print "connection reset" ;;
    *) print "request failed (curl exit $1)" ;;
  esac
}

probe() {
  # Sets CODE to the HTTP status, or empty when nothing answered, and WHY to what happened in words.
  local rc
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$@"); rc=$?
  (( rc == 0 )) && { WHY="HTTP $CODE"; return 0; }
  WHY=$(curl_why $rc 2)
  CODE=
}

call() {
  # A JSON POST: sets BODY and CODE, or CODE empty and WHY in words when nothing answered.
  local out rc
  out=$(curl -s -w '\n%{http_code}' --max-time 15 -X POST -H 'Content-Type: application/json' "$@"); rc=$?
  (( rc == 0 )) && { BODY=${out%$'\n'*}; CODE=${out##*$'\n'}; WHY="HTTP $CODE"; return 0; }
  BODY= CODE= WHY=$(curl_why $rc 15)
}

failed() {
  # What a failed step answered: its HTTP status and error code, or what happened in words.
  local code
  [[ -z $CODE ]] && { print "$1: $WHY"; return; }
  code=$(jq -r '.code // empty' <<< $BODY 2>/dev/null)
  print "$1 answered $CODE${code:+ $code}"
}

convert_once() {
  # Money in, quote, convert: the one path that crosses gateway, conversion, the rate feed and ledger's gRPC port.
  local m=$1 gw="http://localhost:$(port_of gateway-svc)/api/v1/merchants/$1" q st
  call -H "Idempotency-Key: fleet-check-$m" "http://localhost:$(port_of ledger-svc)/internal/v1/deposits" \
    -d "{\"merchantId\":\"$m\",\"currency\":\"ZAR\",\"amount\":\"100.00\",\"reference\":\"fleet check\"}"
  [[ $CODE == 201 ]] || { ROUTES="a conversion did not complete: $(failed deposit)"; return 1; }
  # Quote and convert back to back: the quote lives 15 s.
  call "$gw/quotes" -d '{"sell":"ZAR","buy":"USDT","sellAmount":"50.00"}'
  q=$(jq -r '.data.quoteId // empty' <<< $BODY 2>/dev/null)
  [[ $CODE == 201 && -n $q ]] || { ROUTES="a conversion did not complete: $(failed quote)"; return 1; }
  call -H "Idempotency-Key: fleet-check-$m" "$gw/conversions" -d "{\"quoteId\":\"$q\"}"
  st=$(jq -r '.data.status // empty' <<< $BODY 2>/dev/null)
  [[ $st == COMPLETED ]] && return 0
  if [[ $CODE == 201 ]]; then ROUTES="a conversion did not complete: status ${st:-missing}"
  else ROUTES="a conversion did not complete: $(failed convert)"; fi
  return 1
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
  [[ $b == 200 && $q == 404 ]] || return 1
  convert_once $m
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
    routes_once && { print "  gateway routes to ledger-svc and conversion-svc, and a conversion completes"; return 0; }
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
  if [[ $svc == ledger-svc && $state == UP && $pid != - ]]; then
    # conversion-svc posts to ledger over gRPC, so a healthy HTTP side with no gRPC listener cannot convert.
    lsof -nP -a -p $pid -iTCP:${LEDGER_GRPC_PORT:-9081} -sTCP:LISTEN > /dev/null 2>&1 && detail+=", gRPC :${LEDGER_GRPC_PORT:-9081} listening" ||
      { state=DEGRADED; detail="gRPC port ${LEDGER_GRPC_PORT:-9081} not listening"; }
  fi
  if [[ $svc == gateway-svc && $state == UP ]]; then
    if routes_once; then detail="routes to ledger-svc and conversion-svc, a conversion completes"
    elif [[ $ROUTES == 'a conversion'* ]]; then state=DEGRADED detail=$ROUTES
    else state=DEGRADED detail="health 200 but $ROUTES"; fi
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

ds_ok() {
  # One datasource health check of backend $1 through Grafana; sets BODY to the reply.
  BODY=$(curl -s --max-time 5 "http://localhost:${PORT[lgtm:3000]}/api/datasources/uid/$1/health")
  [[ $(jq -r '.status // empty' <<< $BODY 2>/dev/null) == OK ]]
}

installer_mark() {
  # The last line Grafana's background plugin installer logged; it moves while plugins are still being installed.
  docker compose exec -T lgtm sh -c "grep -E 'logger=(plugin.backgroundinstaller|plugin.installer|installer.fs) ' /otel-lgtm/grafana/data/log/grafana.log | tail -1" 2>/dev/null
}

settle_plugins() {
  # lgtm reports healthy while Grafana still downloads its datasource plugins one by one, and each answers
  # "Plugin not registered" while it is replaced: settled is every backend OK and the installer idle for 5 polls.
  local deadline=$(( SECONDS + SETTLE )) streak=0 name ok mark last=
  print "waiting for grafana plugins to settle"
  while (( streak < 5 && SECONDS < deadline )); do
    ok=1
    for name in $BACKENDS; do ds_ok $name || ok=0; done
    mark=$(installer_mark)
    [[ $mark == *(msg=\"Installing plugin\"|Updating plugin|Downloaded and extracted)* || $mark != "$last" ]] && ok=0
    last=$mark
    if (( ok )); then streak=$(( streak + 1 )); else streak=0; fi
    (( streak < 5 )) && sleep 1
  done
  # A timeout falls through to the status, which names the backend that is still down.
  return 0
}

observability_rows() {
  local g=${PORT[lgtm:3000]} o=${PORT[lgtm:4318]} name msg try up grafana=0
  probe "http://localhost:$g/api/health"
  if [[ $CODE == 200 ]]; then grafana=1; row grafana "inside lgtm" $g - UP "/api/health 200"
  else row grafana "inside lgtm" $g - DOWN "${CODE:+/api/health answered }${CODE:-$WHY}"; fi
  for name in $BACKENDS; do
    if (( ! grafana )); then row $name "inside lgtm" - - SKIPPED "Grafana not answering, so its datasource check cannot run"; continue; fi
    # Grafana answers a datasource health check with a transient error now and then; only 3 in a row is DOWN.
    up=0
    for try in 1 2 3; do
      ds_ok $name && { up=1; break; }
      (( try < 3 )) && sleep 1
    done
    if (( up )); then row $name "inside lgtm" - - UP "datasource health OK"
    elif [[ -z $BODY ]]; then row $name "inside lgtm" - - DOWN "datasource health: no reply within 5s, 3 tries 1s apart"
    else msg=$(jq -r '.message // empty' <<< $BODY 2>/dev/null); row $name "inside lgtm" - - DOWN "datasource health: ${msg:-a reply that is not JSON}, 3 tries 1s apart"; fi
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
  print "postgres, wiremock (the exchange-rate service), redis (the gateway rate limiter), kafka (the dead-letter topic)"
  docker compose up -d --wait postgres wiremock redis kafka || return 1
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
  settle_plugins
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
  docker compose down --remove-orphans
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

purge() {
  # down keeps the data volumes; purge is the one command that deletes them, so it is never implied.
  down || return 1
  local -a vols
  vols=(${(f)"$(docker volume ls -q --filter label=com.docker.compose.project=$PROJECT)"})
  print "removing volumes: ${vols:-none} (every ledger and conversion row, Prometheus and Tempo history)"
  docker compose down --volumes --remove-orphans || return 1
  rm -r -f -- $RUN
  vols=(${(f)"$(docker volume ls -q --filter label=com.docker.compose.project=$PROJECT)"})
  (( ${#vols} )) && { print -u2 "fleet NOT purged: volumes still present: $vols"; return 1; }
  print "fleet purged: containers, network, volumes and $RUN/ released; pulled images are kept"
}

case ${1:-} in
  up) up ;;
  down) down ;;
  purge) purge ;;
  status) load_ports && fleet_status ;;
  *) print -u2 "usage: $0 up|down|purge|status"; exit 2 ;;
esac
