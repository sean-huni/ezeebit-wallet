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
