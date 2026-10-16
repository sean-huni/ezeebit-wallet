#!/usr/bin/env bash
# Runs the fleet on a local kind cluster.
# Usage: k8s/kube.sh up [--no-build] [--only data,obs,app,chaos] | down | status | chaos on|off|run|status | sweep [--no-build]
# Written for the bash 3.2 macOS ships: no ${x,,}, no mapfile, and empty arrays expanded with ${a[@]+...}.
set -uo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
cd "$ROOT" || exit 1
# k8s/chaos/monkey.sh run from here names the cluster through this.
export KCTL_CONTEXT=$CONTEXT

SERVICES=(config-svc eureka-svc ledger-svc conversion-svc gateway-svc)
STAGES=(data obs app chaos)
NAMESPACES=(ezeebit-data ezeebit-app ezeebit-obs ezeebit-chaos)
OVERLAY=k8s/overlays/local
TIMEOUT=300s

cluster_exists() {
  local nodes rc
  nodes=$(kind get nodes --name "$CLUSTER" 2>/dev/null); rc=$?
  (( rc == 0 )) && [[ $nodes == *"$NODE_CONTAINER"* ]]
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

# containerd's name for a Docker-style ref: docker.io/library/redis:8, registry.k8s.io/x:v1 unchanged.
cri_name() {
  local ref=$1 first=${1%%/*}
  if [[ $ref != */* ]]; then printf 'docker.io/library/%s' "$ref"
  elif [[ $first == *.* || $first == *:* || $first == localhost ]]; then printf '%s' "$ref"
  else printf 'docker.io/%s' "$ref"; fi
}

preload_images() {
  # The node never pulls: every third-party ref is loaded from the host, pulled to the host first when absent.
  # A one-platform archive drops the index digest a pinned ref names, so the node's copy is also tagged repo@digest:
  # the CRI resolves a tag+digest ref by repo@digest, and finding it skips the registry. A pinned ref is saved by its
  # tag: saved by digest it imports nameless, and the kubelet then cannot create it.
  local platform rendered line ref src id seen=" " archive rc refs=()
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
    if (( rc != 0 )); then
      log "the host has no $src: pulling it once, later runs load the host copy"
      docker pull --platform "$platform" "$src" > /dev/null; rc=$?
      (( rc == 0 )) || fail "docker pull $src exited $rc"
      id=$(docker image inspect --format '{{.Id}}' "$src"); rc=$?
      (( rc == 0 )) || fail "docker image inspect $src exited $rc after the pull"
    fi
    [[ $ref != *@* || $id == "${ref#*@}" ]] || fail "host $src is $id, the manifests pin ${ref#*@}: re-pull or re-pin"
    archive="$RUN_DIR/images/${src//[\/:]/_}.tar"
    docker save --platform "$platform" -o "$archive" "$src"; rc=$?
    (( rc == 0 )) || fail "docker save $ref exited $rc"
    kind load image-archive --name "$CLUSTER" "$archive"; rc=$?
    rm -f -- "$archive"
    (( rc == 0 )) || fail "kind load image-archive $ref exited $rc"
    if [[ $ref == *@* ]]; then
      docker exec "$NODE_CONTAINER" ctr -n k8s.io images tag --force "$(cri_name "$src")" "$(cri_name "${src%:*}")@${ref#*@}" > /dev/null; rc=$?
      (( rc == 0 )) || fail "tagging $ref on the node exited $rc"
    fi
    refs+=("$ref")
    log "loaded $ref ($platform) from the host"
  done <<< "$rendered"
  # The same lookup the kubelet makes before deciding to pull.
  for ref in ${refs[@]+"${refs[@]}"}; do
    docker exec "$NODE_CONTAINER" crictl inspecti "$ref" > /dev/null 2>&1; rc=$?
    (( rc == 0 )) || fail "the node cannot resolve $ref locally (crictl inspecti exited $rc): it would pull"
    log "node resolves $ref without a registry"
  done
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
  apply_generated chaos-monkey configmap chaos-monkey -n ezeebit-chaos --from-file=monkey.sh=k8s/chaos/monkey.sh
  log "configmaps: postgres-initdb wiremock-mappings lgtm-root lgtm-dashboards lgtm-alerting lgtm-alerting-promtool chaos-monkey"
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
  local build=1 only=data,obs,app,chaos stage selected rc
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

  "$ROOT/scripts/env-guard.sh" acquire k8s; rc=$?
  (( rc == 0 )) || exit "$rc"
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
  "$ROOT/scripts/env-guard.sh" release k8s; rc=$?
  (( rc == 0 )) || fail "env-guard release k8s exited $rc"
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

CHAOS_NS=ezeebit-chaos
CHAOS_JOB_TIMEOUT=180

chaos_suspend() {
  local want=$1 got rc
  kctl patch cronjob chaos-monkey -n "$CHAOS_NS" --type merge -p "{\"spec\":{\"suspend\":$want}}" > /dev/null; rc=$?
  (( rc == 0 )) || fail "patching cronjob chaos-monkey exited $rc"
  got=$(kctl get cronjob chaos-monkey -n "$CHAOS_NS" -o jsonpath='{.spec.suspend}'); rc=$?
  (( rc == 0 )) || fail "reading cronjob chaos-monkey back exited $rc"
  [[ $got == "$want" ]] || fail "suspend reads back '$got', wanted $want"
  if [[ $want == false ]]; then
    log "chaos on: suspend=false, one kill at every :x0 minute"
    log "chaos on: a :x0 slot passed under 30s ago fires now; the next 'kube.sh up' disarms it again"
  else
    log "chaos off: suspend=true"
  fi
}

# Prints Complete or Failed once the Job has either condition; a pod that never starts ends at activeDeadlineSeconds.
chaos_wait() {
  local job=$1 waited=0 state rc
  while (( waited < CHAOS_JOB_TIMEOUT )); do
    state=$(kctl get job "$job" -n "$CHAOS_NS" \
      -o jsonpath='{range .status.conditions[?(@.status=="True")]}{.type}{" "}{end}'); rc=$?
    (( rc == 0 )) || fail "reading job $job exited $rc"
    case " $state " in
      *" Complete "*) printf 'Complete'; return 0 ;;
      *" Failed "*) printf 'Failed'; return 0 ;;
    esac
    sleep 2; waited=$((waited + 2))
  done
  printf 'Timeout'
}

chaos_run() {
  local job state rc
  job="chaos-manual-$(date +%s)"
  kctl create job "$job" --from=cronjob/chaos-monkey -n "$CHAOS_NS" > /dev/null; rc=$?
  (( rc == 0 )) || fail "creating job $job exited $rc"
  log "chaos run: job $job"
  state=$(chaos_wait "$job") || exit 1
  # A pod that never started has no log; the Job's end state is still the verdict.
  kctl logs "job/$job" -n "$CHAOS_NS"; rc=$?
  [[ $state == Complete ]] || fail "job $job ended $state (logs exit $rc)"
  (( rc == 0 )) || fail "job $job Complete but its logs exited $rc"
  log "chaos run: job $job Complete"
}

chaos_status() {
  local line suspend last job state logs rc
  line=$(kctl get cronjob chaos-monkey -n "$CHAOS_NS" -o jsonpath='{.spec.suspend} {.status.lastScheduleTime}'); rc=$?
  (( rc == 0 )) || fail "reading cronjob chaos-monkey exited $rc"
  read -r suspend last <<< "$line"
  printf 'suspend=%s lastScheduleTime=%s\n' "$suspend" "${last:-never}"
  job=$(kctl get jobs -n "$CHAOS_NS" --sort-by=.metadata.creationTimestamp \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'); rc=$?
  (( rc == 0 )) || fail "listing jobs in $CHAOS_NS exited $rc"
  job=${job##*$'\n'}
  if [[ -z $job ]]; then printf 'last job: none (finished jobs are deleted after an hour)\n'; return 0; fi
  state=$(kctl get job "$job" -n "$CHAOS_NS" \
    -o jsonpath='{range .status.conditions[?(@.status=="True")]}{.type}{" "}{end}'); rc=$?
  (( rc == 0 )) || fail "reading job $job exited $rc"
  printf 'last job: %s %s\n' "$job" "${state:-Running}"
  logs=$(kctl logs "job/$job" -n "$CHAOS_NS" 2>&1); rc=$?
  (( rc == 0 )) || { printf 'logs unavailable (exit %s): %s\n' "$rc" "$logs"; return 0; }
  printf 'kills:\n'
  while read -r line; do [[ $line == "KILL "* ]] && printf '  %s\n' "$line"; done <<< "$logs"
  while read -r line; do [[ $line == "KILLED "* ]] && printf 'total: %s\n' "${line#KILLED }"; done <<< "$logs"
}

cmd_chaos() {
  cluster_exists || fail "cluster $CLUSTER does not exist"
  case ${1:-} in
    on) chaos_suspend false ;;
    off) chaos_suspend true ;;
    run) chaos_run ;;
    status) chaos_status ;;
    *) printf 'usage: %s chaos on|off|run|status\n' "$0" >&2; exit 2 ;;
  esac
}

# Spec 6 order. Monitoring wraps the others: it records the lgtm pod at the start and compares at the end.
SWEEP_CHECKS=(parity money replicas policy chaos outage)

cmd_sweep() {
  local log failed=0 check rc out
  log="$ROOT/docs/k8s/verification/$(date +%F)-sweep.log"
  mkdir -p "$(dirname "$log")"
  : > "$log"
  sweep_log() { printf '%s\n' "$*" | tee -a "$log"; }
  sweep_log "sweep started $(date -u +%FT%TZ) at $(git rev-parse --short HEAD 2>/dev/null || printf unknown)"
  if cluster_exists; then
    ( cmd_down ) >> "$log" 2>&1; rc=$?
    sweep_log "pre-down exit=$rc"
    (( rc == 0 )) || exit 1
  fi
  ( cmd_up "$@" ) >> "$log" 2>&1; rc=$?
  sweep_log "up exit=$rc"
  if (( rc == 0 )); then
    out=$(bash k8s/sweep/monitoring.sh begin 2>&1); rc=$?
    printf '%s\n' "$out" >> "$log"
    sweep_log "monitoring-begin exit=$rc"
    (( rc == 0 )) || failed=1
    for check in "${SWEEP_CHECKS[@]}"; do
      out=$(bash "k8s/sweep/$check.sh" 2>&1); rc=$?
      printf '== %s\n%s\n' "$check" "$out" >> "$log"
      sweep_log "$check exit=$rc"
      (( rc == 0 )) || failed=1
      out=$(bash k8s/sweep/monitoring.sh mark "after-$check" 2>&1); rc=$?
      printf '%s\n' "$out" >> "$log"
    done
    out=$(bash k8s/sweep/monitoring.sh end 2>&1); rc=$?
    printf '== monitoring\n%s\n' "$out" >> "$log"
    sweep_log "monitoring exit=$rc"
    (( rc == 0 )) || failed=1
  else
    failed=1
  fi
  ( cmd_down ) >> "$log" 2>&1; rc=$?
  sweep_log "down exit=$rc"
  (( rc == 0 )) || failed=1
  sweep_log "sweep exit=$failed"
  exit "$failed"
}

case ${1:-} in
  up) shift; cmd_up "$@" ;;
  sweep) shift; cmd_sweep "$@" ;;
  down) cmd_down ;;
  status) cmd_status ;;
  chaos) shift; cmd_chaos "$@" ;;
  *) printf 'usage: %s up [--no-build] [--only data,obs,app,chaos] | down | status | chaos on|off|run|status | sweep [--no-build]\n' "$0" >&2; exit 2 ;;
esac
