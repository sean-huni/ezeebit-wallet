# shellcheck shell=bash disable=SC2034
# Sourced by the sweep checks. Services bind the pod IP, so per-pod numbers come from Prometheus through Grafana's
# datasource proxy, where `instance` is <pod>:<port>:<pid>.
# shellcheck source=../lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"
cd "$ROOT" || exit 1

GATEWAY=http://127.0.0.1:8080
GRAFANA=http://127.0.0.1:3000
PROM_QUERY=$GRAFANA/api/datasources/proxy/uid/prometheus/api/v1/query
APP_NS=ezeebit-app
# Already on the node (compose's postgres image): busybox wget and psql, no registry pull.
PROBE_IMAGE=postgres:18.6-alpine
SWEEP_DIR=$RUN_DIR/sweep
mkdir -p "$SWEEP_DIR"

now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'; }
uuid() { uuidgen | tr 'A-Z' 'a-z'; }
say() { printf '%s\n' "$*"; }

# prom <promql> [label]: prints "<label value> <sample>" per series. Up to 3 tries 2s apart: a freshly started Grafana
# was seen answering {"message":"Unable to find datasource plugin"} for a moment. Exit 1 when no try got an answer.
prom() {
  local out raw rc try
  for try in 1 2 3; do
    raw=$(curl -s --max-time 10 -G "$PROM_QUERY" --data-urlencode "query=$1"); rc=$?
    if (( rc == 0 )); then
      out=$(jq -r --arg l "${2:-instance}" \
        'if .status == "success" then .data.result[] | "\(.metric[$l] // "-") \(.value[1])" else error("status \(.status)") end' \
        <<< "$raw" 2>&1); rc=$?
      if (( rc == 0 )); then
        printf '%s' "$out"
        [[ -n $out ]] && printf '\n'
        return 0
      fi
      say "prom: try $try: $out for: $1; answer: ${raw:0:200}" >&2
    else
      say "prom: try $try: curl exited $rc for: $1" >&2
    fi
    (( try < 3 )) && sleep 2
  done
  return 1
}

# prom_scalar <promql>: one number, 0 when the query returned no series.
prom_scalar() {
  local out rc
  out=$(prom "$1" __name__); rc=$?
  (( rc == 0 )) || return 1
  out=${out%%$'\n'*}
  printf '%s' "${out#* }"
  [[ -n $out ]] || printf '0'
}

# Ready, not terminating pods of a service, one name per line.
pods_of() {
  kctl get pods -n "${2:-$APP_NS}" -l "app.kubernetes.io/name=$1" \
    -o go-template='{{range .items}}{{$p := .}}{{if not .metadata.deletionTimestamp}}{{range .status.conditions}}{{if and (eq .type "Ready") (eq .status "True")}}{{$p.metadata.name}}{{"\n"}}{{end}}{{end}}{{end}}{{end}}' 2>/dev/null
}

# call <method> <url> [curl args]: sets CODE (000 when nothing answered) and BODY.
call() {
  local method=$1 url=$2 out rc
  shift 2
  out=$(curl -s --max-time 15 -w '\n%{http_code}' -X "$method" -H 'Content-Type: application/json' "$@" "$url"); rc=$?
  CODE=${out##*$'\n'} BODY=${out%$'\n'*}
  (( rc == 0 )) || { CODE=000; BODY="curl exit $rc"; }
}

# in_pod <ns> <pod> <script>: runs <script> in an ephemeral container sharing the pod's network; prints its log and
# returns its exit code. Never `debug -i`: it races a short command and returns empty output.
in_pod() {
  local ns=$1 pod=$2 script=$3 name code waited=0 rc out
  name="sweep-$(date +%s)-$RANDOM"
  out=$(kctl debug "$pod" -n "$ns" --image="$PROBE_IMAGE" --image-pull-policy=Never --profile=general \
    --container="$name" -- sh -c "$script" 2>&1); rc=$?
  (( rc == 0 )) || { say "in_pod: debug $ns/$pod exited $rc: $out"; return 125; }
  while (( waited < 90 )); do
    code=$(kctl get pod "$pod" -n "$ns" \
      -o jsonpath="{.status.ephemeralContainerStatuses[?(@.name==\"$name\")].state.terminated.exitCode}" 2>/dev/null)
    [[ -n $code ]] && break
    sleep 1; waited=$((waited + 1))
  done
  [[ $code =~ ^[0-9]+$ ]] || { say "in_pod: $name in $ns/$pod did not finish in 90s"; return 124; }
  kctl logs "$pod" -n "$ns" -c "$name"
  return "$code"
}

# deposit <merchant> <amount>: the internal route is never published, so it is called from inside a ledger pod
# against that pod's own IP. Sets DEPOSIT_OUT; returns 0 only on 201.
deposit() {
  local m=$1 amount=$2 pods pod ip rc
  pods=$(pods_of ledger-svc); pod=${pods%%$'\n'*}
  [[ -n $pod ]] || { DEPOSIT_OUT="no Ready ledger-svc pod"; return 1; }
  ip=$(kctl get pod "$pod" -n "$APP_NS" -o jsonpath='{.status.podIP}')
  [[ -n $ip ]] || { DEPOSIT_OUT="no IP for $pod"; return 1; }
  DEPOSIT_OUT=$(in_pod "$APP_NS" "$pod" "wget -S -q -O - --header 'Content-Type: application/json' \
    --header 'Idempotency-Key: sweep-$m' \
    --post-data '{\"merchantId\":\"$m\",\"currency\":\"ZAR\",\"amount\":\"$amount\",\"reference\":\"k8s sweep\"}' \
    http://$ip:8081/internal/v1/deposits 2>&1" 2>&1); rc=$?
  [[ $DEPOSIT_OUT == *"HTTP/1.1 201"* ]] || { DEPOSIT_OUT="ledger pod $pod: exit $rc: $DEPOSIT_OUT"; return 1; }
  DEPOSIT_OUT="ledger pod $pod ip $ip: 201"
}

# convert <merchant> <idempotency key> <sell amount>: quote then convert through the gateway. Sets QUOTE_CODE,
# CONVERT_CODE, CONVERSION_ID, CONVERSION_STATUS, BUY_AMOUNT.
convert() {
  local m=$1 key=$2 amount=$3 q
  CONVERT_CODE='' CONVERSION_ID='' CONVERSION_STATUS='' BUY_AMOUNT=''
  call POST "$GATEWAY/api/v1/merchants/$m/quotes" -d "{\"sell\":\"ZAR\",\"buy\":\"USDT\",\"sellAmount\":\"$amount\"}"
  QUOTE_CODE=$CODE
  q=$(jq -r '.data.quoteId // empty' <<< "$BODY" 2>/dev/null)
  [[ $CODE == 201 && -n $q ]] || return 1
  call POST "$GATEWAY/api/v1/merchants/$m/conversions" -H "Idempotency-Key: $key" -d "{\"quoteId\":\"$q\"}"
  CONVERT_CODE=$CODE
  CONVERSION_ID=$(jq -r '.data.conversionId // empty' <<< "$BODY" 2>/dev/null)
  CONVERSION_STATUS=$(jq -r '.data.status // empty' <<< "$BODY" 2>/dev/null)
  BUY_AMOUNT=$(jq -r '.data.buyAmount // empty' <<< "$BODY" 2>/dev/null)
  [[ $CODE == 201 ]]
}

# psql_in <db> <sql>: one unaligned result row from the fleet's postgres, as its admin user.
psql_in() {
  kctl exec -n ezeebit-data postgres-0 -- sh -c "psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d $1 -tAF ' ' -c \"$2\""
}

verdict() {
  local check=$1
  shift
  if (( $# == 0 )); then say "PASS $check"; return 0; fi
  printf '  %s\n' "$@"
  say "FAIL $check: $# finding(s)"
  return 1
}
