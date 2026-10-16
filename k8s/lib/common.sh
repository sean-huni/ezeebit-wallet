# Sourced by k8s/kube.sh. Every cluster call goes through kctl or names the cluster, so no other cluster is touched.
# shellcheck shell=bash disable=SC2034
CLUSTER=ezeebit
CONTEXT=kind-ezeebit
NODE_CONTAINER=ezeebit-control-plane
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
RUN_DIR=$ROOT/.kube-fleet

kctl() { kubectl --context "$CONTEXT" "$@"; }

log() { printf '[kube] %s\n' "$*"; }

fail() { printf '[kube] FAIL: %s\n' "$*" >&2; exit 1; }

port_holder() {
  local port=$1 names pid comm rc
  # A published container port shows in lsof as the Docker backend, so containers are asked first, by name.
  names=$(docker ps --filter "publish=$port" --format '{{.Names}}' 2>/dev/null); rc=$?
  if (( rc == 0 )) && [[ -n $names ]]; then
    printf 'container %s\n' "${names%%$'\n'*}"
    return 0
  fi
  pid=$(lsof -nP -t -iTCP:"$port" -sTCP:LISTEN 2>/dev/null); rc=$?
  (( rc == 0 )) && [[ -n $pid ]] || return 0
  pid=${pid%%$'\n'*}
  comm=$(ps -o comm= -p "$pid" 2>/dev/null)
  printf 'process %s (%s)\n' "$pid" "$comm"
}

compose_running() {
  local ids rc
  # The working-dir label, not the project name: the name moves with COMPOSE_PROJECT_NAME, the directory does not.
  ids=$(docker ps -q --filter "label=com.docker.compose.project.working_dir=$ROOT" 2>/dev/null); rc=$?
  (( rc == 0 )) || fail "docker ps failed (exit $rc): is Docker running?"
  [[ -n $ids ]]
}

refusal_hint() {
  printf '%s; stop it first (./fleet.sh down if it is the compose fleet)\n' "$1" # gate:allow-fleet-mention
}
