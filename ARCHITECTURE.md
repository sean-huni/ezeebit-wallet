# Architecture

How the system is shaped and why. README.md covers running it; SOLUTION.md covers task choice, trade-offs and how tasks 3 to 6 slot in.

**Built vs designed.** At this commit the repository contains: five services that boot, register and route; the gateway's route table and its 503/504 mapping for upstream failures; one database per domain service; local observability and alerting (section 3); **the Task 1 ledger (section 4)**: deposits, balances and the statement; and **Task 2 (section 5)**: quotes, conversions, the ledger's conversion postings and voids, and the PENDING sweeper, all built and tested. A WireMock container stands in for the exchange-rate service the brief says already exists. Where the build departs from the design, the "As built" lists in sections 4 and 5 say so.

## 1. How the design was derived

The brief was read as a grammar exercise before any service was drawn:

- **Nouns** propose candidate things (services, tables, value objects).
- **Verbs** propose operations (endpoints, functions).
- **Adverbs, adjectives and modals** ("completely", "large", "must never") become proof obligations: something in the design has to make each one true.
- A **subtractive pass** then deletes every candidate the brief cannot justify for the chosen tasks.

Requirement ledger (quotes are verbatim from the brief):

| Quote | Word class | Obligation in the design |
|---|---|---|
| "keeps track of what each merchant holds" | verb | per-merchant, per-currency balances |
| "hold balances in several currencies at once" | adverb | one account per (merchant, currency) |
| "Money arrives (a deposit) and the balance goes up" | verb | deposit operation |
| "trust these balances completely" | adverb | append-only journal; balance derived from entries and reconcilable |
| "explain, at any later date, exactly how a balance reached its current value" | adverbs | immutable entries, each carrying `balance_after`; an entry is never updated or deleted |
| "amounts can be large" | adjective | `NUMERIC` / `BigDecimal`, never floating point |
| "two different currencies must never be silently mixed" | modal + adverb | `Money` value object whose cross-currency arithmetic throws; the DB ties an entry's currency to its account's currency |
| "convert an amount of one currency they hold into another" | verb | conversion operation |
| "exchange-rate service already exists ... can be slow and its price keeps moving" | adjectives | call it, never build it; timeout; quotes expire |
| "which rate the merchant actually gets ... between quoting a price and completing the conversion" | verbs | two steps: quote (locks a rate for a short TTL), then accept; the merchant gets exactly the quoted rate or a clear rejection |
| "not end up on the wrong side of a moving market" | phrase | short quote TTL, spread in the platform's favour, rounding in the platform's favour |
| "must never be able to convert more than they actually hold" | modal | atomic debit under a row lock with a balance check; `CHECK (balance >= 0)` as last line of defence |
| "fires requests over flaky mobile connections, sometimes more than once" | adverb | an idempotency key on every POST that moves money (section 4, Idempotency) |
| "a bad exchange rate, a timeout, or a duplicate notification" | nouns | rate sanity validation; a timeout fails closed (no default rate); dedupe |

## 2. Services

### Verification of the candidate list

The initial proposal was Merchant, Ledger, Fiat, Crypto, Settlement. Each was checked against the brief text for tasks 1 and 2.

| Candidate | Evidence in the brief (tasks 1 + 2) | Verdict |
|---|---|---|
| Ledger | "keeps track", "explain exactly how", "audit trail" | kept: `ledger-svc` |
| Conversion | verbs "convert", "quoting", "completing"; its own failure domain (slow rate feed) | **added**: `conversion-svc` (missing from the initial list) |
| Merchant | only "identified by an ID in the request"; "no real authentication or KYC" | dropped: a `MerchantId` value object |
| Fiat | "fiat" is a kind of currency; fiat payouts are tasks 3 and 6 | dropped: `CurrencyKind.FIAT` |
| Crypto | a stablecoin is a kind of currency; on-chain payments are task 4 | dropped: `CurrencyKind.STABLECOIN` |
| Settlement | "settle ... next day" (About section), task 5 | dropped for tasks 1 + 2 |

### Why conversion is a separate service

A context earns its own service only if it passes all three tests:

1. **Distinct invariants.** Conversion owns quote expiry, spread and rate sanity. The ledger owns non-negative merchant balances, zero-sum journals and currency integrity. Neither set refers to the other.
2. **Distinct rate of change.** Pricing policy (spread, TTL, sanity bounds) changes for commercial reasons; the ledger's rules should almost never change.
3. **Distinct failure domain.** A slow or down rate feed must never stop deposits or balance reads. In one service, a thread pool saturated by slow rate calls would.

### Deliberately not built

- Identity and authentication: out of scope per the brief. `merchantId` is a path variable.
- A notification service and Redis: nothing in tasks 1 and 2 needs them (Redis would only back gateway rate limiting, which is not requested).

## 3. Runtime topology

```
                         client (merchant dashboard)
                                    |
                                    v
                        +-----------------------+
                        |  gateway-svc :8080    |  route table, 503/504 on upstream failure
                        +-----------------------+
                          |                   |
             lb://ledger-svc            lb://conversion-svc
                          v                   v
            +------------------+   POST /internal/...   +----------------------+      HTTP, 2s timeouts
            | ledger-svc :8081 |<-----------------------| conversion-svc :8082 |-----> exchange-rate service
            +------------------+   (lb://ledger-svc)    +----------------------+       (assumed; WireMock :8089 locally)
                     |                                             |
                     v                                             v
            +---------------------------------------------------------------+
            |  postgres:18.6-alpine (one container)                         |
            |  db "ledger" (role ledger)  db "conversion" (role conversion) |
            +---------------------------------------------------------------+

   control plane, started first:
     config-svc :8888  (Spring Cloud Config, native backend, serves served/*.yml)
     eureka-svc :8761  (service registry)
   ledger, conversion and gateway fetch config from config-svc and register with eureka-svc on boot.

   observability: lgtm :3000 Grafana, :4317/:4318 OTLP (grafana/otel-lgtm:0.36.0)
     every service pushes metrics, traces and logs to :4318 over OTLP/HTTP.
```

| Service | Port | Role | Exists today |
|---|---|---|---|
| gateway-svc | 8080 | single client-facing entry; Spring Cloud Gateway Server WebMVC; routes by registry name | yes: routes and 503/504 mapping |
| ledger-svc | 8081 | Task 1: balances, internal deposits, journal | yes: deposits, balances, statement (section 4) |
| conversion-svc | 8082 | Task 2: quotes and conversions | yes: quotes, conversions, PENDING sweeper (section 5) |
| wiremock | 8089 | stands in for the exchange-rate service (`wiremock/wiremock:3.13.2-3`, mappings in `docker/wiremock/mappings/`) | yes: local stand-in only |
| config-svc | 8888 | Spring Cloud Config Server, native backend | yes |
| eureka-svc | 8761 | Eureka service registry | yes |
| lgtm | 3000, 4317, 4318 | Grafana, Prometheus, Tempo, Loki, OTLP collector (one container) | yes |

Each service port lives only in that service's own `application.yml` as `${SERVER_PORT:<n>}`; `fleet.sh` reads it from there. The lgtm ports are fixed in `compose.yaml`.

Published route table (`services/config-svc/src/main/resources/served/gateway-svc.yml`). Only these paths reach a service:

| Route | Target | Paths |
|---|---|---|
| ledger | `lb://ledger-svc` | `/api/v1/merchants/*/balances/**`, `/api/v1/merchants/*/entries/**` |
| conversion | `lb://conversion-svc` | `/api/v1/merchants/*/quotes/**`, `/api/v1/merchants/*/conversions/**` |

`/internal/**` is never routed: it is for service-to-service calls only. That includes deposits: a merchant must not be able to credit itself, so `POST /internal/v1/deposits` is called by the money-in rail, never through the gateway.

**Upstream failures map to 503 or 504, built.** `UpstreamUnavailableAdvice` turns each failure shape into a `ProblemDetail` with a fixed detail string that names no service:

- no registered instance (the load balancer's own 503 passed through): **503**, the request never left the gateway;
- refused connection (`ResourceAccessException` caused by `ConnectException`): **503**, the request never reached the service;
- any other upstream I/O failure (connection reset, timeout): **504** "Upstream outcome unknown: retry with the same Idempotency-Key", because the request may have been processed.

Without it all three reached the client as 500, which reads as a gateway bug. Two tests in `GatewayApplicationTests` lock in the two 503 cases; the 504 branch has no test yet.

**Registry timings, built.** Eureka's server response cache is 5s (default 30s), clients fetch the registry every 5s (default 30s), and the gateway's load-balancer instance cache is disabled. With defaults, a fresh fleet could not route for up to about a minute, and a dead instance stayed routable for longer.

**Database per service, built.** `docker/postgres/initdb/01-databases.sql` creates role `ledger` owning database `ledger` and role `conversion` owning database `conversion`, and revokes `CONNECT` on each database from `PUBLIC`. Neither role can even connect to the other's database. The only path from conversion to ledger data is the ledger's HTTP API. There are no cross-database foreign keys: conversion stores merchant and account references as plain UUIDs.

**Observability, built.** One `grafana/otel-lgtm:0.36.0` container runs Grafana, Prometheus, Tempo, Loki and an OTLP collector. Every service exports over OTLP/HTTP to :4318: metrics on a 10 s step, traces sampled at 100%, and logs, where a line logged inside a request carries its `trace_id`. Each JVM is its own series: `service.instance.id` is `host:port`, so two instances of one service never write into one Prometheus series. Dashboards and alert rules are provisioned from files in `docker/lgtm/`.

### Alerting

| Rule | Condition | Fires |
|---|---|---|
| ServiceDown | `absent_over_time` over a pushed JVM metric, 30 s window, one term per service (`absent()` would wait out the 5 min lookback, because a pushed series gets no staleness marker) | after 1 min; one alert per service |
| HighErrorRate | a service's 5xx share of requests above 5% | after 2 min |

The Slack contact point is installed only when `SLACK_WEBHOOK_URL` is set: Grafana refuses to start on a Slack contact point with an empty URL. Grafana's own database lives on tmpfs, because everything in it is provisioned from files and a Pending alert persisted at shutdown fired the instant the next boot evaluated a still-booting fleet. Before that fix a restart produced 4 false pages (reproduced with Slack disconnected); after it the same script produced 0, and a boot produces 0 notifications.

Rehearsed outage, measured once with Slack live on a local run (counts from Grafana's `grafana_alerting_notifications_total` counters):

| Event | Time |
|---|---|
| conversion-svc stopped | t+0s |
| ServiceDown Pending | t+46s |
| Alerting | t+104s |
| Delivered to Slack (sent=1, failed=0); service restored | t+115s |
| Normal | t+146s |
| RESOLVED delivered (sent=2, failed=0) | t+175s |

## 4. Ledger design (Task 1)

**Built**, including the parts Task 2 needs: the `PLATFORM_FX` account type (one seeded per currency), the `CONVERSION` journal type and the `VOID` journal status arrive in changeset `005-conversion.xml`, which widens the CHECK constraints of 002 and 003 rather than editing them. Where the build departs from or adds to the design below, the "As built" lists at the end of this section and of section 5 say so.

### Double-entry model

Every movement of money is a **journal** with two or more **entries**. Per journal, per currency, entries sum to zero. Money is never created by editing a balance: a deposit is money moving from a platform clearing account into the merchant's account.

| Table | Key columns | Constraints |
|---|---|---|
| `currency` | `code` (PK: ZAR, NGN, KES, USDT, USDC), `kind` (FIAT, STABLECOIN), `scale` (2 for fiat, 6 for stablecoins) | an amount with more decimals than `scale` is rejected, never rounded |
| `account` | `id` UUID, `merchant_id` UUID (null for platform accounts), `currency` FK, `type` (MERCHANT, PLATFORM_DEPOSIT_CLEARING, PLATFORM_FX), `balance` NUMERIC(38,18) (MERCHANT only; null on platform accounts), `created` | UNIQUE (`merchant_id`, `currency`, `type`); partial UNIQUE (`currency`, `type`) WHERE `merchant_id IS NULL` (Postgres treats NULLs as distinct, so the first constraint alone allows duplicate platform accounts); UNIQUE (`id`, `currency`); CHECK `balance >= 0` when `type = MERCHANT` |
| `journal` | `id` UUID, `merchant_id`, `type` (DEPOSIT, CONVERSION), `status` (POSTED, VOID), `idempotency_key`, `request_hash`, `reference`, `created` | UNIQUE (`merchant_id`, `type`, `idempotency_key`): a rail reference and a `conversionId` can never collide |
| `entry` | `id`, `journal_id` FK, `account_id`, `currency`, `amount` signed NUMERIC(38,18), `balance_after` (merchant entries only; null on platform entries), `created` | FK (`account_id`, `currency`) references `account` (`id`, `currency`); insert-only |

### Invariants and where each is enforced

| Invariant | Enforced by |
|---|---|
| An entry cannot carry a different currency than its account | DB: composite FK to `account(id, currency)` |
| Two currencies are never added together | code: `Money` value object throws on cross-currency arithmetic |
| A merchant balance never goes negative | code: balance check under row lock; DB: CHECK as last line of defence |
| Amounts never lose precision | DB: NUMERIC(38,18); code: `BigDecimal` from string, scale validated against `currency.scale` |
| Per journal, per currency, entries sum to zero | code: the posting function builds balanced entry sets only; verified by a reconciliation query |
| A merchant `account.balance` equals the sum of its entries | code: the cached balance is updated in the same transaction as the entry insert; reconciliation query `balance == sum(entry.amount)` over merchant accounts. Platform accounts cache no balance: it is derived on read as `sum(entry.amount)` |
| History is never rewritten | code: no update or delete path on `entry` or `journal`; corrections are new, reversing journals |
| One logical request posts at most once | DB: UNIQUE (`merchant_id`, `type`, `idempotency_key`) |
| A conversion posting never lands after its quote's window | ledger: posting deadline checked against the ledger's own DB clock; a VOID marker under the same identity refuses any later posting |

The zero-sum rule is code-enforced, not a DB constraint: a per-transaction sum check would need a deferred trigger, which is not worth its portability cost here. The reconciliation query is the detective control.

### Deposit flow

`POST /internal/v1/deposits` on ledger-svc (not routed by the gateway) with `Idempotency-Key`, body `{merchantId, currency, amount, reference}`. The caller is the money-in rail or chain listener (Task 4), and the key is that rail's own transfer reference. In one transaction:

1. Validate currency exists and the amount's scale fits it.
2. Insert the journal row first. A unique violation on (`merchantId`, DEPOSIT, key) means the request was already posted, or is being posted concurrently: roll back, load the stored journal, and return it if the `request_hash` matches, else 422. Never a 500.
3. Open the merchant account if absent (`INSERT ... ON CONFLICT DO NOTHING`), then lock it (`SELECT ... FOR UPDATE`).
4. Insert two entries: merchant account `+X`, platform deposit-clearing account `-X`, same currency. The merchant entry carries its `balance_after`.
5. Update the merchant's cached balance. Commit. Return 201 with the journal.

Platform clearing accounts may go negative: a negative clearing balance is money the platform is owed from outside. Merchant accounts may not. The clearing account is never locked and carries no cached balance (see Concurrency).

### Concurrency

- A posting locks only the MERCHANT account rows it touches, with `SELECT ... FOR UPDATE`, before reading balances: one for a deposit, the merchant's source and target accounts for a conversion.
- Rows are locked in ascending account id order, so two concurrent postings cannot deadlock on each other.
- Platform accounts (PLATFORM_DEPOSIT_CLEARING, PLATFORM_FX) are never locked and carry no cached balance; their balance is derived on read as `sum(entry.amount)`. Every posting writes them, so locking them would serialise the whole platform per currency during payday bursts.
- The balance check happens after the lock, so two parallel debits of one account serialise and the second sees the first's result.
- Accounts are opened with `INSERT ... ON CONFLICT DO NOTHING` followed by `SELECT ... FOR UPDATE`, so two first deposits in one currency cannot both create the account. A conversion posting opens the merchant's target-currency account the same way if it does not exist yet.

### Idempotency

Every POST that moves money carries an idempotency key, named per endpoint:

| Endpoint | Key |
|---|---|
| `POST /internal/v1/deposits` | header `Idempotency-Key` = the rail's transfer reference |
| `POST .../conversions` | header `Idempotency-Key` chosen by the merchant |
| `POST /internal/v1/conversion-postings` | the `conversionId` |
| `POST .../quotes` | none: a quote moves no money and is cheap to repeat |

The stored identity is (`merchant_id`, `type`, key), never the key alone, so two merchants choosing the same key cannot collide and a rail reference cannot collide with a `conversionId`. Same key and same request hash replays the original result; same key and a different body is 422. The row is inserted first and a unique violation is read as "replay the stored result", so two concurrent identical requests give one posting and two identical answers, never a 500. The key is stored in the journal row itself, in the same transaction as the entries, so a crash cannot leave a recorded key without its posting or the reverse.

### As built

Code: `services/ledger-svc`, package `io.ezeebit.ledger` (`ctl`, `service`, `domain`, `data/model`, `data/repo`, `mapper`, `api/advice`, `exception`). Schema: `db/changelog/2026/10/001` to `004`.

- **Reference data.** The five currencies are seeded by Liquibase, and so is one `PLATFORM_DEPOSIT_CLEARING` account per currency, with fixed ids, so every environment agrees on them.
- **`entry.seq`.** An identity column the design did not have. It is assigned at insert, under the merchant row lock, so it is the order in which `balance_after` values were computed. The statement is ordered by it and pages on it (`after` cursor). `created` is the transaction start time and is not a safe order: a transaction that starts first can take the lock second.
- **Amounts.** Decimal strings on the wire (`"1500.00"`); a JSON number is refused, so no binary float ever reaches the service. `BigDecimal` from the string, `NUMERIC(38,18)` in storage, and shown at the currency's scale. The scale rule counts significant decimals: `"10.500"` is accepted for ZAR because nothing is lost, `"10.001"` is refused. More than 20 digits before the point is refused (`AMOUNT_TOO_LARGE`), because it could not be stored. A deposit that would take the balance past 20 digits is refused after the row lock with 422 `BALANCE_LIMIT_EXCEEDED` and rolls back whole: `Money.plus` refuses any sum the column cannot hold.
- **Input that Postgres cannot store is refused at the edge.** `reference` and `Idempotency-Key` may not contain control characters (a NUL cannot be stored in `text`); statement `from` and `to` must fall in the years 1970 to 9999, with `from` before `to`. All of these are 400 `VALIDATION_FAILED` with an `errors[]` entry naming the field.
- **Request hash.** SHA-256 over the request's fields exactly as sent (merchant id, currency, the amount string, reference), each length-prefixed. It is not a hash of the raw body bytes: a rail that re-serialises the same deposit with different whitespace or key order replays rather than getting 422, while `"100.0"` and `"100.00"` still count as different requests.
- **Replay mechanics.** The duplicate is caught outside the transaction it rolled back, and the stored journal is read in a new one. A concurrent duplicate waits on the unique index until the first commits, then replays its answer: there is no in-flight 409. Every answer, first or replay, is built from the stored rows, so a replay is byte-identical.
- **Keys never expire.** The key is the rail's transfer reference, a permanent identity, not a client retry token.
- **Envelope and errors.** Success bodies are `{"data": ...}`. Errors are RFC 9457 problem details with a stable `code`, a `field` where one applies, and text from `i18n/messages.properties`.
- **Domain to web coupling, accepted for the timebox.** The domain throws `LedgerException(ErrorCode)`, and `ErrorCode` carries the HTTP status, so the framework-free domain still knows its errors' web statuses. A cleaner split would map codes to statuses in the advice.
- **Raw-SQL changesets are re-run safe.** The CHECK and partial-index changesets carry `MARK_RAN` preconditions (`sqlCheck` on `pg_constraint`, `indexExists`); `MigrationRerunIT` deletes their history rows and re-applies them to the existing schema.
- **Versioning.** Path segment 1 is the API version (`/api/v1/...`, `/internal/v1/...`); another version is 400 `UNSUPPORTED_API_VERSION` at the service. The gateway routes v1 only.
- **Statement.** `currency` is optional (without it, every currency, each entry with its own account's `balance_after`); `from` inclusive and `to` exclusive on `created`; `limit` 1 to 500, default 100.
- **Reconciliation** (every journal sums to zero per currency; every merchant `balance` equals the sum of its entries) is a SQL step in the acceptance suite, run after the concurrency, statement and balance-limit scenarios over the whole test database, and was run against the live database during the smoke test (0 unbalanced journals, 0 merchant accounts off). It is not yet a scheduled job or an alert.

Proven by tests (`./gradlew :services:ledger-svc:check`; the conversion-posting scenarios are listed in section 5): 50 concurrent distinct deposits to one account sum exactly and equal the sum of the statement; 20 concurrent copies of one deposit give 20 identical 201 answers and one journal; one key sent at once in two currencies posts once and refuses the other with 422; a new merchant's concurrent first deposits in two currencies open both accounts correctly; an entry whose currency differs from its account's is refused by the database; the statement window includes a deposit made exactly at `from` and excludes one made exactly at `to`. Each was seen red with its control removed (the row lock, the journal unique constraint, the composite foreign key, the window comparison operators, the entry amounts) before being trusted.

## 5. Conversion design (Task 2)

### Two steps: quote, then accept

| Table | Key columns | Constraints |
|---|---|---|
| `quote` | `id`, `merchant_id`, `sell_currency`, `buy_currency`, `sell_amount`, `buy_amount`, `rate` (market), `applied_rate` (after spread), `spread_bps`, `rate_source_timestamp`, `expires_at`, `status` (ACTIVE, USED) | stored in the DB, not in memory, so any replica can accept it |
| `conversion` | `id`, `merchant_id`, `quote_id`, `idempotency_key`, `status` (PENDING, COMPLETED, REJECTED), `reason`, `created`, `completed` | UNIQUE (`quote_id`): a quote is single-use; UNIQUE (`merchant_id`, `idempotency_key`) |

**Quote.** `POST /api/v1/merchants/{id}/quotes` with `{sell, buy, sellAmount | buyAmount}`:

1. Call the exchange-rate service with a short timeout (about 2s).
2. Validate the rate: positive; fresh (source timestamp within N seconds); inside the pair's configured sanity band (min/max, served by config-svc). If the most recent quote for the pair is younger than N minutes (about 5), also within X% of its market `rate`, read from the `quote` table (no separate store). Otherwise band and freshness alone apply, so one refused rate cannot lock a pair out for ever. Repeated refusals for a pair are a future alert: the alerting path now exists, the rule is not written.
3. Apply the spread in the platform's favour.
4. Round in the platform's favour, by at most one minor unit: for a `sellAmount` quote, the buy amount the merchant receives rounds **down** to the buy currency's scale; for a `buyAmount` quote, the sell amount the merchant pays rounds **up** to the sell currency's scale.
5. Persist with a short TTL (about 15s) and return `quoteId`, `appliedRate`, both amounts and `expiresAt`.

A timeout or a rate that fails validation returns 503 or 502 with a reason. There is **no fallback** to a cached or default rate: a conversion at a wrong price is a mis-converted transaction, which the brief names as a serious incident, while a refused quote costs the merchant one retry.

**Which rate the merchant gets.** Exactly the quoted `applied_rate`, if they accept before `expires_at`. After expiry they get 409 `QUOTE_EXPIRED` and re-quote. The price is firm for its whole life.

**How the platform stays on the right side of the market.** The spread and the short TTL bound how far the market can move against the platform during the quote window. The `PLATFORM_FX` account balances per currency are the platform's open position, visible at any moment; hedging that position is outside this slice. Considered and rejected for now: "last look" re-pricing at accept, which protects the platform further but makes the merchant's price non-firm.

**Accept.** `POST /api/v1/merchants/{id}/conversions` with `{quoteId}` and `Idempotency-Key`:

1. Reject if the quote belongs to another merchant (404), is expired (409 `QUOTE_EXPIRED`), or is already used under a different `Idempotency-Key` (409 `QUOTE_ALREADY_USED`).
2. Insert the conversion as PENDING. UNIQUE (`quote_id`) makes a double accept impossible even across replicas; a unique violation on (`merchant_id`, `idempotency_key`) replays.
3. Call the ledger's internal posting with `conversionId` as its idempotency key and a **deadline** = quote `expires_at` + a grace (about 30s).
4. The ledger, in **one local transaction**, refuses the posting if its own DB clock is past the deadline or a VOID marker holds the identity. Otherwise it opens the merchant's target account if absent, locks the merchant's source and target accounts in ascending id order (platform FX accounts are not locked), checks the merchant's source balance, and posts four entries:

| Account | Currency | Amount |
|---|---|---|
| merchant source | sell | `-sell_amount` |
| PLATFORM_FX source | sell | `+sell_amount` |
| PLATFORM_FX target | buy | `-buy_amount` |
| merchant target | buy | `+buy_amount` |

Each currency nets to zero. The result marks the conversion COMPLETED or REJECTED (`INSUFFICIENT_FUNDS`).

```
merchant        gateway       conversion-svc           rate service        ledger-svc
   |  POST quotes   |               |                        |                  |
   |--------------->|-------------->|  GET rate (2s timeout) |                  |
   |                |               |----------------------->|                  |
   |                |               |<-----------------------|                  |
   |                |               | validate, spread, round, save (TTL)       |
   |<---------------|<--------------| 201 quoteId, appliedRate, expiresAt       |
   |                                                                            |
   | POST conversions {quoteId} + Idempotency-Key                               |
   |--------------->|-------------->| check expiry, insert PENDING              |
   |                |               |  POST /internal/v1/conversion-postings    |
   |                |               |------------------------------------------>|
   |                |               |   lock merchant rows, check, 4 entries    |
   |                |               |<------------------------------------------|
   |<---------------|<--------------| COMPLETED (or REJECTED: INSUFFICIENT_FUNDS)|
```

### PENDING recovery, and why there is no saga

If the ledger call times out, the conversion stays PENDING and the client gets 202. A same-key retry of the accept re-checks quote expiry and returns the conversion's current state; it never re-posts at a stale rate.

A sweeper resolves a PENDING conversion only after its posting deadline plus the maximum transaction time, when no posting can still commit. It asks the ledger by `conversionId`. Found: mark COMPLETED. Not found: write a VOID marker in the ledger under the same identity (`merchant_id`, CONVERSION, `conversionId`), so any late posting conflicts and is refused, then mark REJECTED.

No saga and no compensation are needed because the only money movement is one atomic transaction inside one database. The conversion row is bookkeeping about that transaction, not a second money movement that could need undoing.

### The exchange-rate service contract (an assumption)

The brief says the service exists and can be called; it does not say how. This is the contract assumed, and the only place it is written down. The caller-owned DTO is `outbound/rates/RateDto`; WireMock serves it locally from `docker/wiremock/mappings/rates.json`, and conversion-svc's acceptance tier loads the same files.

```
GET /rates?base=ZAR&quote=USDT
200 {"base":"ZAR","quote":"USDT","rate":"0.054254","timestamp":"2026-10-13T10:00:00Z"}
```

- `rate` is how many units of `quote` one unit of `base` buys, as a decimal string (a JSON number is read from its text, never through a double). A conversion selling `base` for `quote` uses it directly; both directions of every pair are served, so the platform never inverts a rate.
- `timestamp` is when the price was taken, as an ISO-8601 instant.
- An error status, an unknown pair (404 in the stand-in), a refused connection or no answer within 2 s means "no rate" (503 `RATE_UNAVAILABLE`). An answer for another pair, one whose rate or timestamp cannot be read, or a rate with more than 14 decimals (the stored rate after the spread would be rounded) is a bad rate (502 `RATE_REJECTED`, reason `RATE_MALFORMED`).

### As built

Code: `services/conversion-svc`, package `io.ezeebit.conversion` (`ctl`, `service`, `domain`, `data/model`, `data/repo`, `mapper`, `outbound/rates`, `outbound/ledger`, `scheduler`, `api/advice`, `exception`, `config`). Schema: `db/changelog/2026/10/001-quote.xml`, `002-conversion.xml`. Ledger side: `ConversionPostingCtrl`, `ConversionPoster`, `ConversionVoider`, changeset `005-conversion.xml`. Where it departs from or adds to the design above:

- **VOID is a journal status, not a journal type.** The void marker is a journal row with type `CONVERSION`, status `VOID` and no entries. Only that way does it share the posting's identity (`merchant_id`, `CONVERSION`, `conversionId`), so the existing unique constraint is what refuses a late posting. A CHECK allows `VOID` on `CONVERSION` journals only.
- **The deadline check is the last statement of the posting transaction**, read from `clock_timestamp()` (`now()` is frozen at the transaction start). What remains after it is the commit itself, which the sweeper's `max-transaction-time` (30 s) covers. Correctness does not depend on the two databases' clocks: a posting and a void race on one unique index, and whichever commits first wins; the deadline only stops a posting at a stale price.
- **Locking order.** The target account is opened first (`INSERT ... ON CONFLICT DO NOTHING`), then both merchant accounts are locked one by one in ascending id order (UUIDs compared as their lowercase text, which is Postgres's byte order). A missing source account is `INSUFFICIENT_FUNDS`.
- **Ledger endpoints.** `POST /internal/v1/conversion-postings` body `{conversionId, merchantId, sellCurrency, sellAmount, buyCurrency, buyAmount, deadline}`; its request hash covers every field, so the same `conversionId` with any other value is 422 `IDEMPOTENCY_KEY_REUSED`. `GET /internal/v1/conversion-postings/{conversionId}?merchantId=` answers `status` POSTED (with both sides) or VOID, else 404. `POST .../{conversionId}/void` body `{merchantId}`. Each answer is built from the stored rows, so replays are byte-identical. The statement shows a conversion as two lines of type `CONVERSION` with reference `conversion <id>`.
- **Same-key retry.** A PENDING conversion is posted again while its quote is live: safe, because the ledger is idempotent on the `conversionId` and checks the deadline. After expiry the retry only returns the current state, so nothing is ever posted at a stale rate. A replay of a REJECTED conversion answers what the first call answered: 422 `INSUFFICIENT_FUNDS`, or 409 `CONVERSION_REJECTED` with its `reason` (`DEADLINE_PASSED`, `VOIDED`). Both problem details carry `conversionId`.
- **Unknown stays PENDING.** Any ledger answer other than posted, insufficient funds, deadline passed or voided (a timeout, a reset, a 5xx, a 400 from configuration drift) leaves the conversion PENDING for the sweeper. Settling is a compare-and-set (`UPDATE ... WHERE status = 'PENDING'`), so the accept path and the sweeper cannot both settle one row.
- **The sweeper** (`scheduler/PendingConversionSweeper`, every 10 s) claims one due row per transaction with `SELECT ... FOR UPDATE OF c SKIP LOCKED`, asks the ledger, voids if absent, settles, and commits; the row stays locked while the ledger is asked. Rows whose answer is unknown are skipped for the rest of that run through a method-local list; nothing is held between runs, so every replica can run it.
- **The quote's single use is enforced only by `UNIQUE (quote_id)`.** `quote.status` becomes USED in the same transaction, for reading; no code path depends on it, so the constraint's test cannot pass for a second reason.
- **Pricing.** `appliedRate = rate x (10000 - spread_bps) / 10000`, exact in decimal. A `sellAmount` quote rounds the received amount down (`FLOOR`) to the buy currency's scale; a `buyAmount` quote rounds the paid amount up (`CEILING`) to the sell currency's scale. An amount that rounds to nothing is 400 `AMOUNT_TOO_SMALL`. The spread is 50 bps on every pair in served config: a placeholder, since the number is a business decision.
- **Rate rules** (served config): positive; timestamp within 10 s of now, either side; inside the pair's band; and within 5% of the market rate of the pair's newest quote (any merchant) if that quote is younger than 5 minutes. Order of checks: positive, fresh, band, jump.
- **Timeouts.** Rate service: 2 s connect and 2 s read, each, so the worst case is about 4 s. Ledger: 1 s connect, 3 s read. Both clients use the JDK HTTP client pinned to HTTP/1.1 and never retry.
- **Calling the ledger by registry name.** The ledger client is a `RestClient` with Spring Cloud's load-balancer interceptor added directly. A `@LoadBalanced RestClient.Builder` bean was tried first and broke the service live: Eureka's own client picked that builder up and tried to resolve the registry's address through the load balancer, so conversion-svc never registered. Every Spring test runs with Eureka off, so only the smoke run showed it; `EurekaClientWiringIT` now boots with Eureka on against a WireMock registry, and goes red with that builder bean restored.
- **Currency scales live in conversion-svc's served config as well as in the ledger's currency table.** That is two homes for one fact: conversion-svc has no read access to the ledger's database and the ledger has no currency endpoint yet. If they drift, the ledger refuses the posting (its scale check fails closed) and the conversion stays PENDING until the sweeper voids it.
- **`ck_conversion_status` was dropped** before release: `ck_conversion_shape` admits only the three states, so the status check could never be the reason a row is refused, and nothing could test it.
- **The WireMock image is `wiremock/wiremock:3.13.2-3`, not an `-alpine` tag**: checked on Docker Hub, the 3.13.2 alpine tags are published for amd64 only, and this machine is arm64. The test library is `org.wiremock:wiremock-standalone:3.13.2`, the latest stable (4.x is beta).

Proven by tests (`./gradlew :services:conversion-svc:check` and `:services:ledger-svc:check`), each seen red with its control removed:

| Claim | Scenario | Red with the control removed |
|---|---|---|
| A merchant can never convert more than it holds, under concurrency | ledger: 8 conversions of 300.00 from 1000.00 ZAR into four different currencies at once; conversion-svc: 8 at once into USDT and USDC | source-account lock removed: `Expected size: 3 but was: 8` (ledger), `Expected size: 3 but was: 6` (conversion-svc) |
| A quote is used once, even under concurrency | conversion-svc: same quote under a second key, and under two keys at once | `UNIQUE (quote_id)` removed: `expected: 409 but was: 201`, `Expecting actual: [201, 201]` |
| A posting after its deadline is refused and moves nothing | ledger: deadline 1 minute ago | deadline check removed: `expected: 409 but was: 201` |
| Two sweepers settle each conversion once | conversion-svc: 4 PENDING conversions, two sweeps at once, void calls counted per conversion | `FOR UPDATE OF c SKIP LOCKED` removed: `Expected size: 1 but was: 2` for each |
| conversion-svc registers with Eureka | `EurekaClientWiringIT` | `@LoadBalanced RestClient.Builder` bean restored: `Expecting actual not to be empty` |

The first ledger concurrency scenario sent every conversion to one target currency and stayed green with the source lock removed: the lock on the shared target account serialised them first. It now buys a different currency per request, so only the source lock stands between them.

Also proven: the merchant gets exactly the quoted amounts (balances and statement lines equal the quote); an expired quote is 409; a void blocks the late arrival of a posting that never reached the ledger in time (the captured request is replayed at the real ledger and refused with 409 `CONVERSION_VOIDED`); a posting whose answer was lost is found by the sweeper and moved money once; a slow rate service is refused in about 2 s, a 500 with 503, and a zero, negative, stale, out-of-band, jumped or unreadable rate with 502 and its reason, storing no quote.

## 6. API summary

**Built:** every row. The `/internal/v1/...` rows are never routed: the gateway answers 404 for them. Success bodies are wrapped as `{"data": ...}`; errors are problem details with a stable `code` (README lists them).

| Method | Path | Idempotency | Status codes |
|---|---|---|---|
| GET | `/api/v1/merchants/{merchantId}/balances` | n/a | 200 list per currency |
| GET | `/api/v1/merchants/{merchantId}/entries?currency=&from=&to=&limit=&after=` | n/a | 200 statement, each row with `balance_after`, keyset pages; 400 unknown currency or bad parameter |
| POST | `/api/v1/merchants/{merchantId}/quotes` | not needed (a quote moves no money) | 201; 400 `SAME_CURRENCY`, `UNSUPPORTED_PAIR`, `UNKNOWN_CURRENCY`, amount errors, `AMOUNT_TOO_SMALL`; 502 `RATE_REJECTED` with `reason`; 503 `RATE_UNAVAILABLE` |
| POST | `/api/v1/merchants/{merchantId}/conversions` | `Idempotency-Key` from the merchant, required; quote is single-use | 201 COMPLETED; 202 PENDING; 404 `QUOTE_NOT_FOUND` (also another merchant's quote); 409 `QUOTE_EXPIRED`, `QUOTE_ALREADY_USED`, `CONVERSION_REJECTED`; 422 `INSUFFICIENT_FUNDS` or `IDEMPOTENCY_KEY_REUSED` |
| GET | `/api/v1/merchants/{merchantId}/conversions/{conversionId}` | n/a | 200; 404 `CONVERSION_NOT_FOUND` |
| POST | `/internal/v1/deposits` (not routed; money-in rail only) | `Idempotency-Key` = the rail's transfer reference, required, stored as (`merchantId`, DEPOSIT, key) | 201 created or replayed; 400 bad amount/scale, unknown currency or missing key; 422 key reused with a different body |
| POST | `/internal/v1/conversion-postings` (not routed) | idempotent on `conversionId`; carries a deadline | 201 posted or replayed; 400 `SAME_CURRENCY`, amount and validation errors; 409 `POSTING_DEADLINE_PASSED` or `CONVERSION_VOIDED`; 422 `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_REUSED`, `BALANCE_LIMIT_EXCEEDED` |
| GET | `/internal/v1/conversion-postings/{conversionId}?merchantId=` (not routed; sweeper) | n/a | 200 status POSTED or VOID; 400 without `merchantId`; 404 `NOT_FOUND` |
| POST | `/internal/v1/conversion-postings/{conversionId}/void` (not routed; sweeper) | identity (`merchantId`, CONVERSION, `conversionId`) | 201 voided or replayed; 409 `CONVERSION_ALREADY_POSTED` |

## 7. Failure handling

| Dependency fails | Behaviour | Status |
|---|---|---|
| A domain service is down, not registered, or refuses the connection | gateway answers 503 `ProblemDetail`, not 500: the request never reached it | built, tested |
| Upstream resets or times out mid-request | gateway answers 504 "outcome unknown": retry with the same `Idempotency-Key` | built, not tested |
| config-svc down | running services keep their config; a service that (re)starts fails to boot, because the config import is mandatory unless `CONFIG_IMPORT_OPTIONALITY=optional:` is set | built (framework behaviour, not tested here) |
| eureka-svc down | gateway keeps its last fetched registry; new instances cannot be discovered | built (framework behaviour, not tested here) |
| Postgres down | the affected service fails its requests; nothing is retried with a partial write because every posting is one transaction | designed |
| Rate service slow, down or failing | quote refused in about 2 s with 503 `RATE_UNAVAILABLE`; no cached or default rate | built, tested |
| Rate service returns a bad rate | quote refused with 502 `RATE_REJECTED` and a `reason`; nothing stored | built, tested (zero, negative, stale, out of band, jump, unreadable) |
| Ledger slow during accept | conversion stays PENDING (202); a same-key retry posts again only while the quote is live, else returns the state; the sweeper resolves it after the posting deadline, voiding if nothing posted | built, tested (answer never sent, answer lost, retry live, retry expired) |
| Duplicate client request | replayed from the stored journal or conversion; never posted twice | built, tested (deposits: sequential and 20 concurrent; conversions: sequential and 5 concurrent) |
| Concurrent debits of one account | serialised by the merchant row lock; the losers are refused with `INSUFFICIENT_FUNDS` | built, tested (8 concurrent, exactly floor(balance / amount) succeed) |
| Two sweepers at once | each claims different rows (`FOR UPDATE SKIP LOCKED`); each conversion voided once | built, tested |

## 8. Single repository and the 12-factor trade-off

All five services live in one repository with one Gradle build root. That departs from 12-factor's "one codebase per app" (https://12factor.net/codebase). It was chosen because the deliverable is a single repository that must build and run with one command. The cost is contained: services share no business code, no database and no runtime, and each ships as its own jar. The one shared code is `observability/` (a logback-to-OpenTelemetry bridge class and its config), compiled into each jar rather than published as a library. The root `build.gradle` holds shared build conventions only; each `services/<svc>/build.gradle` lists only its dependencies. Splitting into one repository per service later is a mechanical move.

The rest of 12-factor is followed: config comes from the environment with committed working defaults (a clean clone runs with no `.env`), backing services are attached by URL (`DB_URL`, `CONFIG_SERVER_URL`, `EUREKA_URL`), and processes hold no state of their own (quotes live in the database, not in memory).

**Database choice.** The brief's stack uses MySQL and allows any mainstream relational database if justified. PostgreSQL was chosen for transactional DDL (a failed migration rolls back cleanly) and to run one identical image (`postgres:18.6-alpine`) in tests and in compose. The design uses only portable features: exact NUMERIC decimals, `SELECT ... FOR UPDATE`, unique and CHECK constraints. Porting to MySQL 8 is a datasource and dialect change, not a redesign.

## 9. Known gaps at this commit

- No alert on repeated rate refusals for a pair, and no business metrics (quotes, conversions, refusals by reason, PENDING age): only HTTP and JVM metrics.
- Currency scales are configured in conversion-svc as well as stored in the ledger (section 5, "As built"); a ledger currency endpoint would remove the copy.
- The sweeper's live run on two replicas is proven in-process (two concurrent sweeps on one database), not with two running conversion-svc instances.
- Quotes are never deleted: a retention job is needed before volume grows.
- The platform FX position per currency is visible (`sum(entry.amount)` over `PLATFORM_FX`), but nothing reports or hedges it.
- Reconciliation is a test assertion and a manual query, not a scheduled job with an alert.
- No alert on a broken ledger invariant: a 5xx `CURRENCY_MISMATCH` or `UNBALANCED_POSTING` is logged at error and is visible only as an HTTP 5xx.
- Alerts reach one Slack channel and there is no on-call rota: a 3am alert reaches a channel, not a person.
- Nothing watches the monitoring stack: rules are evaluated inside the same lgtm container, so if it dies nothing pages.
- HighErrorRate has no minimum-traffic guard: one 500 in a quiet minute reads as 100%. It was seen firing once, in a local forced-5xx run with Slack disconnected (ledger-svc stopped, gateway answering 503): Pending t+20s, Alerting t+140s on gateway-svc.
- No OpenAPI schema.
- One Postgres container hosts both databases: logically separate, but one shared failure domain.
- config-svc and eureka-svc run as single instances.
- No transactional outbox for ledger events (needed by task 5, not by tasks 1 and 2).
- The gateway sets no upstream read timeout yet, so a hung upstream hangs the request rather than answering 504. A conversion accept is bounded by conversion-svc's own ledger timeouts (about 4 s), a quote by its rate timeouts (about 4 s).
