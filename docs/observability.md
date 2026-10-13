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
`localhost:4318`. A log line written inside a request carries its `trace_id`. Each JVM is its own
series: `service.instance.id` is `${HOSTNAME:localhost}:<port>` (Prometheus label `instance`) and
`service.name` is the application name (label `job`), so two instances of one service never write
into one series. The shared OTLP settings live in `observability/`, compiled into every service jar.

Grafana is at http://localhost:3000 with **anonymous Admin access: local use only**.

## Dashboards

Folder **Ezeebit** holds four provisioned, read-only dashboards (`docker/lgtm/dashboards/`):

| Dashboard | Shows |
|---|---|
| Fleet Overview | UP/DOWN tile per service, request rate, 5xx ratio, p95 latency, JVM heap; a **Money** row |
| Traces | latest traces for the selected services; click a trace ID to open it |
| Logs | logs by service and level |
| Alerts | the five alert rules, in every state |

The Money row shows deposits per minute by currency and outcome, conversions by outcome (and those
left PENDING by their first ledger answer), quote refusals by reason, rate-service p95 latency,
overdue PENDING conversions, and sweeper actions.

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
| `ezeebit_gateway_ratelimit_total` | counter | `outcome` allowed, limited, unavailable | gateway `RateLimitFilter`, once per merchant request; every outcome registered at zero on start |

Requests refused before the service layer (malformed JSON, a missing field) are counted only by the
HTTP metrics.

**Levels are read from the database, never from heap state.** A counter is a flow: each replica
counts its own requests and dashboards `sum()` them. A per-replica copy of "how many conversions are
stuck" would be wrong on every replica but one, so `ezeebit_conversion_pending_overdue` is a query
run on each export, every replica reports the same number, and the dashboard and alert take `max()`.
The query runs off the export thread with a 2 s statement timeout and a 2 s wait, so a database that
does not answer cannot hold the OTLP publish thread. If it fails, the gauge exports NaN (a known gap:
see [SOLUTION.md](../SOLUTION.md#7-known-gaps-and-unfinished-work)).

## Alert rules

Grafana-managed, in `docker/lgtm/alerting/rules.yaml`:

| Rule | Condition | Fires |
|---|---|---|
| ServiceDown | `absent_over_time` over a pushed JVM metric, 30 s window, one term per service (`absent()` would wait out the 5 min lookback, because a pushed series gets no staleness marker) | after 1 min; one alert per service |
| HighErrorRate | a service's 5xx share of requests above 5% over 1 min | after 2 min |
| RateFeedRefusing | per pair, quotes refused with `RATE_UNAVAILABLE` or `RATE_REJECTED` in the last minute: more than 2, or at least 1 that is half or more of the pair's quotes. Each count is `clamp_min(X - X offset 1m, 0) or (X unless X offset 1m)` summed by pair, so a series first created inside the window counts whole and a restarted JVM's drop is clamped to 0 | after 2 min; one alert per pair |
| ConversionsStuckPending | `max(ezeebit_conversion_pending_overdue) > 0`; the sweeper runs every 10 s, so a due row normally lives seconds | after 5 min |
| RateLimiterUnavailable (critical) | `sum(increase(ezeebit_gateway_ratelimit_total{outcome="unavailable"}[1m])) > 0`: the gateway refused merchant requests because Redis did not answer. The limiter fails closed, so this is an edge outage for every merchant | after 1 min |

No data is OK for all five, by design: no refusal series means no refusals, and a conversion-svc
that exports nothing is ServiceDown's to page, so one silent service never pages twice. An
evaluation error is Error, never OK.

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

What these rehearsals do not prove: alerts reach one Slack channel, not an on-call rota.
