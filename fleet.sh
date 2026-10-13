#!/usr/bin/env zsh
# Runs the whole fleet on this host: Postgres, Redis and Grafana LGTM in Docker, every service as a JVM.
# Usage: ./fleet.sh up | down | status
set -u
cd "${0:A:h}"

SERVICES=(config-svc eureka-svc ledger-svc conversion-svc gateway-svc)
RUN=.fleet

port_of() {
  # Each service's own application.yml is the only home of its port; fleet.sh ignores SERVER_PORT.
  grep -oE 'SERVER_PORT:[0-9]+' "services/$1/src/main/resources/application.yml" | cut -d: -f2
}

health_of() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:$(port_of $1)/actuator/health"
}

await_healthy() {
  local svc=$1 code deadline=$(( SECONDS + 90 ))
  while (( SECONDS < deadline )); do
    code=$(health_of $svc)
    [[ $code == 200 ]] && { print "  $svc UP on :$(port_of $svc)"; return 0; }
    sleep 1
  done
  print -u2 "  $svc NOT healthy after 90s (last status '$code'); see $RUN/$svc.log"
  return 1
}

await_routed() {
  # Healthy is not routable: the gateway learns instances from the registry a few seconds later.
  local m=00000000-0000-0000-0000-000000000000 deadline=$(( SECONDS + 60 )) b q
  while (( SECONDS < deadline )); do
    b=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$(port_of gateway-svc)/api/v1/merchants/$m/balances")
    q=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$(port_of gateway-svc)/api/v1/merchants/$m/conversions/$m")
    [[ $b == 200 && $q == 404 ]] && { print "  gateway routes to ledger-svc and conversion-svc"; return 0; }
    sleep 1
  done
  print -u2 "  gateway not routing after 60s (balances '$b', conversions '$q')"
  return 1
}

start() {
  local svc=$1
  java -jar services/$svc/build/libs/$svc-*.jar > "$RUN/$svc.log" 2>&1 &
  print $! > "$RUN/$svc.pid"
}

up() {
  mkdir -p $RUN
  local f
  for f in $RUN/*.pid(N); do
    # A second up would fail to bind, health-check the OLD process, and orphan it.
    kill -0 $(<$f) 2>/dev/null && { print -u2 "fleet already running ($f); ./fleet.sh down first"; return 1; }
  done
  [[ -f .env ]] && { set -a; source ./.env; set +a; }
  print "postgres, wiremock (the exchange-rate service), redis (the gateway rate limiter)"
  docker compose up -d --wait postgres wiremock redis || return 1
  print "build"
  ./gradlew -q bootJar || return 1
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
  print "fleet up: gateway http://localhost:$(port_of gateway-svc), registry http://localhost:$(port_of eureka-svc), grafana http://localhost:3000"
}

down() {
  local svc pid
  # Monitoring first, so a deliberate stop never pages.
  docker compose stop lgtm
  for svc in ${(Oa)SERVICES}; do
    [[ -f $RUN/$svc.pid ]] || continue
    pid=$(<$RUN/$svc.pid)
    if kill $pid 2>/dev/null; then
      # Graceful shutdown takes a few seconds; returning early leaves the port bound for the next up.
      local deadline=$(( SECONDS + 30 ))
      while kill -0 $pid 2>/dev/null && (( SECONDS < deadline )); do sleep 0.5; done
      print "stopped $svc ($pid)"
    fi
    rm -f $RUN/$svc.pid
  done
  docker compose down
}

fleet_status() {
  local svc
  for svc in $SERVICES; do printf '%-15s :%-5s %s\n' $svc $(port_of $svc) $(health_of $svc); done
}

case ${1:-} in
  up) up ;;
  down) down ;;
  status) fleet_status ;;
  *) print -u2 "usage: $0 up|down|status"; exit 2 ;;
esac
