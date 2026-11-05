# Profiling spike: span link under virtual threads, CPU versus wall (2026-11-04)

Ruling for spec sections 7.3 and 7.5 of `docs/superpowers/specs/2026-11-04-pyroscope-profiling-design.md`.

| Value | Ruling |
|---|---|
| `LINK_MODE` | `window`: a span opens its service's flame graph for its time window, no span filter |
| `EVENT` | `wall` (unchanged default in `observability/src/main/resources/config/application.yml`) |
| span attribute | `pyroscope.profile.id`, on the service-local root span only |

Universe: kind cluster `kind-ezeebit`, branch `feat-pyroscope-profiling` at `280d3aa` plus this change, images
`ezeebit/*:0.3.2` rebuilt with `./gradlew bootBuildImageAll` (exit 0), loaded with `k8s/kube.sh up --no-build`
(exit 0), then `kubectl rollout restart` of conversion-svc, ledger-svc and gateway-svc (the same tag does not
roll the pods by itself). Victim: conversion-svc, 2 replicas, virtual threads on, `io.pyroscope:agent:2.9.2`,
`io.pyroscope:otel:2.1.4`. Times are UTC.

## 1. The processor, its attribute, and Boot picking it up

Read from the 2.1.4 jar (`javap -c` on `io/otel/pyroscope/PyroscopeOtelSpanProcessor.class`, confirmed in
`otel-2.1.4-sources.jar`):

```
ldc  #38  // String pyroscope.profile.id
invokestatic AttributeKey.stringKey
```

`onStart` writes the span's own hex span id under that key and calls `AsyncProfiler.setTracingContext(spanId,
spanName)`, which is per NATIVE thread; `onEnd` clears it on whatever thread ends the span. With the default
`rootSpanOnly=true` only spans whose parent is invalid or remote are tagged.

Boot 4.1.2 (`OpenTelemetryTracingAutoConfiguration`, read with `javap`) builds `SpanProcessors` from
`ObjectProvider<SpanProcessor>`, and its own `otelSpanProcessor` is `@ConditionalOnMissingBean` on its return
type `BatchSpanProcessor`, so adding a `SpanProcessor` bean neither replaces nor stops the exporter.

Proof on the live fleet, one quote at 15:22:44 (merchant `1e586028-a93d-45b3-8b80-88eacce131c3`, HTTP 201),
trace `30dd9b4acaba71605dc5031b0fe2116b` read back from `GET /api/datasources/proxy/uid/tempo/api/v2/traces/<id>`:

```
gateway-svc     http post /api/v1/merchants/*/quotes/**   645.5ms SERVER   span 4fc3497f25151004 pyroscope.profile.id=4fc3497f25151004
gateway-svc     rate limit                                  9.6ms INTERNAL span 809a9e1d7bf109cf pyroscope.profile.id=<absent>
gateway-svc     http post                                 596.2ms CLIENT   span 3ddd18053bcdd3c3 pyroscope.profile.id=<absent>
conversion-svc  http post /{version:v\d+}/merchants/...   588.9ms SERVER   span 490388a82020412c pyroscope.profile.id=490388a82020412c
conversion-svc  quote                                     351.1ms INTERNAL span a309e4b6a69a636f pyroscope.profile.id=<absent>
conversion-svc  http get                                  285.0ms CLIENT   span 948d953a13a29643 pyroscope.profile.id=<absent>
```

So the processor is registered (the attribute is there) and export still works (the trace is in Tempo). Only
each service's SERVER span carries the link; a click on `quote` or `http get` has no id of its own.

## 2. Method

Slow path, no code change: WireMock on kind (`svc/wiremock`, port-forward `18089:8080`), one mapping added at
runtime and removed after (`POST /__admin/mappings` returned 201 with id `ab88996f-8169-4e98-976b-b65375f8cee4`,
`DELETE` returned 200):

```json
{"name": "spike: slow ZAR/USDT", "priority": 1,
 "request": {"method": "GET", "urlPath": "/rates",
             "queryParameters": {"base": {"equalTo": "ZAR"}, "quote": {"equalTo": "USDT"}}},
 "response": {"status": 200, "fixedDelayMilliseconds": 800, "headers": {"Content-Type": "application/json"},
              "body": "{\"base\":\"ZAR\",\"quote\":\"USDT\",\"rate\":\"0.054254\",\"timestamp\":\"{{now}}\"}",
              "transformers": ["response-template"]}}
```

Traffic: 20 sequential `POST http://127.0.0.1:8080/api/v1/merchants/<fresh uuid>/quotes`
`{"sell":"ZAR","buy":"USDT","sellAmount":"1000.00"}` per event, all 201 at 0.81 to 1.19 s. Slow traces found with
TraceQL `{resource.service.name="conversion-svc" && name="http get" && duration>700ms}` over the drive window
(14 of 20 returned for wall, 17 of 20 for itimer; the search did not return every trace, the found ones were used).

Queries, through `POST /api/datasources/proxy/uid/pyroscope/querier.v1.QuerierService/<method>`, times in ms:

- unfiltered (positive control): `SelectMergeStacktraces`
  `{"profileTypeID": T, "labelSelector": "{service_name=\"conversion-svc\"}", "start": S, "end": E}`
- filtered: `SelectMergeSpanProfile`, the same body plus `"spanSelector": ["<pyroscope.profile.id>"]`

`T` is `wall:wall:nanoseconds:wall:nanoseconds` for wall and `process_cpu:cpu:nanoseconds:cpu:nanoseconds` for
itimer. Between runs the event was switched with `kubectl set env deploy/conversion-svc
EZEEBIT_PROFILING_EVENT=itimer` and reverted with `EZEEBIT_PROFILING_EVENT-` afterwards. During the itimer run
`LabelValues` for `jfr_event` on conversion-svc returned `["itimer", "wall"]` (the wall pods' last upload is in
the window).

## 3. The exact span window returns nothing; the window must be padded

Every query over the span's own `[start, end]` (about 0.81 s) came back empty for wall, unfiltered included,
which is the positive control failing, so the result is "no data in that window", not "the filter removed it":

```
wall   4420341a742f3635167d845f7e7b3e28  [1793805849611,1793805850420]  UNFILTERED total=0  FILTERED total=0
wall   810c1bfb3fc41a41374c32822250d579                                 UNFILTERED total=0  FILTERED total=0
wall   9cde4186447654731a69d9460cb68887                                 UNFILTERED total=0  FILTERED total=0
wall   45860c63035ad1f7593478cb0fc747a4                                 UNFILTERED total=0  FILTERED total=0
wall   a35a039cac9103717b500d4941338f55                                 UNFILTERED total=0  FILTERED total=0
itimer 973a5d500fa55ba3476b2fedbeb0a2c9                                 UNFILTERED total=0           FILTERED total=0
itimer 3f67b2fb9cb34afd409b7c6203d22c18                                 UNFILTERED total=0           FILTERED total=0
itimer 490c58ebda257fd29142e3bad9549980  [1793805957978,1793805958789]  UNFILTERED total=140000000   FILTERED total=0
itimer a706ad3ebf7618aa0111f6679f4b4df9                                 UNFILTERED total=0           FILTERED total=0
itimer c9ac03b818a38b04b26e90b94badae3f                                 UNFILTERED total=190000000   FILTERED total=0
```

Profiles are uploaded in chunks (the SDK's upload interval, 10 s by default) and a sub-second window mostly falls
between chunk timestamps. All later queries pad the span by 15 s each side. Consequence for the fallback: "the
span's exact time window" must be widened to at least one upload interval each side, and the graph then holds
everything the service ran in about 30 s, not only this request.

## 4. Run 1: `event: wall`

One trace, `4420341a742f3635167d845f7e7b3e28` (quote 814.8 ms; conversion SERVER span `535c0c74e2faa858`
808.6 ms, `http get` 804.8 ms), window `[1793805834611,1793805865420]` (span plus 15 s each side):

```
UNFILTERED SelectMergeStacktraces: HTTP 200 total=3299970000000
   io/ezeebit   RateCheck.fetch = 20000000; GatewayProofFilter.doFilterInternal = 20000000; RateHistory.read = 10000000
   RateCall     io/ezeebit/conversion/outbound/rates/RateCall.get = 10000000
   HttpClientImpl.send = 10000000
FILTERED   SelectMergeSpanProfile: HTTP 200 total=800000000 names=11
   io/ezeebit   (none)
   RateCall     (none)
   ForkJoinPool java/util/concurrent/ForkJoinPool.runWorker = 800000000; ForkJoinPool.deactivate = 800000000; ForkJoinPool.awaitWork = 800000000
   park         jdk/internal/misc/Unsafe.park = 800000000
```

All 14 slow spans in one `spanSelector`, window `[1793805823106,1793805865419]` (42.3 s):

```
UNFILTERED total=4331980ms   RateCall.get=10ms  QuoteService.quote=20ms  HttpClientImpl.send=10ms  ForkJoinPool.awaitWork=226480ms
FILTERED   total=16130ms     RateCall.get=10ms  QuoteService.quote=20ms  HttpClientImpl.send=0ms   ForkJoinPool.awaitWork=15680ms
           top io/ezeebit frames: GatewayProofFilter.doFilterInternal=260ms; QuoteService.issue=20ms; RateCheck.fetch=20ms
```

Reading: the 14 spans spent about 11.2 s waiting on the delayed call (14 x 800 ms). The filtered graph holds
16.1 s, MORE than the spans lasted, and 15.68 s of it (97%) is a carrier thread idle in `ForkJoinPool.awaitWork`.
The virtual thread unmounts when it blocks, the profiler's tracing context stays on the carrier (it is per native
thread), and the carrier's idle time is billed to the span; the span is often ended on a different carrier, so
the first carrier stays tagged afterwards. The delayed call itself appears as 10 ms of `RateCall.get` (one
sample, before the park); the 800 ms wait appears nowhere, filtered or not, because a parked virtual thread is
not on any OS thread for the wall sampler to see.

## 5. Run 2: `event: itimer`

One trace, `490c58ebda257fd29142e3bad9549980` (quote 816.7 ms; conversion SERVER span `69d2f1bfc2949584`
810.1 ms, `http get` 805.5 ms), window `[1793805942978,1793805973789]`:

```
UNFILTERED SelectMergeStacktraces: HTTP 200 total=1050000000
   io/ezeebit   RateClient.fetch = 20000000; RateMapper.toDomain = 10000000; GatewayProofFilter.doFilterInternal = 10000000
   RateCall     io/ezeebit/conversion/outbound/rates/RateCall.get = 10000000
FILTERED   SelectMergeSpanProfile: HTTP 200 total=0 names=1
```

All 17 slow spans, window `[1793805930641,1793805975480]` (44.8 s):

```
UNFILTERED total=1930ms   RateCall.get=10ms  QuoteService.quote=20ms
FILTERED   total=140ms    RateCall.get=0ms   QuoteService.quote=10ms
           top io/ezeebit frames: GatewayProofFilter.doFilterInternal=90ms; QuoteCtrl.quote=10ms; QuoteService.quote=10ms
```

Reading: CPU samples are tagged to spans more honestly (no idle carrier, filtered is a small subset), but the
delayed call is absent from the filtered graph and is 10 ms (one CPU sample) unfiltered. Expected: the wait
burns no CPU.

## 6. Ruling, against the spec's rule

- **`LINK_MODE=window`.** The rule is `per-span` only if the filtered query returned the slow call's frames on a
  virtual thread. Single trace: no `io.ezeebit` frame at all under wall, an empty graph under itimer. Over 14 and
  17 spans: one 10 ms `RateCall.get` sample under wall, none under itimer. Worse, under wall the filtered graph
  is 97% carrier idle time and exceeds the spans' own duration, so a per-span link would show a confidently
  wrong picture. Sean's fallback applies, with the padding of section 3.
- **`EVENT=wall`.** The rule is the event under which the injected blocking call AND an `io.ezeebit` frame
  appear. `io.ezeebit` frames appear under both. The blocking call appears under neither as the wait it is:
  `RateCall.get` is 10 ms against 11.2 to 13.6 s of injected delay in both runs, which is the CPU spent before
  the park, not the call being slow. Neither shows both, so per the rule `wall`, which is the shipped default.
- **Stated plainly:** with virtual threads on, neither event shows time a request spends parked on I/O. Wall
  shows code that runs plus platform threads that wait (selector, pool and scheduler threads); it does not show
  a parked virtual thread. The trace remains the instrument for "where did the time go"; the profile answers
  "what was the CPU doing in that window".

## 7. Cluster left as found

- Mapping `ab88996f-...` deleted (200); no mapping named `spike` remains. `GET /__admin/mappings` reports 39
  (the configmap holds 13; the count before the spike was not captured, so 39 is not proven unchanged).
- `EZEEBIT_PROFILING_EVENT` removed from the conversion-svc Deployment (0 env entries match `PROFILING`),
  rollout complete. A quote after revert: 201 in 0.46 s.
- The WireMock port-forward was stopped; the postgres and redis port-forwards that predate the spike were left.
