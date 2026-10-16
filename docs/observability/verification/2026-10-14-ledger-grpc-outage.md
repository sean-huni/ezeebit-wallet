# Rehearsed ledger outage over gRPC, and the chaos audit (2026-10-14)

Branch `feat-ledger-grpc` at `3b92e9d`, worktree fleet (compose project `ezeebit-wallet-grpc`), `.env`
settings, Slack contact point `ezeebit-slack` live (paging is allowed in dev). All times are UTC.
t0 = 12:00:04, the `kill -9` of ledger-svc.

## What was done

1. Five fresh merchants were each credited 100.00 ZAR straight on ledger-svc `:8081` (all `201`).
   This happened before the kill, because a deposit needs the ledger.
2. t0: `kill -9 $(<.fleet/ledger-svc.pid)` (pid 97627, exit 0).
3. Each merchant then quoted 50.00 ZAR to USDT through the gateway and converted it straight away.
4. A poller read the rule states from `localhost:3000/api/prometheus/grafana/api/v1/alerts` every 2 s
   for 240 s. It read the Slack counter from Grafana's own `/metrics`.
5. While the outage was still live, `node 2026-10-14-ledger-grpc-capture.mjs` captured the dashboards
   (see [Screenshots](#screenshots)).
6. At t+257s, `./fleet.sh up` restored the fleet. Its status read `ledger-svc DOWN, gateway-svc DEGRADED`,
   so it bounced the whole stack once and ended `RESULT: healthy, all 16 checks passed`, exit 0.
7. The audit below ran once no conversion was PENDING.

## Timing

| Event | Clock | Since t0 |
|---|---|---|
| ledger-svc killed (`kill -9`) | 12:00:04 | t+0s |
| 5 conversions answered `202` status `PENDING`, 0.62 s to 0.97 s each | 12:00:05 to 12:00:08 | t+1s to t+4s |
| 5 `POST` dead-letter records written (`at` field) | 12:00:05.40 to 12:00:08.33 | t+1s to t+4s |
| `ConversionLedgerDeadLettered{op=POST,reason=RETRIES_EXHAUSTED}` Alerting (activeAt 12:00:20). The rule has `for: 0s`, so there is no Pending stage | 12:00:20 | t+16s |
| Slack page delivered, `grafana_alerting_notifications_total{integration="slack"}` absent to 1 | 12:00:31 | t+27s |
| `ServiceDown{job=ledger-svc}` Pending | 12:00:39 (activeAt 12:00:30) | t+35s |
| First conversion's deadline (quote expiry + 30 s posting grace) | 12:00:49.68 | t+45s |
| Sweeper's first `FIND` dead letter (deadline + 30 s max transaction time, next 10 s sweep) | 12:01:22 | t+78s |
| `ConversionLedgerDeadLettered{op=FIND,reason=RETRIES_EXHAUSTED}` Alerting | 12:01:30 | t+86s |
| `ServiceDown{job=ledger-svc}` Alerting | 12:01:39 | t+95s |
| Slack counter 2 | 12:01:49 | t+105s |
| Slack counter 3 | 12:02:31 | t+147s |
| `ConversionLedgerDeadLettered{op=FIND,reason=CIRCUIT_OPEN}` Alerting: the Find breaker opened | 12:02:50 | t+166s |
| Slack counter 4, `grafana_alerting_notifications_failed_total` never present | 12:03:30 | t+206s |
| `./fleet.sh up` started | 12:04:21 | t+257s |
| Sweeper voided all 5 (ledger `VOID` journals, conversions `REJECTED`/`VOIDED`) | 12:05:54 | t+350s |
| `./fleet.sh up` exit 0, 16 of 16 checks | 12:06:05 | t+361s |
| `PENDING` count read as 0 | 12:06:13 | t+369s |

The Slack counter counts notifications, not alerts. Counts 2 to 4 are group updates for both rules:
`ServiceDown` firing, and the two later dead-letter instances. The counter cannot attribute each
send to one rule, and this run did not read the Slack channel.

## Where the Slack counter lives

The brief said to read `grafana_alerting_notifications_total{integration="slack"}` through the
Prometheus datasource proxy (uid `prometheus`). That returned an empty result. The control returned
empty too: `count by (job) ({__name__=~"grafana_.*"})` finds no Grafana self-metrics in Prometheus at
all, so Prometheus never scrapes them. The counter was read from Grafana's own `/metrics`, the same
source `docs/observability.md` used on 2026-10-13:

```
$ curl -s localhost:3000/metrics | grep '^grafana_alerting_notifications'     # at t+248s
grafana_alerting_notifications_total{integration="slack",org="1"} 4
```

## The 202, not 201 or 504

The brief expected 504 `UPSTREAM_OUTCOME_UNKNOWN` or 201 with `PENDING`. The service answered
**202** with `PENDING`, which is its documented contract: `ConversionCtrl` returns 201 when the money
moved and 202 while the ledger's answer is unknown, and ARCHITECTURE.md says "stays PENDING (202) for
the sweeper". No 504 is possible here: a dead peer refuses the connection at once, so the 3 Post
attempts plus 200 ms and 400 ms of backoff finish in about 0.62 s, well inside the gateway's 10 s.
The first response body:

```json
{"data":{"conversionId":"67c4f8ed-8ecf-450a-899f-e7841fdbdbe0","merchantId":"7655f695-b495-4b9c-834f-096bd6daae60","quoteId":"4c8993f9-f8a1-4f3b-928b-e9217bb83f3c","status":"PENDING","reason":null,"sellCurrency":"ZAR","sellAmount":"50.00","buyCurrency":"USDT","buyAmount":"2.699136","appliedRate":"0.05398273","created":"2026-10-14T12:00:04.700923Z","completed":null}}
```

## The dead-letter topic

Read inside the kafka container on the INTERNAL listener, `localhost:29092`. The published 9092
listener was not tried. The read happened during the outage, after the first FIND sweep:

```
$ docker exec ezeebit-wallet-grpc-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:29092 --topic ezeebit.conversion.ledger.deadletter \
    --from-beginning --property print.key=true --property key.separator='|' --timeout-ms 10000
Processed a total of 10 messages        (consumer exit 0)
```

All 10 records had a key equal to their `conversionId`, and every one of `op`, `conversionId`,
`merchantId`, `request`, `attempts`, `lastStatus`, `lastReason`, `at` and `traceId` was present and non-empty:

| op | count | attempts | lastStatus | lastReason | at |
|---|---|---|---|---|---|
| POST | 5 | 3 | UNAVAILABLE | io exception | 12:00:05.40 to 12:00:08.33, one trace each |
| FIND | 5 | 3 | UNAVAILABLE | io exception | 12:01:22.09 to 12:01:28.15, one sweep trace |

The first record in full (key `67c4f8ed-8ecf-450a-899f-e7841fdbdbe0`, `request` decoded from its JSON string):

```json
{"op":"POST","conversionId":"67c4f8ed-8ecf-450a-899f-e7841fdbdbe0","merchantId":"7655f695-b495-4b9c-834f-096bd6daae60","request":{"conversionId":"67c4f8ed-8ecf-450a-899f-e7841fdbdbe0","merchantId":"7655f695-b495-4b9c-834f-096bd6daae60","sellCurrency":"ZAR","sellAmount":"50.00","buyCurrency":"USDT","buyAmount":"2.699136","deadline":"2026-10-14T12:00:49.683488Z"},"attempts":3,"lastStatus":"UNAVAILABLE","lastReason":"io exception","reason":"RETRIES_EXHAUSTED","at":"2026-10-14T12:00:05.404435Z","traceId":"25b71a20795ba0cd5de5a8f01a5c077e"}
```

By t+248s the topic's end offset was 60, which matches the counter:

```
$ docker exec ezeebit-wallet-grpc-kafka-1 /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:29092 --topic ezeebit.conversion.ledger.deadletter
ezeebit.conversion.ledger.deadletter:0:60
sum by (op, reason) (ezeebit_conversion_ledger_deadletter_total{job="conversion-svc"})
  POST RETRIES_EXHAUSTED 5, FIND RETRIES_EXHAUSTED 41, FIND CIRCUIT_OPEN 14
```

**After the restore the end offset was 0. Every record was gone.** At the time of this run the
kafka service in `compose.yaml` had no volume, and `fleet.sh down` (which `up` runs when it bounces)
does `docker compose down`, which removed the container and its log directory with it. The topic's
`retention.ms=604800000` (7 days) therefore really meant "until the next down or bounce". The
recovery path a responder follows after a ledger outage is exactly that bounce, so the record of
the outage was deleted by its own recovery. Reported as a defect and not fixed in this run.
Fixed later the same day by commit `2c9851f` (2026-10-14 14:20 +0200), which gives the broker the
named `kafkadata` volume: records now survive `down` and a bounce until `./fleet.sh purge`.

## Audit

Every query and its output exactly as run. psql ran inside `ezeebit-wallet-grpc-postgres-1` at
12:06:13 (t+369s), after the restore. The volumes hold earlier runs' rows as well (`fleet.sh down`
keeps the Postgres volume), so every query covers the whole database, not just these 5.

```
-- psql -d conversion
SELECT status, count(*) FROM conversion GROUP BY status ORDER BY status;
  status   | count
-----------+-------
 COMPLETED |    28
 REJECTED  |    13
(2 rows)
exit=0

-- psql -d conversion
SELECT count(*) AS pending FROM conversion WHERE status = 'PENDING';
 pending
---------
       0
(1 row)
exit=0

-- psql -d conversion   (the 5 rehearsal merchants)
SELECT id, merchant_id, status, reason, deadline, completed FROM conversion WHERE merchant_id IN (<the 5>) ORDER BY created;
                  id                  |             merchant_id              |  status  | reason |           deadline            |           completed
--------------------------------------+--------------------------------------+----------+--------+-------------------------------+-------------------------------
 67c4f8ed-8ecf-450a-899f-e7841fdbdbe0 | 7655f695-b495-4b9c-834f-096bd6daae60 | REJECTED | VOIDED | 2026-10-14 12:00:49.683488+00 | 2026-10-14 12:05:54.355892+00
 9a2c9258-1ec4-4866-96ce-05486b2599b2 | c8fda3c8-af6d-46eb-9d6b-ec5ac1ce365c | REJECTED | VOIDED | 2026-10-14 12:00:50.691981+00 | 2026-10-14 12:05:54.369975+00
 045b21ba-4e34-45f9-9004-4290d31c899d | 942fe108-5708-47a6-90dd-05f9e8e576ea | REJECTED | VOIDED | 2026-10-14 12:00:51.353143+00 | 2026-10-14 12:05:54.384596+00
 5765964a-16af-4ec7-9576-47c2681bca03 | ddc50f79-992e-46f6-9e95-b99c211a4475 | REJECTED | VOIDED | 2026-10-14 12:00:52.025724+00 | 2026-10-14 12:05:54.396621+00
 ee9aa085-2ede-498a-94cb-08a78d548d59 | c810265e-f11d-4543-8056-fd9fae71b728 | REJECTED | VOIDED | 2026-10-14 12:00:52.694873+00 | 2026-10-14 12:05:54.407852+00
(5 rows)
exit=0

-- psql -d ledger
SELECT type, status, count(*) FROM journal GROUP BY type, status ORDER BY type, status;
    type    | status | count
------------+--------+-------
 CONVERSION | POSTED |    28
 CONVERSION | VOID   |    13
 DEPOSIT    | POSTED |    41
(3 rows)
exit=0

-- psql -d ledger   (a conversion journal's idempotency_key is the conversion id: ConversionPostingMapper)
SELECT count(*) AS journals, count(DISTINCT idempotency_key) AS distinct_conversion_ids FROM journal WHERE type = 'CONVERSION';
 journals | distinct_conversion_ids
----------+-------------------------
       41 |                      41
(1 row)
exit=0

-- psql -d ledger   (the 5 rehearsal merchants)
SELECT idempotency_key, status, reason, created FROM journal WHERE type = 'CONVERSION' AND merchant_id IN (<the 5>) ORDER BY created;
           idempotency_key            | status | reason |            created
--------------------------------------+--------+--------+-------------------------------
 67c4f8ed-8ecf-450a-899f-e7841fdbdbe0 | VOID   |        | 2026-10-14 12:05:54.349624+00
 9a2c9258-1ec4-4866-96ce-05486b2599b2 | VOID   |        | 2026-10-14 12:05:54.364262+00
 045b21ba-4e34-45f9-9004-4290d31c899d | VOID   |        | 2026-10-14 12:05:54.378349+00
 5765964a-16af-4ec7-9576-47c2681bca03 | VOID   |        | 2026-10-14 12:05:54.391549+00
 ee9aa085-2ede-498a-94cb-08a78d548d59 | VOID   |        | 2026-10-14 12:05:54.403349+00
(5 rows)
exit=0

-- psql -d ledger   (the books balance: the three statements of LedgerSteps "the ledger's books balance")
SELECT count(*) AS unbalanced_journal_currency_pairs FROM (SELECT journal_id, currency FROM entry GROUP BY journal_id, currency HAVING sum(amount) <> 0) unbalanced;
 unbalanced_journal_currency_pairs
-----------------------------------
                                 0
exit=0
SELECT count(*) AS merchant_balances_off_their_entries FROM account a WHERE a.type = 'MERCHANT' AND a.balance <> (SELECT coalesce(sum(e.amount), 0) FROM entry e WHERE e.account_id = a.id);
 merchant_balances_off_their_entries
-------------------------------------
                                   0
exit=0
SELECT count(*) AS negative_merchant_balances FROM account WHERE type = 'MERCHANT' AND balance < 0;
 negative_merchant_balances
----------------------------
                          0
exit=0
```

The databases are separate, so the cross-database check reads three id lists and compares them with `comm`
(psql exits 0, 0, 0):

```
-- conversion: SELECT id FROM conversion WHERE status = 'COMPLETED' ORDER BY 1
-- conversion: SELECT id FROM conversion WHERE status <> 'COMPLETED' ORDER BY 1
-- ledger:     SELECT idempotency_key, count(*) FROM journal WHERE type = 'CONVERSION' AND status = 'POSTED' GROUP BY 1 ORDER BY 1
completed conversions: 28; posted conversion journals: 28
posted journals per id other than 1: 0
completed with no posted journal: 0
posted journal with no completed conversion: 0
non-COMPLETED conversions holding a posted journal: 0
positive control (one invented COMPLETED id added): completed with no posted journal = 1
```

**Verdict: clean.** Zero PENDING. On the ledger's conversion journals, `count(*) = count(distinct)`
(41 = 41). Each of the 28 COMPLETED conversions has exactly one POSTED journal, and no other
conversion has one. The books balance on all three statements. All 5 outage conversions were voided
on both sides. No money moved for them: each deposit of 100.00 ZAR stands.

## gRPC p95 panel has data

The Task 12 `grpc.client` histogram:

```
histogram_quantile(0.95, sum by (rpc_method, le) (rate(grpc_client_milliseconds_bucket{job="conversion-svc", net_peer_name="ledger-svc"}[5m])))
  before the outage (11:58, 4 Posts from fleet.sh's conversion checks):  Post 16.75 ms
  after the restore (12:07):  [5m]  Find 1.07 ms, Post 183.63 ms, Void NaN
                              [15m] Post 15.75 ms, Find 1.07 ms, Void NaN
count(grpc_client_milliseconds_bucket{job="conversion-svc"}) = 138 series
```

Void reads NaN because its 5 calls came in one burst right after conversion-svc restarted. That burst
created the series at 5, so `rate()` sees no increase. This is the counter-appears-mid-window
shape: `increase()` and `rate()` have nothing to extrapolate from when a counter is born inside the
window. It is a limit of the panel at restart, not
missing data.

## Screenshots

Captured at 12:00:54 to 12:01:12, during the outage, by
[2026-10-14-ledger-grpc-capture.mjs](2026-10-14-ledger-grpc-capture.mjs) (a copy of the 2026-10-13
pattern, 1440x900, installed Chrome):

- [Fleet Overview, Ledger gRPC row](../evidence/2026-10-14-fleet-overview-ledger-grpc-row.png):
  "Post UNAVAILABLE" at 0.285 req/s, the Post error ratio at 100%, Post p95 down to 2.5 ms (refused
  connections fail fast), all three breakers still CLOSED at capture time, and Dead letters at 5
  for POST RETRIES_EXHAUSTED.
- [Dead letters panel](../evidence/2026-10-14-fleet-overview-dead-letters.png): one series, POST
  RETRIES_EXHAUSTED, appears at 5 at 14:00 local (the counter is born with the first dead letter, so
  no 0 segment is drawn), above the dashed threshold.
- [Alerts dashboard](../evidence/2026-10-14-alerts-dead-letter-firing.png): ConversionLedgerDeadLettered
  "Firing for 39s" in both lists, and ServiceDown "Pending for 29s".
- [ConversionLedgerDeadLettered rule input](../evidence/2026-10-14-alerts-dead-letter-input.png): the
  expression steps to 5 with a red state-change annotation. KafkaAbsent's input ("conversion-svc
  producer") stays at 0.

The browser shows local time (UTC+2), so 14:00 on the panels is 12:00 UTC.

## What this does not prove

- **The all-clear for this incident was never sent.** `fleet.sh down` stops lgtm first, and Grafana's
  database is a tmpfs (`/data/grafana/data`), so the restore wiped the alert state before the rules
  could resolve. The fresh Grafana then re-evaluated the persisted metrics and re-fired the two FIND
  instances (activeAt 12:06:00). It paged Slack again for an incident that was already over:
  counter 1 on the new process at 12:07:40. Follow-up poll on the new process:

  ```
  12:07:50 t+466s slack_sent=1 FIND/CIRCUIT_OPEN=Alerting FIND/RETRIES_EXHAUSTED=Alerting POST/RETRIES_EXHAUSTED=Normal
  12:09:10 t+546s slack_sent=1 FIND/CIRCUIT_OPEN=Normal   FIND/RETRIES_EXHAUSTED=Alerting POST/RETRIES_EXHAUSTED=Normal
  12:09:22 t+558s slack_sent=1 all three Normal (NoData)
  12:09:53 t+589s slack_sent=2 (the all-clear for the re-fired page), slack_failed absent throughout
  ```

  So a responder gets the outage page at t+27s and then a second page after recovery. The only
  all-clear arrives at t+589s, and it is for that second page.
- One Slack channel and one integration is not an on-call rota.
- The circuit breaker opened only for Find (14 CIRCUIT_OPEN records). Post saw 5 calls, below the
  20-call window, so its breaker stayed closed, as configured.
