#!/usr/bin/env bash
# Spec 6.7: ledger-svc scaled to zero from the host's own context (the chaos account cannot scale), conversions
# dead-letter, ConversionLedgerDeadLettered reaches Alerting, Slack delivery counted when a webhook is set, and the
# declared replica count is put back and read back whatever happened in between.
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
CHECK=outage
RULE=ConversionLedgerDeadLettered
DEAD_LETTERS='sum(ezeebit_conversion_ledger_deadletter_total{job="conversion-svc"})'
DRIVE_WAIT=180
ALERT_GRACE=120
findings=()

# "<state> <for seconds> <instance states>", "missing", or empty when Grafana did not answer.
rule_state() {
  local out rc
  out=$(curl -s --max-time 10 "$GRAFANA/api/prometheus/grafana/api/v1/rules"); rc=$?
  (( rc == 0 )) || return 1
  jq -r --arg r "$RULE" '[.data.groups[].rules[] | select(.name == $r)] | if length == 1 then .[0] | "\(.state) \(.duration // 0) \([.alerts[]?.state] | join(","))" else "missing" end' <<< "$out" 2>/dev/null
}

# Grafana's own notifier counter, summed over its slack series; empty when Grafana did not answer.
slack_sent() {
  local out rc
  out=$(curl -s --max-time 10 "$GRAFANA/metrics"); rc=$?
  (( rc == 0 )) || return 1
  awk '/^grafana_alerting_notifications_total\{/ && /integration="slack"/ {s += $NF} END {print s + 0}' <<< "$out"
}

declared=$(kctl get deployment ledger-svc -n "$APP_NS" -o jsonpath='{.spec.replicas}'); rc=$?
say "ledger-svc declared replicas: ${declared:-none} (exit $rc)"
[[ $rc == 0 && $declared =~ ^[1-9][0-9]*$ ]] || { verdict "$CHECK" "cannot read ledger-svc .spec.replicas ('$declared')"; exit 1; }

restore() {
  local got rc
  kctl scale deployment ledger-svc -n "$APP_NS" --replicas="$declared" > /dev/null; rc=$?
  (( rc == 0 )) || findings+=("restoring ledger-svc to $declared exited $rc")
  kctl rollout status deployment/ledger-svc -n "$APP_NS" --timeout=240s > /dev/null; rc=$?
  got=$(kctl get deployment ledger-svc -n "$APP_NS" -o jsonpath='{.spec.replicas} {.status.readyReplicas}')
  say "ledger-svc restored: spec ready = $got (want $declared $declared, rollout exit $rc)"
  [[ $got == "$declared $declared" ]] || findings+=("ledger-svc reads back '$got' after restore, want '$declared $declared'")
}

m=$(uuid)
deposit "$m" 1000.00 || { verdict "$CHECK" "deposit: $DEPOSIT_OUT"; exit 1; }
dl_before=$(prom_scalar "$DEAD_LETTERS") || { verdict "$CHECK" "Prometheus did not answer"; exit 1; }
state0=$(rule_state)
say "dead letters before: $dl_before; $RULE before: ${state0:-no answer}"
slack=absent
kctl get secret slack -n ezeebit-obs > /dev/null 2>&1 && slack=present
sent_before=''
if [[ $slack == present ]]; then sent_before=$(slack_sent) || findings+=("Grafana /metrics did not answer before the outage"); fi
say "slack secret: $slack; notifications to slack before: ${sent_before:-n/a}"

t0=$(now_ms)
kctl scale deployment ledger-svc -n "$APP_NS" --replicas=0 > /dev/null; rc=$?
say "t=$t0 scaled ledger-svc to 0 (exit $rc)"
if (( rc != 0 )); then findings+=("scale to 0 exited $rc"); restore; verdict "$CHECK" "${findings[@]}"; exit 1; fi

# Conversions until the counter moves: each one gives up on the ledger after its retries or an open breaker.
waited=0 n=0 dl=$dl_before codes=""
while (( waited < DRIVE_WAIT )); do
  n=$((n + 1))
  convert "$m" "outage-$t0-$n" 1.00
  codes+="${CONVERT_CODE:-q$QUOTE_CODE}/${CONVERSION_STATUS:--} "
  dl=$(prom_scalar "$DEAD_LETTERS") || dl=$dl_before
  awk -v a="$dl" -v b="$dl_before" 'BEGIN {exit !(a > b)}' && break
  sleep 2; waited=$(( ($(now_ms) - t0) / 1000 ))
done
t1=$(now_ms)
say "after ${n} conversions in $(( (t1 - t0) / 1000 ))s: dead letters $dl_before -> $dl; convert answers: $codes"
awk -v a="$dl" -v b="$dl_before" 'BEGIN {exit !(a > b)}' || findings+=("no dead letter recorded within ${DRIVE_WAIT}s")

duration=$(awk '{print $2}' <<< "$state0")
[[ $duration =~ ^[0-9]+$ ]] || duration=0
deadline=$((duration + ALERT_GRACE)) state='' waited=0
while (( waited <= deadline )); do
  state=$(rule_state)
  [[ $state == firing* && $state == *Alerting* ]] && break
  sleep 5; waited=$(( ($(now_ms) - t1) / 1000 ))
done
say "$RULE: '$state' ${waited}s after the dead letter (want firing with an Alerting instance within ${deadline}s = for ${duration}s + ${ALERT_GRACE}s)"
[[ $state == firing* && $state == *Alerting* ]] || findings+=("$RULE did not reach Alerting within ${deadline}s: '$state'")

if [[ $slack == present ]]; then
  sent=$sent_before waited=0
  while (( waited <= ALERT_GRACE )); do
    sent=$(slack_sent) || sent=$sent_before
    awk -v a="$sent" -v b="$sent_before" 'BEGIN {exit !(a > b)}' && break
    sleep 5; waited=$((waited + 5))
  done
  say "notifications to slack: $sent_before -> $sent (want a rise within ${ALERT_GRACE}s)"
  awk -v a="$sent" -v b="$sent_before" 'BEGIN {exit !(a > b)}' || findings+=("grafana_alerting_notifications_total{integration=\"slack\"} did not rise")
else
  say "no webhook: delivery not asserted (the slack Secret is absent, so Grafana has no Slack contact point)"
fi

restore
verdict "$CHECK" ${findings[@]+"${findings[@]}"}
