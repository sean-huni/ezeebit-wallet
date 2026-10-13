#!/usr/bin/env bash
# Grafana refuses to start on a Slack contact point with an empty URL, so the Slack file is
# installed only when the webhook is set. Grafana's database is tmpfs, so nothing older survives.
target=/otel-lgtm/grafana/conf/provisioning/alerting/ezeebit-notify.yaml
if [[ -n "${SLACK_WEBHOOK_URL:-}" ]]; then
  cp /ezeebit/notify-slack.yaml "$target"
else
  rm -f "$target"
fi
exec /otel-lgtm/run-all.sh
