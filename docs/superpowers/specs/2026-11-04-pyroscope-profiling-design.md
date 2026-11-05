# Continuous profiling with Pyroscope: design

Date: 2026-11-04. Branch: `feat-pyroscope-profiling`. Status: draft, awaiting Sean's approval.

This is project 1 of 2. Project 2, the ops-support agent (`/wat-triage`), consumes what this one produces
and has its own design document.

## 1. Intent

An engineer, or an agent, opens the trace of a slow or failed request, clicks the slow span, and lands
on the flame graph of the code that ran inside that span, for that service, in that time window.

Why: a trace says WHICH span was slow and stops at its boundary. Metrics are aggregated before anyone
asks. Neither can say which code inside a 400 ms `ezeebit.ledger.posting` span spent the time. Without a
profile, the code-level cause is an inference; with one, it is a picture that can be cited as evidence.

Success, in Sean's words: "click the span and land on its flame graph".

## 2. Current state (measured 2026-11-04 on `dev` at `5d11dfd`)

| Piece | State | Evidence |
|---|---|---|
| Pyroscope backend inside `grafana/otel-lgtm:0.36.0` (bumped to `0.39.0` by this project, 6.1) | running | `fleet.sh` lists `pyroscope` in `BACKENDS` and reports datasource health OK |
| Grafana datasource `uid: pyroscope`, `url: http://127.0.0.1:4040` | provisioned | `docker/lgtm/datasources.yaml:72-75` |
| Anything that SENDS profiles | absent | no `pyroscope`, `async-profiler`, `javaagent` or `JAVA_TOOL_OPTIONS` in any `build.gradle`, `k8s/base/app` or `compose.yaml` |
| Tempo `tracesToProfiles` | absent | Tempo block in `docker/lgtm/datasources.yaml` has `tracesToLogsV2`, `tracesToMetrics`, `serviceMap` only |
| Port 4040 reachable from the services | no | compose publishes 3000, 4317, 4318; the k8s `lgtm` Service and container expose 3000, 4317, 4318; `k8s/base/policy/obs.yaml` admits 4318 and 3000 |
| Dashboard row, absence alert, proof | absent | none in `docker/lgtm/generate.py` or `alerting/rules.yaml` |

The defect this design closes: a datasource that reports healthy and has never received a profile. It
reads as a working feature and is not one.

## 3. Goals

1. Every service (config, eureka, gateway, conversion, ledger) pushes profiles continuously, in compose
   and on kind, from ONE shared place.
2. Tempo's span view offers "Profiles for this span", and it opens a flame graph for that span.
3. Profiles carry the same identity as metrics: `service_name` and a per-JVM instance label.
4. A generated dashboard row and an absence alert, like every other signal in the fleet.
5. A rehearsed proof: an injected slow method appears, by name, in the flame graph reached by clicking
   its span, captured as a screenshot.

## 4. Non-goals

- Production-grade profile storage, retention or HA. The single `otel-lgtm` container stays the backend.
- Profiling anything that is not one of our JVMs (Postgres, Kafka, Redis, WireMock).
- The ops-support agent itself (project 2).

## 5. Anti-goals: a failure even if it technically works

- **A healthy datasource with no data, again.** Delivery is proven by a query that returns profiles,
  never by datasource health.
- **The profiler can take a service down.** It fails OPEN: a start or push failure is logged and
  counted, and the service keeps serving.
- **Tests push profiles.** Off in every test task, like OTLP export (`build.gradle:75-77`).
- **Two homes for the setting.** No per-service copy, no `-javaagent` flag in five manifests.
- **A privileged actor.** No eBPF profiler with `privileged: true` and `pid: host`.

## 6. Constraints

- Spring Boot 4.1.2 (after the bump in 6.1), Java 25, virtual threads on (pod CPU limit 2, `rules/_global/kubernetes.md` §3).
- Images built by Paketo buildpacks; no hand-written Dockerfile.
- Config only from the environment with working defaults (12FactorApp Alignment, https://12factor.net/);
  a clean clone runs with no `.env`.
- Dependency pins: newest release at least two weeks old, versions in `gradle.properties`. On
  2026-11-04: `io.pyroscope:agent:2.9.2` (Maven Central `lastUpdated` 2026-09-09) and
  `io.pyroscope:otel:2.1.4` (2026-10-08).
- `checkSupplyChain` must stay PASSED: the profiler ships a native library (async-profiler) in the image.
- No outdated versions (Sean, 2026-11-04). See 6.1.

### 6.1 Version currency (checked 2026-11-04)

| Component | Pinned now | Latest | Released | Action |
|---|---|---|---|---|
| `grafana/otel-lgtm` | `0.36.0` | `0.39.0` | 2026-10-30 (Docker Hub `tag_last_pushed`) | bump to `0.39.0`, Sean's instruction |
| Spring Boot | `4.1.1` | `4.1.2` | 2026-10-22 (Maven Central `last-modified`) | bump to `4.1.2` |
| `io.pyroscope:agent` | new | `2.9.2` | 2026-09-09 | use latest |
| `io.pyroscope:otel` | new | `2.1.4` | 2026-10-08 | use latest |

`[!BEST-PRACTICE-CONFLICT]` The repo's pin rule takes the newest release at least two weeks old, a
supply-chain cool-off (a compromised or broken release is usually pulled within days). `otel-lgtm:0.39.0`
is 5 days old and Boot `4.1.2` is 13; the rule would pick `0.37.0` and wait one day for `4.1.2`. Sean
asked for the latest, so both are bumped, and the cost is accepted: `checkSupplyChain` plus the full kind
sweep on the new image are the control that stands in for the cool-off.

Bump sites for `otel-lgtm` (grep `otel-lgtm:0\.` before and after; want one tag across all):
`compose.yaml`, `k8s/base/obs/lgtm.yaml` (2 sites), `ARCHITECTURE.md`, `docs/observability.md`,
`docs/k8s.md`. `docker/lgtm/test-rules.sh` reads the tag from `compose.yaml`. Committed sweep logs and
`EVIDENCE.md`'s record of a past run describe history and are not edited. Boot's single site is
`gradle.properties`. The Boot bump also dropped the tomcat, jackson and freemarker overrides (Boot 4.1.2
manages those versions) and moved `protoc` to `4.36.2` and `protoc-gen-grpc-java` to `1.84.1`.

The bumps land FIRST, as their own commit with their own green gates, so a regression from a new
image or Boot is never confused with one from profiling. Re-check every row above on the day the plan
executes: "latest" is a fact with an expiry date.
- At most 3 injected collaborators per bean; comments 5 lines max; no em dashes.
- Compose and kind stay at parity (same image tag, same config keys).

## 7. Design

### 7.1 Where it lives

The shared `observability` module, beside `OpenTelemetryLogbackAutoConfiguration`. It is compiled into
every service (root `build.gradle`), and its `config/application.yml` already carries the OTLP settings.
One auto-configuration starts the profiler; services change nothing.

### 7.2 Starting the profiler

The Pyroscope Java SDK (`io.pyroscope:agent`) is started from code (`PyroscopeAgent.start(...)`) by the
auto-configuration, not attached with `-javaagent`. Reason: a `-javaagent` path has to be present in the
Paketo image and named in every manifest, which is two homes for one fact.

Configuration, bound from properties with defaults in `observability/src/main/resources/config/application.yml`:

| Property | Default | Notes |
|---|---|---|
| `ezeebit.profiling.enabled` | `true` | set `false` in every test task |
| `ezeebit.profiling.server-address` | `${PYROSCOPE_URL:http://localhost:4040}` | new env var beside `OTLP_URL` |
| application name | `${spring.application.name}` | must match Tempo's `service.name` |
| instance label | same value as `service.instance.id` | one series per JVM, as for metrics |
| profile types | CPU (or wall, see 7.5), allocation, lock | |

Start and push failures are caught, logged once per cause, and counted on a Micrometer counter so the
absence is visible. They never fail the context.

### 7.3 Linking spans to profiles

`io.pyroscope:otel` provides a span processor that tags profile samples with the span they ran under.
It is registered as a `SpanProcessor` bean so Boot's OpenTelemetry SDK auto-configuration includes it.
Tempo's datasource gains `tracesToProfiles` (`datasourceUid: pyroscope`, tag `service.name` to
`service_name`, the chosen `profileTypeId`).

**Ruled 2026-11-04 by the spike** (`docs/observability/verification/2026-11-04-profiling-spike.md`):
- Boot 4.1.2 adds the `SpanProcessor` bean to its SDK and keeps its own exporter; the processor writes
  `pyroscope.profile.id` (read from the 2.1.4 jar) on each service's SERVER span only (`rootSpanOnly`).
- The span filter is distorted under virtual threads: the tracing context is per carrier, so over 14 slow
  spans the filtered graph was 97% carrier idle time, more time than the spans lasted, and held one 10 ms
  `RateCall.get` sample of the slow call. For the single filtered trace it held no frame of the slow call.
- The exact sub-second span window returned no samples, unfiltered included: profiles arrive in roughly
  10 s chunks, so any window must be padded by at least that.
- A service-window link without the span filter was ruled first and then withdrawn (Task 4, 2026-11-04):
  Grafana 13.3.0 cannot express it. Its bundled Tempo plugin always adds `spanSelector` with the span's
  `pyroscope.profile.id`, ignores time shifts for profiles, and drops the link on a span without the tag.

**Ruled 2026-11-04 (Sean): Grafana's native per-span link.** `tracesToProfiles` with `customQuery: false`.
Only SERVER spans carry the link; child spans have none. Grafana opens the span plus 60 s each side, filtered
by the span's profile id. Under virtual threads that graph is dominated by carrier idle time and a single
trace may hold no frame of the slow call; time parked on I/O does not appear (the span's duration shows it).
The service-wide view is the dashboard's profiling row (7.6), opened with the trace's time range. A
native-load failure of the processor degrades to a no-op processor counted on `ezeebit.profiling.failures`.

### 7.4 Network

Port 4040 opened on the same path as 4318, in both fleets:
- compose: publish `127.0.0.1:4040:4040` on `lgtm` (loopback only, as for the others).
- kind: `4040` on the `lgtm` Service and container ports, `k8s/base/policy/obs.yaml` ingress from the
  app namespace, and the matching egress in `k8s/base/policy/app.yaml`.
- `PYROSCOPE_URL: 'http://lgtm.ezeebit-obs:4040'` in each `k8s/base/app/*-svc.yaml`, beside `OTLP_URL`.

### 7.5 CPU versus wall-clock

async-profiler records one execution event at a time. CPU shows code burning cycles; wall-clock also
shows threads waiting, which is where our gRPC, JDBC and Redis latency goes. Virtual threads parked on
I/O use no CPU, so a CPU profile can make a slow span look empty.

Decided by measurement, not here: the proof (section 8) injects one CPU-bound and one blocking slow path,
and the event that shows BOTH by name in the clicked flame graph wins. If neither shows both, ship
wall-clock and say so.

**Ruled 2026-11-04: `EVENT=wall`.** With an 800 ms WireMock delay on the rate call, neither event showed the
wait: `RateCall.get` was 10 ms (one sample, before the park) under both, against 11 to 14 s of injected delay,
while `io.ezeebit` frames appeared under both. On the literal rule above the two events tie, since neither
shows both; the tie was ruled to wall, the shipped default. A parked virtual thread is on
no OS thread, so no async-profiler event sees it: the trace answers "where did the time go", the profile
answers "what was the JVM doing in that window". Evidence: the spike file named in 7.3.

### 7.6 Dashboard and alert

- `docker/lgtm/generate.py` gains a profiling row: a flame graph panel per service and a profiles
  received indicator. `--check` must stay green and `verify` must execute the new targets.
- An absence alert for "a service sent no profiles for N minutes", in the Grafana-managed half (the
  responder opens Explore, `rules/_global/observability.md` §7), with a `promtool` or eval test where
  the engine allows it, red-proofed. The builder finds the series to alert on (a Pyroscope ingest metric
  or the SDK-side counter from 7.2) and documents which.

## 8. Proof (what "done" means)

1. Unit: the auto-configuration starts with `enabled=true` and does not with `false`; a start failure
   does not fail the context (red-proofed by forcing the failure).
2. Delivery: a query against the Pyroscope API returns profiles for each of the 5 services, on compose
   AND on kind. Red-proof (ruled 2026-11-04): the same query on the same kind fleet with
   `EZEEBIT_PROFILING_ENABLED=false` on all five Deployments, run after the query window has passed, returns
   none. The cost: a `dev` build, which has no SDK at all, is not shown separately; the red proves the query
   sees the profiler stopping, not the SDK's absence.
3. Identity: at 2 replicas, two distinct instance labels for ledger, gateway and conversion.
4. Click-through, Playwright: drive traffic that hits an injected slow method, open its trace in Tempo,
   click the span, follow "Profiles for this span". Ruled 2026-11-04 (Sean: "per-span link + dashboard"):
   the span link is asserted by its datasource request (the service and the span's profile id) plus a
   non-empty flame graph, and the injected method's name is asserted on the dashboard's profiling row over
   the trace's time range padded by at least 10 s each side, because under virtual threads the per-span
   graph is carrier idle time. Screenshot, read the image, and assert from the datasource request, not only
   the pixels.
5. Overhead: p95 latency and CPU with profiling on and off over the same traffic. Budget (Sean,
   2026-11-04): at most +5% p95, and added CPU at most 5% of the pod CPU limit (100 millicores at limit 2).
   The relative CPU delta is reported beside it but does not gate: on a near-idle baseline it is a ratio of
   small numbers (idle config-svc read +57% for 2 to 3 millicores). Numbers committed as evidence.
6. Gates: `./gradlew clean build`, `checkSupplyChain`, and the kind sweep stay green.
7. A written statement of what is still unproven.

## 9. Review focus: the inputs most likely to bite

1. A test task boots a context and pushes profiles to a port nothing listens on. Expected: disabled, no
   log noise.
2. Two replicas merged under one label. Expected: one instance label per JVM.
3. The native library fails to load on the Paketo base or on arm64 kind nodes. Expected: the service
   starts, the failure is logged and counted, the absence alert fires.
4. A child span is clicked and the link opens the root span's profile. Expected: documented, not hidden.
5. A blocking slow path under virtual threads disappears from a CPU profile. Expected: caught by the
   blocking half of the proof in section 8.

## 10. Rejected alternatives

| Alternative | Why not |
|---|---|
| `-javaagent` via `JAVA_TOOL_OPTIONS` | agent jar must be added to the Paketo image and the flag repeated in 5 manifests and compose |
| OTel eBPF profiler (the timosalm demo) | `privileged: true`, `pid: host`; JVM frames need extra perf-map flags |
| Grafana Alloy `pyroscope.java` | an extra component that attaches into other processes, needing their pid namespace |
| OTLP profiles through the collector on 4318 | the OTLP profiles signal is still behind a feature gate; revisit when stable |

## 11. Decisions

Sean's: profiling comes before the agent; success is clicking the span and landing on its flame graph;
no outdated versions, `otel-lgtm` at `0.39.0`.

Claude's calls, open to override: SDK started from code in the shared module; `PYROSCOPE_URL` as a new
env var; the overhead budget (5% of the pod CPU limit in added CPU, +5% p95, section 8 item 5); CPU versus wall decided by the proof; loopback-only port in compose.

### 11.1 Rulings from the builder check (2026-11-04)

Sean's answers:
- **Virtual threads.** async-profiler samples the OS carrier thread, so the span link may never attach
  to a virtual thread's samples. The FIRST task is a spike that settles this on one service. If
  per-span linking fails, "click the span" opens that service's flame graph for the span's exact time
  window (service plus window, no span filter), and the doc and the dashboard say so. Proof 8.4 then
  asserts the injected method in that windowed graph.
- **Both bumps now:** `otel-lgtm:0.39.0` and Spring Boot `4.1.2`, accepting the cool-off conflict in 6.1.

Claude's rulings:

- **Image still serves Pyroscope on 4040?** Verified in the bump commit, before any profiling code:
  start `otel-lgtm:0.39.0`, require the Pyroscope datasource health AND a `POST /ingest` answer on
  4040. If the port or path moved, fix 7.4 in that commit; if Pyroscope is gone from the image, stop.
- **`checkSupplyChain` flags the profiler's native library.** The fleet's existing policy applies: a
  fixable HIGH or CRITICAL blocks, and is fixed by a version bump; one with no fix is named in
  `docs/supply-chain.md` with its reason. Never a silent suppression.
- **Overhead over budget.** In order: lower the sampling rate, then drop lock, then allocation
  profiling. Still over budget after all three: stop and report with the numbers.
- **Unrelated working-tree changes** (`services/ledger-svc/.../002-account.xml`, `docs/question/`)
  are Sean's and stay out of every commit on this branch: commit with explicit pathspecs.
  - **Superseded by Sean's ruling of 2026-11-05.** The ledger 2026/10 changeset reformat (all six files
    under `services/ledger-svc/src/main/resources/db/changelog/2026/10/`, `002-account.xml` among them) was
    committed on this branch in `e997664`, after the Liquibase 5.0.4 v9 checksums of all 16 changesets were
    verified unchanged against `5d11dfd`, with a positive control
    (`docs/evidence/liquibase/2026-11-05-ledger-checksums/run.md`). `docs/question/` moved to the
    `feat-presentation` branch, and `presentation.md` stays out through the local exclude.

## 12. Left to the builder

The exact Pyroscope SDK builder API calls, label names, alert series, panel JSON, and the injected slow
path's shape (it must not ship in `src/main`; an acceptance-test-only bean or a test profile).
