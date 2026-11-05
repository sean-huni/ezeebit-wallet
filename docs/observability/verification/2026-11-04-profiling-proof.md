# Profiling proof: delivery, identity, span click-through, overhead (2026-11-04)

Proof for spec section 8 of `docs/superpowers/specs/2026-11-04-pyroscope-profiling-design.md`, with the rulings in
force: `LINK_MODE=per-span` (Grafana's native "Profiles for this span", span plus 60 s each side, filtered by
`pyroscope.profile.id`) and `EVENT=wall`. Branch `feat-pyroscope-profiling`. Times are UTC. Which build each run
used: delivery, identity, compose, the 10 ms overhead arm, the lock and alloc arms and click-through run 1 ran on
images or jars built from `c035dcf`; the fix-round images, the 20 ms and 50 ms arms with their off baseline,
both 50 ms click-throughs and click-through run 2 ran on images built from `b2d53e6`.

Raw evidence, committed beside this file: `2026-11-04-overhead-raw/` (each overhead arm's `run-*.json` from the load
generator and `measure-*.txt` from Prometheus), `2026-11-04-overhead-phases.log` (each arm's phase output),
`2026-11-04-span-to-profile-runs.log` (all four click-through outputs) and `2026-11-04-profiles-delivery-runs.log`
(the delivery and identity runs). Screenshots are gitignored and kept only on the machine that took them.

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

Red-proof on kind, as ruled 2026-11-04 (spec 8.2 amended): profiling disabled on the same fleet rather than a fleet
built from `dev`. Its cost: `dev`'s lack of an SDK is not shown separately; the red shows that the query sees the
profiler stop. `EZEEBIT_PROFILING_ENABLED=false` set on all five app Deployments with
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
the OpenTelemetry resource `service.instance.id`. The queries, runnable as they stand:

```bash
now=$(( $(date +%s) * 1000 ))
for s in ledger-svc gateway-svc conversion-svc; do
  curl -s -X POST -H 'Content-Type: application/json' \
    http://127.0.0.1:3000/api/datasources/proxy/uid/pyroscope/querier.v1.QuerierService/LabelValues \
    -d "{\"name\":\"instance\",\"matchers\":[\"{service_name=\\\"$s\\\"}\"],\"start\":$((now-30000)),\"end\":$now}"
  kubectl -n ezeebit-app get pods -l app.kubernetes.io/name=$s -o jsonpath='{.items[*].metadata.name}'; echo
done
curl -s --data-urlencode 'query=group by (job, service_instance_id) (last_over_time(target_info{job=~"ledger-svc|gateway-svc|conversion-svc"}[30s]))' \
  http://127.0.0.1:3000/api/datasources/proxy/uid/prometheus/api/v1/query
```


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

Ruled 2026-11-04 (Sean, "per-span link + dashboard"; spec 8.4 amended): the span link is asserted by its datasource
request (service and the span's profile id) plus a non-empty flame graph, and the injected method's name is asserted
on the dashboard's profiling row over the trace +/- 15 s, because under virtual threads the per-span graph is carrier
idle time.

`node docs/observability/verification/2026-11-04-span-to-profile.mjs` on kind, run twice at 10 ms, exit 0 both
times, every check PASS (outputs in `2026-11-04-span-to-profile-runs.log`). It adds a WireMock mapping through a
port-forward to `svc/wiremock` (`18089:8080`): `GET /rates` ZAR/USDT with `fixedDelayMilliseconds: 800` (POST 201),
drives 48 quotes (4 workers x 12, all `201`, all at least 800 ms), deletes the mapping (DELETE 200; 39 mappings
left, 0 with `fixedDelayMilliseconds`, 39 before), waits 30 s and finds the slow traces with TraceQL
`{resource.service.name="conversion-svc" && name="http get" && duration>700ms}` (48 found). Both runs below took
the MIDDLE trace by start time; the script now takes the slowest (`durationMs`), as the brief asks, and that version
has not been run against the cluster.

| | run 1, 17:04, images from `c035dcf` | run 2, 19:35, images from `b2d53e6` |
|---|---|---|
| trace | `cae9bcfa41894c81f9483229f2b60fc3` | `428ec6a0d1fe8a3910e926fc50688374` |
| conversion SERVER span, `pyroscope.profile.id` | `c8b811cf1387b313`, 808 ms, same id | `e8ee9d117ec35639`, 807 ms, same id |
| "Profiles for this span" | offered once | offered once |
| (a) link request | `{service_name="conversion-svc"}`, `spanSelector=["c8b811cf1387b313"]`, span +/- 60 s | `{service_name="conversion-svc"}`, `spanSelector=["e8ee9d117ec35639"]`, span +/- 60 s |
| (b) span flame graph | 41 rows, canvas 343x682 | 60 rows, 10 ms (one sample), canvas 343x1320 |
| `RateCall.get` in the span graph | no | no |
| (c) dashboard row, trace +/- 15 s | 2396 rows, holds `RateCall.get` | 4577 rows, holds `RateCall.get` (60 ms) |

(a) and (c) are read off the `/api/ds/query` request bodies and responses the browser sent; (b) off the response
and the canvas.

**Run 1's screenshots were not retained**: the script then wrote fixed names and the 50 ms runs overwrote them, so
nothing in this file rests on a run-1 image. Run 1 stands on its logged datasource traffic only. The script now
writes `out/t7-<date>-<commit>-<time>-<state>.png` and refuses to overwrite an existing file or a tracked path.

Run 2's four images are retained, renamed to `out/t7-2026-11-04-b2d53e6-run2-*.png`, and each was read:

- `trace`: the six-span trace, gateway then conversion: `http post` 809.34 ms, `quote` 805.71 ms, `http get` 803 ms.
- `span-links`: the conversion SERVER span (807.18 ms) with `pyroscope.profile.id e8ee9d117ec35639` and
  `service.instance.id conversion-svc-86b9c87cd4-mnqht:8082:1`, and the Links menu holding "Profiles for this span".
- `span-flamegraph`: Pyroscope pane `{service_name="conversion-svc"}`, "Span ID: e8ee9d117ec35639", "(60) total
  (10 ms)": one sample, drawn as a single collapsed 60-frame chain.
- `service-window`: Fleet Overview, Service `conversion-svc`, 21:34:51 to 21:35:21 local, the Wall clock panel searched
  for `RateCall.get`: one `io/ezeebit/conversi...` symbol, total 60 ms.

As the spike predicted, the per-span graph holds no frame of the slow call, and what it holds varies from trace to
trace (41 rows in run 1, one sample in run 2). The service-window view names the rate client's frame. `RateCall.get`
is the CPU spent before the virtual thread parks (60 ms for 48 calls in run 2), not the 800 ms wait, and every quote
runs it, delayed or not. No window without the delay was captured, so (c) proves the frame can be found there, not
that the delay put it there.

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
- measures (`-measure.sh`) and restores the HPAs on exit.

The arms recorded here ran on earlier versions of the phase and measure scripts (the 10 ms, lock and alloc arms on a
scratch copy, the 20 ms and 50 ms arms on the version committed in `c96cf0f`). Both have since been made fail
closed: any step that cannot prove its result exits 1, the settle loop has a deadline, and the HPAs get back the
`maxReplicas` read before pinning instead of a hardcoded 3/3/4. The raw files of every arm are in
`2026-11-04-overhead-raw/`; compare with `python3 2026-11-04-overhead-compare.py 2026-11-04-overhead-raw <on> <off>`
(labels `r0-10ms`, `r0-lock1h`, `r0-lock1h-alloc1gb` against `r0-off`; `r1-20ms`, `r1-50ms` against `r1-off`;
`void-hpa-10ms` is the first void attempt). Every kept window:

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

**The noise floor for p95.** The two off baselines are the same configuration measured an hour apart on two
clusters, so their difference is what p95 does with nothing changed:

| service | p95 off, 17:42 (ms) | p95 off, 18:38 (ms) | off against off |
|---|---|---|---|
| eureka-svc | 0.95 | 0.96 | +1.0% |
| ledger-svc | 1.39 | 1.59 | +14.3% |
| conversion-svc | 9.90 | 10.47 | +5.8% |
| gateway-svc | 11.36 | 11.91 | +4.9% |
| client side, all requests | 13.0 | 13.4 | +3.1% |

A single on-against-off p95 delta here is only meaningful beyond about 14%.

**Verdict at 10 ms, the shipped default: within budget on CPU; on p95, no regression visible above a noise floor
of about 14%.**

- Added CPU is at most 11.5 millicores per JVM (gateway-svc), against the 100-millicore budget.
- p95 moved -0.4% to +1.4%, well inside that noise floor. The data cannot show a +5% p95 effect either way; it can
  only rule out one larger than about 14%.
- The relative deltas (+14.6% to +26.2% on the loaded services, and over +100% on the near-idle ones) are ratios
  of small baselines (1 to 34 CPU-seconds in 300 s). They do not gate.
- The p95 drops at 20 ms and 50 ms (up to -15.3%) sit at the edge of the same noise floor.

**Why the interval stays at 10 ms although 50 ms costs less.** At 50 ms the click-through was run twice
(19:06 and 19:08). (a) and (b) passed both times, but (b) held only 11 rows (one collapsed chain, 800 ms in 16
samples). (c) passed in only 1 of 2 runs. In the failing run, a direct `SelectMergeStacktraces` over the whole
drive window plus 15 s each side (40 s, 48 delayed quotes) held no `RateCall.get`, `QuoteService.quote` or
`HttpClientImpl.send` frame. The control frames were there: 7 `io/ezeebit` frames, including 3.35 s of
`RecordingExporter.export`. The query, runnable as it stands (drive 19:06:20 to 19:06:31 UTC, +/- 15 s):

```bash
curl -s -X POST -H 'Content-Type: application/json' \
  http://127.0.0.1:3000/api/datasources/proxy/uid/pyroscope/querier.v1.QuerierService/SelectMergeStacktraces \
  -d '{"profileTypeID":"wall:wall:nanoseconds:wall:nanoseconds","labelSelector":"{service_name=\"conversion-svc\"}","start":1793819165000,"end":1793819206000}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin)["flamegraph"]; n=d["names"]; t={}
[t.__setitem__(n[v[i+3]], t.get(n[v[i+3]],0)+v[i+1]) for lv in d["levels"] for v in [list(map(int,lv["values"]))] for i in range(0,len(v),4)]
print({k: x/1e6 for k,x in t.items() if "io/ezeebit" in k})'
```

Our reading (inference, not measured per call): the quote path's CPU per call is far below one 50 ms sample, so it
shows only when a sample lands on it. At 10 ms it showed in both runs.

The interval is now configurable: `PYROSCOPE_INTERVAL` / `ezeebit.profiling.interval`, default 10 ms, commit
`b2d53e6`. It reaches `Config.Builder.setProfilingInterval(Duration)`, which agent 2.9.2 sends to async-profiler
as `,interval=` (both read with `javap`). Before that existed, the brief's other two options were tried at 10 ms:

- dropping lock (`EZEEBIT_PROFILING_LOCK=1h`): ledger +15.0%, conversion +17.0%, gateway +11.6% on the gauge
  (raw: `r0-lock1h`);
- dropping alloc as well (`EZEEBIT_PROFILING_ALLOC=1GB`): +14.2%, +16.5%, +11.4% (raw: `r0-lock1h-alloc1gb`).

Neither moved the cost. Inference, not measured: the cost sits in the wall-clock event, which samples every thread,
idle carriers included; what the data shows is only that lock and alloc changed nothing and the interval did. Every
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
- Overhead variance: one 300 s run per arm. Only the off baseline was repeated (two clusters): CPU-seconds agreed
  within 2%, p95 differed by up to 14.3% (ledger-svc), which is the floor any p95 claim here sits on.
- A p95 effect smaller than about 14%, in either direction, at any interval.
- Overhead on compose: measured on kind only, by ruling.
- Server-side drops inside Pyroscope: the delivery script sees a label, not every chunk; a profile Pyroscope
  accepts and then drops is invisible to it and to ProfilesAbsent.
- Native async-profiler crashes: no crash was induced; what a SIGSEGV in the native library does to the JVM is
  untested.
- Parked I/O under virtual threads: not visible in any profile (spike, and section 3: 60 ms of
  `RateCall.get` for 48 x 800 ms waits). The trace's span duration is the only instrument for it.
- That the service-window frame is caused by the delay: no undelayed control window (section 3).
- Slack delivery for ProfilingFailing and ProfilesAbsent: not rehearsed; neither alert was raised in this task.
- The one-time ProfilesAbsent page when the fleet rolls out from a pre-gauge image (Task 6 observed it on kind):
  not re-tested, still expected on the first rollout of this branch over older images.
- The lock and alloc overrides reaching the profiler (section 4); their raw measurements are retained, their
  effect inside async-profiler is not observed.
- Run 1 of the click-through: its screenshots were overwritten; only its logged datasource traffic remains.
- The slowest-trace selection in `2026-11-04-span-to-profile.mjs`: changed after both 10 ms runs, not yet run.
- The fail-closed phase and measure scripts against a real cluster: red-proofed with stubs (empty HPA read, jq
  failure on the event read) and read-only measure runs, but no full overhead arm has run on them. The stub
  red-proof harness (scratch copy, stub `kubectl`, `jq` and `node`) was not retained; its recorded output is in the
  Task 7 report (`.superpowers/sdd/2026-11-04-pyroscope-profiling/task-7-report.md`, local and gitignored), not in
  this repository.
- ProfilingFailing firing in Grafana: its expression is proven by `promtool` only
  (`2026-11-04-profiling-promtool.log`); the rule has never been seen firing in the running Grafana.
- "Open in Profiles Drilldown", rendered beside "Profiles for this span": its target was never checked.
- The JDK 25 native-access warning (`java.lang.System::load has been called by
  io.pyroscope.vendor.one.profiler.AsyncProfiler in an unnamed module`): a future JDK that denies native access
  by default stops the profiler from loading. The service keeps running, so only
  `ezeebit_profiling_failures_total` and ProfilingFailing (and ProfilesAbsent) would show it.
- The Task 4 tests (`SpanProfileLinkTest`: `aLinkThatCannotLoadDegradesToNoOp`, `aLinkThatCannotLoadIsCounted`)
  were not red-proofed by mutation.
- Identity on compose at 2 replicas: compose runs one JVM per service.
- Until the fix round the kind images were the host's `ezeebit/*:0.3.2` from Task 6's final build; the fix round
  rebuilt them from `b2d53e6`. The compose jars built by `./fleet.sh up` and the fix-round images included the
  then-uncommitted reformat of `services/ledger-svc/src/main/resources/db/changelog/2026/10/002-account.xml`.
  It is now committed, with the rest of the ledger 2026/10 reformat, in `e997664`; its checksums are unchanged
  (`docs/evidence/liquibase/2026-11-05-ledger-checksums/run.md`).
