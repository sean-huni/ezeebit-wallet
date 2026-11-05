#!/usr/bin/env bash
# Usage: memsnap.sh <label>. One snapshot of pod working set and JVM memory pools, read through Grafana's Prometheus proxy.
G=${GRAFANA:-http://127.0.0.1:3000}
q() { curl -s -G "$G/api/datasources/proxy/uid/prometheus/api/v1/query" --data-urlencode "query=$1"; }
echo "== $1 $(date -u +%FT%TZ)"
echo "-- kubectl top pods -n ezeebit-app (working set)"; kubectl top pods -n ezeebit-app --no-headers | grep -- '-svc-'
for spec in \
 "gc|count by (job, gc) (jvm_gc_pause_milliseconds_count)" \
 "heap_used_MiB|sum by (job, instance) (jvm_memory_used_bytes{area=\"heap\"}) / 1048576" \
 "heap_committed_MiB|sum by (job, instance) (jvm_memory_committed_bytes{area=\"heap\"}) / 1048576" \
 "nonheap_used_MiB|sum by (job, instance, id) (jvm_memory_used_bytes{area=\"nonheap\"}) / 1048576" \
 "threads|max by (job) (jvm_threads_live_threads)" \
 "profiling_running|sum by (job) (ezeebit_profiling_running)"; do
  name=${spec%%|*}; expr=${spec#*|}
  echo "-- $name: $expr"
  q "$expr" | python3 -I -c 'import json,sys
d=json.load(sys.stdin); assert d["status"]=="success", d
for r in sorted(d["data"]["result"], key=lambda r:(r["metric"].get("job",""),r["metric"].get("instance",""),r["metric"].get("id",""))):
  m=r["metric"]; print("  ", m.get("job",""), m.get("instance",""), m.get("id", m.get("gc","")), round(float(r["value"][1]),1))'
done
