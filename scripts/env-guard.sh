#!/usr/bin/env bash
# Keeps the host fleet (compose) and the kind fleet (k8s) from running at the same time. The one file that knows both.
# Usage: scripts/env-guard.sh acquire <compose|k8s> | release <compose|k8s> | state
# Exit: 0 lease held (or released), 1 refused with the blocker named on stderr, 2 could not look (fails closed).
set -uo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P) || exit 2
LEASE=$ROOT/.env-lease
RECLAIM_LOCK=$ROOT/.env-lease.reclaim
FLEET_RUN=$ROOT/.fleet
KIND_CLUSTER=ezeebit
KIND_NODE=ezeebit-control-plane
TOOK=0 LOCKED=0
ERRF=''
DOCKER='' KIND=absent OWNER='' HOLDER=''
COMPOSE=() JVMS=() HELD=() PORTS=() BLOCKERS=()

say() { printf '[env-guard] %s\n' "$*" >&2; }

blind() {
  say "cannot tell what runs ($*): refusing"
  exit 2
}

on_exit() {
  local rc=$?
  # A lease this run took is dropped on any refusal, so a refused up never blocks the other fleet.
  (( rc != 0 && TOOK )) && rm -rf -- "$LEASE"
  (( LOCKED )) && rmdir "$RECLAIM_LOCK" 2> /dev/null
  [[ -n $ERRF ]] && rm -f -- "$ERRF"
  exit "$rc"
}
trap on_exit EXIT

other_of() { if [[ $1 == compose ]]; then printf 'k8s'; else printf 'compose'; fi; }

stop_hint() { if [[ $1 == compose ]]; then printf './fleet.sh down'; else printf './k8s/kube.sh down'; fi; }

scan_docker() {
  local out rc name state project dir kind ports project_name
  out=$(docker ps -a --no-trunc --format '{{.Names}}|{{.State}}|{{.Label "com.docker.compose.project"}}|{{.Label "com.docker.compose.project.working_dir"}}|{{.Label "io.x-k8s.kind.cluster"}}|{{.Ports}}' 2>&1); rc=$?
  (( rc == 0 )) || blind "docker ps exited $rc: ${out%%$'\n'*}"
  DOCKER=$out KIND=absent COMPOSE=()
  # As docker compose names the project: COMPOSE_PROJECT_NAME, else the directory. The working_dir label catches a .env override.
  project_name=${COMPOSE_PROJECT_NAME:-$(basename "$ROOT" | tr '[:upper:]' '[:lower:]')}
  while IFS='|' read -r name state project dir kind ports; do
    [[ -n $name ]] || continue
    if [[ $kind == "$KIND_CLUSTER" ]]; then
      if [[ $state == running ]]; then KIND=running; elif [[ $KIND == absent ]]; then KIND=stopped; fi
    elif [[ $state == running && ( $project == "$project_name" || $dir == "$ROOT" ) ]]; then
      COMPOSE+=("$name")
    fi
  done <<< "$DOCKER"
}

scan_jvms() {
  local f svc pid cmd rc
  JVMS=()
  for f in "$FLEET_RUN"/*.pid; do
    [[ -e $f ]] || continue
    [[ -r $f ]] || blind "cannot read $f"
    svc=$(basename "$f" .pid)
    pid=$(cat "$f")
    [[ $pid =~ ^[0-9]+$ ]] || continue
    # A pid file outlives its process and the pid can be reused: only a JVM of this checkout's jars counts.
    cmd=$(ps -o command= -p "$pid" 2> /dev/null); rc=$?
    if (( rc != 0 )) || [[ -z $cmd ]]; then
      kill -0 "$pid" 2> /dev/null && blind "ps could not read live pid $pid of $f (exit $rc)"
      continue
    fi
    [[ $cmd == *"$ROOT/services/$svc/build/libs/"* ]] && JVMS+=("fleet JVM $svc (pid $pid)")
  done
}

read_ports() {
  local f port spec var def n=0 yml=$ROOT/services/ledger-svc/src/main/resources/application.yml
  PORTS=()
  for f in "$ROOT"/services/*/src/main/resources/application.yml; do
    [[ -f $f ]] || blind "no services/*/src/main/resources/application.yml under $ROOT"
    port=$(sed -nE 's/.*\$\{SERVER_PORT:([0-9]+)\}.*/\1/p' "$f")
    [[ $port =~ ^[0-9]+$ ]] || blind "no \${SERVER_PORT:<port>} in $f"
    PORTS+=("$port")
  done
  port=$(sed -nE 's/.*\$\{LEDGER_GRPC_PORT:([0-9]+)\}.*/\1/p' "$yml")
  port=${LEDGER_GRPC_PORT:-$port}
  [[ $port =~ ^[0-9]+$ ]] || blind "no ledger gRPC port in $yml"
  PORTS+=("$port")
  [[ -r $ROOT/compose.yaml ]] || blind "cannot read $ROOT/compose.yaml"
  while read -r spec; do
    if [[ $spec == \$\{*\} ]]; then
      var=${spec#\$\{}; var=${var%%:-*}; def=${spec#*:-}; def=${def%\}}
      port=${!var:-$def}
    else
      port=$spec
    fi
    [[ $port =~ ^[0-9]+$ ]] || blind "compose.yaml host port '$spec' does not resolve to a number"
    PORTS+=("$port"); n=$((n + 1))
  done < <(sed -nE "s/^[[:space:]]*- '127\.0\.0\.1:(.+):[0-9]+'\$/\1/p" "$ROOT/compose.yaml")
  (( n > 0 )) || blind "no published ports read from compose.yaml"
}

publishes() {
  local port=$1 entry host lo hi
  # docker prints adjacent ports as one range, e.g. 127.0.0.1:4317-4318->4317-4318/tcp.
  while read -r entry; do
    [[ $entry == *"->"* ]] || continue
    host=${entry%%->*}; host=${host##*:}
    lo=${host%-*}; hi=${host#*-}
    [[ $lo =~ ^[0-9]+$ && $hi =~ ^[0-9]+$ ]] || continue
    (( port >= lo && port <= hi )) && return 0
  done < <(printf '%s\n' "${2//, /$'\n'}")
  return 1
}

holder_of() {
  local port=$1 name state project dir kind ports pid rc comm err
  HOLDER=
  # A published container port shows in lsof as the Docker backend, so containers are asked first.
  while IFS='|' read -r name state project dir kind ports; do
    [[ $state == running ]] || continue
    publishes "$port" "$ports" || continue
    [[ $kind == "$KIND_CLUSTER" ]] || HOLDER="container $name"
    return 0
  done <<< "$DOCKER"
  [[ -n $ERRF ]] || ERRF=$(mktemp "${TMPDIR:-/tmp}/env-guard.XXXXXX") || blind "mktemp failed"
  # lsof exits 1 both for no listener and for an error; only stderr tells them apart (-t implies -w, no warnings).
  pid=$(lsof -nP -t -iTCP:"$port" -sTCP:LISTEN 2> "$ERRF"); rc=$?
  err=$(cat "$ERRF")
  if (( rc == 0 )) && [[ -n $pid ]]; then
    pid=${pid%%$'\n'*}
    comm=$(ps -o comm= -p "$pid" 2> /dev/null)
    HOLDER="process $pid (${comm:-exited})"
  elif (( rc != 1 )) || [[ -n $pid || -n $err ]]; then
    blind "lsof on port $port exited $rc: ${err%%$'\n'*}"
  fi
}

scan_ports() {
  local port seen=" "
  HELD=()
  read_ports
  for port in "${PORTS[@]}"; do
    [[ $seen == *" $port "* ]] && continue
    seen+="$port "
    holder_of "$port"
    [[ -n $HOLDER ]] && HELD+=("port $port is held by $HOLDER")
  done
}

scan_fleet() {
  BLOCKERS=()
  scan_docker
  if [[ $1 == compose ]]; then
    (( ${#COMPOSE[@]} )) && BLOCKERS+=("compose containers running: ${COMPOSE[*]}")
    scan_jvms
    BLOCKERS+=(${JVMS[@]+"${JVMS[@]}"})
    scan_ports
    BLOCKERS+=(${HELD[@]+"${HELD[@]}"})
  else
    case $KIND in
      running) BLOCKERS+=("kind node $KIND_NODE is running") ;;
      # A stopped node rebinds its ports the next time Docker starts.
      stopped) BLOCKERS+=("kind node $KIND_NODE is stopped (it restarts with Docker)") ;;
    esac
  fi
}

joined() { local IFS=';'; printf '%s' "$*" | sed 's/;/; /g'; }

take_lease() {
  mkdir "$LEASE" 2> /dev/null || return 1
  TOOK=1
  write_lease "$1"
}

write_lease() {
  # owner is written last, by rename, so a reader never sees a lease without its holder.
  if ! { printf '%s\n' "$PPID" > "$LEASE/pid" &&
    ps -o lstart= -p "$PPID" > "$LEASE/started" 2> /dev/null &&
    date -u +%Y-%m-%dT%H:%M:%SZ > "$LEASE/since" &&
    printf '%s\n' "$1" > "$LEASE/owner.tmp" &&
    mv -f "$LEASE/owner.tmp" "$LEASE/owner"; }; then
    blind "could not write $LEASE"
  fi
}

read_owner() {
  local tries=0
  OWNER=
  while [[ ! -f $LEASE/owner ]] && (( tries < 20 )); do
    [[ -d $LEASE ]] || return 1
    sleep 0.1; tries=$((tries + 1))
  done
  [[ -r $LEASE/owner ]] || blind "$LEASE has no readable owner; if scripts/env-guard.sh state shows neither fleet running, rm -r $LEASE"
  OWNER=$(cat "$LEASE/owner")
  [[ $OWNER == compose || $OWNER == k8s ]] ||
    blind "$LEASE is held by unknown owner '$OWNER'; if scripts/env-guard.sh state shows neither fleet running, rm -r $LEASE"
}

# The process that took the lease, while it lives with the same start time, is an up still in progress.
holder_alive() {
  local pid started now
  pid=$(cat "$LEASE/pid" 2> /dev/null)
  started=$(cat "$LEASE/started" 2> /dev/null)
  [[ $pid =~ ^[0-9]+$ && -n $started ]] || return 1
  kill -0 "$pid" 2> /dev/null || return 1
  now=$(ps -o lstart= -p "$pid" 2> /dev/null)
  [[ $now == "$started" ]]
}

reclaim() {
  local me=$1 judged=$2
  # One reclaimer at a time, and the lease is re-judged under the lock, so a lease taken meanwhile is never deleted.
  if ! mkdir "$RECLAIM_LOCK" 2> /dev/null; then
    say "refused $me: another acquire is reclaiming the lease; if none is running, rmdir $RECLAIM_LOCK"
    exit 1
  fi
  LOCKED=1
  if [[ $(cat "$LEASE/owner" "$LEASE/pid" "$LEASE/since" 2> /dev/null) != "$judged" ]]; then
    say "refused $me: the lease changed hands while it was judged stale"
    exit 1
  fi
  rm -rf -- "$LEASE" || blind "could not remove the stale $LEASE"
  take_lease "$me" || { say "refused $me: a concurrent acquire took the lease"; exit 1; }
  rmdir "$RECLAIM_LOCK" 2> /dev/null; LOCKED=0
}

acquire() {
  local me=$1 other judged
  other=$(other_of "$me")
  if ! take_lease "$me"; then
    read_owner || take_lease "$me" || { say "refused $me: a concurrent acquire took the lease"; exit 1; }
    if (( ! TOOK )) && [[ $OWNER == "$me" ]]; then
      write_lease "$me"
    elif (( ! TOOK )); then
      judged=$(cat "$LEASE/owner" "$LEASE/pid" "$LEASE/since" 2> /dev/null)
      if holder_alive; then
        say "refused $me: the $OWNER fleet holds the lease and its up (pid $(cat "$LEASE/pid")) is still running"
        exit 1
      fi
      scan_fleet "$OWNER"
      if (( ${#BLOCKERS[@]} )); then
        say "refused $me: the $OWNER fleet holds the lease and still runs: $(joined "${BLOCKERS[@]}"). Stop it first: $(stop_hint "$OWNER")"
        exit 1
      fi
      say "stale lease of $OWNER (since $(cat "$LEASE/since" 2> /dev/null), nothing of it runs): reclaimed"
      reclaim "$me" "$judged"
    fi
  fi
  scan_fleet "$other"
  if (( ${#BLOCKERS[@]} )); then
    say "refused $me: the $other fleet still runs: $(joined "${BLOCKERS[@]}"). Stop it first: $(stop_hint "$other")"
    exit 1
  fi
  say "lease held by $me"
}

release() {
  local me=$1
  [[ -d $LEASE ]] || { say "no lease to release"; return 0; }
  read_owner || { say "no lease to release"; return 0; }
  if [[ $OWNER != "$me" ]]; then
    say "lease held by $OWNER, not $me: left alone"
    return 0
  fi
  rm -rf -- "$LEASE" || blind "could not remove $LEASE"
  say "released by $me"
}

state() {
  local lease=none
  if [[ -d $LEASE ]]; then read_owner && lease=$OWNER; fi
  scan_docker
  scan_jvms
  scan_ports
  printf 'lease %s\n' "$lease"
  if (( ${#COMPOSE[@]} )); then printf 'compose running\n'; else printf 'compose absent\n'; fi
  printf 'jvms %s\n' "${#JVMS[@]}"
  if (( ${#HELD[@]} )); then printf 'ports %s\n' "$(joined "${HELD[@]}")"; else printf 'ports none\n'; fi
  printf 'kind %s\n' "$KIND"
}

usage() {
  say "usage: $0 acquire <compose|k8s> | release <compose|k8s> | state"
  exit 2
}

case ${1:-} in
  acquire | release)
    [[ ${2:-} == compose || ${2:-} == k8s ]] || usage
    "$1" "$2" ;;
  state) state ;;
  *) usage ;;
esac
