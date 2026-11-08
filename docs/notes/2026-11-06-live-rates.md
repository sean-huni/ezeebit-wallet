# Live rates: recommendations (note, not a plan)

Status: working note, written 2026-11-06, committed 2026-11-08. Probes below were run on 2026-11-06 and go stale.

## Recommendation

1. Start with **VALR** for the ZAR pairs (USDT/ZAR, USDC/ZAR): public, keyless, decimal strings, timestamped.
2. Add **CoinGecko** (with a key) only for the NGN and KES pairs. VALR lists no NGN pair and serves no
   KES market summary (see the test run below).
3. Keep WireMock as the test stand-in; add one test that deserialises a recorded REAL provider payload
   and asserts the whole body.

## Probed 2026-11-06

| Source | Endpoint | Auth | Answer |
|---|---|---|---|
| VALR | `GET https://api.valr.com/v1/public/USDTZAR/marketsummary` (also `USDCZAR`) | none | 200; bid 16.6159, ask 16.616, `created` timestamp; prices as strings |
| CoinGecko | `GET https://api.coingecko.com/api/v3/simple/price?ids=tether,usd-coin&vs_currencies=zar,ngn,kes&include_last_updated_at=true` | none on this endpoint | 200; USDT 16.53 ZAR, 1345.97 NGN, 129.75 KES; data 12 s old; prices as JSON numbers |

Every live value sat inside the configured bands (`usdt-zar 10-30`, `usdt-ngn 800-3000`, `usdt-kes 80-200`).

## Test run, 2026-11-06 ~20:55Z (corrects the first probe)

| Finding | Evidence | Consequence |
|---|---|---|
| VALR `created` is a CDN snapshot time, not a trade time | 20 samples at 2 s: age 4.2 to 57.2 s, 15 of 20 over 10 s; `cache-control: max-age=5`; mark moved 7 times while `created` reset once | `max-age: 10s` refuses most quotes as `RATE_STALE` |
| VALR orderbook is cached too | `LastChange` 19.5 s old, `age: 20`, `cache-control: max-age=30` | not a fresher source |
| VALR KES pairs unusable | `/pairs`: USDTKES `active=false`, USDCKES active; both `marketsummary` answer 404 `-111 Market summary not found` | NGN and KES must come from elsewhere |
| VALR unknown pair | `USDTNGN` answers 400 `{"code":-21,"message":"Unsupported Currency Pair"}` | an error status, never retried (matches the current retry rule) |
| VALR latency | 10 calls, 22 to 43 ms | well inside the 2 s read timeout |
| CoinGecko keyless is throttled | 429 after about 10 calls in about 60 s; `cache-control: max-age=30, s-maxage=60` | keyless is unusable for production; a key is mandatory |
| Market drift | USDT/ZAR mark 16.6233 to 16.627 over 40 s (about 0.02%) | a 30 to 60 s max-age risks far less than the 50 bps spread |

## How it merges with and replaces WireMock

WireMock is not deleted. Its role changes from impersonating an assumed rates service to replaying
recorded vendor payloads in tests.

- Seam: the `RateSource` port. A live adapter (`outbound/rates/valr/`, `outbound/rates/coingecko/`) plus a
  per-pair router implements it; selection is config (`ezeebit.conversion.rates.provider: stub | live`).
  `stub` keeps today's `RateClient` against `RATES_URL`, so compose, kind and the acceptance suite are
  unchanged by default.
- Alternative considered: a separate rates-svc that serves the existing `/rates` contract, so only
  `RATES_URL` changes. Rejected for now: it moves the anti-corruption layer out of the context that owns
  the meaning of a rate, and adds a service and a failure domain for one outbound call.
- Tests: add WireMock mappings that serve recorded VALR and CoinGecko bodies on the vendor paths, and
  one test per vendor asserting the whole payload. The 13 current mappings stay for the stub provider.
- Touch points found (grep for wiremock, WireMock, RATES_URL, 8089): `compose.yaml`, `fleet.sh`,
  `k8s/base/app/{wiremock,conversion-svc}.yaml`, `k8s/base/policy/app.yaml` (conversion egress only to
  wiremock), `k8s/kube.sh` (mappings configmap), `docker/lgtm/alerting/rules.yaml` runbook text,
  conversion-svc `build.gradle` and acceptance tests. Live needs a new egress to DNS and 443.
- Cutover trap: the jump rule compares with the pair's newest quote from the last 5 minutes. Stub
  USDT/ZAR 18.4321 against live about 16.62 is -9.8%, and USDT/NGN 1550.12 against about 1346 is -13%,
  so those pairs answer `RATE_REJECTED` (jump) until 5 minutes after the last stub quote. KES (stub
  129.45, live about 129.8) passes. Switch when quoting is idle, or accept a 5 minute fail-closed window.
- Freshness decision needed before go-live: raise `max-age` per provider to 60 s, or move to a keyed
  streaming feed. Use the vendor's own timestamp, never fetch time, or staleness stops being measured.

## Cost

- VALR: no charge. Rate limits not found in public docs; confirm before relying on it.
- CoinGecko (third-party figures, UNVERIFIED against coingecko.com/en/api/pricing):
  Demo free, about 10k calls/month with attribution; Basic about $35/month (100k); Analyst about
  $129/month (500k, commercial licence). Demo is roughly 333 calls/day: too few for one fetch per quote.
- Engineering: about 1 to 2 days, conversion-svc only. Ledger and gateway unchanged.

## Work items

1. One `RateCall` + DTO + mapper per provider in `outbound/rates/`; nothing above `RateSource` changes.
2. Read CoinGecko numbers as text or `BigDecimal`, never `double`.
3. Inverse pairs (`zar-usdt` etc.): compute `1/rate` with an explicit rounding rule inside
   `RateMapper`'s 14-decimal cap.
4. Price side: bid, ask or mid per direction. A pricing decision for the owner, not a code default.
5. Freshness: `max-age: 10s` would refuse the 12 s CoinGecko answer as stale. Either raise max-age per
   source, or cache in shared Redis with a TTL under max-age (never a per-pod cache).
6. Re-tune `max-jump-percent: 5` against a real feed's update cadence.
7. kind NetworkPolicy: conversion-svc needs egress to DNS and 443. Compose needs nothing.
