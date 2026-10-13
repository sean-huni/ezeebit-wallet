# ezeebit-wallet

Ezeebit backend take-home. The chosen tasks are **Task 1** (hold a merchant's money across
currencies) and **Task 2** (convert one currency into another). Status: **both are built and
tested.** The ledger takes internal deposits into a double-entry journal and serves balances and a
statement through the gateway; conversion-svc quotes a firm price from the exchange-rate service and
converts at exactly that price through one ledger transaction. Five Spring Boot services build,
test, boot, register and route through one gateway, and each domain service owns its own
PostgreSQL database. The exchange-rate service is assumed to exist; locally a WireMock container
stands in for it. Design, trade-offs, the remaining tasks and AI usage notes are in
[SOLUTION.md](SOLUTION.md).

Walkthrough video: link to be added.

## Prerequisites

| Need | Notes |
|---|---|
| JDK 25 | `.sdkmanrc` pins `java=25-tem`; with sdkman run `sdk env install` |
| Docker, running | Postgres and Grafana LGTM for the fleet, and Testcontainers during the build |
| Free ports | 5432 (Postgres), 8089 (WireMock rate service), 3000, 4317, 4318 (Grafana LGTM), 8080, 8081, 8082, 8761, 8888 |
| zsh, curl | used by `fleet.sh` |

No Gradle install is needed: the repository ships one wrapper at the root (Gradle 9.8.1).
Stack: Spring Boot 4.1.1, Spring Cloud 2025.1.3.

## Build and test

```sh
./gradlew clean build
```

`build` runs every tier. ledger-svc and conversion-svc have three, wired as a shared convention in
the root `build.gradle` (a service opts in by being added to its `tieredServices` list):

| Gradle task | Source | What it is | ledger-svc | conversion-svc |
|---|---|---|---|---|
| `test` | `src/test` | unit tests: domain (money, pricing, rate rules, quote expiry, state machine), mapping, i18n bundle; no Spring context, no Docker | 50 | 81 |
| `integrationTest` | `src/acceptanceTest` (JUnit) | Spring Boot on Testcontainers Postgres: migrations (including a re-run on an existing schema), each DB constraint proven by the row it refuses; for conversion-svc also the Eureka client wiring; for ledger-svc also that it refuses connections on the LAN address | 16 | 11 |
| `acceptanceTest` | `src/acceptanceTest` (Cucumber 8.0.4, `features/*.feature`) | business scenarios over real HTTP, some ending in a SQL check that the books balance | 57 scenarios | 40 scenarios |

The other three services have one `test` task each (4 tests). Total: 259 tests. The Spring tiers
start `postgres:18.6-alpine` containers through Testcontainers, so Docker must be running. They run
under the `acceptance` profile and boot each service alone, with the config server, the registry and
OTLP export switched off. conversion-svc's acceptance tier also runs the **real ledger-svc jar** as
a separate process on its own database, puts an in-process WireMock (`wiremock-standalone` 3.13.2)
in front of it to inject ledger faults, and serves the rate feed from the same mapping files compose
mounts (`docker/wiremock/mappings/`). Run one tier with `./gradlew :services:ledger-svc:acceptanceTest`
(or `test`, `integrationTest`; same for conversion-svc); Cucumber's own reports land in
`services/<svc>/build/reports/cucumber/`.

## Run the fleet

```sh
./fleet.sh up       # Postgres, bootJar, Grafana LGTM, then every service in order, each waited until healthy
./fleet.sh status   # port and /actuator/health HTTP code per service (000 = not answering)
./fleet.sh down     # stop the JVMs, then docker compose down (the data volumes are kept)
```

`up` starts Postgres and the WireMock rate service (`docker compose up -d --wait postgres wiremock`), builds the jars, starts the control
plane first (config-svc, then eureka-svc), then ledger-svc, conversion-svc and gateway-svc, and
finally Grafana LGTM (`docker compose up -d --wait lgtm`). Each service is waited on for up to 90 s (a real deadline) to report healthy. `up` then waits (up to 60 s) until the gateway actually routes to ledger-svc and conversion-svc, because a healthy service is only routable once the gateway has fetched the registry.
`up` refuses to start when a fleet from `.fleet/*.pid` is still running. Expected tail of `up`:

```
  config-svc UP on :8888
  eureka-svc UP on :8761
  ledger-svc UP on :8081
  conversion-svc UP on :8082
  gateway-svc UP on :8080
fleet up: gateway http://localhost:8080, registry http://localhost:8761, grafana http://localhost:3000
```

If `up` stops part way, or refuses because a fleet is already running, run `./fleet.sh down`
before retrying so no JVM keeps a port.

## Services

| Service | Port | Role |
|---|---|---|
| gateway-svc | 8080 | The single client-facing entry. Spring Cloud Gateway (Server WebMVC), routes by registry name |
| ledger-svc | 8081 | Task 1: balances, internal deposits, double-entry journal. Own database `ledger` |
| conversion-svc | 8082 | Task 2: quotes and conversions. Own database `conversion` |
| config-svc | 8888 | Spring Cloud Config Server, native backend |
| eureka-svc | 8761 | Eureka service registry (dashboard at http://localhost:8761) |

Each port is defined once, in that service's `src/main/resources/application.yml` as
`${SERVER_PORT:<n>}`; `fleet.sh` reads the default from there and ignores a `SERVER_PORT`
environment variable.

## Gateway routes and what you can call today

Only these paths reach a service; everything else is answered by the gateway itself.

| Route | Upstream |
|---|---|
| `/api/v1/merchants/*/balances/**`, `/entries/**` | ledger-svc |
| `/api/v1/merchants/*/quotes/**`, `/conversions/**` | conversion-svc |
| `/internal/**` | never routed (service-to-service only) |

Deposits are not merchant-facing: a merchant must not be able to credit itself. The deposit
operation is `POST /internal/v1/deposits` on ledger-svc, called by the money-in rail or chain
listener, and is never routed by the gateway. Locally it is exercised against ledger-svc on :8081
directly.

With the fleet up (merchant ids are UUIDs; amounts are decimal strings):

```sh
M=4402340a-8ce5-4052-816a-a1ff6170b086

# Deposit (the rail's call, straight to ledger-svc). Same key and body again: 201, identical body, credited once.
curl -s -X POST localhost:8081/internal/v1/deposits \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: rail-transfer-1' \
  -d "{\"merchantId\":\"$M\",\"currency\":\"ZAR\",\"amount\":\"1500.00\",\"reference\":\"rail transfer 1\"}"

curl -s localhost:8080/api/v1/merchants/$M/balances                  # {"data":[{"currency":"ZAR","balance":"1500.00"}]}
curl -s "localhost:8080/api/v1/merchants/$M/entries?currency=ZAR"    # statement, each entry with balanceAfter
curl -i localhost:8080/internal/v1/deposits                          # 404 from the gateway: unrouted
curl -s localhost:8888/ledger-svc/default                            # config that config-svc serves to ledger-svc
```

Ledger answers and errors:

| Call | Success | Errors (RFC 9457 problem detail with a stable `code`) |
|---|---|---|
| `POST /internal/v1/deposits` | 201 `{"data":{journalId, merchantId, currency, amount, balanceAfter, reference, created}}`; a replay returns the same body | 400 `AMOUNT_SCALE_EXCEEDED`, `AMOUNT_NOT_POSITIVE`, `AMOUNT_TOO_LARGE`, `UNKNOWN_CURRENCY`, `IDEMPOTENCY_KEY_MISSING`, `VALIDATION_FAILED` (with `errors[]` naming the field: a missing or non-UUID `merchantId`, an `amount` sent as a JSON number, a control character in `reference` or `Idempotency-Key`), `MALFORMED_REQUEST` (not JSON); 422 `IDEMPOTENCY_KEY_REUSED`, `BALANCE_LIMIT_EXCEEDED` (the balance would pass 20 digits before the point) |
| `GET /api/v1/merchants/{id}/balances` | 200 `{"data":[{currency, balance}]}`, empty for a merchant that never received money | 400 `VALIDATION_FAILED` (id not a UUID) |
| `GET /api/v1/merchants/{id}/entries?currency=&from=&to=&limit=&after=` | 200 `{"data":{entries:[...], nextCursor}}`; `from` inclusive, `to` exclusive (ISO-8601, years 1970 to 9999, `from` before `to`), `limit` 1 to 500 (default 100), pass `nextCursor` back as `after` | 400 `UNKNOWN_CURRENCY`, `VALIDATION_FAILED` (with `errors[]`) |

Amounts with more decimals than the currency allows (ZAR, NGN, KES: 2; USDT, USDC: 6) are
refused, never rounded. An unsupported version on ledger-svc itself (`/api/v2/...`) is 400
`UNSUPPORTED_API_VERSION`; through the gateway it is 404, because the route table publishes v1 only.

Stop a domain service (`kill $(cat .fleet/conversion-svc.pid)`) and its routes answer **503**
from the gateway instead of 500, whether the service has left the registry or is still listed but
refusing connections. Any other upstream I/O failure (connection reset, timeout) answers **504**
"Upstream outcome unknown: retry with the same Idempotency-Key", because the request may have
reached the service. Problem details are fixed strings and name no service. The sibling service's
routes keep answering. Registry fetch and response cache are tuned to 5 seconds, so a change is
visible within a few seconds.

## Quote and convert

A conversion is two calls through the gateway: a quote (a firm price for 15 s), then accepting it.
With the fleet up and the deposit above made:

```sh
# Quote: sell exactly 1000.00 ZAR for USDT (or give "buyAmount" to fix what is received instead).
curl -s -X POST localhost:8080/api/v1/merchants/$M/quotes -H 'Content-Type: application/json' \
  -d '{"sell":"ZAR","buy":"USDT","sellAmount":"1000.00"}'
# {"data":{"quoteId":"...","sellCurrency":"ZAR","sellAmount":"1000.00","buyCurrency":"USDT",
#  "buyAmount":"53.982730","rate":"0.054254","appliedRate":"0.05398273","spreadBps":50,
#  "rateTimestamp":"...","expiresAt":"..."}}

Q=<quoteId from the answer>
# Accept within 15 s. Same key again: the same answer, converted once. Same quote, another key: 409.
curl -s -X POST localhost:8080/api/v1/merchants/$M/conversions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: convert-1' -d "{\"quoteId\":\"$Q\"}"
# 201 {"data":{"conversionId":"...","status":"COMPLETED","sellAmount":"1000.00","buyAmount":"53.982730",...}}

curl -s localhost:8080/api/v1/merchants/$M/conversions/<conversionId>   # current state
curl -s localhost:8080/api/v1/merchants/$M/balances                    # USDT 53.982730, ZAR 0.00
```

Conversion answers and errors:

| Call | Success | Errors (problem detail with a stable `code`) |
|---|---|---|
| `POST /api/v1/merchants/{id}/quotes` `{sell, buy, sellAmount \| buyAmount}` | 201 quote | 400 `SAME_CURRENCY`, `UNSUPPORTED_PAIR`, `UNKNOWN_CURRENCY`, `AMOUNT_SCALE_EXCEEDED`, `AMOUNT_NOT_POSITIVE`, `AMOUNT_TOO_LARGE` (the fixed or the computed side would pass 20 digits), `AMOUNT_TOO_SMALL` (rounding would move the rate more than 1%), `VALIDATION_FAILED` (both or neither amount, `errors[]` names `sellAmount`); 502 `RATE_REJECTED` with `reason` (`RATE_NOT_POSITIVE`, `RATE_STALE`, `RATE_OUT_OF_BAND`, `RATE_JUMP`, `RATE_MALFORMED`); 503 `RATE_UNAVAILABLE` (timeout, refused connection, error status) |
| `POST /api/v1/merchants/{id}/conversions` `{quoteId}` + `Idempotency-Key` | 201 COMPLETED; 202 PENDING (the ledger's answer did not arrive; the sweeper settles it) | 400 `IDEMPOTENCY_KEY_MISSING`, `VALIDATION_FAILED`; 404 `QUOTE_NOT_FOUND` (including another merchant's quote); 409 `QUOTE_EXPIRED`, `QUOTE_ALREADY_USED`, `CONVERSION_REJECTED` (with `reason`: `INPUT_REFUSED`, `DEADLINE_PASSED`, `VOIDED`); 422 `INSUFFICIENT_FUNDS` or `BALANCE_LIMIT_EXCEEDED` (with `conversionId`; the ledger has recorded the refusal, so it is final), `IDEMPOTENCY_KEY_REUSED` |
| `GET /api/v1/merchants/{id}/conversions/{conversionId}` | 200 with `status` PENDING, COMPLETED or REJECTED and a `reason` when rejected | 404 `CONVERSION_NOT_FOUND` |

## The exchange-rate service (WireMock)

The brief says the rate service already exists. Its contract is an assumption, written down once in
ARCHITECTURE.md section 5: `GET /rates?base=ZAR&quote=USDT` answers
`{"base":"ZAR","quote":"USDT","rate":"0.054254","timestamp":"<ISO-8601 instant>"}`. Locally the
compose service `wiremock` (`wiremock/wiremock:3.13.2-3`, bound to 127.0.0.1:8089) serves it from
`docker/wiremock/mappings/rates.json`: one fixed rate per supported pair (ZAR, NGN and KES against
USDT and USDC, both directions), stamped with the current time on every answer, and 404 for any
other pair.

```sh
curl -s 'localhost:8089/rates?base=ZAR&quote=USDT'
# Script a fault for a live demo (WireMock admin API; a priority-1 stub beats the committed ones):
curl -s -X POST localhost:8089/__admin/mappings -d \
  '{"priority":1,"request":{"urlPath":"/rates"},"response":{"status":200,"fixedDelayMilliseconds":3000,"body":"{}"}}'
curl -s -X POST localhost:8089/__admin/mappings/reset          # back to the committed mappings
```

## Observability

`compose.yaml` runs `grafana/otel-lgtm:0.36.0`: Grafana, Prometheus, Tempo, Loki and an
OpenTelemetry collector in one container; metrics, traces and logs persist in the `lgtmdata`
volume. Grafana's own database is on tmpfs and resets on every start by design: dashboards, alert
rules and the contact point re-provision from files, and no stale alert state survives a restart
to page on the next boot. Every service exports
metrics (every 10 s), traces (100% sampled) and logs over OTLP/HTTP to `localhost:4318`. Grafana
is at http://localhost:3000 with **anonymous Admin access: local use only**.

Folder **Ezeebit** holds four provisioned dashboards (read-only, from `docker/lgtm/dashboards/`):

| Dashboard | Shows |
|---|---|
| Fleet Overview | UP/DOWN tile per service, request rate, 5xx ratio, p95 latency, JVM heap |
| Traces | latest traces for the selected services; click a trace ID to open it |
| Logs | logs by service and level; a line logged inside a request carries its `trace_id` |
| Alerts | the two alert rules below, in every state |

Each JVM is its own series: the OpenTelemetry resource attribute `service.instance.id` is
`${HOSTNAME:localhost}:<port>` (Prometheus label `instance`), and `service.name` is the
application name (label `job`). The shared OTLP settings live in `observability/`, which the root
`build.gradle` compiles into every service jar.

Alert rules (`docker/lgtm/alerting/rules.yaml`, Grafana-managed):

| Rule | Fires when |
|---|---|
| ServiceDown | a service has exported no metrics for 30 s, for 1 min; one alert per service |
| HighErrorRate | more than 5% of a service's requests answer 5xx over 1 min, for 2 min |

`fleet.sh` starts lgtm only after every service is healthy and stops it first on `down`, so
neither booting nor a deliberate stop can page. Notifications go to Slack only when
`SLACK_WEBHOOK_URL` is set in the root `.env` (gitignored, never committed). The container installs the Slack contact point at
start only in that case, because Grafana refuses to start on a Slack contact point with an empty
URL; without it no contact point exists and alerts stay visible in Grafana only. The URL is
expanded by Grafana from its environment and is never written to a file.

## Configuration

Committed defaults run a clean clone with no `.env`. To override, export variables in the shell
or put them in an optional root `.env` (gitignored); `fleet.sh` sources it, and
`docker compose` reads it too.

| Variable | Default | Effect |
|---|---|---|
| `POSTGRES_PORT` | 5432 | Host port for Postgres, and the port in both services' default JDBC URL |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | postgres / postgres | Postgres superuser in the container |
| `DB_URL` | `jdbc:postgresql://localhost:${POSTGRES_PORT}/<ledger or conversion>` | JDBC URL |
| `DB_USER`, `DB_PASSWORD` | `ledger`/`ledger`, `conversion`/`conversion` | Database credentials. The roles are created in `docker/postgres/initdb/01-databases.sql`: an override needs a matching change there plus `docker compose down -v` |
| `SERVER_PORT` | per service, see above | HTTP port of one service started by hand; `fleet.sh` ignores it |
| `SERVER_ADDRESS` | 127.0.0.1 | Address every service binds and registers in Eureka. Loopback on purpose: `/internal/**` moves money and has no authentication. Production needs network policy or mTLS on `/internal/**` (ARCHITECTURE.md section 5) |
| `CONFIG_SERVER_URL` | http://localhost:8888 | Where ledger, conversion and gateway fetch config |
| `CONFIG_IMPORT_OPTIONALITY` | empty (config-svc mandatory) | Set to `optional:` to start ledger, conversion or gateway without config-svc |
| `CONFIG_SEARCH_LOCATIONS` | `classpath:/served` | Where config-svc reads the configuration it serves |
| `RATES_PORT` | 8089 | Host port of the WireMock rate service, and the port in conversion-svc's default rate URL |
| `RATES_URL` | `http://localhost:${RATES_PORT}` | Base URL of the exchange-rate service conversion-svc calls |
| `EUREKA_URL` | http://localhost:8761/eureka/ | Registry address for the clients |
| `OTLP_URL` | http://localhost:4318 | OTLP/HTTP base URL for metrics, traces and logs |
| `SLACK_WEBHOOK_URL` | empty (no Slack) | Slack incoming webhook for alert notifications. A secret: `.env` only |

`DB_URL`, `DB_USER`, `DB_PASSWORD` and `SERVER_PORT` carry the same name in every service. Set
them per process when starting a service by hand, not in `.env` for the whole fleet, or every
service picks up the same value.

Served configuration lives in `services/config-svc/src/main/resources/served/`:
`application.yml` (shared by all clients: registry address, 5 s registry fetch, actuator
`health,info`), `ledger-svc.yml` (datasource), `conversion-svc.yml` (datasource, and the pricing
policy: quote TTL, posting grace, sweeper timing, rate-service timeouts and freshness, jump limit,
currency scales, and per pair its sanity band and spread), `gateway-svc.yml` (the route table). ledger, conversion and gateway need config-svc at boot; config-svc and eureka-svc
depend on nothing.

## Repository layout

```
ezeebit-wallet/
├── build.gradle            shared build conventions for every service
├── settings.gradle         the five service projects
├── gradle.properties       Spring Boot and Spring Cloud versions
├── compose.yaml            Postgres 18.6 (alpine) with both databases; WireMock rate service; Grafana LGTM
├── docker/lgtm/            dashboards, alert rules, Slack route, container start script
├── docker/wiremock/mappings/   the assumed exchange-rate service: one rate per supported pair
├── observability/          OTLP settings, logback appender wiring; compiled into every service
├── docker/postgres/initdb/01-databases.sql   role + database per service, CONNECT revoked from PUBLIC
├── fleet.sh                up | status | down
└── services/
    ├── config-svc/         Config Server; served/*.yml is the fleet's configuration
    ├── eureka-svc/         service registry
    ├── gateway-svc/        edge routing; api/advice/UpstreamUnavailableAdvice maps upstream failures to 503/504
    ├── ledger-svc/         Task 1; changesets in db/changelog/2026/10/; src/acceptanceTest holds the Spring and Cucumber tiers
    └── conversion-svc/     Task 2; changesets in db/changelog/2026/10/; outbound/ holds the rate and ledger clients
```

Each `services/<svc>/build.gradle` lists dependencies only. All services live in one repository
with one build root so the submission builds and runs with one command; they share no database
and no business code, and each ships as its own jar. The one exception is `observability/`
(OTLP settings and the logback appender wiring), compiled into each jar rather than shipped as a
shared library. SOLUTION.md records this trade-off.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Port 5432 already in use | `POSTGRES_PORT=55432 ./fleet.sh up` (or set it in `.env`); the services follow it |
| Edited `01-databases.sql`, nothing changed | The init script runs only on an empty volume: `docker compose down -v`, then `./fleet.sh up` (this wipes the data) |
| A service is not healthy after 90 s | Read `.fleet/<svc>.log` |
| Build fails in ledger or conversion tests | Docker is not running (Testcontainers) |
| Quotes answer 503 `RATE_UNAVAILABLE` | The `wiremock` container is not running: `docker compose up -d --wait wiremock` |
| Port 3000, 4317 or 4318 already in use | Stop the other Grafana or collector; these ports are fixed |
| A dashboard shows no data right after `up` | Metrics export every 10 s; wait one step |

Run state (pids and logs) lives in `.fleet/`, which is gitignored.

## Not here yet

Tasks 3 to 6. How they slot into this design is in [SOLUTION.md](SOLUTION.md); the known gaps of the
built part are in ARCHITECTURE.md section 9.
