#!/usr/bin/env bash
# Spec 6.3: money in, quote, convert through the gateway, then the ledger's balances read back.
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
CHECK=money
findings=()
m=$(uuid)
say "merchant $m"

if ! deposit "$m" 100.00; then
  verdict "$CHECK" "deposit: $DEPOSIT_OUT"; exit 1
fi
say "deposit 100.00 ZAR: $DEPOSIT_OUT"

convert "$m" "sweep-money-$m" 50.00; rc=$?
say "quote $QUOTE_CODE; convert ${CONVERT_CODE:-not sent} status=${CONVERSION_STATUS:-none} id=${CONVERSION_ID:-none} buy=${BUY_AMOUNT:-none} USDT"
if (( rc != 0 )); then
  verdict "$CHECK" "conversion did not complete: quote $QUOTE_CODE, convert ${CONVERT_CODE:-not sent}: $BODY"; exit 1
fi
[[ $CONVERSION_STATUS == COMPLETED ]] || findings+=("conversion status '$CONVERSION_STATUS', want COMPLETED")

call GET "$GATEWAY/api/v1/merchants/$m/balances"
zar=$(jq -r '.data[]? | select(.currency == "ZAR") | .balance' <<< "$BODY" 2>/dev/null)
usdt=$(jq -r '.data[]? | select(.currency == "USDT") | .balance' <<< "$BODY" 2>/dev/null)
say "balances $CODE: ZAR=${zar:-none} (want 50.00) USDT=${usdt:-none} (want $BUY_AMOUNT)"
[[ $CODE == 200 ]] || findings+=("balances answered $CODE")
[[ $zar == 50.00 ]] || findings+=("ZAR balance '${zar}', want 50.00")
[[ -n $BUY_AMOUNT && $usdt == "$BUY_AMOUNT" ]] || findings+=("USDT balance '${usdt}', want the conversion's buyAmount '$BUY_AMOUNT'")

posting=$(psql_in ledger "SELECT count(*) FROM journal WHERE type = 'CONVERSION' AND status = 'POSTED' AND merchant_id = '$m' AND idempotency_key = '$CONVERSION_ID'"); rc=$?
say "ledger journal rows for conversion $CONVERSION_ID: ${posting:-none} (want 1, psql exit $rc)"
[[ $rc == 0 && $posting == 1 ]] || findings+=("ledger holds '$posting' CONVERSION postings for $CONVERSION_ID, want 1")

verdict "$CHECK" ${findings[@]+"${findings[@]}"}
