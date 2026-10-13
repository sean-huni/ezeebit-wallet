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
| Docker, running | Postgres and Grafana LGTM for the fleet; Testcontainers and the `checkSecrets` scanners during the build; Paketo image builds |
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
| `test` | `src/test` | unit tests: domain (money, pricing, rate rules, quote expiry, state machine), mapping, i18n bundle, business-metric tags and when they are counted; no Spring context, no Docker | 63 | 106 |
| `integrationTest` | `src/acceptanceTest` (JUnit) | Spring Boot on Testcontainers Postgres: migrations (including a re-run on an existing schema), each DB constraint proven by the row it refuses; for conversion-svc also the Eureka client wiring; for ledger-svc also that it refuses connections on the LAN address | 16 | 11 |
| `acceptanceTest` | `src/acceptanceTest` (Cucumber 8.0.4, `features/*.feature`) | business scenarios over real HTTP, some ending in a SQL check that the books balance; `business-metrics.feature` checks that the counters move on the real path | 59 scenarios | 44 scenarios |

The other three services have one `test` task each (4 tests). Total: 303 tests. The Spring tiers
start `postgres:18.6-alpine` containers through Testcontainers, so Docker must be running. They run
under the `acceptance` profile and boot each service alone, with the config server, the registry and
OTLP export switched off. conversion-svc's acceptance tier also runs the **real ledger-svc jar** as
a separate process on its own database, puts an in-process WireMock (`wiremock-standalone` 3.13.2)
in front of it to inject ledger faults, and serves the rate feed from the same mapping files compose
mounts (`docker/wiremock/mappings/`). Run one tier with `./gradlew :services:ledger-svc:acceptanceTest`
(or `test`, `integrationTest`; same for conversion-svc); Cucumber's own reports land in
`services/<svc>/build/reports/cucumber/`.

## Quality gates

`./gradlew build` also runs five static and coverage gates, plus the secrets scan (see Supply-chain gate), configured once in the root
`build.gradle` for every service. Tool versions are pinned in `gradle.properties`.

| Tool | Reads | Fails the build when |
|---|---|---|
| SpotBugs 4.10.5 with Find Security Bugs 1.14.0 (`spotbugsMain`) | compiled main classes, effort max, confidence low | any finding not in `config/spotbugs/exclude.xml` |
| `checkSpotbugsReport` (runs on every build, even when `spotbugsMain` is up to date) | the SpotBugs XML report and task settings | the report does not list Find Security Bugs with `enabled="true"`; it records an analysis error or missing class; no class was analysed; any SpotBugs task sets `visitors` or `omitVisitors` |
| Checkstyle 14.3.0 (`checkstyleMain`, `checkstyleTest`, `checkstyleAcceptanceTest`) | every Java source set, against `config/checkstyle/checkstyle.xml` (tabs, static imports first, `}` alone before `catch`/`else`; main code may not catch `Exception`, `RuntimeException`, `Error` or `Throwable`, tests only `Error` or `Throwable`) | any violation (`maxWarnings` and `maxErrors` are 0) |
| JaCoCo 0.8.15 (`jacocoTestCoverageVerification`) | one report per service, merged over every test tier it runs | line or branch coverage falls below the service's floor in `coverageFloors` |
| `checkCoverageFloors` | the same report | a floor sits more than 5 points below the measured value (raise it to the value the message prints), or a measured counter has no floor |

Every exclusion carries a one-line reason in `config/spotbugs/exclude.xml`. Reports land in
`services/<svc>/build/reports/{spotbugs,checkstyle,jacoco}/`.

SonarQube is opt-in and needs no server to build:

```sh
./gradlew sonarAll                                   # no SONAR_TOKEN: prints SKIPPED and runs nothing (so does `sonar`)
SONAR_TOKEN=... SONAR_HOST_URL=https://sonar.example ./gradlew sonarAll
```

With a token, `sonarAll` runs every service's `check`, then analyses all five as one project with
each module's compile classpath as `sonar.java.libraries`, the merged JaCoCo report and the
Checkstyle reports, and waits for the quality gate (`sonar.qualitygate.wait`). `SONAR_HOST_URL` defaults to `http://localhost:9000`; `SONAR_PROJECT_KEY`
overrides the default key `ezeebit-wallet`.

## Supply-chain gate

Two Gradle tasks, split by cost. Both run the scanners as containers pinned by tag and digest in
`security/scanners.lock`, the only place a scanner version lives.

| Task | Runs in | Reads | Needs |
|---|---|---|---|
| `checkSecrets` | every `./gradlew build` (root `check`) | the git history and the working tree | Docker; no images; under 2 s measured on a warm Gradle daemon |
| `checkSupplyChain` | only when asked | secrets again, then the five images, their SBOMs and the compose and docker files | `bootBuildImageAll`, which it depends on |

```sh
./gradlew bootBuildImageAll          # ezeebit/<svc>:0.0.1-SNAPSHOT, one per service, Paketo only
./gradlew bootBuildImageAll checkSupplyChain
cat build/reports/supply-chain/console.txt   # the console text, saved as printed
```

Images come from Spring Boot's `bootBuildImage` with `BP_JVM_VERSION=25`. The Paketo builder and
its run image are both pinned by tag and digest in `gradle.properties` (`paketoBuilder`,
`paketoRunImage`), which `bootBuildImage` reads;
the repository has no Dockerfile. They are built for the Docker daemon's architecture.

### What each stage does

| Stage | Instrument | Universe |
|---|---|---|
| secrets | gitleaks `git --log-opts="--all --diff-merges=first-parent"` | every commit reachable from any ref, merges included as their diff against the first parent, so a secret added while resolving a merge is read; the console prints the commits gitleaks scanned beside the commits with changes git counts, and a mismatch fails |
| secrets | gitleaks `dir`, then trivy `fs --scanners secret` | tracked files plus untracked files that `.gitignore` does not exclude, copied out first, so `.env` never reaches a scanner |
| images | trivy `image --input` on a `docker save` tarball | the OS packages, JRE, buildpack helpers and jars in each image; the scanner gets no Docker socket |
| sbom | trivy `--format cyclonedx` from the same tarball | one CycloneDX document per image in `build/supply-chain/sbom/` |
| config | trivy `config` | `compose.yaml`, `docker/` and any tracked `Dockerfile*`; reported, never gating |

Both image scans skip `layers/sbom`, where Paketo stores SBOM documents describing packages that
already sit in other layers. Read as a second source, they put the buildpack launcher into a layer
that differs per image and so made it look like this repository's code.

### Reading the console

1. **Stages required by this run.** One line per stage: scanners that recorded a completing exit
   code, and report files present, non-empty and parseable. Anything short of full is `MISSING` and fails the
   gate: a scanner that never ran must not read as one that found nothing.
2. **Secrets.** Findings per instrument, as rule, file and line. Values are redacted (`--redact`
   for gitleaks; trivy masks them) and the console never prints one. Any finding fails.
3. **Images.** `N images, M layers carried by all N`, then per service the distinct findings in
   SERVICE layers and in SHARED layers, and how many of them gate. Layers are attributed by who
   BUILT them, read from each image's `io.buildpacks.lifecycle.metadata` label. SHARED is the run
   image's layers and the buildpack and launcher layers: their findings move when the builder or
   run-image pin moves, so they are reported, not gated. SERVICE is every `app` layer (the
   application and its jars), even when all five images carry an identical one, plus any layer the
   label does not name, so an unattributed layer fails safe. Only SERVICE findings are ours to fix.
   The `layers carried by all N` count is context, not the classification.
4. **SBOM.** Component count per image; 0 components fails.
5. **Misconfiguration.** Its own universe: checks against files, never vulnerabilities in an image,
   so its totals must not be added to the image totals.
6. **Suppressions**, then the verdict.

**The verdict is a three-clause test.** `checkSupplyChain` fails only on a finding that is HIGH or
CRITICAL **and** has a published fix **and** sits in a SERVICE layer. Everything else is reported.
`security/trivy-ignore.yaml` starts with no vulnerability entries. It is read in block form only
(`- id:`, `statement:`, `expired_at:` and nothing else); flow style, an empty `statement`, a
non-ISO date or an expired date fails the gate. trivy itself stops applying an expired entry.

**Evidence goes stale on every new commit.** The history count, the tree and the images describe
the commit they were scanned at; re-run `checkSupplyChain` after any change rather than quoting an
older console.

**State at 2026-10-13 (trivy DB of that day): PASSED, nothing suppressed.** The first run failed
on nine CVEs in jars Spring Boot 4.1.1 manages. They are cleared by patch overrides of Boot's own
properties in the root `build.gradle` (`tomcat.version`, `jackson-bom.version`,
`jackson-2-bom.version`, `freemarker.version`), each pinned in `gradle.properties` with the CVEs it
clears: Tomcat 11.0.26, Jackson 3.1.7 and 2.21.7, FreeMarker 2.3.35 (the Eureka dashboard renders
with it, so it is overridden, not excluded). Drop each override once Boot manages that version or
newer. One SERVICE finding remains and does not gate: `httpclient` 4.5.3, MEDIUM, via the Eureka
client, in three images. The SHARED HIGH findings (OpenSSL in the run image, Go `stdlib` and `x/net`
in buildpack helpers) belong to the builder and run-image pins. The misconfiguration stage ran, and trivy recognised none of the 11 files as a format
it checks (it has no compose checks), so it reports zero checks rather than a clean bill.

### SAST and SCA read different things

- **SCA (trivy)** reads the shipped image: jars, OS packages, the JRE, buildpack binaries. It finds
  **known** CVEs in code nobody here wrote.
- **SAST (SpotBugs with Find Security Bugs, Checkstyle, SonarQube; see Quality gates)** reads this
  repository's own code. It finds **unknown** defects that no CVE describes.
- For secrets, **gitleaks reads git objects and trivy reads files**, so the history is the half
  trivy cannot see. That is why both run.

Reports land in `build/supply-chain/` (one JSON per scanner plus `status.txt`, one exit code per
scanner) and the console in `build/reports/supply-chain/console.txt`. Re-render the verdict from
those files without rescanning:

```sh
SERVICES="config-svc conversion-svc eureka-svc gateway-svc ledger-svc" IMAGE_VERSION=0.0.1-SNAPSHOT \
  OUT_DIR=build/supply-chain CONSOLE_FILE=build/reports/supply-chain/console.txt \
  ./security/scan.sh gate secrets,sbom,images,config
```

## Continuous integration

**This pipeline has not run anywhere.** The repository has no remote yet, so no CircleCI project
reads `.circleci/config.yml`: there is no build number, no badge and no stored result. What has
been checked is the file and the commands it runs. `circleci config validate` (CLI 1.0.49592)
accepts it, and both Gradle commands below pass on a laptop. That validator also accepts unknown
keys inside a step, so a valid result does not prove every key does something.

The pipeline runs the same Gradle commands a laptop runs and names no quality tool itself. A gate
wired into `check` (SpotBugs, Checkstyle, JaCoCo, `checkSecrets`) is picked up without the
pipeline changing. There is one workflow with one job, `gate`, on every branch. It uses no orbs and
has no deploy step, and it holds no registry or cluster credential. It runs on a `machine`
executor (`ubuntu-2604:2026.07.1`), because Testcontainers and the scanner containers need a
Docker daemon on the job's host.

The job has 14 steps. Nine are `run` steps, listed below in order. The other five are `checkout`,
`restore_cache`, `save_cache`, `store_test_results` and `store_artifacts`, and none of them gates.

| # | Step | What fails it |
|---|---|---|
| 1 | The pinned JDK still matches `.sdkmanrc` | `.sdkmanrc` naming a java candidate other than `JDK_SDKMANRC_ID`. `.sdkmanrc` is the authority; the config holds a derived copy |
| 2 | Install Temurin 25, checksum verified | a tarball whose SHA-256 is not `JDK_SHA256` |
| 3 | The running JDK is the pinned one | `$JAVA_HOME/release` not saying `Temurin-25+36`, or `java` on the PATH not being `$JAVA_HOME/bin/java` |
| 4 | Derive the Gradle dependency cache key | no build file matching (9 today), which would make the key the digest of nothing |
| 5 | `./gradlew --no-build-cache --rerun-tasks clean build` | any test in any tier, a SpotBugs or Checkstyle finding, a coverage floor, `checkSecrets` |
| 6 | Every service's test tasks ran | a service in `settings.gradle` with no `:test` line in the log, or a `tieredServices` service missing `:integrationTest` or `:acceptanceTest`, or any of them leaving no JUnit XML, or any `FROM-CACHE` line |
| 7 | `./gradlew bootBuildImageAll checkSupplyChain` | the supply-chain verdict (see Supply-chain gate) or an image that does not build |
| 8 | `./gradlew sonarAll`, skipped without `SONAR_TOKEN` | the SonarQube quality gate. With no token it prints why it skipped and exits 0 |
| 9 | Collect test XMLs and reports (`when: always`) | nothing. It is never a gate, because a red run is when the reports matter |

Step 6 reads the service list from `settings.gradle` and the three-tier list from `tieredServices`
in `build.gradle`, so neither list is typed into the config. On this commit's log it found 9 test
tasks for 5 services. It went red when the `:services:ledger-svc:acceptanceTest` line was removed,
and again when one `FROM-CACHE` line was added. The collected artifacts are every service's
`build/reports`, `build/reports/supply-chain/console.txt`, the five CycloneDX SBOMs with
`status.txt`, and both Gradle logs. Every JUnit XML goes to `store_test_results`. The Gradle cache
holds only the wrapper distribution and the module cache, never task output.

**The JDK.** `.sdkmanrc` says `java=25-tem`, which on this machine is Temurin `25+36`. The job
downloads `OpenJDK25U-jdk_x64_linux_hotspot_25_36.tar.gz` from the Adoptium GitHub release with
SHA-256 `ee04de95ab9da7287d40bd2173076ecc2a6dd662f007bedfc6eb0380c0ef90e8`. Three sources agreed
on that hash on 2026-10-13: the Adoptium v3 API for `jdk-25+36` (linux, x64), the release's
published `.sha256.txt`, and the hash of the downloaded tarball, whose `release` file reads
`Temurin-25+36`, Linux, x86_64. When `.sdkmanrc` changes, the four `JDK_*` variables change with it.

**The images on amd64.** CircleCI machine executors are amd64, while a Mac builds arm64. Both
Paketo pins in `gradle.properties` are multi-arch index digests. `docker buildx imagetools inspect`
lists a `linux/amd64` manifest under each, and each manifest's config says `linux amd64`. The
builder index `a5100b7d...` resolves to amd64 `2187d365...`, and the run image index `b5d00dc2...`
resolves to amd64 `33e5931e...`. The trivy and gitleaks pins in `security/scanners.lock` resolve
the same way. This was read off the registry and not from an amd64 build, which has not happened.

**Scanner file ownership on Linux.** Both scanner images run as root by default. On Linux they
left `gitleaks-history.json`, `gitleaks-tree.json` and `trivy-secrets.json` under `build/` owned
by `0:0`, and the build user could not overwrite them. Docker Desktop on a Mac hides this, because
its file sharing maps every file to the host user. `security/scan.sh` now runs every scanner as
the calling uid and gid, and hands the trivy cache volume to that uid first. This was proven on
Linux in a throwaway Docker-in-Docker daemon, running as uid 1001. Before the change, `secretgate`
left 3 of 13 entries owned by root. After it, 0 of 13 were, and the full `all` run left 0 of 46.

**Resource class: `medium` (2 vCPU, 7.5 GB), measured.** The gate command below was run locally
on 2026-10-13 with a fresh Gradle daemon, sampled every 2 to 3 seconds:

| Command | Wall time (18-core laptop) | Peak JVMs | Peak JVM resident memory | Peak containers | Peak container memory |
|---|---|---|---|---|---|
| `--no-build-cache --rerun-tasks clean build` | 151 s, 82 of 82 tasks executed | 12 | 2,153 MiB | 3 | 122 MiB |
| `bootBuildImageAll checkSupplyChain` | 76 s | 8 | 233 MiB, a lower bound: the reused daemon was not counted | 1 | 28 MiB |

The peak is about 2.2 GiB, under a third of medium's memory, and `large` would only buy CPU. The
2 vCPUs are not measured. The suite has timing-bound tests (a 15 s quote expiry, 2 s rate-service
timeouts, concurrent deposits), so a slow or flaky first run on `medium` means switching to
`large`, a one-line change. A 2-second sampler can also miss a short spike.

**On a laptop, the same thing.** Steps 5, 7 and 8 are plain Gradle commands with no CI-only flags
beyond `--console=plain`:

```sh
./gradlew --no-build-cache --rerun-tasks clean build
./gradlew bootBuildImageAll checkSupplyChain
./gradlew sonarAll          # prints SKIPPED without SONAR_TOKEN
```

On 2026-10-13 the first command passed 303 tests with 0 failures. ledger-svc ran 63, 16 and 59;
conversion-svc ran 106, 11 and 44; and config, eureka and gateway ran 4 between them. The second
command printed `SUPPLY CHAIN GATE: PASSED`.

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
| Fleet Overview | UP/DOWN tile per service, request rate, 5xx ratio, p95 latency, JVM heap; a **Money** row (below) |
| Traces | latest traces for the selected services; click a trace ID to open it |
| Logs | logs by service and level; a line logged inside a request carries its `trace_id` |
| Alerts | the four alert rules below, in every state |

Each JVM is its own series: the OpenTelemetry resource attribute `service.instance.id` is
`${HOSTNAME:localhost}:<port>` (Prometheus label `instance`), and `service.name` is the
application name (label `job`). The shared OTLP settings live in `observability/`, which the root
`build.gradle` compiles into every service jar.

The **Money** row of Fleet Overview shows what the services do with money, from business metrics
the ledger and conversion-svc export beside their HTTP and JVM metrics (names and tags in
ARCHITECTURE.md section 3):

| Panel | Shows |
|---|---|
| Deposits per minute by currency | posted, replayed and refused deposits, a refusal with its code (`BALANCE_LIMIT_EXCEEDED` is the balance-limit refusal) |
| Conversions by outcome | conversions settled COMPLETED or REJECTED (by reason), each counted once; conversions left PENDING by their first ledger answer, as a separate line; and the ledger's conversion postings by outcome |
| Quote refusals per minute by reason | refused quotes by code; `RATE_REJECTED` also names the rate rule broken (`RATE_STALE`, `RATE_OUT_OF_BAND`, `RATE_JUMP`, ...) |
| Rate service p95 latency | conversion-svc's calls to the exchange-rate service, by outcome |
| Overdue PENDING conversions | PENDING conversions the sweeper should already have resolved, counted in the database |
| Sweeper actions | what the sweeper did with each overdue conversion: completed, voided, refused, unresolved |

Alert rules (`docker/lgtm/alerting/rules.yaml`, Grafana-managed):

| Rule | Fires when |
|---|---|
| ServiceDown | a service has exported no metrics for 30 s, for 1 min; one alert per service |
| HighErrorRate | more than 5% of a service's requests answer 5xx over 1 min, for 2 min |
| RateFeedRefusing | quotes for one pair refused for the rate (`RATE_UNAVAILABLE` or `RATE_REJECTED`) in the last minute: more than 2, or at least 1 that is half or more of that pair's quotes; for 2 min; one alert per pair |
| ConversionsStuckPending | at least one PENDING conversion is overdue for the sweeper, for 5 min |

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
├── gradle.properties       Spring Boot, Spring Cloud and quality-tool versions
├── config/                 checkstyle/checkstyle.xml and spotbugs/exclude.xml, read by every service
├── security/               scanners.lock, trivy-ignore.yaml, scan.sh and report.py: the supply-chain gate
├── .circleci/config.yml    the CI job: the same Gradle commands as a laptop (see Continuous integration)
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
