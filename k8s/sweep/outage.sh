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
# The rule counts dead letters over 5m: a leftover firing clears within that plus a few evaluations.
CLEAR_WAIT=360
findings=()

# "<rule state> <for seconds> <Alerting instances active at or after $1 epoch s>", "missing", or empty when Grafana
# did not answer. Counting only instances that became active after the outage began keeps a firing left over from
# an earlier check (for: 0s over a 5m increase) from passing this one.
rule_state() {
  local out rc
  out=$(curl -s --max-time 10 "$GRAFANA/api/prometheus/grafana/api/v1/rules"); rc=$?
  (( rc == 0 )) || return 1
  jq -r --arg r "$RULE" --argjson since "${1:-0}" '
    def epoch: if test("(Z|[+]00:00)$") then .[0:19] + "Z" | fromdateiso8601 else -1 end;
    [.data.groups[].rules[] | select(.name == $r)]
    | if length == 1 then .[0]
        | "\(.state) \(.duration // 0) \([.alerts[]? | select(.state == "Alerting" and ((.activeAt // "") | epoch) >= $since)] | length)"
      else "missing" end' <<< "$out" 2>/dev/null
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

restored=0
restore() {
  local got rc
  (( restored )) && return 0
  restored=1
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
# A firing instance keeps its first activeAt while it stays firing, so one left by an earlier dead letter would hide
# this outage's: wait for it to clear (the rule's 5m increase window), or fail rather than pass on it.
waited=0 t_clear=$(now_ms)
while :; do
  state0=$(rule_state 0)
  [[ -n $state0 && $state0 != missing && ${state0##* } == 0 ]] && break
  (( waited >= CLEAR_WAIT )) && break
  sleep 10; waited=$(( ($(now_ms) - t_clear) / 1000 ))
done
say "dead letters before: $dl_before; $RULE before (state, for, Alerting instances): '${state0:-no answer}' after ${waited}s (want 0 Alerting within ${CLEAR_WAIT}s)"
if [[ -z $state0 || $state0 == missing || ${state0##* } != 0 ]]; then
  verdict "$CHECK" "$RULE was not clear before the outage: '${state0:-no answer}' after ${CLEAR_WAIT}s"; exit 1
fi
slack=absent
kctl get secret slack -n ezeebit-obs > /dev/null 2>&1 && slack=present
sent_before=''
if [[ $slack == present ]]; then sent_before=$(slack_sent) || findings+=("Grafana /metrics did not answer before the outage"); fi
say "slack secret: $slack; notifications to slack before: ${sent_before:-n/a}"

t0=$(now_ms)
trap 'restore; exit 130' INT TERM
trap 'restore' EXIT
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
  state=$(rule_state $((t0 / 1000)))
  [[ $state == firing* && ${state##* } =~ ^[1-9][0-9]*$ ]] && break
  sleep 5; waited=$(( ($(now_ms) - t1) / 1000 ))
done
say "$RULE: state, for, Alerting instances active since the scale-down = '$state' ${waited}s after the dead letter (want firing and >= 1 within ${deadline}s = for ${duration}s + ${ALERT_GRACE}s)"
[[ $state == firing* && ${state##* } =~ ^[1-9][0-9]*$ ]] || findings+=("$RULE did not reach Alerting within ${deadline}s: '$state'")

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
