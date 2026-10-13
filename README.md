# ezeebit-wallet

Ezeebit backend take-home. The chosen tasks are **Task 1** (hold a merchant's money across
currencies) and **Task 2** (convert one currency into another). Status: a runnable skeleton. Five
Spring Boot services build, test, boot, register and route through one gateway, each domain
service owns its own PostgreSQL database, and Liquibase runs on boot. The business endpoints
(internal deposits, balances, statement, quotes, conversions) are designed in
[ARCHITECTURE.md](ARCHITECTURE.md) and **not yet implemented**. Design, trade-offs, the remaining
tasks and AI usage notes are in [SOLUTION.md](SOLUTION.md).

Walkthrough video: link to be added.

## Prerequisites

| Need | Notes |
|---|---|
| JDK 25 | `.sdkmanrc` pins `java=25-tem`; with sdkman run `sdk env install` |
| Docker, running | Postgres and Grafana LGTM for the fleet, and Testcontainers during the build |
| Free ports | 5432 (Postgres), 3000, 4317, 4318 (Grafana LGTM), 8080, 8081, 8082, 8761, 8888 |
| zsh, curl | used by `fleet.sh` |

No Gradle install is needed: the repository ships one wrapper at the root (Gradle 9.8.1).
Stack: Spring Boot 4.1.1, Spring Cloud 2025.1.3.

## Build and test

```sh
./gradlew clean build
```

Runs 6 tests in 5 suites. The ledger and conversion tests start a `postgres:18.6-alpine`
container through Testcontainers, so Docker must be running. Tests boot each service alone, with
the config server and the registry switched off, and with OTLP export switched off (no collector
is needed).

## Run the fleet

```sh
./fleet.sh up       # Postgres, bootJar, Grafana LGTM, then every service in order, each waited until healthy
./fleet.sh status   # port and /actuator/health HTTP code per service (000 = not answering)
./fleet.sh down     # stop the JVMs, then docker compose down (the data volumes are kept)
```

`up` starts Postgres (`docker compose up -d --wait postgres`), builds the jars, starts Grafana
LGTM (`docker compose up -d --wait lgtm`), then starts the control plane first (config-svc, then eureka-svc), then ledger-svc, conversion-svc and
gateway-svc. Each service is waited on for up to 90 s (a real deadline) to report healthy.
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

With the fleet up:

```sh
curl -i localhost:8080/api/v1/merchants/m1/balances      # 404 from ledger-svc: routed, endpoint not built yet
curl -i localhost:8080/api/v1/merchants/m1/quotes        # 404 from conversion-svc, same reason
curl -i localhost:8080/internal/v1/deposits              # 404 from the gateway: unrouted
curl -s localhost:8888/ledger-svc/default                # config that config-svc serves to ledger-svc
```

Stop a domain service (`kill $(cat .fleet/conversion-svc.pid)`) and its routes answer **503**
from the gateway instead of 500, whether the service has left the registry or is still listed but
refusing connections. Any other upstream I/O failure (connection reset, timeout) answers **504**
"Upstream outcome unknown: retry with the same Idempotency-Key", because the request may have
reached the service. Problem details are fixed strings and name no service. The sibling service's
routes keep answering. Registry fetch and response cache are tuned to 5 seconds, so a change is
visible within a few seconds.

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

On `up`, ServiceDown is briefly Pending while the JVMs boot (about 30 s, measured) and clears once
each service exports; it fires only after 1 min. Notifications go to Slack only when
`SLACK_WEBHOOK_URL` is set in the root `.env` (gitignored, never committed). The container installs the Slack contact point at
start only in that case, because Grafana refuses to start on a Slack contact point with an empty
URL; without it the route is removed again and alerts stay visible in Grafana only. The URL is
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
| `CONFIG_SERVER_URL` | http://localhost:8888 | Where ledger, conversion and gateway fetch config |
| `CONFIG_IMPORT_OPTIONALITY` | empty (config-svc mandatory) | Set to `optional:` to start ledger, conversion or gateway without config-svc |
| `CONFIG_SEARCH_LOCATIONS` | `classpath:/served` | Where config-svc reads the configuration it serves |
| `EUREKA_URL` | http://localhost:8761/eureka/ | Registry address for the clients |
| `OTLP_URL` | http://localhost:4318 | OTLP/HTTP base URL for metrics, traces and logs |
| `SLACK_WEBHOOK_URL` | empty (no Slack) | Slack incoming webhook for alert notifications. A secret: `.env` only |

`DB_URL`, `DB_USER`, `DB_PASSWORD` and `SERVER_PORT` carry the same name in every service. Set
them per process when starting a service by hand, not in `.env` for the whole fleet, or every
service picks up the same value.

Served configuration lives in `services/config-svc/src/main/resources/served/`:
`application.yml` (shared by all clients: registry address, 5 s registry fetch, actuator
`health,info`), `ledger-svc.yml` and `conversion-svc.yml` (datasources), `gateway-svc.yml` (the
route table). ledger, conversion and gateway need config-svc at boot; config-svc and eureka-svc
depend on nothing.

## Repository layout

```
ezeebit-wallet/
├── build.gradle            shared build conventions for every service
├── settings.gradle         the five service projects
├── gradle.properties       Spring Boot and Spring Cloud versions
├── compose.yaml            Postgres 18.6 (alpine) with both databases; Grafana LGTM
├── docker/lgtm/            dashboards, alert rules, Slack route, container start script
├── observability/          OTLP settings, logback appender wiring; compiled into every service
├── docker/postgres/initdb/01-databases.sql   role + database per service, CONNECT revoked from PUBLIC
├── fleet.sh                up | status | down
└── services/
    ├── config-svc/         Config Server; served/*.yml is the fleet's configuration
    ├── eureka-svc/         service registry
    ├── gateway-svc/        edge routing; api/advice/UpstreamUnavailableAdvice maps upstream failures to 503/504
    ├── ledger-svc/         Task 1; db/changelog/db.changelog-master.xml (no changesets yet)
    └── conversion-svc/     Task 2; db/changelog/db.changelog-master.xml (no changesets yet)
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
| Port 3000, 4317 or 4318 already in use | Stop the other Grafana or collector; these ports are fixed |
| A dashboard shows no data right after `up` | Metrics export every 10 s; wait one step |

Run state (pids and logs) lives in `.fleet/`, which is gitignored.

## Not here yet

The business endpoints and their database schema. Designs are in
[ARCHITECTURE.md](ARCHITECTURE.md); the plan for the rest is in [SOLUTION.md](SOLUTION.md).
