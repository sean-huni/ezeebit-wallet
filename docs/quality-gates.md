# Quality gates: tests, SAST and coverage

Back to [README](../README.md). The supply-chain half (secrets, images, SBOMs) is in
[supply-chain.md](supply-chain.md).

## Test tiers

`./gradlew clean build` runs every tier. ledger-svc and conversion-svc have three, gateway-svc the
first two, wired as a shared convention in the root `build.gradle` (a service opts in by being
listed in `tieredServices`, and to Cucumber by `cucumberServices`):

| Gradle task | Source | What it is |
|---|---|---|
| `test` | `src/test` | unit tests: domain (money, pricing, rate rules, quote expiry, state machine), mapping, i18n bundle, business-metric tags; no Spring context, no Docker |
| `integrationTest` | `src/acceptanceTest` (JUnit) | Spring Boot on Testcontainers Postgres: migrations (including a re-run on an existing schema), each DB constraint proven by the row it refuses, the gateway proof header; for conversion-svc also the Eureka client wiring; for ledger-svc also that it refuses connections on the LAN address. For gateway-svc: real gateway contexts on Testcontainers Redis (the pinned compose image) against a stub upstream: the rate limit, two gateways sharing one bucket, Redis stopped, paused and absent, the path rewrite and the proof header |
| `acceptanceTest` | `src/acceptanceTest` (Cucumber 8.0.4, `features/*.feature`) | business scenarios over real HTTP, some ending in a SQL check that the books balance; `business-metrics.feature` checks that the counters move on the real path |

config-svc and eureka-svc have one `test` task each. Counts per tier are in
[SOLUTION.md](../SOLUTION.md#1-tasks-chosen-and-status).

The Spring tiers start `postgres:18.6-alpine` through Testcontainers, so Docker must be running.
They run under the `acceptance` profile and boot each service alone, with the config server, the
registry and OTLP export switched off. conversion-svc's acceptance tier also runs the **real
ledger-svc jar** as a separate process on its own database, puts an in-process WireMock
(`wiremock-standalone` 3.13.2) in front of it to inject ledger faults, and serves the rate feed from
the same mapping files compose mounts (`docker/wiremock/mappings/`).

Run one tier with `./gradlew :services:ledger-svc:acceptanceTest` (or `test`, `integrationTest`;
same for conversion-svc). Cucumber's reports land in `services/<svc>/build/reports/cucumber/`.

## Static analysis and coverage

`./gradlew build` also runs these gates, configured once in the root `build.gradle` for every
service. Tool versions are pinned in `gradle.properties`.

| Tool | Reads | Fails the build when |
|---|---|---|
| SpotBugs 4.10.5 with Find Security Bugs 1.14.0 (`spotbugsMain`) | compiled main classes, effort max, confidence low | any finding not in `config/spotbugs/exclude.xml` |
| `checkSpotbugsReport` (runs on every build, even when `spotbugsMain` is up to date) | the SpotBugs XML report and task settings | the report does not list Find Security Bugs with `enabled="true"`; it records an analysis error or missing class; no class was analysed; any SpotBugs task sets `visitors` or `omitVisitors` |
| Checkstyle 14.3.0 (`checkstyleMain`, `checkstyleTest`, `checkstyleAcceptanceTest`) | every Java source set, against `config/checkstyle/checkstyle.xml` (tabs, static imports first, `}` alone before `catch`/`else`; main code may not catch `Exception`, `RuntimeException`, `Error` or `Throwable`, tests only `Error` or `Throwable`) | any violation (`maxWarnings` and `maxErrors` are 0) |
| JaCoCo 0.8.15 (`jacocoTestCoverageVerification`) | one report per service, merged over every test tier it runs | line or branch coverage falls below the service's floor in `coverageFloors` |
| `checkCoverageFloors` | the same report | a floor sits more than 5 points below the measured value (raise it to the value the message prints), or a measured counter has no floor |

Every SpotBugs exclusion carries a one-line reason in `config/spotbugs/exclude.xml`. Reports land in
`services/<svc>/build/reports/{spotbugs,checkstyle,jacoco}/`.

The first run of these gates found a real defect: conversion-svc accepted a currency code spelled
with U+212A (Kelvin sign), which lower-cases onto `kes`. Codes are now ASCII `[A-Z]{3,5}`, as in
the ledger.

## SonarQube (opt-in)

It needs no server to build:

```sh
./gradlew sonarAll                                   # no SONAR_TOKEN: prints SKIPPED and runs nothing (so does `sonar`)
SONAR_TOKEN=... SONAR_HOST_URL=https://sonar.example ./gradlew sonarAll
```

With a token, `sonarAll` runs every service's `check`, then analyses all five as one project with
each module's compile classpath as `sonar.java.libraries`, the merged JaCoCo report and the
Checkstyle reports, and waits for the quality gate (`sonar.qualitygate.wait`). `SONAR_HOST_URL`
defaults to `http://localhost:9000`; `SONAR_PROJECT_KEY` overrides the default key `ezeebit-wallet`.

## SAST and SCA read different things

- **SAST** (SpotBugs with Find Security Bugs, Checkstyle, SonarQube) reads this repository's own
  code. It finds **unknown** defects that no CVE describes.
- **SCA** (trivy, in [supply-chain.md](supply-chain.md)) reads the shipped image: jars, OS
  packages, the JRE, buildpack binaries. It finds **known** CVEs in code nobody here wrote.
