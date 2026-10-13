# Observability: dashboards, business metrics, alerts

Back to [README](../README.md). This is beyond the brief; see
[SOLUTION.md](../SOLUTION.md#time-and-scope).

## The stack

`compose.yaml` runs `grafana/otel-lgtm:0.36.0`: Grafana, Prometheus, Tempo, Loki and an
OpenTelemetry collector in one container. Metrics, traces and logs persist in the `lgtmdata` volume.
Grafana's own database is on tmpfs and resets on every start by design: dashboards, alert rules and
the contact point re-provision from files in `docker/lgtm/`, and no stale alert state survives a
restart to page on the next boot. Before that change a restart produced 4 false pages (reproduced
with Slack disconnected); after it the same script produced 0.

Every service exports metrics (every 10 s), traces (100% sampled) and logs over OTLP/HTTP to
`localhost:4318`. A log line written inside a request carries its `trace_id` and `span_id`. Each JVM is its own
series: `service.instance.id` is `${HOSTNAME:localhost}:<port>:<pid>` (Prometheus label `instance`) and
`service.name` is the application name (label `job`), so two instances of one service never write
into one series. The pid makes a restarted JVM a new series, as a new pod is under Kubernetes: a restart that
reused the old labels and reached the same counts (one deposit per currency, say) was invisible to every
counting panel. Measured on 2026-10-13 after a restart and one traffic run (4 posted, 4 replayed, 1 refused
deposits): the old `rate()` deposits panel peaked at 0, the per-window count at 9
(`docs/observability/verification/2026-10-13-first-count-after-restart.log`). The shared OTLP settings live
in `observability/`, compiled into every service jar.

Grafana is at http://localhost:3000 with **anonymous Admin access: local use only**.

## Dashboards

Folder **Ezeebit** holds five provisioned, read-only dashboards. They are generated: edit
`docker/lgtm/generate.py`, run `python3 docker/lgtm/generate.py`, commit the script and its output
together. `./gradlew check` runs `checkDashboards` (`generate.py --check`), which fails naming any file that
differs from what the script writes, so a hand edit to the JSON or a forgotten regeneration is red.

| Dashboard | Shows | Variables |
|---|---|---|
| Fleet Overview | UP/DOWN per service; JVMs per service (metric identity); firing alerts; rate, 5xx ratio and p95 per service and per endpoint; outbound calls by target; limiter decisions, the Redis round trip and Redis reachability; Hikari connections, utilisation, acquire time and timeouts; heap, GC, warnings and errors | service, rate window |
| Money | deposits by currency and outcome; quotes by pair, refusals by reason and rule; rate-feed calls; conversion requests by pair and outcome; settled outcomes; ledger postings; overdue PENDING; sweeper actions; p95 of each business step | currency, pair, outcome, rate window |
| Traces | a trace by ID (in a collapsed row: a trace panel with no ID is an error), merchant requests, traces with an error or a 5xx, slow merchant requests, business spans with their attributes, the service map, span rates | service, trace ID, min duration, rate window |
| Logs | lines per service and level, the lines, warnings and errors | service, level, search, trace ID |
| Alerts | every rule in every state, what is firing, and each rule's expression plotted against its threshold with its state changes as annotations | none |

Money is its own dashboard because currency, pair and outcome filter only the business panels; on Fleet
Overview they would be three controls that do nothing to twenty panels.

How the boards connect:

- **Time picker and variables** drive every panel; the Playwright suite proves it from the window and the
  substituted expression of each `/api/ds/query` request the browser sends.
- **Data links.** Each per-service series and each Alive tile links to that service's Logs and Traces,
  carrying the time range (`${__url_time_range}`).
- **Dashboard links** (top bar) keep the time range and the variables the boards share.
- **Exemplars.** Latency panels (diamonds) carry one real request each; its `trace_id` opens Tempo. They
  ride on histogram buckets, which the shared config enables for the HTTP and business timers.
- **Datasources** (`docker/lgtm/datasources.yaml`, replacing the image's file): Loki's `trace_id` field
  links to Tempo; Tempo links a span to its logs (filtered by trace id) and to its service's request rate,
  p95 and 5xx ratio; the Prometheus step is 10 s, the services' export interval.
- **Annotations.** Every rule is linked to its input panel on the Alerts dashboard, so each Pending,
  Alerting and Normal transition is a marker on the graph of the expression that caused it.

## Tracing

One merchant request is one trace: the gateway's request span, its `rate limit` span, its call to the
service, the service's request span and business span, and the calls to the rate feed and to
ledger-svc with the ledger's own spans. conversion-svc's two `RestClient`s carry the
`ObservationRegistry`; before that, the rate feed and the ledger each began a new trace
(`tracing.feature` proves the `traceparent` reaches both). Sampling is 100%.

| Span | Service | Attributes (bounded; never a merchant, an id or an amount) |
|---|---|---|
| `rate limit` | gateway-svc | `outcome` allowed, limited, contention, unavailable |
| `quote` | conversion-svc | `pair`, `outcome` issued or refused, `reason` (error code), `detail` (rate rule) |
| `conversion accept` | conversion-svc | `pair`, `outcome` completed, pending, rejected, refused; `code` |
| `sweep` | conversion-svc | `result` idle or resolved; per-action counts as span-only values |
| `deposit` | ledger-svc | `currency`, `outcome` posted, replayed, refused; `code` |
| `ledger posting` | ledger-svc | `operation` post or void, `outcome`, `code` |

Each span also produces a timer of the same name (`ezeebit_quote_milliseconds_*` and so on) tagged with
the same values. A span starts as `outcome=error` and keeps it when the step ends in neither an answer
nor a refusal. A refusal is an answer, so it is tagged, not recorded as an error (its message can echo
caller input). A database or transaction failure is recorded on the business span, and the catch-all
500 handler records the exception on the request span. JDBC statements have no spans: no JDBC
instrumentation is on the classpath, and Hikari's pool metrics stand in.

Every log line written inside a request carries `trace_id` and `span_id` as Loki structured metadata.

## Business metrics

Micrometer meters, exported over the same OTLP path (Prometheus names). Every tag is a bounded enum:
a known currency or pair, an outcome, a code. No tag carries a merchant, a key or an id.

| Metric | Type | Tags | Counted where |
|---|---|---|---|
| `ezeebit_ledger_deposits_total` | counter | `currency` (`unknown` when refused for it), `outcome` posted, replayed, refused; `code` | ledger `DepositService`, once per answer |
| `ezeebit_ledger_conversion_postings_total` | counter | `outcome` posted, replayed, voided, refused; `code` | ledger `ConversionPostingService`, once per answer; a replayed refusal counts again |
| `ezeebit_conversion_quotes_total` | counter | `pair`, `outcome` issued, refused; `reason`; `detail` (the rate rule for `RATE_REJECTED`) | `QuoteService`. Not counted: a quote that passes every check and then fails to save, which surfaces as a 500 |
| `ezeebit_conversion_rate_calls_milliseconds_{bucket,count,sum}` | timer, 25 ms to 5 s | `outcome` answered, unavailable, unreadable | `RateClient`, around the HTTP call |
| `ezeebit_conversion_outcomes_total` | counter | `status` COMPLETED, REJECTED; `reason` | once per conversion across replicas: only the call whose compare-and-set moved the row counts it, after that update committed |
| `ezeebit_conversion_left_pending_total` | counter | none | when a conversion's first ledger answer left it PENDING |
| `ezeebit_conversion_sweeps_total` | counter | `action` completed, voided, refused, unresolved | `PendingResolver`, once per claimed row, after the sweep's transaction commits |
| `ezeebit_conversion_pending_overdue` | gauge | none | `PendingOverdueGauge`: `count(*)` of PENDING rows past deadline plus max transaction time |
| `ezeebit_gateway_ratelimit_total` | counter | `outcome` allowed, limited, contention, unavailable, refused_path | gateway `RateLimitFilter`, once per merchant request or refused path; every outcome registered at zero on start |
| `ezeebit_gateway_ratelimit_store_up` | gauge | none | gateway `StorePingScheduler`: 1 while Redis answers PING within 250 ms, else 0, every 10 s. One view per replica: read with `min()` |

Requests refused before the service layer (malformed JSON, a missing field) are counted only by the
HTTP metrics.

Beside these, every service exports Spring's RED timers: `http_server_requests_milliseconds_*` per route
template and status (with an exact 500 ms bucket for the latency SLO) and `http_client_requests_milliseconds_*`
per called host, both as histograms so p95 and exemplars work. Each business span adds a timer of its
name (`ezeebit_deposit`, `ezeebit_ledger_posting`, `ezeebit_quote`, `ezeebit_conversion_accept`,
`ezeebit_ratelimit_decision`, `ezeebit_sweep`, all `_milliseconds_*`) with the span's bounded tags. ledger-svc
and conversion-svc export Hikari's `hikaricp_connections_{active,idle,pending,max,timeout_total}` and
acquire time per JVM.

**Levels are read from the database, never from heap state.** A counter is a flow: each replica
counts its own requests and dashboards `sum()` them. A per-replica copy of "how many conversions are
stuck" would be wrong on every replica but one, so `ezeebit_conversion_pending_overdue` is a query
run on each export, every replica reports the same number, and the dashboard and alert take `max()`.
The query runs off the export thread with a 2 s statement timeout and a 2 s wait, so a database that
does not answer cannot hold the OTLP publish thread. If it fails, the gauge exports NaN (a known gap:
see [SOLUTION.md](../SOLUTION.md#7-known-gaps-and-unfinished-work)).

## Alert rules

Grafana-managed, generated into `docker/lgtm/alerting/rules.yaml`. Every summary names the service and
every rule carries a `runbook` annotation.

| Rule | Condition | Fires |
|---|---|---|
| ServiceDown | `absent_over_time` over a pushed JVM metric, 30 s window, one term per service (`absent()` would wait out the 5 min lookback, because a pushed series gets no staleness marker) | after 1 min; one alert per service |
| HighErrorRate | a service's 5xx share of requests above 5% over 1 min | after 2 min |
| RateFeedRefusing | per pair, quotes refused with `RATE_UNAVAILABLE` or `RATE_REJECTED` in the last minute: more than 2, or at least 1 that is half or more of the pair's quotes. Each count is `clamp_min(X - X offset 1m, 0) or (X unless X offset 1m)` summed by pair, so a series first created inside the window counts whole and a restarted JVM's drop is clamped to 0 | after 2 min; one alert per pair |
| ConversionsStuckPending | `max(ezeebit_conversion_pending_overdue) > 0`; the sweeper runs every 10 s, so a due row normally lives seconds | after 5 min |
| RateLimiterUnavailable (critical) | `sum(increase(ezeebit_gateway_ratelimit_total{outcome="unavailable"}[1m])) > 0`: the gateway refused merchant requests because Redis did not answer. The limiter fails closed, so this is an edge outage for every merchant | after 1 min |
| RateLimiterStoreDown (critical) | `(1 - min(last_over_time(ezeebit_gateway_ratelimit_store_up[30s]))) or absent_over_time(ezeebit_gateway_ratelimit_store_up[30s])`: a gateway cannot PING Redis, or no gateway has reported for 30 s | after 1 min; needs no traffic |
| LatencySLOBurn | SLO 99% of business requests within 500 ms. The slow share (against the exact `le="500.0"` bucket) burns the 1% budget more than 14.4 times too fast over both 5 min and 1 h, while the service sees at least 3 requests a minute | after 2 min; one alert per service |
| DbPoolSaturated | a thread waited for a Hikari connection in each of the last 30 s windows (`max_over_time(hikaricp_connections_pending[30s]) > 0`) | after 1 min; one alert per JVM |
| LedgerInvariantBroken (critical) | the ledger refused its own posting as `CURRENCY_MISMATCH` or `UNBALANCED_POSTING` (both map to 500: a code defect, never bad input) in the last 5 min, counted with the same born-in-window arm as RateFeedRefusing | at once; one alert per code |

No data is OK for all nine, by design: no refusal series means no refusals, and a service that exports
nothing is ServiceDown's to page, so one silent service never pages twice. An evaluation error is
Error, never OK.

**The rules are unit-tested.** `docker/lgtm/test-rules.sh` runs `promtool test rules` from the pinned
lgtm image over `alerting/promtool/rules.yml`, the generated Prometheus-format mirror of the Grafana rules
(same expressions, thresholds and holds; Grafana's `noDataState` is not mirrored). The cases are in
`alerting/promtool/tests.yml`. Red-proof: rewriting LedgerInvariantBroken as `sum(increase(X[5m]))` fails
both of its cases, because `increase()` cannot see a counter born inside the window
(`docs/observability/verification/2026-10-13-promtool-red.log`).

`fleet.sh` starts lgtm only after every service is healthy and stops it first on `down`, so neither
booting nor a deliberate stop can page.

## Slack delivery

Notifications go to Slack only when `SLACK_WEBHOOK_URL` is set in the root `.env` (gitignored). The
container installs the Slack contact point at start only in that case, because Grafana refuses to
start on a Slack contact point with an empty URL; without it no contact point exists and alerts stay
visible in Grafana only. Grafana expands the URL from its environment; it is never written to a file.

## Rehearsals (local, 2026-10-13, measured once each)

**Delivery, Slack live.** conversion-svc stopped; counts from Grafana's
`grafana_alerting_notifications_total`:

| Event | Time |
|---|---|
| conversion-svc stopped | t+0s |
| ServiceDown Pending | t+46s |
| Alerting | t+104s |
| Delivered to Slack (sent=1, failed=0); service restored | t+115s |
| Normal | t+146s |
| RESOLVED delivered (sent=2, failed=0) | t+175s |

**Business alerts, Slack disconnected.** ledger-svc frozen with SIGSTOP and a conversion accepted
(202 PENDING); the WireMock rate service stopped while a quote was sent every 2 s:

| Event | Time |
|---|---|
| ledger frozen, conversion PENDING | t+0s |
| rate service stopped | t+3s |
| RateFeedRefusing Pending for ZAR/USDT | t+24s |
| ConversionsStuckPending Pending (the conversion became due at about t+75s) | t+96s |
| RateFeedRefusing Alerting, 29 refusals in the last minute | t+147s |
| ConversionsStuckPending Alerting | t+393s |
| ledger resumed, rate service started | t+490s |
| ConversionsStuckPending Normal; the sweeper had voided the conversion (REJECTED, VOIDED) | t+512s |
| RateFeedRefusing Normal | t+543s |

Three stale-rate refusals for ZAR/USDC in a few seconds took that pair to Pending and back to Normal
without firing: the 2 min hold doing its job. The ratio arm was rehearsed the same day: KES/USDC
quoted twice a minute against a stale rate went Pending at t+63s and Alerting at t+177s, while
ZAR/USDT, with 2 stale refusals a minute among about 20 good quotes, stayed Normal.

The posting sent while the ledger was frozen was never counted: its caller had timed out and closed
the connection, and on resume Tomcat dropped the 28 queued connections unread (`SocketException:
Invalid argument` from `setSoLinger`, macOS). With no answer, the sweeper voided the conversion.

**HighErrorRate, Slack disconnected.** ledger-svc stopped, gateway answering 503: Pending t+20s,
Alerting t+140s on gateway-svc.

**RateLimiterUnavailable, Slack disconnected (2026-10-13).** Redis stopped with the fleet up and a
merchant request every 2 s through the gateway, each answered 503 `RATE_LIMITER_UNAVAILABLE` while
the gateway's health stayed 200:

| Event | Time |
|---|---|
| redis stopped | t+0s |
| RateLimiterUnavailable Pending | t+14s |
| Alerting | t+74s |
| redis started; the next request admitted (200) | t+74s |
| Normal | t+144s |

**RateLimiterStoreDown, Slack disconnected (2026-10-13), no traffic.** RateLimiterUnavailable stayed
Normal throughout, because nothing was refused:

| Event | Redis stopped | Gateway stopped (absence arm) |
|---|---|---|
| fault | t+0s | t+0s |
| Pending | t+15s | t+39s |
| Alerting | t+74s | t+98s |
| restored (Redis started, gateway restarted) | t+74s | about t+100s |
| Normal | t+95s | 29 s after the restart |

What these rehearsals do not prove: alerts reach one Slack channel, not an on-call rota. The three rules
added on 2026-10-13 (LatencySLOBurn, DbPoolSaturated, LedgerInvariantBroken) are proven by promtool, not
by a rehearsed outage.

## Verifying the dashboards (Playwright)

With the fleet and lgtm up:

```sh
cd docs/observability/verification
npm install                                  # once; playwright, driving the installed Google Chrome
node 2026-10-13-dashboards-playwright.mjs    # about 6 minutes; exit 1 on any failed check
```

It drives traffic (`traffic.mjs`), then checks metric identity, one trace across the three services,
20 random request-path log lines against their spans in Tempo, and exemplars; captures every dashboard at
1440 and 390 px, scrolled inside Grafana's own pane, with a content floor; and proves interactivity from
the `/api/ds/query` requests: time picker, every variable, a data link, dashboard links, a log line's
trace opening in Tempo, the trace view and alert annotations. Captures land in `out/` (gitignored); a
check passing is not the verdict, the pictures are read. The run of 2026-10-13 is
`2026-10-13-dashboards-playwright-run.log` (65 passed, 0 failed) and the curated captures are in
[observability-evidence.md](observability-evidence.md). A new wave of dashboard work adds a new dated
suite and re-runs this one.
