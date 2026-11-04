#!/usr/bin/env bash
# measure.sh <startMs> <endMs> <label>: per job over the window, server p95 (non-actuator), request count, mean
# process_cpu_usage, CPU-seconds, profiler-running series and the JVMs seen. Fails closed: a query that does not
# answer HTTP 200 with status "success" and at least one series ends the run with exit=1.
set -uo pipefail
P=${GRAFANA_URL:-http://127.0.0.1:3000}/api/datasources/proxy/uid/prometheus/api/v1/query
s=$1; e=$2; n=$(( (e - s) / 1000 )); t=$(( e / 1000 )); bad=0
q() {
  local out code body rows
  out=$(curl -s -m 20 -w '\n%{http_code}' --data-urlencode "query=$1" --data-urlencode "time=$t" "$P"); local rc=$?
  code=${out##*$'\n'}; body=${out%$'\n'*}
  if (( rc != 0 )) || [[ $code != 200 ]]; then echo "QUERY FAILED $2: curl exit $rc, HTTP $code"; bad=1; return; fi
  rows=$(jq -r --arg l "$2" 'if .status == "success" then .data.result[] | "\($l) \(.metric.job) \(.value[1])" else error("status \(.status)") end' <<< "$body") \
    || { echo "QUERY FAILED $2: unreadable body"; bad=1; return; }
  [[ -n $rows ]] || { echo "QUERY EMPTY $2"; bad=1; return; }
  sort <<< "$rows"
}
echo "label=$3 window=${n}s ending $(date -u -r $t +%FT%TZ)"
q "histogram_quantile(0.95, sum by (job, le) (increase(http_server_requests_milliseconds_bucket{uri!~\"/actuator.*\"}[${n}s])))" p95ms
q "sum by (job) (increase(http_server_requests_milliseconds_count{uri!~\"/actuator.*\"}[${n}s]))" requests
q "avg by (job) (avg_over_time(process_cpu_usage[${n}s]))" cpu_usage_mean
q "sum by (job) (increase(process_cpu_time_nanoseconds_total[${n}s])) / 1e9" cpu_seconds
q "count by (job) (count by (job, instance) (count_over_time(process_cpu_usage[${n}s])))" jvms_seen_in_window
echo "exit=$bad"; exit $bad
