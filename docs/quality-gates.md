# Quality gates: tests, static and dynamic application security testing (SAST, DAST) and coverage

Back to [README](../README.md). The supply-chain half, covering secrets, images and software bills of materials (SBOMs), is
in [supply-chain.md](supply-chain.md).

## Test tiers

`./gradlew clean build` runs every tier. ledger-svc and conversion-svc have three, gateway-svc the
first two, wired as a shared convention in the root `build.gradle` (a service opts in by being
listed in `tieredServices`, and to Cucumber by `cucumberServices`):

| Gradle task | Source | What it is |
|---|---|---|
| `test` | `src/test` | unit tests: domain (money, pricing, rate rules, quote expiry, state machine), mapping, internationalisation (i18n) bundle, business-metric tags; no Spring context, no Docker |
| `integrationTest` | `src/acceptanceTest` (JUnit) | Spring Boot on Testcontainers Postgres: migrations (including a re-run on an existing schema), each database constraint proven by the row it refuses, the gateway proof header, the OpenAPI spec against its committed snapshot in `docs/openapi` (`OpenApiContractIT`); for conversion-svc also the Eureka client wiring and the dead-letter publisher against Testcontainers Kafka (the pinned compose image: every field on the topic, and a broker stopped under a live producer neither blocks nor throws); for ledger-svc also that it refuses connections on the local area network (LAN) address, and the gRPC Remote Procedure Call (gRPC) server in-process (`ConversionPostingRpcIT`: post, replay, every refusal code to its status and reason, find, void). For gateway-svc: real gateway contexts on Testcontainers Redis (the pinned compose image) against a stub upstream: the rate limit, two gateways sharing one bucket, Redis stopped, paused and absent, the path rewrite and the proof header |
| `acceptanceTest` | `src/acceptanceTest` (Cucumber 8.0.4, `features/*.feature`) | business scenarios over real Hypertext Transfer Protocol (HTTP), some ending in a Structured Query Language (SQL) check that the books balance; `business-metrics.feature` checks that the counters move on the real path |

config-svc and eureka-svc have one `test` task each. Counts per tier are in
[SOLUTION.md](../SOLUTION.md#1-tasks-chosen-and-status).

The Spring tiers start `postgres:18.6-alpine` through Testcontainers, so Docker must be running.
They run under the `acceptance` profile and boot each service alone, with the config server, the
registry and OpenTelemetry Protocol (OTLP) export switched off. conversion-svc's acceptance tier also runs the **real
ledger-svc jar** as a separate process on its own database, puts a small gRPC forwarding proxy
(`LedgerFaultProxy`, test code) in front of its gRPC port to inject ledger faults (an answer never
sent, an answer lost or late), and serves the rate feed from an in-process WireMock
(`wiremock-standalone` 3.13.2) loaded with the same mapping files compose mounts
(`docker/wiremock/mappings/`).

Run one tier with `./gradlew :services:ledger-svc:acceptanceTest` (or `test`, `integrationTest`;
same for conversion-svc). Cucumber's reports land in `services/<svc>/build/reports/cucumber/`.

## Static analysis and coverage

`./gradlew build` also runs these gates, configured once in the root `build.gradle` for every
service. Tool versions are pinned in `gradle.properties` (SpotBugs Gradle plugin 6.5.13).

| Tool | Reads | Fails the build when |
|---|---|---|
| SpotBugs 4.10.5 with Find Security Bugs 1.14.0 (`spotbugsMain`) | compiled main classes, effort max, confidence low | any finding not in `config/spotbugs/exclude.xml` |
| `checkSpotbugsReport` (runs on every build, even when `spotbugsMain` is up to date) | the SpotBugs Extensible Markup Language (XML) report and task settings | the report is absent or empty; it does not list Find Security Bugs with `enabled="true"`; it records an analysis error or missing class; no class was analysed; any SpotBugs task sets `visitors` or `omitVisitors`, or passes `-visitors`, `-omitVisitors` or `-chooseVisitors` |
| Checkstyle 14.3.0 (`checkstyleMain`, `checkstyleTest`, `checkstyleAcceptanceTest`) | every Java source set, against `config/checkstyle/checkstyle.xml` (tabs, static imports first, `}` alone before `catch`/`else`; main code may not catch `Exception`, `RuntimeException`, `Error` or `Throwable`, except the six files each suppression in that file names with its reason: gateway-svc's `RateLimitFilter` (the limiter's fail-closed line), `DiscoveryNameResolver` (a gRPC name resolver must answer UNAVAILABLE, never throw), `LedgerGuard` and `KafkaDeadLetterPublisher` (a dead-letter sink must never change the failed call's outcome), and `ProfilerStarter` and `SpanProfileLink` (the profiler fails open and counts the failure); tests may not catch `Error` or `Throwable`) | any violation (`maxWarnings` and `maxErrors` are 0) |
| Spotless 8.11.0 (`spotlessCheck`, part of `check`; `spotlessApply` fixes) | every service `src`, and from the root `observability/`, `architecture/`, `discovery/`, `persistence/`: import order, unused imports, trailing whitespace, final newline; layout stays with Checkstyle | any file it would change |
| JaCoCo 0.8.15 (`jacocoTestCoverageVerification`) | one report per service, merged over every test tier it runs | line or branch coverage falls below the service's floor in `coverageFloors` |
| `checkCoverageFloors` | the same report | a floor sits more than 5 points below the measured value (`coverageBand`; raise it to the value the message prints), a floor sits above it, a measured counter has no floor, a declared floor has nothing to measure, or the report measured no lines |

Floors in `coverageFloors` today (line / branch): ledger-svc 0.93 / 0.87, conversion-svc 0.92 / 0.84,
gateway-svc 0.95 / 0.97, config-svc and eureka-svc 0.90 / 0.97 (`coverageFloors` in the root `build.gradle` is the authority).

Every SpotBugs exclusion carries a comment with its reason in `config/spotbugs/exclude.xml`. Reports land in
`services/<svc>/build/reports/{spotbugs,checkstyle,jacoco}/`.

The root `check` also runs `checkSecrets` ([supply-chain.md](supply-chain.md)), `checkSingleProto`
(see [Architecture gates](#architecture-gates)) and `checkDashboards`
(`python3 docker/lgtm/generate.py --check`: the committed dashboards and alert rules must equal what the
generator writes), and `testVersionReport`, the offline half of `checkVersions` ([supply-chain.md](supply-chain.md)).
When `spotlessCheck` fails, `./gradlew spotlessApply` rewrites the files it names.

The first run of these gates found a real defect: conversion-svc accepted a currency code spelled
with U+212A (Kelvin sign), which lower-cases onto `kes`. Codes are now American Standard Code for Information Interchange (ASCII) `[A-Z]{3,5}`, as in
the ledger.

## Architecture gates

Design rules, not security findings, so they are not SAST. They run inside `test`, so `check` and
`./gradlew build` fail on them like any other gate.

| Gate | Reads | Fails the build when |
|---|---|---|
| Dependency-injection budget (`DependencyBudgetTest`; source `architecture/src/test/java`, compiled into every service's `test` task) | every Spring component (`@Component` and its stereotypes, `@Configuration`, advice, conditional ones included) found by scanning the service's compiled main classes under `io.ezeebit`, and every `@Bean` method on them or inherited from a project superclass or interface | a bean or a `@Bean` method takes more than 3 injected dependencies (the message lists each with its count; no suppression), or the scan does not reach the service's `@SpringBootApplication` class |
| Code to interfaces (`CodeToInterfaceTest`; source `architecture/src/test/java`, compiled into every service's `test` task) | every Spring component, conditional ones included, and each project class a `@Bean` method (declared, inherited from a project superclass or interface, or meta-annotated) declares or instantiates, read from its bytecode, its lambdas and its constructor references (not through a helper or static factory it calls, an anonymous class body, or a plain class brought in by `@Import`): constructors, injected fields and setters, generic arguments, up the superclass chain | one takes a concrete project class from another package; records and enums are data, and `@Bean` parameters are the composition root |
| Ports before adapters (`ServiceImportsNoOutboundTest`, conversion-svc `test`) | every main source file under `service/` | one imports `io.ezeebit.conversion.outbound.`: the use cases call `service/port/` interfaces, never an adapter |
| One contract (`checkSingleProto`, root `check`) | every `.proto` in the repository outside `contracts/`, `build/`, `.gradle/`, `node_modules/` and `.superpowers/` | any `.proto` outside `contracts/`, or none inside it |

One gate runs before a release rather than inside the build: `scripts/verify-topology.sh` compares, by
name, the `*-svc` services drawn in `docs/fleet/fleet-architecture.html` with the directories under
`services/` that hold a `build.gradle` and with the `services:*` includes in `settings.gradle`. It exits
0 when the three sets match, 1 naming each service missing from a set, and 2 when an input cannot be
read. Each of its four directions was seen failing (a service removed from the diagram, the
directories and `settings.gradle` in turn) on 2026-11-05.

What counts as one injected dependency:

- each parameter of the constructor Spring selects (the `@Autowired` one, the greediest of several
  marked ones, the only one, else the no-argument one, else the largest);
- each injected field and each parameter of an injected method (a setter), on the class and every superclass;
- injection markers, matched by class name and through composed annotations: `@Autowired`, `@Value`,
  `@Inject` (jakarta and javax), `@Resource` (jakarta and javax);
- an `ObjectProvider<T>`, `List<T>`, `Set<T>` or `Map<String, T>` parameter is one injection point;
- an object built inside a `@Bean` method is budgeted by that method's parameters, not by its own
  constructor.

The limit is `DependencyBudget.MAX_INJECTED = 3`; a bean counts only when its class was compiled from
the service's main source set (`/classes/java/main/`), which includes the shared `observability/` code.

`InjectionCounterTest` pins each rule with a fixture, and `DependencyBudgetTest` scans a committed
over-budget fixture package (`architecture/.../fixture`) through the same scan and report path,
asserting the exact message. Each rule was seen failing before its fix. The gate's first run failed
on 8 beans (ledger `ConversionPostingService` 10, `DepositService` 6, `ConversionPoster` 5,
`DepositPoster` 4; conversion `ConversionService` 9, `QuoteService` 8, `PendingResolver` 7,
`ConversionSettler` 5), which were split by job; [project-layout.md](project-layout.md#dependency-injection-budget)
has the before and after.

## SonarQube (opt-in)

It needs no server to build:

```sh
./gradlew sonarAll                                   # no SONAR_TOKEN: prints SKIPPED and runs nothing (so does `sonar`)
SONAR_TOKEN=... SONAR_HOST_URL=https://sonar.example ./gradlew sonarAll
```

With a token (SonarQube Gradle plugin 7.5.0.8588), `sonarAll` runs every service's `check`, then analyses all five as one project with
each module's compile classpath as `sonar.java.libraries`, the merged JaCoCo report and the
Checkstyle reports, and waits for the quality gate (`sonar.qualitygate.wait`). `SONAR_HOST_URL`
(the server's Uniform Resource Locator) defaults to `http://localhost:9000`; `SONAR_PROJECT_KEY` overrides the default key `ezeebit-wallet`.

## SAST and software composition analysis (SCA) read different things

- **SAST** (SpotBugs with Find Security Bugs, Checkstyle, SonarQube) reads this repository's own code. It finds **unknown** defects that no Common Vulnerabilities and Exposures (CVE) entry describes.
- **SCA** (trivy, in [supply-chain.md](supply-chain.md)) reads the shipped image: jars, operating system
  (OS) packages, the Java Runtime Environment (JRE), buildpack binaries. It finds **known** CVEs in code nobody here wrote.
- **DAST** (dynamic application security testing, below) reads nothing: it attacks the running edge
  the way a caller can, and finds what the code and the image both looked fine on.

## DAST: ZAP, Schemathesis and Nuclei against the running gateway

`security/dast.sh` is a release gate beside `scripts/verify-topology.sh`. It needs a running fleet
(`./fleet.sh up`), so it runs before a release rather than inside `./gradlew build`. Every scan goes
through the gateway, because that is the only surface a caller has; a finding behind it that no caller
can reach is not one.

```sh
security/dast.sh            # the compose fleet at http://localhost:8080 (DAST_TARGET, loopback only)
security/dast.sh control    # the same scans against security/dast-fixture.py, where each must find its plant
```

| Scanner | What it does | Fails the run when |
|---|---|---|
| ZAP 2.17.0, API scan | imports `docs/openapi/{conversion-svc,ledger-svc}.json` and actively attacks every operation: injection, path traversal, error disclosure, Spring4Shell, Log4Shell and the rest of its active rules | any High alert, read from its JSON report (the console calls some Highs WARN) |
| Schemathesis 4.32.4 | generates requests from the same specs, valid and invalid, including methods and content types the specs do not declare | any 5xx answer |
| Nuclei v3.11.1 | its `springboot`, `spring`, `actuator`, `exposure` and `misconfig` templates against the gateway's root | any finding rated low or above (info is reported only): an exposed `/actuator/env` is a defect although its template rates it low |

- **Exit codes**: 0 clean, 1 a finding (in `control`, a missed plant), 2 a scanner could not run
  (Docker absent, an import of zero operations, zero templates loaded, a report naming no site, or the
  reach probe failing). A 2 means nothing was learned.
- **The reach probe** asks, from a container through the same `dast-target` route the scanners use,
  before and after the scans, for answers only the services produce: ledger's balances (200 with
  `data`) and conversion's `CONVERSION_NOT_FOUND`. Without it, a renamed route (every request a 404 from
  the edge), a wall of 429s, or a host the containers cannot reach would pass every scanner having
  tested nothing. Seen failing: aimed at config-svc, which answers its own health check, the run
  stops at exit 2 before any scan.
- **It refuses any target that is not loopback.** The scanners send attack payloads and write rows
  (quotes, rejected conversions); they never run against a shared environment.
- **ZAP is throttled** to one thread and 100 ms between requests. It reuses one example merchant id,
  and the gateway allows 20 requests a second per merchant: unthrottled, its SQL-injection rule read
  the resulting 429 answers as a High (measured 2026-11-05).
- **Pins**: images by tag and digest in `security/scanners.lock`. Schemathesis and Nuclei are Alpine
  images; ZAP publishes Debian only, the one non-Alpine scanner. Nuclei's templates are data, like
  trivy's database: cached in the `ezeebit-nuclei-templates` volume, the loaded count printed per run.
- **The control is the proof the scanners can see.** `security/dast-fixture.py` plants one defect
  per scanner: a MySQL error on any quote (ZAP SQL injection, plugin 40018), a 500 on balances
  (Schemathesis), an exposed `/actuator/env` (Nuclei `springboot-env`). `control` fails unless each
  is found by name.

What the first runs found, 2026-11-05, both fixed with a red test first:

| Finding | Where | Fix |
|---|---|---|
| Any method reached the proxy client: QUERY and FOO answered 500 with an ERROR stack trace per request; PATCH and DELETE were forwarded | gateway-svc routes, `Path` predicate only | `Method=GET,HEAD,POST` on both merchant routes (HEAD with GET, RFC 9110); any other method stops at the edge, never forwarded: 404 like any unpublished path, TRACE 405 from Tomcat (`EdgeHeadersIT`, `RouteCoverageIT`) |
| A `multipart/*` Content-Type without a boundary answered 500 on every route | ledger-svc and conversion-svc; the same class in config-svc and eureka-svc, found by sweeping every service | `spring.servlet.multipart.enabled: false` (nothing takes multipart): reads ignore the header, JSON writes answer 415 (`MultipartRequestIT`, config and eureka boot tests) |

The second finding was hidden behind the first: Schemathesis reports one failure per operation, so it
appeared only once the method fix had landed. Evidence: [the gate red on the unfixed fleet](evidence/2026-11-05-dast-fleet-red.log)
(5 QUERY 500s), [the control red with its plants removed](evidence/2026-11-05-dast-control-red.log), then
[the fleet run](evidence/2026-11-05-dast-fleet.log) and [the control run](evidence/2026-11-05-dast-control.log)
at the committed fix. The first version of the method fix left HEAD out; the independent review caught it.

Known and accepted, from the same review:

- A refused method still costs the merchant a rate-limit token: the limiter runs before routing, so
  probing leaves a trace, as for a malformed path.
- An `OPTIONS` request is answered by the gateway itself with an `Allow` header listing every method
  (Spring's default), including ones the routes refuse. It is never forwarded.
- The gateway's own 404 for an unpublished path or method is Spring's default body, not the services'
  problem format with a `code`.
- Each route allows GET, HEAD and POST as a set, so `POST /balances` is forwarded and answered 405 by
  ledger-svc rather than stopped at the edge.
- Nothing runs the gate automatically: it needs a live fleet, so CI cannot run it, and the release
  checklist must.

What it does not cover: authenticated flows (the API has none at the gateway yet), the internal
`/internal/**` and gRPC surfaces (not routed, so not reachable by a caller), and business-logic abuse
such as replaying a quote, which the acceptance tier tests by name ("A quote cannot be used twice" in
`conversions.feature`).
