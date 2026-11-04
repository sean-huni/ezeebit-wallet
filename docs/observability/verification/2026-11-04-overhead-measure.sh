#!/usr/bin/env bash
# measure.sh <startMs> <endMs> <label>: per job over the window, server p95 (non-actuator), request count, mean
# process_cpu_usage, CPU-seconds, profiler-running series and the JVMs seen (replica check).
P=${GRAFANA_URL:-http://127.0.0.1:3000}/api/datasources/proxy/uid/prometheus/api/v1/query
s=$1; e=$2; n=$(( (e - s) / 1000 )); t=$(( e / 1000 ))
q() { curl -s --data-urlencode "query=$1" --data-urlencode "time=$t" "$P" | jq -r --arg l "$2" '.data.result[] | "\($l) \(.metric.job) \(.value[1])"' | sort; }
echo "label=$3 window=${n}s ending $(date -u -r $t +%FT%TZ)"
q "histogram_quantile(0.95, sum by (job, le) (increase(http_server_requests_milliseconds_bucket{uri!~\"/actuator.*\"}[${n}s])))" p95ms
q "sum by (job) (increase(http_server_requests_milliseconds_count{uri!~\"/actuator.*\"}[${n}s]))" requests
q "avg by (job) (avg_over_time(process_cpu_usage[${n}s]))" cpu_usage_mean
q "sum by (job) (increase(process_cpu_time_nanoseconds_total[${n}s])) / 1e9" cpu_seconds
q "count by (job) (ezeebit_profiling_running)" profiling_running_series
q "count by (job) (count by (job, instance) (count_over_time(process_cpu_usage[${n}s])))" jvms_seen_in_window
