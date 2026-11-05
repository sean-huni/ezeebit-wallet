#!/usr/bin/env bash
# Funds 8 new merchants with 10,000,000.00 ZAR each from inside a ledger pod (ephemeral container, as
# k8s/sweep/lib.sh does: a port-forward to ledger-svc is refused because it binds the pod IP).
# Prints the merchants comma-separated on the last line; exit 1 unless all 8 deposits answered 201.
set -uo pipefail
pod=$(kubectl -n ezeebit-app get pods -l app.kubernetes.io/name=ledger-svc -o jsonpath='{.items[0].metadata.name}')
ip=$(kubectl -n ezeebit-app get pod "$pod" -o jsonpath='{.status.podIP}')
ms=(); script=''
for i in 1 2 3 4 5 6 7 8; do
  m=$(uuidgen | tr 'A-Z' 'a-z'); ms+=("$m")
  script+="wget -S -q -O - --header 'Content-Type: application/json' --header 'Idempotency-Key: overhead-$m' --post-data '{\"merchantId\":\"$m\",\"currency\":\"ZAR\",\"amount\":\"10000000.00\",\"reference\":\"overhead\"}' http://$ip:8081/internal/v1/deposits 2>&1 | grep 'HTTP/'; "
done
name=overhead-$(date +%s)
kubectl -n ezeebit-app debug "$pod" --image=postgres:18.6-alpine --image-pull-policy=Never --profile=general \
  --container="$name" -- sh -c "$script" > /dev/null 2>&1 || { echo "debug failed"; exit 1; }
for w in $(seq 1 60); do
  code=$(kubectl -n ezeebit-app get pod "$pod" -o jsonpath="{.status.ephemeralContainerStatuses[?(@.name==\"$name\")].state.terminated.exitCode}")
  [[ -n $code ]] && break; sleep 1
done
out=$(kubectl -n ezeebit-app logs "$pod" -c "$name")
n=$(awk '/ 201/{c++} END{print c+0}' <<< "$out")
echo "ledger pod $pod: $n of 8 deposits 201"
(( n == 8 )) || { echo "$out"; exit 1; }
(IFS=,; echo "${ms[*]}")
