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

## Prerequisites

| Need | Notes |
|---|---|
| JDK 25 | `.sdkmanrc` pins `java=25-tem`; with sdkman run `sdk env install` |
| Docker, running | Postgres, WireMock and Grafana LGTM for the fleet; Testcontainers and the secrets scanners during the build |
| zsh and curl | `./fleet.sh` is a zsh script (default shell on macOS). Without zsh, use the manual commands below |
| Free ports | 5432 (Postgres), 8089 (WireMock), 3000, 4317, 4318 (Grafana LGTM), 8080, 8081, 8082, 8761, 8888 |

No Gradle install is needed: the repository ships the Gradle 9.8.1 wrapper. Stack: Java 25, Spring
Boot 4.1.1, Spring Cloud 2025.1.3.

## Build and test

```sh
./gradlew clean build
```

This runs every test tier (unit, Spring integration on Testcontainers, Cucumber acceptance over real
HTTP), the static analysis and coverage gates, and a secrets scan (about 151 s on an 18-core laptop with dependencies and images cached; the
first run also downloads them).
Test counts are in [SOLUTION.md](SOLUTION.md#1-tasks-chosen-and-status); the tiers and gates are
described in [docs/quality-gates.md](docs/quality-gates.md).

## Run the fleet

```sh
./fleet.sh up       # Postgres + WireMock, bootJar, config-svc and eureka-svc first, then the other three together,
                    # each waited until healthy, then a routing check, then Grafana LGTM
./fleet.sh status   # port and /actuator/health HTTP code per service (000 = not answering)
./fleet.sh down     # stop the JVMs, then docker compose down (data volumes are kept)
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
docker compose up -d --wait postgres wiremock
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
curl -s -X POST localhost:8089/__admin/mappings -d \
  '{"priority":1,"request":{"urlPath":"/rates"},"response":{"status":200,"fixedDelayMilliseconds":3000,"body":"{}"}}'
                                                # a slow rate feed: the next quote is refused with 503 RATE_UNAVAILABLE in about 2 s
curl -s -X POST localhost:8089/__admin/mappings/reset   # back to the committed rates
kill $(cat .fleet/conversion-svc.pid)           # then a quote through the gateway answers 503 UPSTREAM_UNAVAILABLE
./fleet.sh down && ./fleet.sh up                # restore the fleet afterwards
```

Every endpoint and error code is in [ARCHITECTURE.md section 6](ARCHITECTURE.md#6-api-summary).

## Services

| Service | Port | Role |
|---|---|---|
| gateway-svc | 8080 | The single client-facing entry: Spring Cloud Gateway (Server WebMVC), routes by registry name |
| ledger-svc | 8081 | Task 1: balances, internal deposits, double-entry journal. Own database `ledger` |
| conversion-svc | 8082 | Task 2: quotes and conversions. Own database `conversion` |
| config-svc | 8888 | Spring Cloud Config Server, native backend |
| eureka-svc | 8761 | Eureka service registry (dashboard at http://localhost:8761) |
| wiremock | 8089 | Stand-in for the exchange-rate service (`docker/wiremock/mappings/rates.json`) |
| lgtm | 3000, 4317, 4318 | Grafana, Prometheus, Tempo, Loki and an OTLP collector |

Each service port is defined once, in that service's `src/main/resources/application.yml` as
`${SERVER_PORT:<n>}`; `fleet.sh` reads it from there.

## Repository layout

```
ezeebit-wallet/
├── README.md, ARCHITECTURE.md, SOLUTION.md
├── docs/                   quality gates, supply chain, CI, observability
├── build.gradle            shared build conventions for every service
├── settings.gradle         the five service projects
├── gradle.properties       Spring Boot, Spring Cloud, quality-tool and override versions; Paketo pins
├── config/                 checkstyle/checkstyle.xml and spotbugs/exclude.xml, read by every service
├── security/               scanners.lock, trivy-ignore.yaml, scan.sh and report.py: the supply-chain gate
├── .circleci/config.yml    the CI job: the same Gradle commands as a laptop
├── compose.yaml            Postgres 18.6 (alpine) with both databases; WireMock rate service; Grafana LGTM
├── docker/lgtm/            dashboards, alert rules, Slack route, container start script
├── docker/wiremock/mappings/   the assumed exchange-rate service: one rate per supported pair
├── docker/postgres/initdb/01-databases.sql   role + database per service, CONNECT revoked from PUBLIC
├── observability/          OTLP settings, logback appender wiring; compiled into every service
├── fleet.sh                up | status | down
└── services/
    ├── config-svc/         Config Server; served/*.yml is the fleet's configuration
    ├── eureka-svc/         service registry
    ├── gateway-svc/        edge routing; api/advice/UpstreamUnavailableAdvice maps upstream failures to 503/504
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

An upstream that is down or refuses the connection answers **503 `UPSTREAM_UNAVAILABLE`**; one that
resets or gives no answer within 10 s answers **504 `UPSTREAM_OUTCOME_UNKNOWN`**, because the
request may have been processed. Problem details name no service.

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
| `SLACK_WEBHOOK_URL` | empty (no Slack) | Slack incoming webhook for alerts. A secret: `.env` only |

`DB_URL`, `DB_USER`, `DB_PASSWORD` and `SERVER_PORT` carry the same name in every service: set them
per process when starting a service by hand, not in `.env`, or every service picks up the same value.

Served configuration lives in `services/config-svc/src/main/resources/served/`: `application.yml`
(shared by all clients), `ledger-svc.yml` (datasource), `conversion-svc.yml` (datasource and pricing
policy: quote TTL, posting grace, sweeper timing, rate-service timeouts and freshness, jump limit,
currency scales, per-pair band and spread) and `gateway-svc.yml` (the route table and proxy read
timeout).

## Troubleshooting

| Symptom | Fix |
|---|---|
| Port 5432 already in use | `POSTGRES_PORT=55432 ./fleet.sh up` (or set it in `.env`); the services follow it |
| Edited `01-databases.sql`, nothing changed | The init script runs only on an empty volume: `docker compose down -v`, then `./fleet.sh up` (this wipes the data) |
| A service is not healthy after 90 s | Read `.fleet/<svc>.log` |
| Build fails in ledger or conversion tests | Docker is not running (Testcontainers) |
| Quotes answer 503 `RATE_UNAVAILABLE` | The `wiremock` container is not running: `docker compose up -d --wait wiremock` |
| Quote accepted after 15 s answers 409 `QUOTE_EXPIRED` | Expected: re-quote and accept within 15 s |
| Port 3000, 4317 or 4318 already in use | Stop the other Grafana or collector; these ports are fixed |
| A dashboard shows no data right after `up` | Metrics export every 10 s; wait one step |

Run state (pids and logs) lives in `.fleet/`, which is gitignored.

## Further reading

- [SOLUTION.md](SOLUTION.md): tasks chosen, design and trade-offs, time and scope, how Tasks 3 to 6
  fit, known gaps, AI usage notes.
- [ARCHITECTURE.md](ARCHITECTURE.md): how the design was derived, data model, flows, API, failure
  handling.
- [docs/quality-gates.md](docs/quality-gates.md): test tiers, SAST and coverage.
- [docs/supply-chain.md](docs/supply-chain.md): secrets, images, SBOMs, how to read the console.
- [docs/ci.md](docs/ci.md): the CircleCI pipeline (not yet run).
- [docs/observability.md](docs/observability.md): dashboards, business metrics, alerts, rehearsal
  timings.
