#!/usr/bin/env bash
# Runs the fleet on a local kind cluster. Usage: k8s/kube.sh up [--no-build] [--only data,obs,app] | down | status
# Written for the bash 3.2 macOS ships: no ${x,,}, no mapfile, and empty arrays expanded with ${a[@]+...}.
set -uo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
cd "$ROOT" || exit 1

SERVICES=(config-svc eureka-svc ledger-svc conversion-svc gateway-svc)
STAGES=(data obs app)
NAMESPACES=(ezeebit-data ezeebit-app ezeebit-obs ezeebit-chaos)
OVERLAY=k8s/overlays/local
TIMEOUT=300s

cluster_exists() {
  local nodes rc
  nodes=$(kind get nodes --name "$CLUSTER" 2>/dev/null); rc=$?
  (( rc == 0 )) && [[ $nodes == *"$NODE_CONTAINER"* ]]
}

fleet_ports() {
  # Read from the files that own them, as the other fleet does, so a moved port cannot drift from a copy here.
  local f port spec var def n=0
  for f in services/*/src/main/resources/application.yml; do
    port=$(sed -nE 's/.*\$\{SERVER_PORT:([0-9]+)\}.*/\1/p' "$f")
    [[ $port =~ ^[0-9]+$ ]] || fail "no \${SERVER_PORT:<port>} in $f"
    printf '%s\n' "$port"; n=$((n + 1))
  done
  (( n == ${#SERVICES[@]} )) || fail "found $n service application.yml files, expected ${#SERVICES[@]}"
  port=$(sed -nE 's/.*\$\{LEDGER_GRPC_PORT:([0-9]+)\}.*/\1/p' services/ledger-svc/src/main/resources/application.yml)
  port=${LEDGER_GRPC_PORT:-$port}
  [[ $port =~ ^[0-9]+$ ]] || fail "no ledger gRPC port in services/ledger-svc/src/main/resources/application.yml"
  printf '%s\n' "$port"
  n=0
  while read -r spec; do
    if [[ $spec == \$\{*\} ]]; then
      var=${spec#\$\{}; var=${var%%:-*}; def=${spec#*:-}; def=${def%\}}
      port=${!var:-$def}
    else
      port=$spec
    fi
    [[ $port =~ ^[0-9]+$ ]] || fail "compose.yaml host port '$spec' does not resolve to a number"
    printf '%s\n' "$port"; n=$((n + 1))
  done < <(sed -nE "s/^[[:space:]]*- '127\.0\.0\.1:(.+):[0-9]+'\$/\1/p" compose.yaml)
  (( n > 0 )) || fail "no published ports read from compose.yaml"
}

refuse_if_occupied() {
  local ports port holder held=()
  if compose_running; then fail "$(refusal_hint "compose containers of $ROOT are running")"; fi
  ports=$(fleet_ports) || exit 1
  ports=$(printf '%s\n' "$ports" | sort -un)
  # shellcheck disable=SC2086 # one port per line, split on purpose
  for port in $ports; do
    holder=$(port_holder "$port")
    [[ -z $holder || $holder == "container $NODE_CONTAINER" ]] && continue
    held+=("$port is held by $holder")
  done
  (( ${#held[@]} == 0 )) || fail "$(refusal_hint "$(IFS=';'; printf '%s' "${held[*]}" | sed 's/;/; /g')")"
  log "ports free: $(printf '%s' "$ports" | tr '\n' ' ')"
}

build_and_load() {
  local version svc rc
  version=$(sed -nE "s/^[[:space:]]*version = '([^']+)'.*/\1/p" build.gradle)
  [[ -n $version ]] || fail "no version = '...' line in build.gradle"
  ./gradlew bootBuildImageAll; rc=$?
  (( rc == 0 )) || fail "bootBuildImageAll exited $rc"
  for svc in "${SERVICES[@]}"; do
    kind load docker-image --name "$CLUSTER" "ezeebit/$svc:$version"; rc=$?
    (( rc == 0 )) || fail "kind load of ezeebit/$svc:$version exited $rc"
  done
}

preload_images() {
  # A fresh node pulled otel-lgtm for over 14 minutes; the host's copy loads in seconds. A one-platform archive drops
  # the index digest a pinned ref names, so the node still pulls that index, but its layers are then already present.
  # A pinned ref is saved by its tag: saved by digest it imports nameless, and the kubelet then cannot create it.
  local platform rendered line ref src id seen=" " archive rc
  platform=$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}'); rc=$?
  (( rc == 0 )) || fail "docker version exited $rc"
  rendered=$(kctl kustomize "$OVERLAY"); rc=$?
  (( rc == 0 )) || fail "kustomize $OVERLAY exited $rc"
  mkdir -p "$RUN_DIR/images"
  while read -r line; do
    [[ $line == image:* || $line == "- image:"* ]] || continue
    ref=${line#*image:}; ref=${ref//[\'\" ]/}
    [[ $seen == *" $ref "* || $ref == ezeebit/* ]] && continue
    seen+="$ref "
    src=${ref%@*}
    id=$(docker image inspect --format '{{.Id}}' "$src" 2>/dev/null); rc=$?
    if (( rc != 0 )) || [[ $ref == *@* && $id != "${ref#*@}" ]]; then
      log "node pulls $ref (the host has no $src${ref#"$src"})"
      continue
    fi
    archive="$RUN_DIR/images/${src//[\/:]/_}.tar"
    docker save --platform "$platform" -o "$archive" "$src"; rc=$?
    (( rc == 0 )) || fail "docker save $ref exited $rc"
    kind load image-archive --name "$CLUSTER" "$archive"; rc=$?
    rm -f -- "$archive"
    (( rc == 0 )) || fail "kind load image-archive $ref exited $rc"
    log "loaded $ref ($platform) from the host"
  done <<< "$rendered"
}

apply_generated() {
  # Server-side: no last-applied annotation, which caps an object at 256 KiB and would copy a secret's data.
  # Process substitution, never <<<: bash 3.2 backs a here-string with a file in /private/var/tmp.
  local name=$1 yaml rc
  shift
  yaml=$(kctl create "$@" --dry-run=client -o yaml); rc=$?
  (( rc == 0 )) || fail "rendering $name exited $rc"
  yaml=$(kctl label --local -f <(printf '%s\n' "$yaml") -o yaml "app.kubernetes.io/name=$name" app.kubernetes.io/part-of=ezeebit); rc=$?
  (( rc == 0 )) || fail "labelling $name exited $rc"
  kctl apply --server-side --field-manager=kube.sh -f <(printf '%s\n' "$yaml") > /dev/null; rc=$?
  (( rc == 0 )) || fail "applying $name exited $rc"
}

secret() {
  local ns=$1 name=$2
  shift 2
  # An env file on a file descriptor: values reach neither argv nor the disk.
  apply_generated "$name" secret generic "$name" -n "$ns" --from-env-file=<(printf '%s\n' "$@")
}

create_secrets() {
  local had_x=0 rc slack=absent
  [[ $- == *x* ]] && had_x=1
  set +x
  secret ezeebit-data postgres-admin "POSTGRES_USER=${POSTGRES_USER:-postgres}" "POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-postgres}"
  secret ezeebit-data redis "REDIS_PASSWORD=${REDIS_PASSWORD:-redis-local}"
  secret ezeebit-app redis "REDIS_PASSWORD=${REDIS_PASSWORD:-redis-local}"
  # Fixed, not from the environment: docker/postgres/initdb creates the roles with exactly these passwords.
  secret ezeebit-app ledger-db DB_USER=ledger DB_PASSWORD=ledger
  secret ezeebit-app conversion-db DB_USER=conversion DB_PASSWORD=conversion
  secret ezeebit-app gateway-proof "GATEWAY_SHARED_SECRET=${GATEWAY_SHARED_SECRET:-local-gateway-proof}"
  if [[ -n ${SLACK_WEBHOOK_URL:-} ]]; then
    secret ezeebit-obs slack "url=$SLACK_WEBHOOK_URL"
    slack="set"
  else
    kctl delete secret slack -n ezeebit-obs --ignore-not-found > /dev/null; rc=$?
    (( rc == 0 )) || fail "deleting secret ezeebit-obs/slack exited $rc"
  fi
  (( had_x )) && set -x
  log "secrets: postgres-admin redis ledger-db conversion-db gateway-proof, slack $slack"
}

create_configmaps() {
  apply_generated postgres-initdb configmap postgres-initdb -n ezeebit-data --from-file=docker/postgres/initdb
  apply_generated wiremock-mappings configmap wiremock-mappings -n ezeebit-app --from-file=docker/wiremock/mappings
  apply_generated lgtm-root configmap lgtm-root -n ezeebit-obs --from-file=docker/lgtm
  apply_generated lgtm-dashboards configmap lgtm-dashboards -n ezeebit-obs --from-file=docker/lgtm/dashboards
  apply_generated lgtm-alerting configmap lgtm-alerting -n ezeebit-obs --from-file=docker/lgtm/alerting
  apply_generated lgtm-alerting-promtool configmap lgtm-alerting-promtool -n ezeebit-obs --from-file=docker/lgtm/alerting/promtool
  log "configmaps: postgres-initdb wiremock-mappings lgtm-root lgtm-dashboards lgtm-alerting lgtm-alerting-promtool"
}

apply_overlay() {
  local selector=$1 rc
  kctl apply -k "$OVERLAY" -l "$selector"; rc=$?
  (( rc == 0 )) || fail "apply -k $OVERLAY -l '$selector' exited $rc"
}

resource_of() {
  case $1 in
    Deployment) printf 'deployment' ;;
    StatefulSet) printf 'statefulset' ;;
    Job) printf 'job' ;;
    *) fail "no wait rule for kind $1" ;;
  esac
}

ROWS=()
FAILED=0

wait_stage() {
  local stage=$1 list rc kind res name ns ready
  list=$(kctl get deployments,statefulsets,jobs -A -l "ezeebit.io/stage=$stage" \
    -o jsonpath='{range .items[*]}{.kind} {.metadata.name} {.metadata.namespace}{"\n"}{end}'); rc=$?
  (( rc == 0 )) || fail "listing stage $stage workloads exited $rc"
  while read -r kind name ns; do
    [[ -n ${kind:-} ]] || continue
    res=$(resource_of "$kind") || exit 1
    if [[ $res == job ]]; then
      kctl wait --for=condition=complete "job/$name" -n "$ns" --timeout="$TIMEOUT" > /dev/null; rc=$?
      ready=$(kctl get job "$name" -n "$ns" -o jsonpath='{.status.succeeded}/{.spec.completions}')
    else
      kctl rollout status "$res/$name" -n "$ns" --timeout="$TIMEOUT" > /dev/null; rc=$?
      ready=$(kctl get "$res" "$name" -n "$ns" -o jsonpath='{.status.readyReplicas}/{.spec.replicas}')
    fi
    (( rc == 0 )) || FAILED=1
    ROWS+=("$(printf '%-6s %-28s %-14s %-6s exit=%s' "$stage" "$res/$name" "$ns" "${ready:-?}" "$rc")")
  done <<< "$list"
}

cmd_up() {
  local build=1 only=data,obs,app stage selected rc
  local stages=()
  while (( $# )); do
    case $1 in
      --no-build) build=0 ;;
      --only) [[ $# -ge 2 && -n $2 ]] || fail "--only needs a list, e.g. --only data,obs"; only=$2; shift ;;
      *) fail "unknown option $1" ;;
    esac
    shift
  done
  IFS=, read -r -a stages <<< "$only"
  for stage in "${stages[@]}"; do
    [[ " ${STAGES[*]} " == *" $stage "* ]] || fail "unknown stage '$stage' (stages: ${STAGES[*]})"
  done

  refuse_if_occupied
  mkdir -p "$RUN_DIR"
  if cluster_exists; then
    log "cluster $CLUSTER exists"
  else
    kind create cluster --name "$CLUSTER" --config k8s/kind-cluster.yaml --wait 120s; rc=$?
    (( rc == 0 )) || fail "kind create cluster exited $rc"
  fi
  # The images serve only the app stage, so a data or obs bring-up never pays for a build.
  if (( build )) && [[ " ${stages[*]} " == *" app "* ]]; then build_and_load; else log "image build skipped"; fi
  preload_images

  apply_overlay ezeebit.io/stage=namespaces
  create_secrets
  create_configmaps
  selected=$(IFS=,; printf '%s' "${stages[*]}")
  apply_overlay "ezeebit.io/stage in ($selected)"

  for stage in "${STAGES[@]}"; do
    [[ " ${stages[*]} " == *" $stage "* ]] && wait_stage "$stage"
  done
  {
    printf '%-6s %-28s %-14s %-6s %s\n' STAGE WORKLOAD NAMESPACE READY STATUS
    printf '%s\n' ${ROWS[@]+"${ROWS[@]}"}
  } > "$RUN_DIR/last-up.txt"
  cat "$RUN_DIR/last-up.txt"
  (( FAILED == 0 )) || fail "a workload did not become ready (table above)"
  log "up: stages $selected ready; grafana http://127.0.0.1:3000"
}

cmd_down() {
  local rc
  if cluster_exists; then
    kind delete cluster --name "$CLUSTER"; rc=$?
    (( rc == 0 )) || fail "kind delete cluster exited $rc"
  else
    log "cluster $CLUSTER does not exist"
  fi
  if [[ -d $RUN_DIR ]]; then rm -r -- "$RUN_DIR" || fail "could not remove $RUN_DIR"; fi
  log "down: cluster $CLUSTER and $RUN_DIR removed"
}

cmd_status() {
  local ns rc
  cluster_exists || fail "cluster $CLUSTER does not exist"
  for ns in "${NAMESPACES[@]}"; do
    printf '== %s\n' "$ns"
    kctl get pods -n "$ns" -o wide; rc=$?
    (( rc == 0 )) || fail "get pods -n $ns exited $rc"
  done
  printf '== hpa\n'
  kctl get hpa -A; rc=$?
  (( rc == 0 )) || fail "get hpa exited $rc"
}

case ${1:-} in
  up) shift; cmd_up "$@" ;;
  down) cmd_down ;;
  status) cmd_status ;;
  *) printf 'usage: %s up [--no-build] [--only data,obs,app] | down | status\n' "$0" >&2; exit 2 ;;
esac
