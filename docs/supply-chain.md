# Supply-chain gate

Back to [README](../README.md). Static analysis of this repository's own code is in
[quality-gates.md](quality-gates.md).

Two Gradle tasks, split by cost. Both run the scanners as containers pinned by tag and digest in
`security/scanners.lock`, the only place a scanner version lives.

| Task | Runs in | Reads | Needs |
|---|---|---|---|
| `checkSecrets` | every `./gradlew build` (root `check`) | the git history and the working tree | Docker; no images |
| `checkSupplyChain` | only when asked | secrets again, then the five images, their software bills of materials (SBOMs) and the compose and docker files | `bootBuildImageAll`, which it depends on |

```sh
./gradlew bootBuildImageAll                  # ezeebit/<svc>:0.3.1, one per service, Paketo only
./gradlew bootBuildImageAll checkSupplyChain
cat build/reports/supply-chain/console.txt   # the console text, saved as printed
```

Images come from Spring Boot's `bootBuildImage` with `BP_JVM_VERSION=25`. The Paketo builder and
its run image are both pinned by tag and digest in `gradle.properties` (`paketoBuilder`,
`paketoRunImage`). The repository has no Dockerfile. Images are built for the Docker daemon's
architecture.

## What each stage does

| Stage | Instrument | Universe |
|---|---|---|
| secrets | gitleaks `git --log-opts="--all --diff-merges=first-parent"` | every commit reachable from any ref, merges included as their diff against the first parent; the console prints the commits gitleaks scanned beside the commits git counts, and a mismatch fails |
| secrets | gitleaks `dir`, then trivy `fs --scanners secret` | tracked files plus untracked files that `.gitignore` does not exclude, copied out first, so `.env` never reaches a scanner |
| images | trivy `image --input` on a `docker save` tarball | the operating-system packages, Java Runtime Environment (JRE), buildpack helpers and jars in each image; the scanner gets no Docker socket |
| sbom | trivy `--format cyclonedx` from the same tarball | one CycloneDX document per image in `build/supply-chain/sbom/` |
| config | trivy `config` | `compose.yaml`, `docker/` and any tracked `Dockerfile*`; reported, never gating |

Both image scans skip `layers/sbom`, where Paketo stores SBOM documents describing packages that
already sit in other layers. Read as a second source, they put the buildpack launcher into a layer
that differs per image and so made it look like this repository's code.

## Reading the console

1. **Stages required by this run.** One line per stage: scanners that recorded a completing exit
   code, and report files present, non-empty and parseable. Anything short of that is `MISSING` and
   fails the gate: a scanner that never ran must not read as one that found nothing.
2. **Secrets.** Findings per instrument, as rule, file and line. Values are redacted (`--redact`
   for gitleaks; trivy masks them) and the console never prints one. Any finding fails.
3. **Images.** `N images, M layers carried by all N`, then per service the distinct findings in
   SERVICE layers and in SHARED layers, and how many of them gate. Layers are attributed by who
   built them, read from each image's `io.buildpacks.lifecycle.metadata` label. SHARED is the run
   image's layers and the buildpack and launcher layers: their findings move when the builder or
   run-image pin moves, so they are reported, not gated. SERVICE is every `app` layer (the
   application and its jars), plus any layer the label does not name, so an unattributed layer
   fails safe.
4. **SBOM.** Component count per image; 0 components fails.
5. **Misconfiguration.** Checks against files, never vulnerabilities in an image, so its totals
   must not be added to the image totals.
6. **Suppressions**, then the verdict.

**The verdict is a three-clause test.** `checkSupplyChain` fails only on a finding that is HIGH or
CRITICAL **and** has a published fix **and** sits in a SERVICE layer. Everything else is reported.
`security/trivy-ignore.yaml` has no vulnerability entries. It is read in block form only (`- id:`,
`statement:`, `expired_at:`); flow style, an empty `statement`, a date not in International Organization for Standardization (ISO) 8601 form, or an expired date
fails the gate.

**Evidence goes stale on every new commit.** Re-run `checkSupplyChain` after any change rather than
quoting an older console.

## State

Latest recorded verdict: PASSED on 2026-10-15, 5 of 5 images scanned, trivy database 2026-10-15T07:10Z,
after the run before it failed on one HIGH, fixable SERVICE finding: CVE-2026-106451 in lz4-java 1.10.1,
pulled into conversion-svc by kafka-clients 4.2.1. Commit `65f9d0c` pins lz4-java 1.11.4 and records
both results in its message; that run's console and status file were not committed.

The last run with committed evidence: PASSED, nothing suppressed: `SUPPLY CHAIN GATE: PASSED`, re-run on 2026-10-13 at commit `d538b32` after
`bootBuildImageAll` rebuilt all 5 images, with the trivy database updated 2026-10-13T01:09:18Z. Its console and
per-scanner exit codes are committed as [2026-10-13-supply-chain-console.txt](evidence/security/2026-10-13-supply-chain-console.txt)
and [2026-10-13-supply-chain-status.txt](evidence/security/2026-10-13-supply-chain-status.txt). The first run failed on nine Common Vulnerabilities and Exposures (CVE) entries in jars Spring Boot 4.1.1 manages. They are cleared by patch overrides
of Boot's own properties in the root `build.gradle` (`tomcat.version`, `jackson-bom.version`,
`jackson-2-bom.version`, `freemarker.version`), each pinned in `gradle.properties`: Tomcat 11.0.26,
Jackson 3.1.7 and 2.21.7, FreeMarker 2.3.35 (the Eureka dashboard renders with it, so it is
overridden, not excluded). Each override must be dropped once Boot manages that version or newer.
Since 2026-10-15 conversion-svc also pins lz4-java 1.11.4 (`lz4JavaVersion` in `gradle.properties`,
a dependency constraint in its `build.gradle`) for CVE-2026-106451. It is a transitive override of
kafka-clients 4.2.1, not a Boot property, and must be dropped once Boot manages a kafka-clients that brings a fixed lz4.

One SERVICE finding remains and does not gate: `httpclient` 4.5.3, MEDIUM, via the Eureka client, in 3 of the 5 images.
The SHARED HIGH findings (OpenSSL in the run image, Go `stdlib` and `x/net` in buildpack helpers)
belong to the builder and run-image pins. trivy has no compose checks, so the misconfiguration stage
recognises none of the files it reads and reports zero checks rather than a clean bill.

## Re-rendering the verdict without rescanning

Reports land in `build/supply-chain/` (one JavaScript Object Notation (JSON) file per scanner plus `status.txt`, one exit code per
scanner):

```sh
SERVICES="config-svc conversion-svc eureka-svc gateway-svc ledger-svc" IMAGE_VERSION=0.3.1 \
  OUT_DIR=build/supply-chain CONSOLE_FILE=build/reports/supply-chain/console.txt \
  ./security/scan.sh gate secrets,sbom,images,config
```

For secrets, gitleaks reads git objects and trivy reads files, so the history is the half trivy
cannot see. That is why both run.
