# Trace continuity over the ledger gRPC seam, 2026-10-14

Question: does one merchant conversion stay ONE trace when conversion-svc calls ledger-svc over gRPC,
with ledger-svc's gRPC server span a child of conversion-svc's gRPC client span? Spec section 4:
"the conversion-to-ledger span continues into Tempo as it does today".

Verdict: **continuous.** Measured on the worktree fleet at `0cdc830` (branch `feat-ledger-grpc`),
checked 2026-10-14. No code change was needed: the trace context already crosses the gRPC hop.

## Commands

```sh
./fleet.sh up        # RESULT: healthy, all 16 checks passed (convert step now requires 201 and COMPLETED)

M=$(uuidgen | tr 'A-Z' 'a-z')                # ef295908-6ca4-45fc-922b-3d162a6a276d
curl -s -X POST localhost:8081/internal/v1/deposits -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: rail-transfer-1' \
  -d "{\"merchantId\":\"$M\",\"currency\":\"ZAR\",\"amount\":\"1500.00\",\"reference\":\"rail transfer 1\"}"   # 201
Q=...  # POST /api/v1/merchants/$M/quotes {"sell":"ZAR","buy":"USDT","sellAmount":"1000.00"}            # 201
# POST /api/v1/merchants/$M/conversions, Idempotency-Key convert-1                                      # 201
#   {"conversionId":"83a80783-6153-43ab-b6d9-87e5d56a4456","status":"COMPLETED"}

# Find the conversion's trace in Tempo through Grafana's datasource proxy, by the merchant in the URL:
curl -s -G localhost:3000/api/datasources/proxy/uid/tempo/api/search \
  --data-urlencode "q={span.http.url =~ \".*$M/conversions\"}" \
  --data-urlencode "start=$(($(date +%s)-1800))" --data-urlencode "end=$(date +%s)" --data-urlencode limit=20
#   {"traceID":"f9b98a426a168f23f9bbc22fdce2f5e2","rootServiceName":"gateway-svc",
#    "rootTraceName":"http post /api/v1/merchants/*/conversions/**"}    (exactly one trace)

curl -s localhost:3000/api/datasources/proxy/uid/tempo/api/traces/f9b98a426a168f23f9bbc22fdce2f5e2 > trace.json
# decoded (span ids base64 to hex) by a short python script: service, trace, span, parent, kind, name, rpc.*
```

## Output: every span of trace f9b98a426a168f23f9bbc22fdce2f5e2

```
gateway-svc     trace=f9b98a426a168f23f9bbc22fdce2f5e2 span=3ee5530eb73e1094 parent=0eb41ac710d19cb3 SPAN_KIND_INTERNAL   rate limit {}
gateway-svc     trace=f9b98a426a168f23f9bbc22fdce2f5e2 span=bcbc0fc2d96704c1 parent=0eb41ac710d19cb3 SPAN_KIND_CLIENT     http post {'status': '201', 'uri': 'none', 'http.url': 'http://127.0.0.1:8082/v1/merchants/ef295908-6ca4-45fc-922b-3d162a6a276d/conversions'}
gateway-svc     trace=f9b98a426a168f23f9bbc22fdce2f5e2 span=0eb41ac710d19cb3 parent=- SPAN_KIND_SERVER     http post /api/v1/merchants/*/conversions/** {'status': '201', 'uri': '/api/v1/merchants/*/conversions/**', 'http.url': '/api/v1/merchants/ef295908-6ca4-45fc-922b-3d162a6a276d/conversions'}
ledger-svc      trace=f9b98a426a168f23f9bbc22fdce2f5e2 span=63cc12ff8fd03f76 parent=820132262de3aa14 SPAN_KIND_INTERNAL   ledger posting {}
ledger-svc      trace=f9b98a426a168f23f9bbc22fdce2f5e2 span=820132262de3aa14 parent=4359704be8bc701f SPAN_KIND_SERVER     ezeebit.ledger.v1.ConversionPostings/Post {'grpc.status_code': 'OK', 'net.peer.name': 'ledger-svc', 'net.peer.port': '-1', 'rpc.method': 'Post', 'rpc.service': 'ezeebit.ledger.v1.ConversionPostings', 'rpc.type': 'UNARY'}
conversion-svc  trace=f9b98a426a168f23f9bbc22fdce2f5e2 span=de8f78ab9e3818ba parent=bcbc0fc2d96704c1 SPAN_KIND_SERVER     http post /{version:v\d+}/merchants/{merchantId}/conversions {'status': '201', 'uri': '/{version:v\\d+}/merchants/{merchantId}/conversions', 'http.url': '/v1/merchants/ef295908-6ca4-45fc-922b-3d162a6a276d/conversions'}
conversion-svc  trace=f9b98a426a168f23f9bbc22fdce2f5e2 span=a315b6cb2b348901 parent=de8f78ab9e3818ba SPAN_KIND_INTERNAL   conversion accept {}
conversion-svc  trace=f9b98a426a168f23f9bbc22fdce2f5e2 span=4359704be8bc701f parent=a315b6cb2b348901 SPAN_KIND_CLIENT     ezeebit.ledger.v1.ConversionPostings/Post {'grpc.status_code': 'OK', 'net.peer.name': 'ledger-svc', 'net.peer.port': '-1', 'rpc.method': 'Post', 'rpc.service': 'ezeebit.ledger.v1.ConversionPostings', 'rpc.type': 'UNARY'}
```

## The link, read from that output

| | span | parent | service, kind |
|---|---|---|---|
| gRPC client | `4359704be8bc701f` | `a315b6cb2b348901` (conversion accept) | conversion-svc, CLIENT, `ezeebit.ledger.v1.ConversionPostings/Post` |
| gRPC server | `820132262de3aa14` | `4359704be8bc701f` (the client span above) | ledger-svc, SERVER, `ezeebit.ledger.v1.ConversionPostings/Post` |
| business | `63cc12ff8fd03f76` | `820132262de3aa14` | ledger-svc, INTERNAL, `ledger posting` |

All 8 spans carry the one trace id `f9b98a426a168f23f9bbc22fdce2f5e2`: gateway-svc (3), conversion-svc (3),
ledger-svc (2). The ledger server span's parent is the conversion client span, so the trace is
continuous across the gRPC hop, not two traces joined by time.

Corroborated by the two conversions `./fleet.sh up` made itself (traces `0d191bcd9b223e784ee3e38df8ab53ee`
and `bbae6257659c8d87ce2fa1c385d8821d`), each with the same client to server parent link.

Control: `GET .../api/traces/00000000000000000000000000c0ffee` answers 404, so a found trace is a real
lookup and not a proxy that answers anything.

Not proven here: the `ConversionSettlement` (Find, Void) calls made by the sweeper. They use the same
channel and interceptors, but no sweep had work in this run.
