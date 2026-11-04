# Profiling proof: delivery, identity, span click-through, overhead (2026-11-04)

Proof for spec section 8 of `docs/superpowers/specs/2026-11-04-pyroscope-profiling-design.md`, with the rulings in
force: `LINK_MODE=per-span` (Grafana's native "Profiles for this span", span plus 60 s each side, filtered by
`pyroscope.profile.id`) and `EVENT=wall`. Branch `feat-pyroscope-profiling` at `c035dcf`. Times are UTC.

Universe: kind cluster `kind-ezeebit` (Grafana `http://127.0.0.1:3000` through NodePort 30300, gateway
`http://127.0.0.1:8080` through NodePort 30080), images `ezeebit/*:0.3.2` already built from this tree; then the
compose fleet (`./fleet.sh up`, Grafana `http://127.0.0.1:3000`). Only one fleet ran at a time.

Image identity before every kind measurement (`2026-11-04-overhead-imgcheck.sh`, the chain `k8s/kube.sh sweep_images` uses plus the
running containers): the host's manifest digest (`docker image inspect .Descriptor.digest`) equals the node's
(`ctr -n k8s.io images ls`), and every running app container's image (`crictl ps .imageRef`) equals that manifest's
config digest (`ctr content get`). Result each time: `imgcheck exit=0`, 5 of 5 services matched:

```
config-svc     manifest 8af5792cd005  config c06f20761bbf
eureka-svc     manifest 27ee18e251a3  config 660dde1a6b34
ledger-svc     manifest 1c1c21679127  config 600d2e8b6679
conversion-svc manifest 425cc0e4c676  config 501f0788bf4d
gateway-svc    manifest e60a5260afd5  config 097bfc5aadc2
```

Fix round 1 (images rebuilt from `b2d53e6`), same check, `imgcheck exit=0` each time:

```
config-svc     manifest 4883868c67cd  config 808667dda157
eureka-svc     manifest 3acf066f69c3  config e097104d8e9d
ledger-svc     manifest f845b6e24c7c  config 7c467558c407
conversion-svc manifest 64c68bfc6d42  config 4dff3999ad54
gateway-svc    manifest 639c215a912f  config ae85d30b0b6f
```

## 1. Delivery (spec 8.2)

`docs/observability/verification/2026-11-04-profiles-delivery.sh <grafana-url>` calls
`POST <grafana>/api/datasources/proxy/uid/pyroscope/querier.v1.QuerierService/LabelValues` for `service_name` over
the last 600 s (`PROFILES_WINDOW_SECONDS`). Exit 0 all five present, 1 any missing (named), 2 could not query.
Its controls, each run once:

| arm | how | result |
|---|---|---|
| control: a service that does not exist returns nothing | built in, every run | `no-such-svc-<pid> returned nothing (expected)` |
| control arm can go red | copy with `CONTROL=ledger-svc` | `control: ledger-svc returned [ledger-svc], expected nothing`, `exit=2` |
| could not query | Grafana URL `http://127.0.0.1:3999` (nothing listening) | `curl exit 7, HTTP 000`, `exit=2` |
| wrong datasource | copy with uid `nope` | HTTP 404, `exit=2` |

Green on kind, 17:03:18 (and again at 18:11:59 after every revert):

```
control: no-such-svc-8782 returned nothing (expected)
service_name values: [config-svc conversion-svc eureka-svc gateway-svc ledger-svc pyroscope]
present config-svc / present eureka-svc / present ledger-svc / present conversion-svc / present gateway-svc
all 5 services present
exit=0
```

Red-proof on kind, as ruled: `EZEEBIT_PROFILING_ENABLED=false` set on all five app Deployments with
`kubectl set env` (rollouts done 17:36:37, the last profiled pod gone 17:36:56), script run at 17:47:29, 10 min
33 s later, so the whole 600 s window postdates the last profiled JVM:

```
control: no-such-svc-32582 returned nothing (expected)
service_name values: [pyroscope]
MISSING config-svc / MISSING eureka-svc / MISSING ledger-svc / MISSING conversion-svc / MISSING gateway-svc
missing 5 of 5: config-svc eureka-svc ledger-svc conversion-svc gateway-svc
exit=1
```

Reverted (`EZEEBIT_PROFILING_ENABLED-` with the other overrides below, rollouts exit 0, 0 `PROFILING` env entries
left on any Deployment), green again at 18:11:59: `all 5 services present`, `exit=0`.

Green on compose: `k8s/kube.sh down` (exit 0), `./fleet.sh up` (exit 0, "RESULT: healthy, all 16 checks
passed", lgtm publishes 4040, `GET :4040/ready` 200), script at 18:14:32:

```
control: no-such-svc-42539 returned nothing (expected)
service_name values: [config-svc conversion-svc eureka-svc gateway-svc ledger-svc pyroscope]
all 5 services present
exit=0
```

The compose profiles are this fleet's: `instance` over the last 60 s was `localhost:8080:41018`,
`localhost:8081:41016`, `localhost:8082:41017`, `localhost:8761:40964`, `localhost:8888:40904`, the PIDs the
fleet printed for gateway, ledger, conversion, eureka and config. Then `./fleet.sh down` (exit 0, guard released).

## 2. Identity at 2 replicas (spec 8.3)

Kind, 18:12:07, Pyroscope `LabelValues` for `instance` per service over the last 30 s, beside the running pods and
the OpenTelemetry resource `service.instance.id` (`last_over_time(target_info[30s])` in Prometheus):

| service | Pyroscope `instance` | pods | `service.instance.id` |
|---|---|---|---|
| ledger-svc | `ledger-svc-84dc7b6665-btcvj:8081:1`, `ledger-svc-84dc7b6665-kjfsd:8081:1` | same two | same two |
| gateway-svc | `gateway-svc-74d85884c8-l6r8h:8080:1`, `gateway-svc-74d85884c8-xnzfj:8080:1` | same two | same two |
| conversion-svc | `conversion-svc-f799758c6-mvnsh:8082:1`, `conversion-svc-f799758c6-q7zrc:8082:1` | same two | same two |

Two distinct values each, equal to `service.instance.id` (`HOSTNAME:port:PID`, PID 1 in a container). The same
held at 17:03 on the previous pod generation (120 s window, 2 each, equal to Prometheus `instance`). A wider
window also returns the previous generation's pods, which is history, not a split: at 60 s right after a rollout
each service listed 4.

## 3. Span click-through (spec 8.4), at the shipped 10 ms interval

`node docs/observability/verification/2026-11-04-span-to-profile.mjs` on kind, run twice at 10 ms, exit 0 both
times, every check PASS. It adds a WireMock mapping through a port-forward to `svc/wiremock` (`18089:8080`):
`GET /rates` ZAR/USDT with `fixedDelayMilliseconds: 800` (POST 201). It drives 48 quotes (4 workers x 12, all
`201`, all at least 800 ms), deletes the mapping (DELETE 200; 39 mappings left, 0 with `fixedDelayMilliseconds`,
39 before), waits 30 s, finds the slow traces with TraceQL
`{resource.service.name="conversion-svc" && name="http get" && duration>700ms}` (48 found) and takes the middle one.

| | run 1, 17:04, images from `c035dcf` | run 2, 19:35, images from `b2d53e6` |
|---|---|---|
| trace | `cae9bcfa41894c81f9483229f2b60fc3` | `428ec6a0d1fe8a3910e926fc50688374` |
| conversion SERVER span, `pyroscope.profile.id` | `c8b811cf1387b313`, 808 ms, same id | `e8ee9d117ec35639`, 807 ms, same id |
| "Profiles for this span" | offered once | offered once |
| (a) link request | `{service_name="conversion-svc"}`, `spanSelector=["c8b811cf1387b313"]`, span +/- 60 s | `{service_name="conversion-svc"}`, `spanSelector=["e8ee9d117ec35639"]`, span +/- 60 s |
| (b) span flame graph | 41 rows, 150 ms, canvas 343x682 | 60 rows, 10 ms (one sample), canvas 343x1320 |
| `RateCall.get` in the span graph | no | no |
| (c) dashboard row, trace +/- 15 s | 2396 rows, holds `RateCall.get` (80 ms) | 4577 rows, holds `RateCall.get` (60 ms) |

(a) and (c) are read off the `/api/ds/query` request bodies and responses the browser sent; (b) off the response
and the canvas. Screenshots are in the gitignored `out/`, and each one was read:

- `t7-trace.png` (run 1): the six-span trace, gateway then conversion, `http get` 803.74 ms inside `quote` 806.18 ms.
- `t7-span-links.png` (run 1): the conversion SERVER span's attributes (`pyroscope.profile.id`,
  `service.instance.id conversion-svc-bcb444774-xbc48:8082:1`) and the Links menu holding "Profiles for this span".
- `t7-span-flamegraph.png`: run 1 is 150 ms total, almost all `java/util/concurrent/ForkJoinWorkerThread.run`
  (carrier time). Run 2 is "(60) total (10 ms)": one sample, drawn as a single collapsed 60-frame chain.
- `t7-service-window.png`: Fleet Overview with Service `conversion-svc` over the trace +/- 15 s, and the Wall clock
  panel searched for `RateCall.get`. It shows one `io/ezeebit/conversi...` symbol, 80 ms in run 1 and 60 ms in run 2.

As the spike predicted, the per-span graph is carrier-dominated and holds no frame of the slow call. How much it
holds varies from trace to trace (15 samples, then 1). The service-window view names the rate client's frame.
`RateCall.get` is the CPU spent before the virtual thread parks (60 to 80 ms for 48 calls), not the 800 ms wait,
and every quote runs it, delayed or not. No window without the delay was captured, so (c) proves the frame can be
found there. It does not prove the delay put it there.

## 4. Overhead (spec 8.5): within budget at 10 ms

Budget (spec section 8 item 5, Sean 2026-11-04): p95 at most +5%, and added CPU at most 5% of the pod's CPU limit,
which is 100 millicores per JVM at limit 2. The relative CPU delta is reported here but does not gate.

Method: the same traffic for 300 s per arm on kind, profiling on against `EZEEBIT_PROFILING_ENABLED=false`, run by
`2026-11-04-overhead-phase.sh <label> '<kubectl set env args>'`. Each run:

- restarts or re-envs the 5 apps and checks image identity (`-imgcheck.sh`);
- pins the HPAs at `maxReplicas: 2`, then waits 240 s and for 8 app pods;
- funds 8 merchants (`-deposit.sh`, from inside a ledger pod, because a port-forward to ledger-svc is refused);
- warms up for 60 s, then drives an open loop of 10 units/s for 300 s (`-load.mjs`; a unit is a ZAR/USDT quote of
  100.00, its conversion and a balances read);
- measures (`-measure.sh`) and restores the HPAs to 3/3/4 on exit.

Compare with `python3 2026-11-04-overhead-compare.py out/overhead <on> <off>`. Every window:

- 3000 quotes 201, 3000 conversions 201, 3000 balances 200;
- 1/1/2/2/2 JVMs (`count_over_time(process_cpu_usage[299s])`);
- no `ScalingReplicaSet` or `SuccessfulRescale` event.

The first two attempts were void: the HPAs scaled ledger and conversion to 3 during the window, which is why they
are pinned.

```
p95:  histogram_quantile(0.95, sum by (job, le) (increase(http_server_requests_milliseconds_bucket{uri!~"/actuator.*"}[299s])))
cpu:  avg by (job) (avg_over_time(process_cpu_usage[299s]))
cpus: sum by (job) (increase(process_cpu_time_nanoseconds_total[299s])) / 1e9
```

Added millicores per JVM = (CPU-seconds on - CPU-seconds off) / 299 s / replicas. The gauge-based figure,
(mean `process_cpu_usage` on - off) x 2 CPUs, is shown in brackets. The 10 ms arm ran 17:30 to 17:35 against an
off baseline at 17:42, on images built from `c035dcf`, where the interval was the agent's default (10 ms). The
20 ms and 50 ms arms ran 18:49 and 19:00 against an off baseline at 18:38, on the rebuilt cluster with images
from `b2d53e6`. The off baselines agree within 2% (conversion 33.37 and 33.96 CPU-seconds, gateway 26.25 and
26.50, ledger 24.34 and 24.64).

| service (JVMs) | 10 ms: mCPU per JVM | 10 ms: rel | 10 ms: p95 | 20 ms: mCPU | 20 ms: rel | 50 ms: mCPU | 50 ms: rel |
|---|---|---|---|---|---|---|---|
| config-svc (1) | +5.4 [-9.3] | +165.3% | n/a | +3.8 | +111.4% | +1.9 | +57.1% |
| eureka-svc (1) | +6.9 [-1.3] | +103.6% | +0.6% | +5.2 | +76.5% | +3.4 | +50.5% |
| ledger-svc (2) | +5.9 [+5.7] | +14.6% | -0.4% | +4.2 | +10.3% | +0.3 | +0.8% |
| conversion-svc (2) | +9.9 [+10.0] | +17.7% | +1.1% | +6.0 | +10.5% | +1.1 | +2.0% |
| gateway-svc (2) | +11.5 [+6.1] | +26.2% | +1.4% | +9.4 | +21.2% | +1.8 | +4.0% |
| fleet (8) | +66.9 total | +23.0% | | +48.1 total | +16.3% | +11.8 total | +4.0% |

p95 at 20 ms ranged from -9.4% to -0.1%, and at 50 ms from -15.3% to -0.7%. The client-side p95 over all requests
was -0.8% at 10 ms, -3.0% at 20 ms and -11.2% at 50 ms. config-svc served no non-actuator request, so it has no p95.

**Verdict at 10 ms, the shipped default: within budget.**

- Added CPU is at most 11.5 millicores per JVM (gateway-svc), against the 100-millicore budget.
- p95 is at most +1.4% (gateway-svc).
- The relative deltas (+14.6% to +26.2% on the loaded services, and over +100% on the near-idle ones) are ratios
  of small baselines (1 to 34 CPU-seconds in 300 s). They do not gate.
- The p95 drops at 20 ms and 50 ms are larger than any plausible profiler effect in that direction. With one run
  per arm they show the run-to-run spread, which bounds how far any single delta here can be trusted.

**Why the interval stays at 10 ms although 50 ms costs less.** At 50 ms the click-through was run twice
(19:06 and 19:08). (a) and (b) passed both times, but (b) held only 11 rows (one collapsed chain, 800 ms in 16
samples). (c) passed in only 1 of 2 runs. In the failing run, a direct `SelectMergeStacktraces` over the whole
drive window plus 15 s each side (40 s, 48 delayed quotes) held no `RateCall.get`, `QuoteService.quote` or
`HttpClientImpl.send` frame. The control frames were there: 7 `io/ezeebit` frames, including 3.35 s of
`RecordingExporter.export`. The quote path's CPU per call is far below one 50 ms sample, so it shows only when a
sample lands on it. At 10 ms it showed in both runs.

The interval is now configurable: `PYROSCOPE_INTERVAL` / `ezeebit.profiling.interval`, default 10 ms, commit
`b2d53e6`. It reaches `Config.Builder.setProfilingInterval(Duration)`, which agent 2.9.2 sends to async-profiler
as `,interval=` (both read with `javap`). Before that existed, the brief's other two options were tried at 10 ms:

- dropping lock (`EZEEBIT_PROFILING_LOCK=1h`): ledger +15.0%, conversion +17.0%, gateway +11.6% on the gauge;
- dropping alloc as well (`EZEEBIT_PROFILING_ALLOC=1GB`): +14.2%, +16.5%, +11.4%.

Neither moved the cost: it is the wall-clock event, which samples every thread, idle carriers included. Every
override set here was removed afterwards.

## 5. Cluster and fleets left as found

- WireMock: every delay mapping deleted (200); 39 mappings, 0 with `fixedDelayMilliseconds` (last checked after the 19:35 run).
- Env: every override set here (`EZEEBIT_PROFILING_ENABLED`, `_LOCK`, `_ALLOC`, `PYROSCOPE_INTERVAL`) removed;
  0 `PROFILING` or `PYROSCOPE_INTERVAL` entries on any Deployment.
- HPAs back to max 4 (gateway), 3 (ledger), 3 (conversion).
- Port-forwards opened here (wiremock, ledger) stopped.
- Kind rebuilt by the ordered `kube.sh down`, compose up/down, then `k8s/kube.sh up --no-build` (exit 0, all
  stages ready, chaos CronJob `suspend=true`), loading the same host images: image identity `imgcheck exit=0`,
  the five digests above; delivery `exit=0` 30 s later. The rebuild means a fresh cluster: its databases hold
  none of the earlier data, and any `kubectl port-forward` opened against the old cluster is dead.
- Final state: pods on the images rebuilt from `b2d53e6` (the second digest list above), `imgcheck exit=0`, no
  `PYROSCOPE_INTERVAL` on any Deployment (so 10 ms), delivery `exit=0`, HPAs at 4/3/3 with 2 replicas each.

## 6. Not proven

- That the profiler applied the non-default intervals (20 ms, 50 ms): inferred from the CPU drop and the 50 ms
  sample granularity in both 50 ms graphs, not read from the profiler.
- The 10 ms overhead arm on the `b2d53e6` images: it ran on `c035dcf` images, where 10 ms was the agent's default
  rather than the configured value; the 20 ms and 50 ms arms ran on `b2d53e6`.
- Overhead variance: one 300 s run per arm; only the off baseline was repeated (on two clusters, within 2%).
- Overhead on compose: measured on kind only, by ruling.
- Server-side drops inside Pyroscope: the delivery script sees a label, not every chunk; a profile Pyroscope
  accepts and then drops is invisible to it and to ProfilesAbsent.
- Native async-profiler crashes: no crash was induced; what a SIGSEGV in the native library does to the JVM is
  untested.
- Parked I/O under virtual threads: not visible in any profile (spike, and section 3: 60 to 80 ms of
  `RateCall.get` for 48 x 800 ms waits). The trace's span duration is the only instrument for it.
- That the service-window frame is caused by the delay: no undelayed control window (section 3).
- Slack delivery for ProfilingFailing and ProfilesAbsent: not rehearsed; neither alert was raised in this task.
- The one-time ProfilesAbsent page when the fleet rolls out from a pre-gauge image (Task 6 observed it on kind):
  not re-tested, still expected on the first rollout of this branch over older images.
- The lock and alloc overrides reaching the profiler (section 4).
- Identity on compose at 2 replicas: compose runs one JVM per service.
- Until the fix round the kind images were the host's `ezeebit/*:0.3.2` from Task 6's final build; the fix round
  rebuilt them from `b2d53e6`. The working tree also carries an uncommitted, unrelated change to
  `services/ledger-svc/src/main/resources/db/changelog/2026/10/002-account.xml`, which the compose jars built by
  `./fleet.sh up` and the fix-round images included.
