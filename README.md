# ezeebit-wallet

Ezeebit backend take-home. The chosen tasks are **Task 1** (hold a merchant's money across
currencies) and **Task 2** (convert one currency into another), and **both are built and tested**.
A double-entry ledger takes internal deposits and serves balances and a statement; conversion-svc
quotes a firm price from the exchange-rate service and converts at exactly that price through one
ledger transaction. Five Spring Boot services route through one gateway, and each domain service
owns its own PostgreSQL database. The exchange-rate service is assumed to exist; locally a WireMock
container stands in for it. Design, trade-offs, scope, known gaps and artificial intelligence (AI) usage notes are in
[SOLUTION.md](SOLUTION.md).

Walkthrough video: link to be added.

## Contents

**Documents**

| Document | What it covers |
|---|---|
| [SOLUTION.md](SOLUTION.md) | Tasks chosen, design and trade-offs, time and scope, how Tasks 3 to 6 fit, known gaps, AI usage notes |
| [ARCHITECTURE.md](ARCHITECTURE.md) | How the design was derived, data model, flows, application programming interface (API), failure handling |
| [docs/project-layout.md](docs/project-layout.md) | Every package and class per service, measured dependencies, where new code goes |
| [docs/quality-gates.md](docs/quality-gates.md) | Test tiers, architecture gates, static application security testing (SAST) and coverage |
| [docs/supply-chain.md](docs/supply-chain.md) | Secrets scanning, images, software bills of materials (SBOMs), how to read the console |
| [docs/ci.md](docs/ci.md) | The continuous integration (CI) pipeline on CircleCI (not yet run) |
| [docs/observability.md](docs/observability.md) | Dashboards, tracing, business metrics, alerts, rehearsals, the Playwright suite |
| [docs/observability-evidence.md](docs/observability-evidence.md) | Screenshots of the dashboards, an end-to-end trace, trace-correlated logs, alert timings |
| [docs/fleet/verification/](docs/fleet/verification/) | The `fleet.sh` sweep test (every outcome of `up`, `down` and `status`) and its last run log |
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
| Java Development Kit (JDK) 25 | `.sdkmanrc` pins `java=25-tem`; with sdkman run `sdk env install` |
| Docker, running | Postgres, WireMock, Redis and Grafana's Loki, Grafana, Tempo and Mimir (LGTM) image for the fleet; Testcontainers (Postgres, Redis) and the secrets scanners during the build |
| zsh, curl, jq and lsof | `./fleet.sh` is a zsh script (default shell on macOS); jq ships with macOS 15 and later, otherwise `brew install jq`. Without zsh, use the manual commands below |
| Free ports | 5432 (Postgres), 6379 (Redis), 8089 (WireMock), 3000, 4317, 4318 (Grafana LGTM), 8080, 8081, 8082, 8761, 8888 |

No Gradle install is needed: the repository ships the Gradle 9.8.1 wrapper. Stack: Java 25, Spring
Boot 4.1.1, Spring Cloud 2025.1.3.

## Build and test

```sh
./gradlew clean build
```

This runs every test tier (unit, Spring integration on Testcontainers, Cucumber acceptance over real
Hypertext Transfer Protocol (HTTP)), the static analysis and coverage gates, and a secrets scan (about 175 s on an 18-core laptop with dependencies and images cached; the
first run also downloads them).
Test counts are in [SOLUTION.md](SOLUTION.md#1-tasks-chosen-and-status); the tiers and gates are
described in [docs/quality-gates.md](docs/quality-gates.md).

## Run the fleet

```sh
./fleet.sh up       # port check, Postgres + WireMock + Redis, bootJar, config-svc and eureka-svc first, then the other
                    # three, a routing check, Grafana LGTM, then waits until every row below is UP or HEALTHY
./fleet.sh status   # the table below; exits 1 if any row is not UP or HEALTHY
./fleet.sh down     # stop lgtm, then the JVMs, then docker compose down (data volumes are kept); then proves it
```

`status` (and the end of `up` and `down`) prints one row per service, grouped as core (one Java virtual machine (JVM) each) or supporting
and by role. Every state is a word, and DETAIL says why, so a service that is not answering reads
`nothing listening (connection refused)` rather than an HTTP code of `000`:

```
SERVICE                TYPE               PORT  PID    STATE        DETAIL
CORE SERVICES (Java microservices launched by fleet.sh)
  Control plane
    config-svc         Java microservice  8888  63645  UP           /actuator/health 200
    eureka-svc         Java microservice  8761  63688  UP           /actuator/health 200
  Business
    ledger-svc         Java microservice  8081  63938  UP           /actuator/health 200
    conversion-svc     Java microservice  8082  63939  UP           /actuator/health 200
  Edge
    gateway-svc        Java microservice  8080  63940  UP           routes to ledger-svc and conversion-svc
SUPPORTING SERVICES (Docker containers, and the backends inside lgtm)
  Data stores
    postgres           Docker container   5432  -      HEALTHY      compose healthcheck passing
    redis              Docker container   6390  -      HEALTHY      compose healthcheck passing
  External stand-ins
    wiremock           Docker container   8089  -      HEALTHY      compose healthcheck passing
  Observability
    lgtm               Docker container   3000  -      HEALTHY      compose healthcheck passing
    grafana            inside lgtm        3000  -      UP           /api/health 200
    prometheus         inside lgtm        -     -      UP           datasource health OK
    loki               inside lgtm        -     -      UP           datasource health OK
    tempo              inside lgtm        -     -      UP           datasource health OK
    pyroscope          inside lgtm        -     -      UP           datasource health OK
    otlp-collector     inside lgtm        4318  -      UP           accepted an empty OTLP export
RESULT: healthy, all 15 checks passed
```

What `up` and `down` guarantee:

- **A taken port stops `up` before anything starts**, naming the holder and the variable that moves
  the port (`port 6379 (redis) is held by container merchant-svc-redis-1 (compose project
  merchant-svc); set REDIS_PORT to a free port`). A bounce would not free it, so none is tried.
- **`up` on a running fleet** reports `fleet already up and healthy` and changes nothing; if any row is
  not healthy it prints the table and bounces the whole stack (`down`, then a fresh start).
- **A fresh start that does not come up healthy** (a container, a JVM, the routing check or a backend
  inside lgtm) is bounced exactly once. A second failure exits 1, prints the table and leaves the
  stack running for diagnosis. A failed build never bounces.
- **`down` verifies**: a JVM that ignores SIGTERM for 30 s is killed, then no fleet port may still be
  held and no compose container may remain, or it exits 1 naming what survived.

`FLEET_SETTLE_SECONDS` (default 90) is how long `up` waits for lgtm's backends after the container
reports healthy. The sweep test [docs/fleet/verification/](docs/fleet/verification/) drives every one
of these outcomes against a real fleet and asserts each on its exit code and its words: 75 checks
passed, 0 failed, on 2026-10-13
([run log](docs/fleet/verification/2026-10-13-fleet-sweep-run.log)).

### Without zsh

The same steps by hand, in any Portable Operating System Interface (POSIX) shell from the repository root:

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
`docker compose stop lgtm; kill $(cat .fleet/*.pid); docker compose down` (lgtm first, so a deliberate stop never pages).

## Demo: deposit, quote, convert, read back

With the fleet up. Merchant ids are universally unique identifiers (UUIDs) and amounts are decimal strings. A fresh merchant id makes
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

Where the numbers come from: the stand-in rate for South African rand (ZAR) to Tether (USDT) is 0.054254. The 50 basis-point (bps) spread gives
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
browsable in the Swagger user interface (UI) below.

## API reference

With the fleet up, **http://localhost:8080/swagger-ui.html** is one Swagger UI over both public
specs, picked from "Select a definition": `ledger` (balances, entries) and `conversion` (quotes,
conversions). Its server is the gateway, so "Try it out" goes through the route table, the rate
limiter and the proof header like any client call. Every operation lists its success body with the
demo's values and each error as a Request for Comments (RFC) 9457 problem with the exact `code` values it can carry.

| Spec | Where |
|---|---|
| ledger public | `GET localhost:8080/v3/api-docs/ledger` (the gateway fetches ledger-svc's `/v3/api-docs/public`) |
| conversion public | `GET localhost:8080/v3/api-docs/conversion` |
| ledger internal (deposits, conversion postings) | `GET localhost:8081/v3/api-docs/internal`, on the service only: never routed, the gateway answers 404 |

The doc routes are not merchant routes, so they are not rate limited; each is one literal path, GET
only. Straight to a service, the public spec answers 403 `GATEWAY_REQUIRED` without the proof header.

The committed snapshots in [docs/openapi](docs/openapi) are what `check` compares the running specs
with. After changing a controller, a data transfer object (DTO), a spec annotation or an error code, regenerate and commit them:

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
| lgtm | 3000, 4317, 4318 | Grafana, Prometheus, Loki, Tempo, Pyroscope and an OpenTelemetry Protocol (OTLP) collector |

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
├── fleet.sh                up | status | down; its sweep test is docs/fleet/verification/
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
| `POSTGRES_PORT` | 5432 | Host port for Postgres, and the port in both services' default Java Database Connectivity (JDBC) Uniform Resource Locator (URL) |
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
policy: quote time to live (TTL), posting grace, sweeper timing, rate-service timeouts and freshness, jump limit,
ledger client timeouts, currency scales, per-pair band and spread) and `gateway-svc.yml` (the route table with its path rewrite and proof
header, the two Swagger UI spec routes, the proxy read timeout, and the rate limits and Redis connection).

## Troubleshooting

| Symptom | Fix |
|---|---|
| `up` says `port 5432 (postgres) is held by ...` | `POSTGRES_PORT=55432 ./fleet.sh up` (or set it in `.env`); the services follow it |
| `up` says `port 6379 (redis) is held by ...` | `REDIS_PORT=6390 ./fleet.sh up` (or set it in `.env`); the gateway follows it |
| `up` says `port 8081 (ledger-svc) is held by process ...` | A JVM from an earlier run, or another program, holds a service port: stop it (the line gives its process id) |
| `up` says `fleet still not healthy after one bounce` | The table above that line names each failing row and why. The stack is left running for diagnosis; `./fleet.sh down` when done |
| `down` says `fleet NOT fully down` | The lines below it name the port holder or container that survived; stop it before the next `up` |
| Every merchant call answers 503 `RATE_LIMITER_UNAVAILABLE` | Redis is not running: `docker compose up -d --wait redis` |
| Edited `01-databases.sql`, nothing changed | The init script runs only on an empty volume: `docker compose down -v`, then `./fleet.sh up` (this wipes the data) |
| A service is not healthy after 90 s | Read `.fleet/<svc>.log` |
| Build fails in ledger, conversion or gateway tests | Docker is not running (Testcontainers) |
| Quotes answer 503 `RATE_UNAVAILABLE` | The `wiremock` container is not running: `docker compose up -d --wait wiremock` |
| Quote accepted after 15 s answers 409 `QUOTE_EXPIRED` | Expected: re-quote and accept within 15 s |
| Port 3000, 4317 or 4318 already in use | Stop the other Grafana or collector; these ports are fixed |
| A dashboard shows no data right after `up` | Metrics export every 10 s; wait one step |
| `./fleet.sh status` shows any row not `UP` or `HEALTHY` | `./fleet.sh up` bounces the whole stack once; the row's DETAIL says what failed |
| `./fleet.sh status` shows a container `UNHEALTHY` | Read `docker compose logs <name>` |
| `./fleet.sh status` shows a backend `DOWN` with a datasource message | That backend inside lgtm is not answering: read `docker compose logs lgtm` |

Run state (pids and logs) lives in `.fleet/`, which is gitignored.
