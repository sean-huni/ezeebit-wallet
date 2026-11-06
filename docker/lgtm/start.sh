#!/usr/bin/env bash
# Grafana refuses to start on a Slack contact point with an empty URL, so the Slack file is
# installed only when the webhook is set. Grafana's database is tmpfs, so nothing older survives.
target=/otel-lgtm/grafana/conf/provisioning/alerting/ezeebit-notify.yaml
if [[ -n "${SLACK_WEBHOOK_URL:-}" ]]; then
  cp /ezeebit/notify-slack.yaml "$target"
else
  rm -f "$target"
fi
# Names peers that send no spans (the rate feed) on Tempo's service map: otelcol-peer.yml.
export OTELCOL_EXTRA_ARGS="${OTELCOL_EXTRA_ARGS:+$OTELCOL_EXTRA_ARGS }--config=file:/ezeebit/otelcol-peer.yml"
exec /otel-lgtm/run-all.sh
