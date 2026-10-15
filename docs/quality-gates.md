# Quality gates: tests, static application security testing (SAST) and coverage

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
| Checkstyle 14.3.0 (`checkstyleMain`, `checkstyleTest`, `checkstyleAcceptanceTest`) | every Java source set, against `config/checkstyle/checkstyle.xml` (tabs, static imports first, `}` alone before `catch`/`else`; main code may not catch `Exception`, `RuntimeException`, `Error` or `Throwable`, except gateway-svc's `RateLimitFilter`, the limiter's fail-closed line; tests may not catch `Error` or `Throwable`) | any violation (`maxWarnings` and `maxErrors` are 0) |
| JaCoCo 0.8.15 (`jacocoTestCoverageVerification`) | one report per service, merged over every test tier it runs | line or branch coverage falls below the service's floor in `coverageFloors` |
| `checkCoverageFloors` | the same report | a floor sits more than 5 points below the measured value (`coverageBand`; raise it to the value the message prints), a floor sits above it, a measured counter has no floor, a declared floor has nothing to measure, or the report measured no lines |

Floors in `coverageFloors` today (line / branch): ledger-svc 0.93 / 0.87, conversion-svc 0.92 / 0.84,
gateway-svc 0.95 / 0.97, config-svc and eureka-svc 0.57 / none (no branches).

Every SpotBugs exclusion carries a comment with its reason in `config/spotbugs/exclude.xml`. Reports land in
`services/<svc>/build/reports/{spotbugs,checkstyle,jacoco}/`.

The root `check` also runs `checkSecrets` ([supply-chain.md](supply-chain.md)) and `checkDashboards`
(`python3 docker/lgtm/generate.py --check`: the committed dashboards and alert rules must equal what the
generator writes).

The first run of these gates found a real defect: conversion-svc accepted a currency code spelled
with U+212A (Kelvin sign), which lower-cases onto `kes`. Codes are now American Standard Code for Information Interchange (ASCII) `[A-Z]{3,5}`, as in
the ledger.

## Architecture gates

Design rules, not security findings, so they are not SAST. They run inside `test`, so `check` and
`./gradlew build` fail on them like any other gate.

| Gate | Reads | Fails the build when |
|---|---|---|
| Dependency-injection budget (`DependencyBudgetTest`; source `architecture/src/test/java`, compiled into every service's `test` task) | every Spring component (`@Component` and its stereotypes, `@Configuration`, advice) found by scanning the service's compiled main classes under `io.ezeebit`, and every `@Bean` method on them | a bean or a `@Bean` method takes more than 3 injected dependencies (the message lists each with its count; no suppression), or the scan does not reach the service's `@SpringBootApplication` class |
| Ports before adapters (`ServiceImportsNoOutboundTest`, conversion-svc `test`) | every main source file under `service/` | one imports `io.ezeebit.conversion.outbound.`: the use cases call `service/port/` interfaces, never an adapter |
| One contract (`checkSingleProto`, root `check`) | every `.proto` in the repository outside `contracts/`, `build/`, `.gradle/`, `node_modules/` and `.superpowers/` | any `.proto` outside `contracts/`, or none inside it |

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
