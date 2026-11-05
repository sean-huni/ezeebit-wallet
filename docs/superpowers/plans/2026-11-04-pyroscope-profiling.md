# Pyroscope Continuous Profiling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every service pushes profiles to the bundled Pyroscope, and clicking a span in Tempo opens the flame graph of the code that ran under it.

**Architecture:** The Pyroscope Java SDK is started from code by an auto-configuration in the shared `observability` sources (compiled into every service by the root `build.gradle`). An OTel span processor links samples to spans; Tempo's datasource gains `tracesToProfiles`. Port 4040 is opened on the same path as OTLP's 4318 in compose and kind.

**Tech Stack:** Spring Boot 4.1.2, Java 25 (virtual threads), `io.pyroscope:agent:2.9.2`, `io.pyroscope:otel:2.1.4`, `grafana/otel-lgtm:0.39.0`, Gradle, kind, Playwright (Node, existing harness in `docs/observability/verification/`).

**Spec:** `docs/superpowers/specs/2026-11-04-pyroscope-profiling-design.md`

## Global Constraints

- Branch: `feat-pyroscope-profiling` (already holds the specs). Never touch `dev`, `main` or tags.
- Never stage `services/ledger-svc/src/main/resources/db/changelog/2026/10/002-account.xml` or `docs/question/`: commit with explicit pathspecs only.
- Versions: `grafana/otel-lgtm:0.39.0`, Spring Boot `4.1.2`, `io.pyroscope:agent:2.9.2`, `io.pyroscope:otel:2.1.4`. Re-check each is still the latest on the day of Task 1; record the check date beside the pin.
- Boot 4.1.2 manages `opentelemetry.version` 1.62.0, the same as 4.1.1 (spring-boot-dependencies POMs, checked 2026-11-04), so `otelLogbackAppenderVersion=2.28.1-alpha` stays.
- Profiler fails OPEN: no start or push failure may fail a context or stop a service.
- Profiling is off in every test task and every test-launched process.
- At most 3 injected collaborators per bean; comment blocks 5 lines max; no em dashes anywhere.
- Compose and kind at parity; `k8s/gates/image-parity.sh` must pass.
- Evidence logs carry the command's own exit code; key evidence files by date AND topic; never overwrite a tracked evidence file.
- The full gate is `./gradlew clean build` from the main checkout, plus `checkSupplyChain` (after `bootBuildImageAll`) and `k8s/kube.sh sweep` where a task says so.
- Correction to the spec: `docker/lgtm/generate.py` has a `--check` mode only, no `verify` mode (spec 7.6). New panel queries are proven live by Task 7's delivery script and Task 6's promtool test instead.

## Review Focus

1. A test context or a test-launched service process starts the profiler and pushes to a port nothing listens on: expected disabled, no log noise (Task 2 tests + Task 2 Step 6 grep).
2. The native library fails to load (`UnsatisfiedLinkError`, a `LinkageError` not an `Exception`): expected the context starts and `ezeebit.profiling.failures{cause="UnsatisfiedLinkError"}` counts 1 (Task 2 test).
3. Pyroscope unreachable for minutes: expected the service serves normally and the SDK logs at most one line per failure cause per minute (Task 2 Step 8).
4. Two replicas of one service: expected two distinct instance labels, never one merged series (Task 7 Step 4).
5. A blocking slow path on a virtual thread vanishes from a CPU profile, or a child span opens its root span's profile: expected settled and documented by the Task 3 ruling, and asserted in Task 7's click-through.

---

### Task 1: Version bumps (otel-lgtm 0.39.0, Spring Boot 4.1.2), alone and green

**Files:**
- Modify: `compose.yaml:80`, `k8s/base/obs/lgtm.yaml:64` and `:85`, `ARCHITECTURE.md:94`, `docs/observability.md:8`, `docs/k8s.md:173` (tag `0.36.0` to `0.39.0`)
- Modify: `gradle.properties:1` (`springBootVersion=4.1.2`); update the "4.1.1" mentions in `gradle.properties` comments and `build.gradle:36` that name the managed POM
- Do NOT modify: `docs/k8s/verification/*.log`, `EVIDENCE.md` (history)

- [ ] **Step 1:** Re-check "latest" for all four pins (Docker Hub tag API for otel-lgtm, Maven Central `maven-metadata.xml` for the rest). If any moved, use the new latest and note it in the commit.
- [ ] **Step 2:** Before editing, run `grep -rn "otel-lgtm:0\." --exclude-dir=build --exclude-dir=node_modules --exclude-dir=.git .` and save the hit list; after editing, re-run and require every non-history hit to read `0.39.0`.
- [ ] **Step 3:** Prove the new image still serves Pyroscope ingest on 4040: `docker run --rm -d --name lgtm-probe -p 127.0.0.1:14040:4040 grafana/otel-lgtm:0.39.0`, wait for readiness, then `curl -s -o /dev/null -w '%{http_code}' -X POST 'http://127.0.0.1:14040/ingest?name=probe.cpu'`. Expected: an HTTP code, not `000` (400 for an empty body is fine). Control: the same curl on a closed port returns `000`. Stop the container. If Pyroscope is gone from the image: STOP and report.
- [ ] **Step 4:** `./gradlew clean build; echo "exit=$?"`. Expected `exit=0` and the same test count as `dev` (641 on 2026-11-04).
- [ ] **Step 5:** `./gradlew bootBuildImageAll checkSupplyChain; echo "exit=$?"`. Expected `exit=0`. A fixable HIGH/CRITICAL blocks: pin the fixed version as the repo already does in `gradle.properties`.
- [ ] **Step 6:** `bash k8s/gates/image-parity.sh .; echo "exit=$?"` and `bash docker/lgtm/test-rules.sh; echo "exit=$?"`. Expected both `exit=0`.
- [ ] **Step 7: Commit** with explicit pathspecs: "Bump otel-lgtm to 0.39.0 and Spring Boot to 4.1.2".

### Task 2: Profiler auto-configuration (fail-open, off in tests)

**Files:**
- Modify: `gradle.properties` (add `pyroscopeAgentVersion=2.9.2`, `pyroscopeOtelVersion=2.1.4` with a "latest on Maven Central, checked <date>" comment)
- Modify: `build.gradle:54-60` (add `implementation "io.pyroscope:agent:${pyroscopeAgentVersion}"` and `implementation "io.pyroscope:otel:${pyroscopeOtelVersion}"`), `build.gradle:47-50` (add `java.srcDir rootProject.file('observability/src/test/java')` to the `test` source set), `build.gradle:75-77` (add `systemProperty 'ezeebit.profiling.enabled', 'false'`)
- Create: `observability/src/main/java/io/ezeebit/observability/ProfilingProperties.java`
- Create: `observability/src/main/java/io/ezeebit/observability/ProfilerLauncher.java`
- Create: `observability/src/main/java/io/ezeebit/observability/PyroscopeLauncher.java`
- Create: `observability/src/main/java/io/ezeebit/observability/ProfilerStarter.java`
- Create: `observability/src/main/java/io/ezeebit/observability/PyroscopeAutoConfiguration.java`
- Modify: `observability/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (add the new class)
- Modify: `observability/src/main/resources/config/application.yml` (the `ezeebit.profiling` block below)
- Modify: `services/conversion-svc/src/acceptanceTest/java/io/ezeebit/conversion/acceptance/LedgerProcess.java:43-44` (add `"--ezeebit.profiling.enabled=false"`)
- Test: `observability/src/test/java/io/ezeebit/observability/PyroscopeAutoConfigurationTest.java`

**Interfaces:**
- Produces: `record ProfilingProperties(boolean enabled, URI serverAddress, String applicationName, String instance, String event, DataSize alloc, Duration lock)` bound to prefix `ezeebit.profiling`.
- Produces: `@FunctionalInterface interface ProfilerLauncher { void launch(ProfilingProperties properties); }`; default bean `PyroscopeLauncher` builds the SDK `Config` (application name, server address, label `instance`, profiling event, alloc and lock thresholds, JFR format) and calls `PyroscopeAgent.start`.
- Produces: `ProfilerStarter(ProfilingProperties, ProfilerLauncher, MeterRegistry)` implementing `SmartInitializingSingleton`; catches `RuntimeException` and `LinkageError` from `launch`, logs once at WARN with the cause class, increments counter `ezeebit.profiling.failures` tagged `cause=<simple class name>`.
- Produces: `PyroscopeAutoConfiguration`, `@ConditionalOnProperty(prefix = "ezeebit.profiling", name = "enabled", havingValue = "true", matchIfMissing = true)`, beans `ProfilerLauncher` (`@ConditionalOnMissingBean`) and `ProfilerStarter`.

`application.yml` defaults (verbatim):

```yaml
ezeebit:
  profiling:
    enabled: true
    server-address: ${PYROSCOPE_URL:http://localhost:4040}
    application-name: ${spring.application.name}
    instance: ${management.opentelemetry.resource-attributes.service.instance.id}
    event: wall
    alloc: 512KB
    lock: 10ms
```

(`event: wall` is provisional; Task 3 rules on it.)

- [ ] **Step 1: Write the failing tests** in `PyroscopeAutoConfigurationTest`, using `ApplicationContextRunner` with `SimpleMeterRegistry` and a recording `ProfilerLauncher` bean:
  - `startsTheProfilerWhenEnabled`: `withPropertyValues("ezeebit.profiling.enabled=true", "spring.application.name=svc", ...)`; assert the launcher received properties with `applicationName() == "svc"` and `serverAddress()` equal to the configured URI.
  - `doesNotStartWhenDisabled`: `enabled=false`; assert no `ProfilerStarter` bean and the launcher never called.
  - `nativeLoadFailureDoesNotFailTheContext`: launcher throws `new UnsatisfiedLinkError("no libasyncProfiler")`; assert `context.getStartupFailure()` is null and `registry.counter("ezeebit.profiling.failures", "cause", "UnsatisfiedLinkError").count() == 1.0`.
  - `runtimeFailureDoesNotFailTheContext`: launcher throws `IllegalStateException`; same assertions with `cause=IllegalStateException`.
- [ ] **Step 2:** `./gradlew :services:ledger-svc:test --tests '*PyroscopeAutoConfigurationTest*'; echo "exit=$?"`. Expected: FAIL (classes do not exist). Save the red output to the scratchpad.
- [ ] **Step 3:** Implement the five classes, the imports line, the yml block, the Gradle edits and the `LedgerProcess` argument.
- [ ] **Step 4:** Re-run Step 2's command. Expected `exit=0`, 4 tests passing.
- [ ] **Step 5:** Red-proof the fail-open: temporarily remove the `LinkageError` catch, re-run, require `nativeLoadFailureDoesNotFailTheContext` red, restore (fresh mtime), re-run green.
- [ ] **Step 6:** Prove nothing else launches a service with profiling on: `grep -rn "management.logging.export.otlp.enabled" --include='*.java' --include='*.gradle' --include='*.sh' . | grep -v /build/` and require every hit to sit beside an `ezeebit.profiling.enabled` false. Print both counts.
- [ ] **Step 7:** `./gradlew clean build; echo "exit=$?"`. Expected `exit=0`; test count = Task 1's plus 4 per service.
- [ ] **Step 8:** Fleet with Pyroscope unreachable: `./fleet.sh up` with `PYROSCOPE_URL=http://127.0.0.1:1`, run 3 minutes, count profiler log lines per service. Expected: gateway 200 and at most one line per failure cause per minute. If the SDK is noisier, lower its logger level in `observability/src/main/resources/logback-spring.xml` for its package and re-measure.
- [ ] **Step 9: Commit** "Start the Pyroscope profiler from the shared observability module, fail-open and off in tests".

### Task 3: Spike and ruling: span link under virtual threads, CPU versus wall

**Files:**
- Modify: `observability/src/main/java/io/ezeebit/observability/PyroscopeAutoConfiguration.java` (add `@Bean SpanProcessor pyroscopeSpanProcessor()` returning the `io.pyroscope:otel` processor)
- Modify: `compose.yaml` lgtm ports (add `'127.0.0.1:4040:4040'`)
- Modify: `docs/superpowers/specs/2026-11-04-pyroscope-profiling-design.md` sections 7.3 and 7.5 (record the ruling)
- Create: `docs/observability/verification/2026-11-04-profiling-spike.md` (evidence)

**Interfaces:**
- Consumes: Task 2's auto-configuration.
- Produces: a written ruling with two values Tasks 4 and 7 consume: `LINK_MODE` (`per-span` or `window`) and `EVENT` (`itimer` or `wall`), plus the span attribute name the processor writes.

- [ ] **Step 1:** Confirm Boot 4.1.2 registers a `SpanProcessor` bean into its SDK: start ledger-svc locally, create one trace, and show the processor's attribute on the root span in Tempo. Read the 2.1.4 processor's source in the jar to name the attribute; record it.
- [ ] **Step 2:** Run the fleet on compose with `event: wall`. Inject a blocking slow path with no code change: add a WireMock mapping at runtime (`POST /__admin/mappings`, `fixedDelayMilliseconds: 800`) on the rate provider path the conversion-svc rate client calls (read `docker/wiremock/mappings` for the path). Drive quotes with `docs/observability/verification/traffic.mjs`.
- [ ] **Step 3:** For one slow trace, query Pyroscope over the span's window once filtered by the Step 1 attribute and once unfiltered. Record whether the filtered query returns samples, and whether the delayed call's frames (the rate client class, a socket read) appear.
- [ ] **Step 4:** Repeat Steps 2 and 3 with `event: itimer`.
- [ ] **Step 5: Rule**, per the spec: `LINK_MODE=per-span` only if the filtered query returned the slow call's frames on a virtual thread; otherwise `window` (Sean's agreed fallback). `EVENT` is the event under which the injected blocking call AND at least one `io.ezeebit` frame appear; if neither shows both, `wall`. Write both values, the evidence and the commands into the spike file and spec sections 7.3 and 7.5. Set `event:` in `application.yml` to the ruling.
- [ ] **Step 6: Commit** "Link spans to profiles and rule on the link mode and profiling event".

### Task 4: Tempo `tracesToProfiles`

**Files:**
- Modify: `docker/lgtm/datasources.yaml` (Tempo `jsonData.tracesToProfiles`)
- Modify: `docs/observability.md` (Profiling section: what is profiled, the link mode and what a click on a child span opens, `PYROSCOPE_URL`)

**Interfaces:**
- Consumes: Task 3's `LINK_MODE`, `EVENT` and attribute name.

- [ ] **Step 1:** Add `tracesToProfiles` with `datasourceUid: pyroscope`, `tags: [{key: service.name, value: service_name}]`, the `profileTypeId` Pyroscope lists for the ruled event (confirm against the type list it returns for our services), and `customQuery` per `LINK_MODE` (`false` for per-span; for `window`, a query on `service_name` only with zero span time shifts).
- [ ] **Step 2:** Restart lgtm on compose; open one slow trace and confirm "Profiles for this span" renders a non-empty flame graph. Screenshot to `docs/observability/verification/out/` (gitignored) and look at it.
- [ ] **Step 3:** Write the Profiling section in `docs/observability.md`, stating the ruled link mode in one sentence.
- [ ] **Step 4: Commit** "Open a span's flame graph from Tempo".

### Task 5: kind wiring: Service, container port, NetworkPolicies, `PYROSCOPE_URL`

**Files:**
- Modify: `k8s/base/obs/lgtm.yaml` (Service ports lines 11-13: add `{name: pyroscope, port: 4040, targetPort: 4040}`; container ports lines 93-95: add `{name: pyroscope, containerPort: 4040}`)
- Modify: `k8s/base/policy/obs.yaml:19-20` (ingress from the five app services: add `{protocol: TCP, port: 4040}` beside 4318)
- Modify: `k8s/base/policy/app.yaml` (each egress block to lgtm, at lines 58, 106, 166, 193, 221: add 4040 beside 4318)
- Modify: `k8s/base/app/{config,eureka,gateway,ledger,conversion}-svc.yaml` (env `{name: PYROSCOPE_URL, value: 'http://lgtm.ezeebit-obs:4040'}` beside `OTLP_URL`)

- [ ] **Step 1:** Measure the blast radius first: `grep -rn "4318" k8s/base` and `grep -rln "OTLP_URL" k8s/base/app`; record counts. Every 4318 policy site gets a 4040 sibling; every `OTLP_URL` site gets a `PYROSCOPE_URL`. Report sites found against sites changed.
- [ ] **Step 2:** `bash k8s/gates/manifests.sh .; echo "exit=$?"` and the build's k8s gate task. Expected `exit=0`.
- [ ] **Step 3:** `k8s/kube.sh up` with this branch's images; prove a profile arrives for `ledger-svc` (Task 7's delivery script, ledger only). Control: a probe pod with a label nothing selects is refused on 4040 (`rules/_global/kubernetes.md` §5 denied arm).
- [ ] **Step 4: Commit** "Open the profile path to Pyroscope on kind".

### Task 6: Dashboard row and absence alert

**Files:**
- Modify: `docker/lgtm/generate.py` (a profiling row on the fleet board; one rule in `RULES`)
- Regenerate: `docker/lgtm/dashboards/*.json`, `docker/lgtm/alerting/rules.yaml`, the promtool mirror
- Modify: the promtool test file under `docker/lgtm/alerting/promtool/`

**Interfaces:**
- Consumes: counter `ezeebit_profiling_failures_total{cause}` (Task 2) as exported to Prometheus; Pyroscope datasource uid `pyroscope`.

- [ ] **Step 1: Pick the alert series.** List metric names in the lgtm Prometheus through the datasource proxy and look for a Pyroscope ingest metric per `service_name`. If one exists, alert on its absence over a range window (`absent_over_time`, observability §2). If none, alert on `ezeebit_profiling_failures_total` with the repo's `increase_new` helper (the counter appears mid-window). Record the choice and why in the rule description.
- [ ] **Step 2: Write the failing promtool case**: input series where one service stops sending (or fails), an `eval_time` where the alert fires naming that service, and one where nothing fires.
- [ ] **Step 3:** `bash docker/lgtm/test-rules.sh; echo "exit=$?"`. Expected FAIL before the rule exists.
- [ ] **Step 4:** Add the rule to `RULES` and a profiling row (one flame graph panel per service on the Pyroscope datasource, plus a stat of `ezeebit_profiling_failures_total`), then `python3 docker/lgtm/generate.py`.
- [ ] **Step 5:** `bash docker/lgtm/test-rules.sh; echo "exit=$?"` and `./gradlew checkDashboards; echo "exit=$?"`. Expected both `exit=0`. Red-proof: break the rule's selector, require red, restore.
- [ ] **Step 6: Commit** "Add the profiling dashboard row and the profiles-absent alert".

### Task 7: Proof: delivery, identity, click-through, overhead

**Files:**
- Create: `docs/observability/verification/2026-11-04-profiles-delivery.sh`
- Create: `docs/observability/verification/2026-11-04-span-to-profile.mjs`
- Create: `docs/observability/verification/2026-11-04-profiling-proof.md` (results, numbers, exit codes, what is unproven)

**Interfaces:**
- Consumes: Task 3's `LINK_MODE` and `EVENT`; Task 4's Tempo link; Task 5's kind wiring.

- [ ] **Step 1: Delivery script.** `2026-11-04-profiles-delivery.sh <grafana-url>` asks Pyroscope (through the Grafana datasource proxy, uid `pyroscope`) for the values of `service_name` over the last 10 minutes and exits 0 only when all five services are present; prints the list and `exit=`. Its own control: a service name that does not exist must return nothing.
- [ ] **Step 2: Red-proof delivery** against a fleet built from `dev` (no SDK): expected exit 1 naming all five missing.
- [ ] **Step 3: Delivery green** on compose (`./fleet.sh up`) and on kind (`k8s/kube.sh up`). Expected exit 0 on both; paste both outputs into the proof file.
- [ ] **Step 4: Identity.** On kind (ledger, gateway, conversion at 2 replicas), query the `instance` label values per service; expected 2 distinct values each, matching the pods' `service.instance.id`.
- [ ] **Step 5: Click-through, Playwright,** following the existing harness conventions (`package.json`, `channel: 'chrome'`, never the real Chrome profile): inject the Task 3 WireMock delay, drive traffic, open the slow trace in Grafana, click the slowest conversion span, follow "Profiles for this span", and assert (a) the datasource request named the expected service (and span filter if `LINK_MODE=per-span`) and (b) the flame graph contains the rate client's frame name. Screenshot each state; read the images.
- [ ] **Step 6: Overhead.** (Superseded by spec section 8 item 5: kind, not compose, and the budget is added CPU at most 5% of the pod CPU limit plus +5% p95; the relative CPU delta does not gate.) Same traffic for 5 minutes twice on compose, profiling on then off: record p95 of `http_server_requests` and mean `process_cpu_usage` per service. Budget +5% CPU and +5% p95. Over budget: lower sampling, then drop lock, then alloc, re-measuring each; still over: STOP and report.
- [ ] **Step 7:** Write the proof file: every command, exit code and number, both fleets, and a "not proven" list.
- [ ] **Step 8: Commit** "Prove profile delivery, identity and the span click-through on compose and kind".

### Task 8: Full gates and kind sweep

- [ ] **Step 1:** `./gradlew clean build; echo "exit=$?"` from the main checkout. Expected `exit=0`.
- [ ] **Step 2:** `./gradlew bootBuildImageAll checkSupplyChain; echo "exit=$?"`. Expected `exit=0`; a non-fixable finding in the profiler's native library is named in `docs/supply-chain.md` with its reason.
- [ ] **Step 3:** `k8s/kube.sh sweep` with its capture written OUTSIDE `.kube-fleet/` (scratchpad); read the sweep's own log for `sweep exit=0`. Commit it as `docs/k8s/verification/2026-11-04-profiling-sweep.log` (new path; never overwrite a tracked log).
- [ ] **Step 4:** Grep every changed file for em dashes; require 0.
- [ ] **Step 5: Commit** "Commit the profiling sweep and gate evidence". The branch is then ready for review; merging to `dev` is Sean's call.
