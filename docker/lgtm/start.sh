#!/usr/bin/env bash
# Grafana refuses to start on a Slack contact point with an empty URL, so the Slack file is
# installed only when the webhook is set. The other file removes a Slack route an earlier run
# stored in the Grafana database (file-provisioned contact points outlive their file).
target=/otel-lgtm/grafana/conf/provisioning/alerting/ezeebit-notify.yaml
if [[ -n "${SLACK_WEBHOOK_URL:-}" ]]; then
  cp /ezeebit/notify-slack.yaml "$target"
else
  cp /ezeebit/notify-none.yaml "$target"
fi
exec /otel-lgtm/run-all.sh
