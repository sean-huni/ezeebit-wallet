# ezeebit-wallet

Ezeebit backend take-home. The chosen tasks are **Task 1** (hold a merchant's money across
currencies) and **Task 2** (convert one currency into another), and **both are built and tested**.
A double-entry ledger takes internal deposits and serves balances and a statement; conversion-svc
quotes a firm price from the exchange-rate service and converts at exactly that price through one
ledger transaction. Five Spring Boot services route through one gateway, and each domain service
owns its own PostgreSQL database. The exchange-rate service is assumed to exist; locally a WireMock
container stands in for it. Design, trade-offs, scope, known gaps and AI usage notes are in
[SOLUTION.md](SOLUTION.md).

Walkthrough video: link to be added.

## Contents

**Documents**

| Document | What it covers |
|---|---|
| [SOLUTION.md](SOLUTION.md) | Tasks chosen, design and trade-offs, time and scope, how Tasks 3 to 6 fit, known gaps, AI usage notes |
| [ARCHITECTURE.md](ARCHITECTURE.md) | How the design was derived, data model, flows, API, failure handling |
| [docs/project-layout.md](docs/project-layout.md) | Every package and class per service, measured dependencies, where new code goes |
| [docs/quality-gates.md](docs/quality-gates.md) | Test tiers, architecture gates, SAST (static application security testing) and coverage |
| [docs/supply-chain.md](docs/supply-chain.md) | Secrets scanning, images, SBOMs (software bills of materials), how to read the console |
| [docs/ci.md](docs/ci.md) | The CircleCI pipeline (not yet run) |
| [docs/observability.md](docs/observability.md) | Dashboards, tracing, business metrics, alerts, rehearsals, the Playwright suite |
| [docs/observability-evidence.md](docs/observability-evidence.md) | Screenshots of the dashboards, an end-to-end trace, trace-correlated logs, alert timings |
| [docs/openapi/](docs/openapi/) | OpenAPI snapshots: [ledger-svc](docs/openapi/ledger-svc.json), [ledger-svc internal](docs/openapi/ledger-svc-internal.json), [conversion-svc](docs/openapi/conversion-svc.json) |

**This README**

[Find your way around](#find-your-way-around) · [Prerequisites](#prerequisites) · [Build and test](#build-and-test) · [Run the fleet](#run-the-fleet) · [Demo: deposit, quote, convert, read back](#demo-deposit-quote-convert-read-back) · [API reference](#api-reference) · [Services](#services) · [Repository layout](#repository-layout) · [Gateway routes](#gateway-routes) · [Configuration](#configuration) · [Troubleshooting](#troubleshooting)

## Find your way around

[docs/project-layout.md](docs/project-layout.md) is the map: the repository to service level, then
each service's packages with every class in them, what each package is for, the measured
dependencies between packages, and where a new endpoint, rule, query, client, metric, error code or
migration belongs.

## Prerequisites

| Need | Notes |
|---|---|
| JDK 25 | `.sdkmanrc` pins `java=25-tem`; with sdkman run `sdk env install` |
| Docker, running | Postgres, WireMock, Redis and Grafana LGTM for the fleet; Testcontainers (Postgres, Redis) and the secrets scanners during the build |
| zsh and curl | `./fleet.sh` is a zsh script (default shell on macOS). Without zsh, use the manual commands below |
| Free ports | 5432 (Postgres), 6379 (Redis), 8089 (WireMock), 3000, 4317, 4318 (Grafana LGTM), 8080, 8081, 8082, 8761, 8888 |

No Gradle install is needed: the repository ships the Gradle 9.8.1 wrapper. Stack: Java 25, Spring
Boot 4.1.1, Spring Cloud 2025.1.3.

## Build and test

```sh
./gradlew clean build
```

This runs every test tier (unit, Spring integration on Testcontainers, Cucumber acceptance over real
HTTP), the static analysis and coverage gates, and a secrets scan (about 175 s on an 18-core laptop with dependencies and images cached; the
first run also downloads them).
Test counts are in [SOLUTION.md](SOLUTION.md#1-tasks-chosen-and-status); the tiers and gates are
described in [docs/quality-gates.md](docs/quality-gates.md).

## Run the fleet

```sh
./fleet.sh up       # Postgres + WireMock + Redis, bootJar, config-svc and eureka-svc first, then the other three together,
                    # each waited until healthy, then a routing check, then Grafana LGTM
./fleet.sh status   # port and /actuator/health HTTP code per service (000 = not answering)
./fleet.sh down     # stop lgtm, then the JVMs, then docker compose down (data volumes are kept)
```

The tail of `up` (Docker's own progress lines left out):

```
  config-svc UP on :8888
  eureka-svc UP on :8761
  ledger-svc UP on :8081
  conversion-svc UP on :8082
  gateway-svc UP on :8080
  gateway routes to ledger-svc and conversion-svc
lgtm
fleet up: gateway http://localhost:8080, registry http://localhost:8761, grafana http://localhost:3000
```

`up` refuses to start while a fleet from `.fleet/*.pid` is still running. If it stops part way, run
`./fleet.sh down` before retrying so no JVM keeps a port.

### Without zsh

The same steps by hand, in any POSIX shell from the repository root:

```sh
docker compose up -d --wait postgres wiremock redis
./gradlew bootJar
mkdir -p .fleet
for svc in config-svc eureka-svc ledger-svc conversion-svc gateway-svc; do
  java -jar services/$svc/build/libs/$svc-*.jar > .fleet/$svc.log 2>&1 &
  echo $! > .fleet/$svc.pid
  # config-svc and eureka-svc must be healthy before the others start
  case $svc in config-svc) port=8888 ;; eureka-svc) port=8761 ;; *) port= ;; esac
  [ -n "$port" ] && until curl -sf localhost:$port/actuator/health >/dev/null; do sleep 1; done
done
for port in 8081 8082 8080; do until curl -sf localhost:$port/actuator/health >/dev/null; do sleep 1; done; done
docker compose up -d --wait lgtm      # optional: Grafana at http://localhost:3000
```

Give the gateway about 10 seconds after the last service is healthy before calling it: it learns
instances from the registry on a 5 s cycle. To stop:
`kill $(cat .fleet/*.pid); docker compose down`.

## Demo: deposit, quote, convert, read back

With the fleet up. Merchant ids are UUIDs and amounts are decimal strings. A fresh merchant id makes
the demo repeatable.

```sh
M=$(uuidgen | tr 'A-Z' 'a-z')

# 1. Deposit 1500.00 ZAR. This is the money-in rail's internal call, made straight to ledger-svc on :8081:
#    /internal/** is never routed by the gateway, so a merchant cannot credit itself.
curl -s -X POST localhost:8081/internal/v1/deposits \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: rail-transfer-1' \
  -d "{\"merchantId\":\"$M\",\"currency\":\"ZAR\",\"amount\":\"1500.00\",\"reference\":\"rail transfer 1\"}"
# 201 {"data":{..."currency":"ZAR","amount":"1500.00","balanceAfter":"1500.00",...}}
# The same call again: 201 with the identical body, credited once.

# 2. Quote through the gateway: sell exactly 1000.00 ZAR for USDT (or send "buyAmount" to fix what is received).
Q=$(curl -s -X POST localhost:8080/api/v1/merchants/$M/quotes -H 'Content-Type: application/json' \
  -d '{"sell":"ZAR","buy":"USDT","sellAmount":"1000.00"}' | tee /dev/stderr | grep -o '"quoteId":"[^"]*"' | cut -d'"' -f4)
# 201 {"data":{"quoteId":"...","sellCurrency":"ZAR","sellAmount":"1000.00","buyCurrency":"USDT",
#      "buyAmount":"53.982730","rate":"0.054254","appliedRate":"0.05398273","spreadBps":50,...,"expiresAt":"..."}}

# 3. Convert within 15 s. The same key again returns the same answer; the same quote under another key is 409.
C=$(curl -s -X POST localhost:8080/api/v1/merchants/$M/conversions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: convert-1' -d "{\"quoteId\":\"$Q\"}" | tee /dev/stderr | grep -o '"conversionId":"[^"]*"' | cut -d'"' -f4)
# 201 {"data":{"conversionId":"...","status":"COMPLETED","sellAmount":"1000.00","buyAmount":"53.982730",...}}

# 4. Read back.
curl -s localhost:8080/api/v1/merchants/$M/conversions/$C            # status COMPLETED
curl -s localhost:8080/api/v1/merchants/$M/balances
# {"data":[{"currency":"USDT","balance":"53.982730"},{"currency":"ZAR","balance":"500.00"}]}
curl -s "localhost:8080/api/v1/merchants/$M/entries?currency=ZAR"
# two lines: DEPOSIT 1500.00 (balanceAfter 1500.00), then CONVERSION -1000.00 (balanceAfter 500.00)
```

Where the numbers come from: the stand-in rate for ZAR to USDT is 0.054254. The 50 bps spread gives
`appliedRate = 0.054254 x 9950 / 10000 = 0.05398273`. The merchant receives
`1000.00 x 0.05398273 = 53.98273`, rounded down to USDT's 6 decimals: 53.982730. ZAR is
`1500.00 - 1000.00 = 500.00`.

Also worth trying:

```sh
curl -i localhost:8080/internal/v1/deposits     # 404 from the gateway: unrouted
curl -i localhost:8082/v1/merchants/$M/quotes   # 403 GATEWAY_REQUIRED: a service answers public routes only via the gateway
seq 130 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/merchants/$M/balances | sort | uniq -c
                                                # about 100 x 200 (plus what refills during the burst), the rest 429 RATE_LIMITED
docker compose stop redis                       # then any merchant call answers 503 RATE_LIMITER_UNAVAILABLE
docker compose start redis                      # admitted again within about a second
curl -s -X POST localhost:8089/__admin/mappings -d \
  '{"priority":1,"request":{"urlPath":"/rates"},"response":{"status":200,"fixedDelayMilliseconds":3000,"body":"{}"}}'
                                                # a slow rate feed: the next quote is refused with 503 RATE_UNAVAILABLE in about 2 s
curl -s -X POST localhost:8089/__admin/mappings/reset   # back to the committed rates
kill $(cat .fleet/conversion-svc.pid)           # then a quote through the gateway answers 503 UPSTREAM_UNAVAILABLE
./fleet.sh down && ./fleet.sh up                # restore the fleet afterwards
```

Every endpoint and error code is in [ARCHITECTURE.md section 6](ARCHITECTURE.md#6-api-summary), and
browsable in the Swagger UI below.

## API reference

With the fleet up, **http://localhost:8080/swagger-ui.html** is one Swagger UI over both public
specs, picked from "Select a definition": `ledger` (balances, entries) and `conversion` (quotes,
conversions). Its server is the gateway, so "Try it out" goes through the route table, the rate
limiter and the proof header like any client call. Every operation lists its success body with the
demo's values and each error as an RFC 9457 problem with the exact `code` values it can carry.

| Spec | Where |
|---|---|
| ledger public | `GET localhost:8080/v3/api-docs/ledger` (the gateway fetches ledger-svc's `/v3/api-docs/public`) |
| conversion public | `GET localhost:8080/v3/api-docs/conversion` |
| ledger internal (deposits, conversion postings) | `GET localhost:8081/v3/api-docs/internal`, on the service only: never routed, the gateway answers 404 |

The doc routes are not merchant routes, so they are not rate limited; each is one literal path, GET
only. Straight to a service, the public spec answers 403 `GATEWAY_REQUIRED` without the proof header.

The committed snapshots in [docs/openapi](docs/openapi) are what `check` compares the running specs
with. After changing a controller, a DTO, a spec annotation or an error code, regenerate and commit them:

```sh
./gradlew generateOpenApiSnapshots    # boots each service on Testcontainers and rewrites docs/openapi/*.json
```

Without that, `OpenApiContractIT` fails `./gradlew build` and names the file that drifted.

## Services

| Service | Port | Role |
|---|---|---|
| gateway-svc | 8080 | The single client-facing entry: Spring Cloud Gateway (Server WebMVC), routes by registry name, limits each merchant's request rate |
| ledger-svc | 8081 | Task 1: balances, internal deposits, double-entry journal. Own database `ledger` |
| conversion-svc | 8082 | Task 2: quotes and conversions. Own database `conversion` |
| config-svc | 8888 | Spring Cloud Config Server, native backend |
| eureka-svc | 8761 | Eureka service registry (dashboard at http://localhost:8761) |
| wiremock | 8089 | Stand-in for the exchange-rate service (`docker/wiremock/mappings/rates.json`) |
| redis | 6379 | The gateway's rate-limit buckets, shared by every gateway replica |
| lgtm | 3000, 4317, 4318 | Grafana, Prometheus, Tempo, Loki and an OTLP collector |

Each service port is defined once, in that service's `src/main/resources/application.yml` as
`${SERVER_PORT:<n>}`; `fleet.sh` reads it from there.

## Repository layout

```
ezeebit-wallet/
├── README.md, ARCHITECTURE.md, SOLUTION.md
├── docs/                   project layout, quality gates, supply chain, CI, observability, OpenAPI snapshots
├── architecture/           the dependency-injection budget gate, compiled into every service's `test` task
├── build.gradle            shared build conventions for every service
├── settings.gradle         the five service projects
├── gradle.properties       Spring Boot, Spring Cloud, quality-tool and override versions; Paketo pins
├── config/                 checkstyle/checkstyle.xml and spotbugs/exclude.xml, read by every service
├── security/               scanners.lock, trivy-ignore.yaml, scan.sh and report.py: the supply-chain gate
├── .circleci/config.yml    the CI job: the same Gradle commands as a laptop
├── compose.yaml            Postgres 18.6 (alpine) with both databases; WireMock rate service; Redis 8.10 (alpine); Grafana LGTM
├── docker/lgtm/            generate.py (dashboards and alert rules), datasources, promtool tests, Slack route, start script
├── docker/wiremock/mappings/   the assumed exchange-rate service: one rate per supported pair
├── docker/postgres/initdb/01-databases.sql   role + database per service, CONNECT revoked from PUBLIC
├── observability/          OTLP settings, logback appender wiring; compiled into every service
├── fleet.sh                up | status | down
└── services/
    ├── config-svc/         Config Server; served/*.yml is the fleet's configuration
    ├── eureka-svc/         service registry
    ├── gateway-svc/        edge routing and rate limiting; api/advice/UpstreamUnavailableAdvice maps upstream failures to 503/504
    ├── ledger-svc/         Task 1; changesets in db/changelog/2026/10/; src/acceptanceTest holds the Spring and Cucumber tiers
    └── conversion-svc/     Task 2; changesets in db/changelog/2026/10/; outbound/ holds the rate and ledger clients
```

## Gateway routes

Only these paths reach a service; everything else is answered by the gateway itself (404).

| Route | Upstream |
|---|---|
| `/api/v1/merchants/*/balances/**`, `/api/v1/merchants/*/entries/**` | ledger-svc |
| `/api/v1/merchants/*/quotes/**`, `/api/v1/merchants/*/conversions/**` | conversion-svc |
| `/internal/**` | never routed (service-to-service only) |

The gateway rewrites `/api/v1/...` to `/v1/...`; the services serve only `/v1/merchants/...` (and
`/internal/v1/...`), so a call straight to a service under `/api/...` is 404. It also replaces any
client-sent `X-Ezeebit-Gateway` header with its own secret, and ledger-svc and conversion-svc answer
their public routes with **403 `GATEWAY_REQUIRED`** without it. That header is defence in depth, not
authentication ([ARCHITECTURE.md section 3](ARCHITECTURE.md#3-runtime-topology)).

An upstream that is down or refuses the connection answers **503 `UPSTREAM_UNAVAILABLE`**; one that
resets or gives no answer within 10 s answers **504 `UPSTREAM_OUTCOME_UNKNOWN`**, because the
request may have been processed. Problem details name no service.

### Rate limiting

Every merchant route is limited per merchant id, in one Redis bucket that every gateway replica
shares: a burst of **100** requests is admitted, then **20 a second** (1200 a minute). The limits are
served from `gateway-svc.yml` and overridable by the variables below.

| Answer | When |
|---|---|
| 200 with `X-RateLimit-Remaining` | admitted and forwarded |
| **429 `RATE_LIMITED`**, `Retry-After` in whole seconds | the merchant's bucket is empty; nothing was forwarded |
| **429 `RATE_LIMITED`**, `Retry-After: 1` | contention: requests for one merchant raced on its bucket and lost more than `RATE_LIMIT_MAX_RETRIES` times on a healthy Redis |
| **503 `RATE_LIMITER_UNAVAILABLE`**, `Retry-After: 1` | Redis did not answer within 250 ms (down, unreachable, stalled, refused the password) or the limiter failed in a way nobody classified. The limiter fails closed: nothing is forwarded unlimited |
| **400 `INVALID_MERCHANT_ID`** | the merchant id in a `/api/v1/merchants/{id}` path is not a UUID, checked before Redis is touched |
| **400 `MALFORMED_PATH`** | ANY request path, on any route or none, that is not canonical: a `%xx` escape, a `;param`, a `.` or `..` segment, `//`, or a character outside letters, digits, `_`, `.`, `-`. The route matcher decodes and normalises paths, so such a path could reach a route uncharged |

Coverage is total by construction: once a path is canonical it is exactly the path the route matcher
sees, every merchant route lives under `/api/v1/merchants/{id}/` (a test enumerates the route table
and fails on any route outside it other than the two allow-listed spec routes below), and every request
under `/api/v1/merchants/` is charged or refused.
A refusal is never forwarded. `ezeebit_gateway_ratelimit_total{outcome}` counts `allowed`, `limited`,
`contention`, `unavailable` and `refused_path`; RateLimiterUnavailable pages on any `unavailable`, and
RateLimiterStoreDown pages when a gateway's own PING to Redis fails or no gateway reports
([docs/observability.md](docs/observability.md#alert-rules)). A changed limit applies to every
merchant at once: the limits are part of the bucket key, so the new value starts a new bucket.

## Configuration

Committed defaults run a clean clone with no `.env`. To override, export variables or put them in an
optional root `.env` (gitignored); `fleet.sh` sources it and `docker compose` reads it.

| Variable | Default | Effect |
|---|---|---|
| `POSTGRES_PORT` | 5432 | Host port for Postgres, and the port in both services' default JDBC URL |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | postgres / postgres | Postgres superuser in the container |
| `DB_URL` | `jdbc:postgresql://localhost:${POSTGRES_PORT}/<ledger or conversion>` | JDBC URL |
| `DB_USER`, `DB_PASSWORD` | `ledger`/`ledger`, `conversion`/`conversion` | Database credentials. The roles are created in `docker/postgres/initdb/01-databases.sql`: an override needs a matching change there plus `docker compose down -v` |
| `SERVER_PORT` | per service | HTTP port of one service started by hand; `fleet.sh` ignores it |
| `SERVER_ADDRESS` | 127.0.0.1 | Address every service binds and registers. Loopback on purpose: `/internal/**` moves money and has no authentication |
| `CONFIG_SERVER_URL` | http://localhost:8888 | Where ledger, conversion and gateway fetch config |
| `CONFIG_IMPORT_OPTIONALITY` | empty (config-svc mandatory) | Set to `optional:` to start ledger, conversion or gateway without config-svc |
| `CONFIG_SEARCH_LOCATIONS` | `classpath:/served` | Where config-svc reads the configuration it serves |
| `RATES_PORT` | 8089 | Host port of the WireMock rate service, and the port in conversion-svc's default rate URL |
| `RATES_URL` | `http://localhost:${RATES_PORT}` | Base URL of the exchange-rate service |
| `EUREKA_URL` | http://localhost:8761/eureka/ | Registry address for the clients |
| `OTLP_URL` | http://localhost:4318 | OTLP/HTTP base URL for metrics, traces and logs |
| `OPENAPI_PUBLIC_URL` | http://localhost:8080/api | The server named in each public OpenAPI spec, where Swagger UI "Try it out" sends calls |
| `REDIS_PORT` | 6379 | Host port for Redis, and the port the gateway dials |
| `REDIS_HOST` | localhost | Where the gateway's rate limiter finds Redis |
| `REDIS_PASSWORD` | redis-local | Redis `requirepass` in compose and the gateway's credential. Override outside local runs |
| `REDIS_TIMEOUT` | 250ms | Connect and command timeout for the limiter; past it the request is 503 |
| `RATE_LIMIT_CAPACITY`, `RATE_LIMIT_REFILL_TOKENS`, `RATE_LIMIT_REFILL_PERIOD` | 100, 20, 1s | Per-merchant burst, and the tokens refilled per period |
| `RATE_LIMIT_MAX_RETRIES` | 5 | Compare-and-swap retries on one merchant's bucket before the request is 429 contention (at least 1) |
| `REDIS_PROBE_INTERVAL` | 10s | How often each gateway PINGs Redis for `ezeebit_gateway_ratelimit_store_up` |
| `GATEWAY_SHARED_SECRET` | local-gateway-proof | The `X-Ezeebit-Gateway` value the gateway adds and the services require. Override outside local runs |
| `SLACK_WEBHOOK_URL` | empty (no Slack) | Slack incoming webhook for alerts. A secret: `.env` only |

`DB_URL`, `DB_USER`, `DB_PASSWORD` and `SERVER_PORT` carry the same name in every service: set them
per process when starting a service by hand, not in `.env`, or every service picks up the same value.

Served configuration lives in `services/config-svc/src/main/resources/served/`: `application.yml`
(shared by all clients: registry settings, the gateway proof secret and the public spec URL), `ledger-svc.yml` (datasource), `conversion-svc.yml` (datasource and pricing
policy: quote TTL, posting grace, sweeper timing, rate-service timeouts and freshness, jump limit,
ledger client timeouts, currency scales, per-pair band and spread) and `gateway-svc.yml` (the route table with its path rewrite and proof
header, the two Swagger UI spec routes, the proxy read timeout, and the rate limits and Redis connection).

## Troubleshooting

| Symptom | Fix |
|---|---|
| Port 5432 already in use | `POSTGRES_PORT=55432 ./fleet.sh up` (or set it in `.env`); the services follow it |
| Port 6379 already in use | `REDIS_PORT=6380 ./fleet.sh up`; the gateway follows it |
| Every merchant call answers 503 `RATE_LIMITER_UNAVAILABLE` | Redis is not running: `docker compose up -d --wait redis` |
| Edited `01-databases.sql`, nothing changed | The init script runs only on an empty volume: `docker compose down -v`, then `./fleet.sh up` (this wipes the data) |
| A service is not healthy after 90 s | Read `.fleet/<svc>.log` |
| Build fails in ledger, conversion or gateway tests | Docker is not running (Testcontainers) |
| Quotes answer 503 `RATE_UNAVAILABLE` | The `wiremock` container is not running: `docker compose up -d --wait wiremock` |
| Quote accepted after 15 s answers 409 `QUOTE_EXPIRED` | Expected: re-quote and accept within 15 s |
| Port 3000, 4317 or 4318 already in use | Stop the other Grafana or collector; these ports are fixed |
| A dashboard shows no data right after `up` | Metrics export every 10 s; wait one step |

Run state (pids and logs) lives in `.fleet/`, which is gitignored.
