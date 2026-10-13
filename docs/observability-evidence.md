# Observability evidence

Back to [observability.md](observability.md). Captured on 2026-10-13 against the local fleet (one JVM per
service, Grafana 13.2.3 in `grafana/otel-lgtm:0.36.0`, Slack disconnected) by the Playwright suite in
[docs/observability/verification/](observability/verification/), right after its traffic run: deposits
(one replayed, one refused), quotes and conversions over four pairs, an unsupported pair, a conversion
refused for funds, a stale-rate refusal, and a 180-request burst on one merchant. Times are SAST
(UTC+2); the services had last been restarted at about 15:52, as the 5xx and ServiceDown markers show. The
suite's own log, 65 checks passed and 0 failed, is
[2026-10-13-dashboards-playwright-run.log](observability/verification/2026-10-13-dashboards-playwright-run.log).

Each image was opened and read before it was kept. A check that passes on a picture that is wrong is a
failure, so what each picture shows is written under it.

## Fleet Overview

![Fleet Overview: health and RED per service](observability/evidence/fleet-overview-health-red.png)

**Proves** every service is up and exports as exactly one JVM series (the metric-identity check:
`count by (job) (group by (job, instance) (jvm_threads_live))` is 1 for each of the five); RED per service
over business routes, with the dashed 5% and 500 ms lines the alerts use; exemplar diamonds on the p95
panel. The 5xx peak at about 15:52 is the gateway answering 503 while the services restarted, and the
3.3% on conversion-svc is the deliberate stale-rate quote (502 `RATE_REJECTED`), which also holds
RateFeedRefusing Pending for a minute. URL `/d/ezeebit-fleet?from=now-30m&to=now`, captured 16:04:53.

![Fleet Overview: limiter, Redis and the database pool](observability/evidence/fleet-overview-limiter-pool.png)

**Proves** the limiter's decisions by outcome (allowed and limited during each burst, a trace of
contention), the Redis round trip as its own span-derived timer with exemplars, Redis reachable, and the
Hikari pool with one connection briefly in use, no waiter and no timeout. Gauges are each JVM's last value
in 30 s, so the restart at 15:52 does not double the idle count. Same URL, scrolled inside Grafana's pane,
16:04:58.

![Fleet Overview: a tile's data links](observability/evidence/fleet-overview-data-links.png)

**Proves** a tile carries per-service links. Clicking "Logs for gateway-svc" landed on
`/d/ezeebit-logs/logs?var-service=gateway-svc&from=now-15m&to=now`, whose Loki queries carried
`gateway-svc` (suite check "data link lands on Logs for the service, window kept"). 16:11:51.

![Fleet Overview at 390 px](observability/evidence/fleet-overview-mobile.png)

**Proves** the board reads at phone width: tiles reflow, the controls wrap, nothing is cut. At this width
Grafana scrolls the document rather than its own pane, which the suite detects and records. 16:06:04.

## Metrics

![Money: deposits and quotes](observability/evidence/money-deposits-quotes.png)

**Proves** the business counters by currency, pair and outcome, refusals by code and rule
(`RATE_REJECTED RATE_STALE`, `UNSUPPORTED_PAIR`), and p95 of each business span with exemplars. Every tag
is bounded: `unknown` is the currency of a refused deposit, never the caller's text. The panels count
per rate window: each spike is one traffic run, including the 15:52 run that came first after a restart,
which a `rate()` panel showed as nothing ([first-count-after-restart.log](observability/verification/2026-10-13-first-count-after-restart.log)).
URL `/d/ezeebit-money?from=now-30m&to=now`, 16:05:11.

![Money: conversions and ledger postings](observability/evidence/money-conversions.png)

**Proves** conversion requests by pair and outcome from the `conversion accept` span, settled outcomes
(COMPLETED, and REJECTED for funds), and the rate feed's calls per minute against its p95 on the right
axis. Same URL, scrolled, 16:05:13.

## Traces

![One conversion, one trace across three services](observability/evidence/trace-three-services.png)

**Proves** one merchant request is one trace: trace `fcc2e6ced20cfdcbab357acaa34bf6a2`, 8 spans, 3
services. gateway-svc's request span holds its `rate limit` span and its call to conversion-svc;
conversion-svc's request span holds `conversion accept` and its call to ledger-svc; ledger-svc's request
span holds `ledger posting`. Before this change the ledger call began a new trace (the `tracing.feature`
red run found no `traceparent` on it). Found by the probe merchant's id in the gateway span's `http.url`.
URL `/d/ezeebit-traces?var-trace_id=fcc2e6ced20cfdcbab357acaa34bf6a2`, row opened, 16:12:48.

![Service map](observability/evidence/traces-service-map.png)

**Proves** Tempo derives the call graph from the spans: user to gateway-svc, gateway-svc to
conversion-svc and ledger-svc, conversion-svc to ledger-svc, plus the registry and config calls. Below
it, span rates from Tempo's span metrics cross-check the services' own counters. 16:05:30.

Not pictured, measured: with Postgres stopped under a deposit, the `deposit` span and the request span
both carry `TransactionSystemException: JDBC rollback failed` with status error (trace
`1da752b225ab41c41cb44000e260f031`); before the change neither carried an exception.

## Logs (trace-correlated)

![A log line and its trace](observability/evidence/logs-trace-correlated.png)

**Proves** a log line written inside a request carries its trace: conversion-svc's
`conversion accepted for ZAR/USDT: COMPLETED` offers `Trace: fcc2e6ced20cfdcbab357acaa34bf6a2`, the same
trace as above. URL `/d/ezeebit-logs?var-service=conversion-svc&var-search=conversion accepted`, 16:12:18.

![The log line's trace opened in Tempo](observability/evidence/logs-trace-opened-in-tempo.png)

**Proves** the derived field works: clicking the link opened Explore on Tempo with that trace id and
rendered the trace. 16:12:33.

Not pictured, measured by the suite: 20 log lines drawn at random from the 132 with a `trace_id` in the
previous 10 minutes on gateway-svc, conversion-svc and ledger-svc, each resolved to a span of the same
service with the same `span_id` in Tempo: 20 of 20.

## Alerts

![The nine rules](observability/evidence/alerts-rule-list.png)

**Proves** the nine provisioned rules load and evaluate, each summary naming its service. URL
`/alerting/list?view=list`, 16:13:13.

![Rule inputs with their state changes](observability/evidence/alerts-inputs-annotations.png)

**Proves** each rule's expression plotted against its threshold, with its state changes as annotations
(dashed markers): ServiceDown went Pending for conversion-svc and gateway-svc while they restarted at
15:52, and RateFeedRefusing went Pending for USDT/ZAR with each stale-rate quote, then Normal: one refusal
holds the ratio arm for a minute, less than its 2-minute hold. DbPoolSaturated shows ledger-svc's old and
new JVM as two series across the restart. URL `/d/ezeebit-alerts`, 16:05:54.

![State history](observability/evidence/alerts-state-history.png)

**Proves** Grafana's own state history for RateFeedRefusing: Pending and Normal, with the value of A at
each transition. URL `/alerting/grafana/ezeebit-rate-feed-refusing/view?tab=history`, 16:13:05.

## Alerts to Slack

_To be filled by the lead: the Slack screenshot (page and all-clear) and the rehearsal timeline, from
one rehearsed outage with `SLACK_WEBHOOK_URL` set._

| Event | Time |
|---|---|
| service stopped | t+0s |
| Pending | |
| Alerting | |
| delivered to Slack | |
| service restored | |
| Normal | |
| RESOLVED delivered | |
