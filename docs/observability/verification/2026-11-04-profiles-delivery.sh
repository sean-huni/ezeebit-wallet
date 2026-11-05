#!/usr/bin/env bash
# Usage: 2026-11-04-profiles-delivery.sh <grafana-url>. Asks Pyroscope, through Grafana's datasource proxy (uid
# pyroscope), which service_name values hold profiles in the last PROFILES_WINDOW_SECONDS (default 600).
# exit 0: all five services present; exit 1: one or more missing (named); exit 2: could not query, or the control
# (a service that does not exist) returned something, so the instrument cannot be trusted.
set -uo pipefail
GRAFANA=${1:?usage: $0 <grafana-url>}
WINDOW=${PROFILES_WINDOW_SECONDS:-600}
SERVICES=(config-svc eureka-svc ledger-svc conversion-svc gateway-svc)
CONTROL=no-such-svc-$$
END=$(( $(date +%s) * 1000 )); START=$(( END - WINDOW * 1000 ))
URL="$GRAFANA/api/datasources/proxy/uid/pyroscope/querier.v1.QuerierService/LabelValues"

# label_values <matchers json array>: prints one value per line; on failure says why on stderr and returns 1.
label_values() {
  local body out code rc
  body="{\"name\":\"service_name\",\"matchers\":$1,\"start\":$START,\"end\":$END}"
  out=$(curl -s -m 20 -w '\n%{http_code}' -X POST -H 'Content-Type: application/json' -d "$body" "$URL" 2>&1); rc=$?
  code=${out##*$'\n'}; out=${out%$'\n'*}
  if (( rc != 0 )) || [[ $code != 200 ]]; then echo "curl exit $rc, HTTP $code: $out" >&2; return 1; fi
  jq -r '(.names // [])[]' <<< "$out" || { echo "unparseable body: $out" >&2; return 1; }
}

echo "window: last ${WINDOW}s ($START..$END ms), $URL"
control=$(label_values "[\"{service_name=\\\"$CONTROL\\\"}\"]") || { echo "control query failed (above)"; echo "exit=2"; exit 2; }
if [[ -n $control ]]; then
  echo "control: $CONTROL returned [${control//$'\n'/ }], expected nothing"; echo "exit=2"; exit 2
fi
echo "control: $CONTROL returned nothing (expected)"
found=$(label_values '[]') || { echo "query failed (above)"; echo "exit=2"; exit 2; }
echo "service_name values: [${found//$'\n'/ }]"
missing=()
for s in "${SERVICES[@]}"; do
  if grep -qxF -- "$s" <<< "$found"; then echo "present $s"; else echo "MISSING $s"; missing+=("$s"); fi
done
if (( ${#missing[@]} )); then echo "missing ${#missing[@]} of ${#SERVICES[@]}: ${missing[*]}"; echo "exit=1"; exit 1; fi
echo "all ${#SERVICES[@]} services present"; echo "exit=0"; exit 0
