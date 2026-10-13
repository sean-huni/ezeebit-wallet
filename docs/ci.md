# Continuous integration (CircleCI)

Back to [README](../README.md).

**This pipeline has not run anywhere.** No CircleCI project reads `.circleci/config.yml` until the
repository is pushed and connected, so there is no build number, no badge and no stored result.
What has been checked is the file and the commands it runs: `circleci config validate` (command-line
interface, CLI, 1.0.49592) accepts it, and the Gradle commands below pass on a laptop. That validator also accepts
unknown keys inside a step, so a valid result does not prove every key does something.

## What it runs

The pipeline runs the same Gradle commands a laptop runs and names no quality tool itself, so a gate
wired into `check` is picked up without the pipeline changing. One workflow, one job, `gate`, on
every branch. No orbs, no deploy step, no registry or cluster credential. It runs on a `machine`
executor (`ubuntu-2604:2026.07.1`), because Testcontainers and the scanner containers need a Docker
daemon on the job's host.

The job has 14 steps. Nine are `run` steps, in this order; the other five (`checkout`,
`restore_cache`, `save_cache`, `store_test_results`, `store_artifacts`) never gate.

| # | Step | What fails it |
|---|---|---|
| 1 | The pinned JDK (Java Development Kit) still matches `.sdkmanrc` | `.sdkmanrc` naming a java candidate other than `JDK_SDKMANRC_ID`. `.sdkmanrc` is the authority; the config holds a derived copy |
| 2 | Install Temurin 25, checksum verified | a tarball whose SHA-256 is not `JDK_SHA256` |
| 3 | The running JDK is the pinned one | `$JAVA_HOME/release` not saying `Temurin-25+36`, or `java` on the PATH not being `$JAVA_HOME/bin/java` |
| 4 | Derive the Gradle dependency cache key | no build file matching, which would make the key the digest of nothing |
| 5 | `./gradlew --no-build-cache --rerun-tasks clean build` | any test in any tier, a SpotBugs or Checkstyle finding, a coverage floor, `checkSecrets`, `checkDashboards` (generated dashboards or alert rules out of date) |
| 6 | Every service's test tasks ran | a service in `settings.gradle` with no `:test` line in the log, a `tieredServices` service missing `:integrationTest`, a `cucumberServices` service missing `:acceptanceTest`, any of them leaving no JUnit XML, or any `FROM-CACHE` line |
| 7 | `./gradlew bootBuildImageAll checkSupplyChain` | the supply-chain verdict ([supply-chain.md](supply-chain.md)) or an image that does not build |
| 8 | `./gradlew sonarAll`, skipped without `SONAR_TOKEN` | the SonarQube quality gate; with no token it prints why it skipped and exits 0 |
| 9 | Collect test XMLs and reports (`when: always`) | nothing: a red run is when the reports matter |

Step 6 reads the service list from `settings.gradle`, the integration tier from `tieredServices` and the
acceptance tier from `cucumberServices` in `build.gradle`, so no list is typed into the config. Today it
expects 10 test tasks: 5 `test`, 3 `integrationTest`, 2 `acceptanceTest`. Run against a real gate log
(`--no-build-cache --rerun-tasks clean build`, 538 tests, 2026-10-13) it finds all 10 and exits 0; with the
`:services:ledger-svc:acceptanceTest` line removed from that log it fails naming it. An earlier version
took the acceptance tier from `tieredServices` and failed every green build once `gateway-svc` joined
that list (fixed in `bugfix-ci-test-tasks`). A `FROM-CACHE` line also fails it. The Gradle cache holds
only the wrapper distribution and the module cache, never task output.

## The JDK

`.sdkmanrc` says `java=25-tem` (Temurin `25+36`). The job downloads
`OpenJDK25U-jdk_x64_linux_hotspot_25_36.tar.gz` from the Adoptium GitHub release with SHA-256
`ee04de95ab9da7287d40bd2173076ecc2a6dd662f007bedfc6eb0380c0ef90e8`. Three sources agreed on that
hash on 2026-10-13: the Adoptium v3 API, the release's published `.sha256.txt`, and the hash of the
downloaded tarball. When `.sdkmanrc` changes, the four `JDK_*` variables change with it.

## Images on amd64

CircleCI machine executors are amd64, while a Mac builds arm64. Both Paketo pins in
`gradle.properties` are multi-arch index digests, and `docker buildx imagetools inspect` lists a
`linux/amd64` manifest under each; so do the scanner pins in `security/scanners.lock`. This was
read off the registry, not from an amd64 build, which has not happened.

## Scanner file ownership on Linux

Both scanner images run as root by default, and on Linux they left reports under `build/` owned by
`0:0` that the build user could not overwrite (Docker Desktop on a Mac hides this). `security/scan.sh`
runs every scanner as the calling uid and gid and hands the trivy cache volume to that uid first.
Proven in a throwaway Docker-in-Docker daemon as uid 1001: before the change 3 of 13 entries were
owned by root, after it 0 of 13, and the full `all` run left 0 of 46.

## Resource class: `large` (4 virtual CPUs, vCPU, 15 GB)

Measured locally on 2026-10-13 at commit `29e49d6` with a fresh Gradle daemon, sampled every 2 to 3
seconds. That commit predates gateway-svc's test tiers and Redis, the generated dashboards and
`checkDashboards`; the numbers have not been re-measured since:

| Command | Wall time (18-core laptop) | Peak JVMs (Java virtual machines) | Peak JVM resident memory | Peak containers | Peak container memory |
|---|---|---|---|---|---|
| `--no-build-cache --rerun-tasks clean build` | 151 s, 82 of 82 tasks executed | 12 | 2,153 MiB | 3 | 122 MiB |
| `bootBuildImageAll checkSupplyChain` | 76 s | 8 | 233 MiB, a lower bound: the reused daemon was not counted | 1 | 28 MiB |

The peak is about 2.2 GiB, so `medium` (2 vCPU, 7.5 GB) has the memory. `large` is chosen for CPU:
the suite has timing-bound tests (a 15 s quote expiry, 2 s rate-service timeouts, concurrent
deposits) and 2 vCPUs were never measured, so a flaky first run would cost more than the larger
class. Dropping to `medium` is a one-line change once a real run shows the timings hold. A 2-second
sampler can also miss a short spike.

## The same thing on a laptop

```sh
./gradlew --no-build-cache --rerun-tasks clean build
./gradlew bootBuildImageAll checkSupplyChain
./gradlew sonarAll          # prints SKIPPED without SONAR_TOKEN
```
