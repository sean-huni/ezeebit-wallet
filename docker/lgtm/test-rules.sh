#!/usr/bin/env bash
# Unit-tests the alert rules with the promtool shipped inside the pinned lgtm image (no other install).
# Usage: docker/lgtm/test-rules.sh [rules-file]; a rules file other than the mirror is how a defect is red-proofed.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
image="$(sed -n "s/^ *image: '\(grafana\/otel-lgtm:[^']*\)'.*/\1/p" "$here/../../compose.yaml")"
rules="${1:-$here/alerting/promtool/rules.yml}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp "$here/alerting/promtool/tests.yml" "$work/tests.yml"
cp "$rules" "$work/rules.yml"
docker run --rm --entrypoint /otel-lgtm/prometheus/promtool -v "$work:/w:ro" -w /w "$image" test rules tests.yml
