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

# sweep_log_path <date> <version> [label]: the sweep's log, named by the label when one is given, else by the version.
# Refuses a committed path, since the sweep truncates its log; git failing to answer is a refusal too.
sweep_log_path() {
  local day=$1 ver=$2 label=${3:-} name=$2 rel out rc
  if [[ -n $label ]]; then
    [[ $label =~ ^[a-z0-9][a-z0-9.-]*$ ]] || { printf '[kube] FAIL: SWEEP_LABEL "%s": want lower-case letters, digits, dots and hyphens\n' "$label" >&2; return 1; }
    name=$label
  fi
  rel="docs/k8s/verification/$day-$name-sweep.log"
  out=$(git -C "$ROOT" ls-files -- "$rel" 2>&1); rc=$?
  (( rc == 0 )) || { printf '[kube] FAIL: git ls-files exited %s: cannot tell whether %s is committed\n' "$rc" "$rel" >&2; return 1; }
  [[ -z $out ]] || { printf '[kube] FAIL: %s is committed: a new run would overwrite it; pick another SWEEP_LABEL or remove it in a commit first\n' "$rel" >&2; return 1; }
  printf '%s\n' "$rel"
}
