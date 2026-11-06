#!/usr/bin/env bash
# usage: 2026-11-06-peer-probe.sh <green|red> <repo root>. A throwaway otel-lgtm on ports 14318/19090 gets synthetic
# spans: a client call to a peer that sends no spans (URL with credentials) and a paired call. green runs start.sh
# (overlay loaded), red runs the image's run-all.sh (no overlay). Prints the service-graph edges Tempo derived.
set -u
arm=$1; wt=$2; name=lgtm-peer-$arm
docker rm -f $name >/dev/null 2>&1
ep=/ezeebit/start.sh; [ "$arm" = red ] && ep=/otel-lgtm/run-all.sh
docker run -d --name $name -p 127.0.0.1:14318:4318 -p 127.0.0.1:19090:9090 -v "$wt/docker/lgtm:/ezeebit:ro" --entrypoint $ep grafana/otel-lgtm:0.39.0 >/dev/null || exit 2
for i in $(seq 1 90); do docker exec $name test -f /tmp/ready && break; sleep 2; done
docker exec $name test -f /tmp/ready || { echo "not ready"; docker logs --tail 30 $name; exit 2; }
now=$(date +%s)
for n in 1 2 3 4 5; do
 t=$(openssl rand -hex 16); c1=$(openssl rand -hex 8); c2=$(openssl rand -hex 8); s2=$(openssl rand -hex 8)
 st=$(( (now+n)*1000000000 )); en=$(( st+5000000 ))
 curl -s -o /dev/null -w "%{http_code} " -H 'Content-Type: application/json' http://127.0.0.1:14318/v1/traces -d @- <<JSON
{"resourceSpans":[
 {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"probe-caller"}}]},"scopeSpans":[{"spans":[
  {"traceId":"$t","spanId":"$c1","name":"http get","kind":3,"startTimeUnixNano":"$st","endTimeUnixNano":"$en","attributes":[{"key":"http.url","value":{"stringValue":"http://svc-user:s3cret@rates.example:8089/rates?base=ZAR"}}]},
  {"traceId":"$t","spanId":"$c2","name":"http post","kind":3,"startTimeUnixNano":"$st","endTimeUnixNano":"$en","attributes":[{"key":"http.url","value":{"stringValue":"http://127.0.0.1:9999/v1/x"}}]}]}]},
 {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"probe-callee"}}]},"scopeSpans":[{"spans":[
  {"traceId":"$t","spanId":"$s2","parentSpanId":"$c2","name":"http post /v1/x","kind":2,"startTimeUnixNano":"$st","endTimeUnixNano":"$en"}]}]}]}
JSON
done; echo
sleep 45
curl -s -G http://127.0.0.1:19090/api/v1/query --data-urlencode 'query=traces_service_graph_request_total' | python3 -c "import json,sys;r=json.load(sys.stdin)['data']['result'];print('edges',len(r));[print(' ',x['metric'].get('client'),'->',x['metric'].get('server'),x['metric'].get('connection_type',''),x['value'][1]) for x in r]"
docker rm -f $name >/dev/null
