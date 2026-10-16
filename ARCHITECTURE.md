# Architecture

How the system is shaped and why. [README.md](README.md) covers running it;
[SOLUTION.md](SOLUTION.md) covers task choice, trade-offs, how Tasks 3 to 6 slot in, and the single
list of known gaps. [docs/project-layout.md](docs/project-layout.md) maps every package and class in
each service, with the measured dependencies between packages.

Everything described here is built and tested unless it says otherwise. A WireMock container stands
in for the exchange-rate service the brief says already exists.

## 1. How the design was derived

The brief was read as a grammar exercise before any service was drawn: nouns propose candidate
things, verbs propose operations, and adverbs, adjectives and modals ("completely", "large", "must
never") become proof obligations that something in the design has to make true. A subtractive pass
then deleted every candidate the brief cannot justify for the chosen tasks. The requirement ledger
(quotes verbatim from the brief):

| Quote | Word class | Obligation in the design |
|---|---|---|
| "keeps track of what each merchant holds" | verb | per-merchant, per-currency balances |
| "hold balances in several currencies at once" | adverb | one account per (merchant, currency) |
| "Money arrives (a deposit) and the balance goes up" | verb | deposit operation |
| "trust these balances completely" | adverb | append-only journal; balance derived from entries and reconcilable |
| "explain, at any later date, exactly how a balance reached its current value" | adverbs | immutable entries, each carrying `balance_after`; an entry is never updated or deleted |
| "amounts can be large" | adjective | `NUMERIC` / `BigDecimal`, never floating point |
| "two different currencies must never be silently mixed" | modal + adverb | `Money` value object whose cross-currency arithmetic throws; the database (DB) ties an entry's currency to its account's currency |
| "convert an amount of one currency they hold into another" | verb | conversion operation |
| "exchange-rate service already exists ... can be slow and its price keeps moving" | adjectives | call it, never build it; timeout; quotes expire |
| "which rate the merchant actually gets ... between quoting a price and completing the conversion" | verbs | two steps: quote (locks a rate for a short time to live (TTL)), then accept; the merchant gets exactly the quoted rate or a clear rejection |
| "not end up on the wrong side of a moving market" | phrase | short quote TTL, spread in the platform's favour, rounding in the platform's favour |
| "must never be able to convert more than they actually hold" | modal | atomic debit under a row lock with a balance check; `CHECK (balance >= 0)` as last line of defence |
| "fires requests over flaky mobile connections, sometimes more than once" | adverb | an idempotency key on every POST that moves money (section 4) |
| "a bad exchange rate, a timeout, or a duplicate notification" | nouns | rate sanity validation; a timeout fails closed (no default rate); dedupe |

## 2. Services

The initial proposal was Merchant, Ledger, Fiat, Crypto, Settlement. Each was checked against the
brief text for Tasks 1 and 2:

| Candidate | Evidence in the brief (Tasks 1 + 2) | Verdict |
|---|---|---|
| Ledger | "keeps track", "explain exactly how", "audit trail" | kept: `ledger-svc` |
| Conversion | verbs "convert", "quoting", "completing"; its own failure domain (slow rate feed) | **added**: `conversion-svc` (missing from the initial list) |
| Merchant | only "identified by an ID in the request"; "no real authentication or KYC" (KYC: know-your-customer) | dropped: a `MerchantId` value object |
| Fiat | "fiat" is a kind of currency; fiat payouts are Tasks 3 and 6 | dropped: `CurrencyKind.FIAT` |
| Crypto | a stablecoin is a kind of currency; on-chain payments are Task 4 | dropped: `CurrencyKind.STABLECOIN` |
| Settlement | "settle ... next day" (About section), Task 5 | dropped for Tasks 1 + 2 |

**Why conversion is a separate service.** It passes all three tests a separate service must:
distinct invariants (quote expiry, spread and rate sanity, against non-negative balances, zero-sum
journals and currency integrity); a distinct rate of change (pricing policy moves for commercial
reasons, ledger rules almost never); and a distinct failure domain (a slow rate feed must never
stop deposits or balance reads, which a shared thread pool would).

**Deliberately not built:** identity and authentication (out of scope per the brief; `merchantId`
is a path variable) and a notification service. Redis exists only as the gateway's rate-limit store,
which is beyond the brief (section 3, "Rate limiting").

## 3. Runtime topology

```
                         client (merchant dashboard)
                                    |
                                    v
                        +-----------------------+
                        |  gateway-svc :8080    |  route table, per-merchant rate limit,    +----------------+
                        |                       |  /api/v1 -> /v1, proof header,  -------->| redis :6379    |
                        +-----------------------+  503/504 on upstream failure             | (rate buckets) |
                                                                                           +----------------+
                          |                   |
             lb://ledger-svc            lb://conversion-svc
                          v                   v
            +------------------+   gRPC :9081, HTTP/2   +----------------------+      HTTP, 2s timeouts, 1 retry
            | ledger-svc :8081 |<-----------------------| conversion-svc :8082 |-----> exchange-rate service
            +------------------+  discovery:///ledger-  +----------------------+       (assumed; WireMock :8089 locally)
                     |            svc, round_robin            |           |
                     |                                        |           | dead letters, record and alert only
                     v                                        v           v
            +---------------------------------------------------------------+   +----------------------------------+
            |  postgres:18.6-alpine (one container)                         |   | kafka :9092 (apache/kafka:4.3.1) |
            |  db "ledger" (role ledger)  db "conversion" (role conversion) |   | ezeebit.conversion.ledger.       |
            +---------------------------------------------------------------+   | deadletter, 7-day retention      |
                                                                                +----------------------------------+

   control plane, started first:
     config-svc :8888  (Spring Cloud Config, native backend, serves served/*.yml)
     eureka-svc :8761  (service registry)
   ledger, conversion and gateway fetch config from config-svc and register with eureka-svc on boot.

   observability: lgtm :3000 Grafana, :4317/:4318 OTLP (grafana/otel-lgtm:0.36.0)
```

The arrows between services are HTTP except one: conversion-svc calls ledger-svc over gRPC Remote
Procedure Call (gRPC), the default for synchronous calls between services this platform owns. The
gateway's routes, the rate feed (an external service) and the deposit rail stay HTTP. The gRPC call
is [section 5, "Conversion to ledger over gRPC"](#conversion-to-ledger-over-grpc).

**The edge.** The gateway publishes one route table (`served/gateway-svc.yml` in config-svc):
balances and entries go to the ledger, quotes and conversions to conversion-svc, and nothing else
reaches a service. `/internal/**` is never routed, so deposits come only from the money-in rail: a
merchant cannot credit itself.

**Path rewrite.** Clients call `/api/v1/merchants/{id}/...`; each route rewrites
`/api/v1/(?<rest>.*)` to `/v1/${rest}`, and the services map only `/{version}/merchants/...` with the
version constrained to `v<digits>`. A request sent straight to a service under `/api/...` matches no
mapping and is 404, so the public prefix exists in one place. `/internal/v1/**` is unchanged.

**Rate limiting (beyond the brief).** A servlet filter in front of the route table takes the
merchant id from the path of every merchant route and spends one token from that merchant's Bucket4j
bucket in Redis (Lettuce, compare-and-swap). The design follows from three constraints:

- *Replica-safe.* The bucket lives in Redis, not in a gateway's heap. An in-heap bucket per replica
  makes the real limit capacity x replicas, and an autoscaler adds replicas exactly when traffic
  spikes, loosening the limit when it matters. `SharedBucketIT` runs two gateway contexts on one
  Redis: a burst split across both admits exactly the capacity, and goes red (8 admitted of a
  capacity of 4) when the store is swapped for an in-heap bucket. Bucket4j computes refill from each
  replica's wall clock, so Network Time Protocol (NTP) is a dependency: replicas skewed by d admit up to d x refill rate extra
  per bucket.
- *Every route is charged.* The limiter decides on the raw request Uniform Resource Identifier (URI) only after proving it
  canonical, on EVERY request: a `%xx` escape, a `;param`, a `.` or `..` segment, `//`, or any
  character outside letters, digits, `_`, `.`, `-` is 400 `MALFORMED_PATH`. A canonical raw path is
  exactly the decoded, normalised path the route matcher sees, so `/%61pi/...` or `/api;x=1/...`
  cannot reach a route uncharged (a review measured both reaching the ledger before this rule).
  Every merchant route lives under `/api/v1/merchants/{id}/`, and `RouteCoverageIT` enumerates the
  served route table and fails on any route the limiter would not charge, except the two spec routes
  on its allow-list (`/v3/api-docs/ledger`, `/v3/api-docs/conversion`): literal paths, GET only,
  uncharged by design, and proven unable to match a merchant path.
- *Bounded keys.* The key is `gateway:ratelimit:<capacity>-<refill>-<period ms>:merchant:<uuid>`,
  lower-cased, built only from a strict universally unique identifier (UUID); anything else is 400 before Redis is touched, so a
  client cannot write arbitrary keys. Keys expire 10 s after their bucket would be full again.
  The limits are in the key because Bucket4j replaces a stored configuration only for a higher
  version, which a value derived from the limits cannot promise (lowering a limit could hash lower).
  A changed limit therefore starts a fresh bucket at the new capacity for every merchant at once; the
  cost is one full bucket granted at the change, and two buckets per merchant while a rolling deploy
  runs both configurations.
- *Contention is not an outage.* Concurrent requests for one merchant race on a compare-and-swap. A
  request that loses more than `max-retries` (default 5) times on a healthy Redis is 429
  `RATE_LIMITED`, counted as `contention`, and never pages. Only a Redis error (refused, reset, a
  command past 250 ms, a bad password) is `unavailable`. `ContentionIT` hammers one key through two
  gateways and requires 200 or 429, never 503.
- *Fail closed.* Redis that is down, unreachable or stalled answers **503
  `RATE_LIMITER_UNAVAILABLE`** with `Retry-After: 1`, and the request is never forwarded. Admitting
  would switch the limit off exactly when the store is under strain, which is when abuse is most
  likely. Connect and command timeouts are 250 ms, so a stalled Redis costs a request a quarter of a
  second, not a client default. The gateway boots without Redis (it connects on first use and
  retries at most once a second), and its health endpoint stays UP: the refusal is the
  application's, so a Redis outage does not empty the gateway out of rotation and hide the cause.
  The cost is real: without Redis no merchant call succeeds, so Redis is on the edge's critical path
  and RateLimiterUnavailable pages critical.

Limits are per merchant: 100 burst, 20 a second sustained, from served config. 429
`RATE_LIMITED` carries `Retry-After` rounded up to whole seconds. Outcomes are counted in
`ezeebit_gateway_ratelimit_total{outcome}` (allowed, limited, contention, unavailable, refused_path;
no merchant tag). Any failure in the limiter path nobody classified is a counted 503, never a 500.
Each gateway also PINGs Redis every 10 s and publishes `ezeebit_gateway_ratelimit_store_up` (1 or
0), read with `min()` across replicas, so RateLimiterStoreDown pages without needing traffic.

**Gateway proof header (defence in depth only).** Every route removes any client-sent
`X-Ezeebit-Gateway` header and adds its own, whose value is `GATEWAY_SHARED_SECRET`, served by
config-svc to every service. ledger-svc and conversion-svc refuse their public merchant routes
(`/{version}/merchants/**`, matched on the container's normalised servlet path, so `;params` and dot
segments cannot step around it) with **403 `GATEWAY_REQUIRED`** unless the header matches, compared
in constant time. A service with a blank secret refuses to start. This is not authentication: anyone
who can read the served configuration or the gateway's environment holds the value, and it is sent
in clear over plain Hypertext Transfer Protocol (HTTP). It stops a mistaken or casual direct call to a service; the real control is
network policy or mutual Transport Layer Security (mTLS). `/internal/**` and ledger-svc's gRPC port are not covered: both are
service-to-service, plaintext, bound to loopback locally, and need a network policy or mTLS in production
([SOLUTION.md section 7](SOLUTION.md#7-known-gaps-and-unfinished-work)). `[!CONVENTION-OVERRIDE]` Least
privilege (https://owasp.org/Top10/A01_2021-Broken_Access_Control/) wants the gRPC port to admit
only conversion-svc. The kind fleet has the NetworkPolicy half: from other pods, ledger-svc admits only
conversion-svc on 9081 and only gateway-svc on 8081 ([docs/k8s.md](docs/k8s.md#network-policy)); a
connection from inside a ledger pod to its own address (the sweep's deposit) is routed over the pod's
loopback interface, so no policy applies to it (measured on a live bring-up on 2026-10-16:
`ip route get <pod IP>` inside the pod answers `local <pod IP> dev lo`; [docs/k8s.md](docs/k8s.md)). mTLS or a mesh is not
built, because locally loopback is the control.

**Upstream failures map to 503 or 504.** `UpstreamUnavailableAdvice` turns each failure shape into
a Request for Comments (RFC) 9457 problem detail with a stable `code` and internationalised (i18n) text that names no service:

- no registered instance, or a connect that was refused or timed out (`spring.http.clients.connect-timeout`
  1 s) on every attempt: **503 `UPSTREAM_UNAVAILABLE`**, the request never reached the service;
- any other upstream input/output (I/O) failure (connection reset, or no answer within the 10 s proxy read timeout,
  `spring.http.clients.read-timeout`): **504 `UPSTREAM_OUTCOME_UNKNOWN`**, because the request may
  have been processed. The text tells the caller to check the resource's state before retrying.

Without the advice all three reached the client as 500, which reads as a gateway bug. All three
branches are tested in `UpstreamFailureIT`; the 504 one against an upstream that accepts the
connection and never answers.

**Resend only what was never sent.** A replica killed without deregistering stays in the registry
for seconds, so the two merchant routes carry `RetryUnsent=3` (`api/filter/UnsentRetryFilterFunctions`):
when the connect was refused or timed out, the request is sent again, choosing an instance again, up
to 3 attempts. Nothing is resent once a byte may have reached a service, because a read timeout or
a reset may mean the request was applied. The advice and the filter share one definition of "never
sent" (`UnsentRetryFilterFunctions.neverSent`: a `ConnectException` or the Apache client's
`ConnectTimeoutException` in the cause chain), so a request the gateway resends is exactly one it
would otherwise answer 503. Each resend is counted in `ezeebit_gateway_upstream_resend_total{route}`,
registered at zero per route (`RouteFilterConfig`, `UpstreamResendMetrics`). `DeadInstanceRetryIT`
proves a refused or black-holed instance is skipped with every POST arriving once, and that a request
that reached an instance is never repeated.

**Registry timings.** Eureka's response cache and the clients' registry fetch are 5 s (default
30 s), and the gateway's load-balancer cache is off; with defaults a fresh fleet could not route for
up to a minute. Clients renew their lease every 5 s with a 15 s expiry (default 30 s and 90 s), and
the server evicts every 5 s with self-preservation off, because self-preservation reads one lost pod
of a five-service fleet as a network partition and stops evicting. eureka-core 2.0.6 counts the expiry twice for a lease that was renewed,
so a SIGKILLed instance leaves the registry about 30 s after its last renewal. An instance stopped on
purpose is set DOWN at once: `DeregisterBeforeDrain` (`discovery/`) does it when its context starts to
close, then keeps serving 10 s, the server cache plus the client fetch, before the graceful drain.

**Database per service.** `docker/postgres/initdb/01-databases.sql` creates role `ledger` owning
database `ledger` and role `conversion` owning database `conversion`, and revokes `CONNECT` from
`PUBLIC` on each. Neither role can connect to the other's database. The only path from conversion
to ledger data is the ledger's gRPC contract (`contracts/ledger/v1/conversion_postings.proto`); conversion stores merchant references as plain UUIDs.

**Observability.** Every service pushes metrics, traces and logs over OpenTelemetry Protocol (OTLP) to one Loki,
Grafana, Tempo and Mimir (LGTM) container, which runs Grafana, Prometheus, Loki, Tempo, Pyroscope
and an OpenTelemetry collector. Levels such as "how many conversions are overdue" are read from the database on each
export, never held in a replica's heap, so every replica reports the same number. Dashboards,
alerts and rehearsal timings: [docs/observability.md](docs/observability.md).

### On Kubernetes

`k8s/kube.sh` runs the same topology on one local kind cluster, `ezeebit`, from the same images and
configuration names ([docs/k8s.md](docs/k8s.md)). PostgreSQL, Redis and Kafka run in `ezeebit-data`;
the five services and WireMock in `ezeebit-app`; lgtm in `ezeebit-obs`; the chaos monkey in
`ezeebit-chaos`. gateway-svc runs 2 replicas under an autoscaler bounded at 4, ledger-svc and
conversion-svc 2 bounded at 3; config-svc and eureka-svc stay at 1 with no autoscaler. Only the gateway
(127.0.0.1:8080) and Grafana (127.0.0.1:3000) reach the host, every namespace denies by default, and
each service binds its pod address (`SERVER_ADDRESS` from the downward API), which the Eureka clients
register. One lease (`scripts/env-guard.sh`) keeps this fleet and `fleet.sh` from running at the same time.

Running two replicas under kills, rolling updates and scale-in needed six decisions
([docs/k8s.md](docs/k8s.md#replica-fixes-r1-to-r6) has the files and tests):

| # | At two or more replicas | Decision |
|---|---|---|
| R1 | a killed pod stays registered and keeps being chosen | 5 s/15 s leases, 5 s eviction, no self-preservation; the gateway resends only a request that was never sent (above) |
| R2 | a pod stopped on purpose is still registered while it drains | deregister first on `ContextClosedEvent`, serve 10 s, then drain; a 5 s `preStop` and a 40 s grace |
| R3 | a SIGKILLed migrator leaves Liquibase's lock row set for every later pod | `persistence/AdvisoryLockService`: the lock is a PostgreSQL session advisory lock that dies with its connection |
| R4 | `SERVER_ADDRESS` is both bind and registered address, loopback by default | the pod IP from the downward API; the default stays for the laptop |
| R5 | breaker and retry state is per replica | accepted, `[!REPLICA-LOCAL]` in `served/conversion-svc.yml` (section 5) |
| R6 | config-svc and eureka-svc are single-instance (Eureka here is not peer-aware) | 1 replica and no autoscaler, enforced by a build gate; the sweep's parity check also reads it live, with no committed run yet |

## 4. Ledger design (Task 1)

Code: `services/ledger-svc`, package `io.ezeebit.ledger`. Schema: Liquibase changesets
`db/changelog/2026/10/001` to `006` (005 adds the conversion account type, journal type and VOID
status; 006 adds the REFUSED status and `journal.reason`, each widening earlier CHECK constraints
rather than editing them).

### Double-entry model

Every movement of money is a **journal** with two or more **entries**. Per journal, per currency,
entries sum to zero. Money is never created by editing a balance: a deposit is money moving from a
platform clearing account into the merchant's account.

| Table | Key columns | Constraints |
|---|---|---|
| `currency` | `code`, the primary key (PK): South African rand (ZAR), Nigerian naira (NGN), Kenyan shilling (KES), Tether (USDT), USD Coin (USDC); `kind` (FIAT, STABLECOIN), `scale` (2 for fiat, 6 for stablecoins) | an amount with more decimals than `scale` is rejected, never rounded |
| `account` | `id`, `merchant_id` (null for platform accounts), `currency` foreign key (FK), `type` (MERCHANT, PLATFORM_DEPOSIT_CLEARING, PLATFORM_FX), `balance` NUMERIC(38,18) (MERCHANT only) | UNIQUE (`merchant_id`, `currency`, `type`); partial UNIQUE (`currency`, `type`) WHERE `merchant_id IS NULL` (Postgres treats NULLs as distinct); UNIQUE (`id`, `currency`); CHECK `balance >= 0` when MERCHANT |
| `journal` | `id`, `merchant_id`, `type` (DEPOSIT, CONVERSION), `status` (POSTED, VOID, REFUSED), `idempotency_key`, `request_hash`, `reference`, `reason` (REFUSED only), `created` | UNIQUE (`merchant_id`, `type`, `idempotency_key`): a rail reference and a `conversionId` can never collide |
| `entry` | `id`, `seq`, `journal_id` FK, `account_id`, `currency`, `amount` signed NUMERIC(38,18), `balance_after` (merchant entries only), `created` | FK (`account_id`, `currency`) references `account` (`id`, `currency`); insert-only |

The five currencies and one platform account per currency and type are seeded by Liquibase with
fixed ids, so every environment agrees on them.

### Invariants and where each is enforced

| Invariant | Enforced by |
|---|---|
| An entry cannot carry a different currency than its account | DB: composite FK to `account(id, currency)` |
| Two currencies are never added together | code: `Money` value object throws on cross-currency arithmetic |
| A merchant balance never goes negative | code: balance check under row lock; DB: CHECK as last line of defence |
| Amounts never lose precision | DB: NUMERIC(38,18); code: `BigDecimal` from string, scale validated against `currency.scale` |
| Per journal, per currency, entries sum to zero | code: the posting functions build balanced entry sets only; a reconciliation query verifies it |
| A merchant `account.balance` equals the sum of its entries | code: the cached balance is updated in the same transaction as the entry insert; reconciliation query. Platform accounts cache no balance: it is `sum(entry.amount)` on read |
| History is never rewritten | code: no update or delete path on `entry` or `journal`; corrections are new, reversing journals |
| One logical request posts at most once | DB: UNIQUE (`merchant_id`, `type`, `idempotency_key`) |
| A conversion posting never lands after its quote's window | ledger: deadline checked against the ledger's own DB clock; a VOID marker under the same identity refuses any later posting |

The zero-sum rule is code-enforced, not a DB constraint: a per-transaction sum check would need a
deferred trigger, which is not worth its portability cost here. Reconciliation is the detective
control; today it runs as a Structured Query Language (SQL) step in the acceptance suite, not as a scheduled job.

### Deposit flow

`POST /internal/v1/deposits` with `Idempotency-Key` (the rail's own transfer reference) and
`{merchantId, currency, amount, reference}`. In one transaction:

1. Validate the currency and the amount's scale.
2. Insert the journal row first. A unique violation on (`merchantId`, DEPOSIT, key) means it was
   already posted, or is being posted concurrently: roll back and replay the stored journal if the
   request hash matches, else 422. Never a 500.
3. Open the merchant account if absent (`INSERT ... ON CONFLICT DO NOTHING`), then lock it.
4. Insert merchant `+X` (with `balance_after`) and platform deposit clearing `-X`.
5. Update the merchant's cached balance, commit, return 201.

A negative clearing balance is money owed to the platform from outside; merchant accounts may not
go negative.

### Concurrency

- A posting locks only the MERCHANT rows it touches (`SELECT ... FOR UPDATE`), in ascending id
  order so two postings cannot deadlock, and checks the balance after the lock.
- Platform accounts are never locked and cache no balance: every posting writes them, so a lock
  would serialise the whole platform per currency during payday bursts.
- `entry.seq` is assigned under the merchant row lock, so it is the order in which `balance_after`
  values were computed; the statement orders and pages by it, not by `created`.

### Idempotency

| Endpoint | Key |
|---|---|
| `POST /internal/v1/deposits` | header `Idempotency-Key` = the rail's transfer reference |
| `POST .../conversions` | header `Idempotency-Key` chosen by the merchant |
| gRPC `ConversionPostings.Post` | the `conversion_id` |
| `POST .../quotes` | none: a quote moves no money and is cheap to repeat |

The stored identity is (`merchant_id`, `type`, key). Same key and same request hash replays the
original result byte for byte; a different body is 422 `IDEMPOTENCY_KEY_REUSED`. The row is inserted
first and a unique violation means "replay", so concurrent duplicates give one posting and identical
answers. The key lives in the journal row, in the same transaction as the entries, so a crash cannot
separate them. Keys never expire.

The request hash is the 256-bit Secure Hash Algorithm (SHA-256) over the fields as sent, not the raw body, so re-serialised whitespace
replays while `"100.0"` and `"100.00"` are different requests.

### Wire format

Amounts are decimal strings; a JavaScript Object Notation (JSON) number is refused, so no binary float reaches the service. Too
many decimals for the currency is refused (`"10.500"` is fine for ZAR, `"10.001"` is not), as is
more than 20 digits before the point. Input Postgres cannot store (a NUL in `reference`, a statement
window outside 1970 to 9999) is 400 at the edge. `ErrorCode` carries only the stable wire code and
its message key; `api/advice/ErrorStatus` is the one table mapping each code to its HTTP status, an
exhaustive switch (a new code does not compile without a status), and `ErrorStatusTest` pins every
published status.

## 5. Conversion design (Task 2)

Code: `services/conversion-svc`, package `io.ezeebit.conversion`; schema changesets
`001-quote.xml`, `002-conversion.xml`. Ledger side: `grpc/ConversionPostingRpc`,
`grpc/ConversionSettlementRpc` and the `service/conversion` package (`ConversionPostingService`, `ConversionPoster`, `ConversionVoider` and
their collaborators, mapped in [docs/project-layout.md](docs/project-layout.md)).

### Two steps: quote, then accept

| Table | Key columns | Constraints |
|---|---|---|
| `quote` | `id`, `merchant_id`, currencies, `sell_amount`, `buy_amount`, `rate` (market), `applied_rate` (after spread), `spread_bps`, `rate_source_timestamp`, `expires_at`, `status` | in the DB, not in memory, so any replica can accept it |
| `conversion` | `id`, `merchant_id`, `quote_id`, `idempotency_key`, `status` (PENDING, COMPLETED, REJECTED), `reason`, `created`, `completed` | UNIQUE (`quote_id`): a quote is single-use; UNIQUE (`merchant_id`, `idempotency_key`) |

**Quote** (`POST .../quotes` with `{sell, buy, sellAmount | buyAmount}`):

1. Call the exchange-rate service: 2 s connect and 2 s read timeouts. A timeout or an I/O failure is
   tried once more after 200 ms (Resilience4j, 4.2 s at worst); an error status is never retried.
2. Validate the rate, in order: positive; fresh (within 10 s); inside the pair's configured band;
   and, if the pair's newest quote is younger than 5 minutes, within 5% of its market rate.
3. Apply the spread in the platform's favour: `appliedRate = rate x (10000 - spread_bps) / 10000`,
   exact in decimal.
4. Round in the platform's favour by at most one minor unit: a `sellAmount` quote rounds the
   received amount down, a `buyAmount` quote rounds the paid amount up. A quote whose rounded amounts
   deliver a rate more than 1% from `appliedRate` is 400 `AMOUNT_TOO_SMALL`.
5. Persist with a 15 s TTL and return `quoteId`, `appliedRate`, both amounts and `expiresAt`.

A timeout or error status is 503 `RATE_UNAVAILABLE`; a bad rate is 502 `RATE_REJECTED` with its rule
as `reason`. There is **no fallback** rate: a conversion at a wrong price is a mis-converted
transaction, while a refused quote costs the merchant one retry.

The merchant gets exactly the quoted `applied_rate` if they accept before `expires_at`; after it, 409
`QUOTE_EXPIRED`. The spread and the short TTL bound how far the market can move against the platform
in the window, and the `PLATFORM_FX` balances per currency are its open position. "Last look"
re-pricing at accept was rejected: it makes the merchant's price non-firm.

**Accept** (`POST .../conversions` with `{quoteId}` and `Idempotency-Key`):

1. Refuse another merchant's quote (404), an expired one (409 `QUOTE_EXPIRED`), or one already used
   under a different key (409 `QUOTE_ALREADY_USED`).
2. Insert the conversion as PENDING. UNIQUE (`quote_id`) makes a double accept impossible across
   replicas; a unique violation on (`merchant_id`, `idempotency_key`) replays.
3. Call the ledger's gRPC `Post` with `conversionId` as its key and a **deadline** = quote expiry
   + 30 s: 2 s per attempt, up to 3 attempts, only on `UNAVAILABLE` or `DEADLINE_EXCEEDED`
   ([Conversion to ledger over gRPC](#conversion-to-ledger-over-grpc)).
4. The ledger, in **one local transaction**, opens the merchant's target account if absent, locks
   both merchant accounts in ascending id order, checks the source balance, posts four entries, and
   as its last statement refuses if its own `clock_timestamp()` is past the deadline:

| Account | Currency | Amount |
|---|---|---|
| merchant source | sell | `-sell_amount` |
| PLATFORM_FX source | sell | `+sell_amount` |
| PLATFORM_FX target | buy | `-buy_amount` |
| merchant target | buy | `+buy_amount` |

Each currency nets to zero. The answer settles the conversion COMPLETED or REJECTED with a compare
and set (`UPDATE ... WHERE status = 'PENDING'`), so the accept path and the sweeper cannot both
settle one row.

### How each ledger answer settles a conversion

The ledger's `ErrorCode` travels as `google.rpc.ErrorInfo.reason` (domain `ledger.ezeebit.io`) inside
the gRPC status. conversion-svc decides on that code; the status code only says whether to retry. A
status with no ledger `ErrorInfo` (from a proxy or the gRPC library) settles nothing.

| Ledger answer (gRPC status, `ErrorInfo.reason`) | Conversion |
|---|---|
| OK, `PostingResult` POSTED (first or replayed) | COMPLETED, 201 |
| `FAILED_PRECONDITION`, `INSUFFICIENT_FUNDS` or `BALANCE_LIMIT_EXCEEDED` | REJECTED, 422. Final: the ledger first commits a REFUSED journal (no entries) under the same identity, so that `conversionId` can never post later, even after the merchant is funded |
| `INVALID_ARGUMENT` on the posting's content (`AMOUNT_SCALE_EXCEEDED`, `AMOUNT_TOO_LARGE`, `AMOUNT_NOT_POSITIVE`, `UNKNOWN_CURRENCY`, `SAME_CURRENCY`, `VALIDATION_FAILED`, `MALFORMED_REQUEST`) | REJECTED with reason `INPUT_REFUSED` at once (409 `CONVERSION_REJECTED`). Safe without a marker: the ledger decides it from the message alone, and conversion-svc only ever sends one message per `conversionId` |
| `FAILED_PRECONDITION`, `POSTING_DEADLINE_PASSED` / `CONVERSION_VOIDED` | REJECTED, reason `DEADLINE_PASSED` / `VOIDED` |
| `ALREADY_EXISTS`, `CONVERSION_ALREADY_POSTED` (on a void) | POSTED, so COMPLETED |
| `UNAVAILABLE` or `DEADLINE_EXCEEDED` after the last attempt, an open circuit, `INTERNAL`, `FAILED_PRECONDITION` `IDEMPOTENCY_KEY_REUSED`, any other status | stays PENDING (202) for the sweeper, and an exhausted call or an open circuit writes a dead letter. `IDEMPOTENCY_KEY_REUSED` means another journal holds the id, possibly a POSTED one, so only the lookup can settle it |

At the ledger, a repeated posting is answered from what it holds for the `conversionId`: a VOID
marker answers `CONVERSION_VOIDED` for any message; otherwise the request hash is checked first (a
different message is `IDEMPOTENCY_KEY_REUSED`), and then a REFUSED marker replays its refusal and a
POSTED journal replays its result.

### Conversion to ledger over gRPC

conversion-svc reaches ledger-svc in exactly three calls, all gRPC over HTTP/2, all on ledger-svc's
gRPC port (`LEDGER_GRPC_PORT`, default 9081, bound to `SERVER_ADDRESS`). The contract is one file,
[`contracts/ledger/v1/conversion_postings.proto`](contracts/ledger/v1/conversion_postings.proto),
compiled by both services; a build check (`checkSingleProto`) fails if any other copy appears.

| gRPC service | Method | Caller | Ledger class |
|---|---|---|---|
| `ConversionPostings` | `Post(PostingRequest)` | the accept path (`ConversionLedgerPoster`) | `grpc/ConversionPostingRpc` |
| `ConversionSettlement` | `Find(PostingLookup)` | the sweeper (`PendingLedgerCheck`) | `grpc/ConversionSettlementRpc` |
| `ConversionSettlement` | `Void(PostingLookup)` | the sweeper | `grpc/ConversionSettlementRpc` |

The methods sit in two services so each server class stays within 3 injected dependencies. They
call the existing `ConversionPostingService`, `ConversionVoidService` and
`answer.ConversionPostingReader` (behind `Find`) unchanged;
`grpc/PostingMessages` translates messages and `grpc/GrpcErrors` turns a `LedgerException` into a
status with `ErrorInfo`.

**Finding the ledger.** The channel target is `discovery:///ledger-svc`.
`outbound/discovery/DiscoveryNameResolverProvider` reads every UP instance of `ledger-svc` from
Eureka, with the gRPC port each one publishes as `grpcPort` metadata, and the channel balances
`round_robin` over them; HTTP/2 would otherwise pin every call to one replica. The resolver re-reads
the registry only when a connection ends, so a healthy channel would never see a replica added later.
The ledger therefore ages every gRPC connection out (`spring.grpc.server.keepalive.connection.max-age`
30 s, 5 s grace for calls in flight): each GOAWAY makes the client reconnect and re-read Eureka, so a
new replica takes traffic within about 30 s plus Eureka's own registration delay
(`DiscoveryNameResolverTest.aReplicaRegisteredAfterStartIsReachedOnceTheServerAgesConnectionsOut`).

**The service layer owns the seam.** `service/port/LedgerPostings` is the interface the use cases
call, in conversion's own words; `outbound/ledger/LedgerGrpcClient` implements it. `service/` imports
no `outbound/` class (`ServiceImportsNoOutboundTest`). The rate feed has the same shape
(`service/port/RateSource`, `outbound/rates/RateClient`).

**Retries and circuit breakers** (Resilience4j, `outbound/ledger/LedgerGuard`, one instance per
operation so a failing `Void` cannot open the breaker in front of `Post`). Only `UNAVAILABLE` and
`DEADLINE_EXCEEDED` are retried, inside the breaker, so one exhausted sequence counts as one breaker
failure. Every retry resends the identical message, deadline included, which the ledger's replay
needs.

| Operation | Deadline per attempt | Attempts | Backoff | Worst case | Caller |
|---|---|---|---|---|---|
| `Post` | 2 s | 3 | 200 ms, x2 | 6.6 s | merchant convert request, inside the gateway's 10 s |
| `Find`, `Void` | 2 s | 3 | 500 ms, x2 | 7.5 s | sweeper (10 s interval) |

A breaker opens at a 50% failure rate over the last 20 calls, stays open 10 s, then lets 3 trial
calls through. While it is open a call returns UNKNOWN at once and the conversion stays PENDING.
Breaker state is per replica: each conversion-svc replica counts only its own calls and opens its
own breakers, so at N replicas there are N independent breakers per operation and one can be open
while another is closed. That is acceptable because the breaker is a fast local signal and holds no
business state: correctness never depends on it, every PENDING conversion is settled by the sweeper
whichever replica's breaker refused the call, and the dashboard shows the worst replica
(`max by (name)`).

**Dead letters.** When retries are exhausted or the circuit is open (never for a refusal),
`outbound/deadletter/KafkaDeadLetterPublisher` publishes one record to the Kafka topic
`ezeebit.conversion.ledger.deadletter`, keyed by the conversion id. It is **record and alert only**:
nothing consumes it, and the sweeper stays the only settler of a PENDING conversion, because a
dead-lettered posting may have committed and after its deadline the ledger refuses it anyway. Each
record carries `op` (`POST`, `FIND`, `VOID`), `conversionId`, `merchantId`, the `request` as sent
(JSON), `attempts` (0 when the circuit is open), `lastStatus` and `lastReason` (both null when the
circuit is open), `reason` (`RETRIES_EXHAUSTED` or `CIRCUIT_OPEN`), `at` and `traceId`. Publishing is
asynchronous (`acks=all`, idempotent producer, `delivery.timeout.ms=2000`, `max.block.ms=250`), so a
dead broker never slows the merchant's request; a failed publish is counted and paged instead. The
topic has one partition, 7-day retention, and lives on the `kafkadata` volume, so it survives
`fleet.sh down` and is deleted only by `purge`. The counters and the three alert rules are in
[docs/observability.md](docs/observability.md#alert-rules).

The design, its rejected options and the as-built notes are in
[docs/superpowers/specs/2026-10-14-ledger-grpc-design.md](docs/superpowers/specs/2026-10-14-ledger-grpc-design.md).

### PENDING recovery, and why there is no saga

A same-key retry of a PENDING conversion re-posts only while its quote is live (the ledger is
idempotent on `conversionId`); after expiry it only returns the current state.

The sweeper (every 10 s) takes a PENDING conversion only after its deadline plus 30 s maximum
transaction time, when no posting can still commit. It claims one due row per transaction with
`SELECT ... FOR UPDATE OF c SKIP LOCKED`, so every replica can run it, and asks the ledger. The
lookup answers POSTED, VOID or REFUSED (with its reason), else 404 `POSTING_NOT_FOUND`. Found:
settle accordingly. `POSTING_NOT_FOUND`: write a VOID marker under the posting's identity, so a late
posting hits the unique index and is refused, then mark REJECTED. Only `NOT_FOUND` carrying the
ledger's `POSTING_NOT_FOUND` leads to a void: a bare `NOT_FOUND` says nothing about the id, so the row
stays PENDING.

A posting and a void race on one unique index and the first commit wins, so correctness does not
depend on the two databases' clocks. No saga is needed: the only money movement is one atomic
transaction in one database.

### The exchange-rate service contract (an assumption)

The brief says the service exists and can be called; it does not say how. This is the only place
the assumed contract is written down. The caller-owned data transfer object (DTO) is `outbound/rates/RateDto`; WireMock
serves it from `docker/wiremock/mappings/rates.json`, and conversion-svc's acceptance tier loads the
same files.

```
GET /rates?base=ZAR&quote=USDT
200 {"base":"ZAR","quote":"USDT","rate":"0.054254","timestamp":"2026-10-13T10:00:00Z"}
```

- `rate` is how many units of `quote` one unit of `base` buys, as a decimal string. Both directions
  of every pair are served, so the platform never inverts a rate.
- `timestamp` is when the price was taken, as an International Organization for Standardization (ISO) 8601 instant.
- An error status, an unknown pair, a refused connection or no answer within 2 s (on each of the two
  attempts a timeout or an I/O failure gets) means "no rate" (503). An answer for another pair, an unreadable rate or timestamp, or a rate with more than 14
  decimals is a bad rate (502 `RATE_REJECTED`, reason `RATE_MALFORMED`).

### Notes on the build

- **Currency scales have two homes**: the ledger's `currency` table and conversion-svc's served
  config, because conversion-svc cannot read the ledger's database and the ledger has no currency
  endpoint yet. If they drift so that a quote carries more decimals than the ledger allows, the
  ledger refuses the posting with 400 `AMOUNT_SCALE_EXCEEDED` and the conversion goes REJECTED
  (`INPUT_REFUSED`) at once; no money moves.
- **Loopback only, locally.** Every service binds `${SERVER_ADDRESS:127.0.0.1}` and registers that
  address in Eureka. `/internal/**` moves money and has no authentication, so loopback is the local
  control; `LoopbackOnlyIT` requires a refusal on the host's local area network (LAN) address.

### Proven by tests

Each was seen red with its control removed, or before its fix:

| Claim | Red with the control removed |
|---|---|
| A merchant can never convert more than it holds, under concurrency (8 conversions of 300.00 from 1000.00 ZAR, each into a different target currency, so only the source lock serialises them) | source-account lock removed: `Expected size: 3 but was: 8` |
| A quote is used once, even under concurrency | `UNIQUE (quote_id)` removed: `Expecting actual: [201, 201]` |
| A posting after its deadline is refused and moves nothing | deadline check removed: `expected: 409 but was: 201` |
| Two sweepers settle each conversion once | `SKIP LOCKED` claim removed: `Expected size: 1 but was: 2` |
| A refusal is final: money arriving later cannot post the same conversion | REFUSED write removed: `expected: 422 but was: 201` |
| A content refusal by the ledger is final | before the fix: 202 PENDING |
| 50 concurrent deposits to one account sum exactly; 20 concurrent copies of one deposit post once | row lock or journal unique constraint removed |
| An entry whose currency differs from its account's is refused by the database | composite foreign key removed |

## 6. API summary

The `/internal/v1/...` row is never routed: the gateway answers 404 for it. The `/api/v1/...`
rows are the gateway's paths; each service serves the same row under `/v1/...` and requires the
gateway proof header there (403 `GATEWAY_REQUIRED` without it). Every `/api/v1/merchants/...` row can
also answer, from the gateway, 400 `INVALID_MERCHANT_ID` or `MALFORMED_PATH`, 429 `RATE_LIMITED` and
503 `RATE_LIMITER_UNAVAILABLE` (section 3). Success bodies are `{"data": ...}`; errors are problem
details with a stable `code`.

| Method | Path | Idempotency | Status codes |
|---|---|---|---|
| GET | `/api/v1/merchants/{merchantId}/balances` | n/a | 200 list per currency; 400 `VALIDATION_FAILED` on a service for an id that is not a UUID (the gateway answers `INVALID_MERCHANT_ID` first) |
| GET | `/api/v1/merchants/{merchantId}/entries?currency=&from=&to=&limit=&after=` | n/a | 200 statement, each row with `balanceAfter`, keyset pages (`limit` 1 to 500, default 100; `from` inclusive, `to` exclusive); 400 `UNKNOWN_CURRENCY`, `VALIDATION_FAILED` |
| POST | `/api/v1/merchants/{merchantId}/quotes` | not needed | 201; 400 `SAME_CURRENCY`, `UNSUPPORTED_PAIR`, `UNKNOWN_CURRENCY`, amount errors (including `AMOUNT_TOO_SMALL`), `VALIDATION_FAILED`; 502 `RATE_REJECTED` with `reason`; 503 `RATE_UNAVAILABLE` |
| POST | `/api/v1/merchants/{merchantId}/conversions` | `Idempotency-Key`, required; quote single-use | 201 COMPLETED; 202 PENDING; 400 `IDEMPOTENCY_KEY_MISSING`, `VALIDATION_FAILED`; 404 `QUOTE_NOT_FOUND` (also another merchant's quote); 409 `QUOTE_EXPIRED`, `QUOTE_ALREADY_USED`, `CONVERSION_REJECTED` (reason `INPUT_REFUSED`, `DEADLINE_PASSED`, `VOIDED`); 422 `INSUFFICIENT_FUNDS`, `BALANCE_LIMIT_EXCEEDED` (with `conversionId`), `IDEMPOTENCY_KEY_REUSED` |
| GET | `/api/v1/merchants/{merchantId}/conversions/{conversionId}` | n/a | 200 with `status` and `reason` when rejected; 404 `CONVERSION_NOT_FOUND` |
| POST | `/internal/v1/deposits` (money-in rail only) | `Idempotency-Key` = the rail's transfer reference | 201 created or replayed; 400 amount, currency, key or validation errors; 422 `IDEMPOTENCY_KEY_REUSED`, `BALANCE_LIMIT_EXCEEDED` |

The conversion postings are not HTTP. They are three gRPC methods on ledger-svc's gRPC port (9081),
called only by conversion-svc and never routed by the gateway
([section 5](#conversion-to-ledger-over-grpc)). Each answer is a `PostingResult` or a status whose
`ErrorInfo.reason` is the ledger's code:

| gRPC method | Idempotency | Answers |
|---|---|---|
| `ConversionPostings.Post` | idempotent on `conversion_id`; carries a deadline | OK posted or replayed; `INVALID_ARGUMENT` content errors; `FAILED_PRECONDITION` `POSTING_DEADLINE_PASSED`, `CONVERSION_VOIDED` (any message), `INSUFFICIENT_FUNDS`, `BALANCE_LIMIT_EXCEEDED` (recorded as REFUSED first, replayed for the same message), `IDEMPOTENCY_KEY_REUSED` |
| `ConversionSettlement.Find` (sweeper) | n/a | OK with `status` POSTED, VOID, or REFUSED with `reason`; `INVALID_ARGUMENT` without `merchant_id`; `NOT_FOUND` `POSTING_NOT_FOUND` |
| `ConversionSettlement.Void` (sweeper) | identity (`merchant_id`, CONVERSION, `conversion_id`) | OK voided, replayed, or the stored REFUSED journal; `ALREADY_EXISTS` `CONVERSION_ALREADY_POSTED` |

A path no controller maps answers 404 `NOT_FOUND`; that includes
any `/api/...` path sent straight to a service. An unsupported version on a service itself
(`/v2/...`) is 400 `UNSUPPORTED_API_VERSION`; through the gateway it is 404, because the route table
publishes v1 only.

### OpenAPI

Each service describes itself: springdoc reads the controllers, and `api/doc` adds what annotations
cannot say well. Error responses are derived, not written: `OperationErrors` lists the codes each
operation can answer and `ErrorStatus` picks each one's status, so a response is an RFC 9457 problem
whose `code` is an enum of exactly those codes. The `public` spec (merchant routes, server
`http://localhost:8080/api`) is served only with the gateway proof header, the same rule as the routes
it describes; ledger-svc's `internal` spec follows `/internal/**`: loopback, never routed. The gateway
serves one Swagger user interface (UI) over both public specs through two allow-listed, uncharged routes. Three checks
keep the description true: `OpenApiContractIT` compares each spec with the controllers' mappings and
with the snapshot in `docs/openapi`, the acceptance runs fail on any status or code a scenario saw that
the snapshot does not list, and a gateway test fails if a gateway code is missing from either public
spec's description.

## 7. Failure handling

| Dependency fails | Behaviour | Tested |
|---|---|---|
| A domain service down, unregistered or refusing | gateway 503 `UPSTREAM_UNAVAILABLE` | yes |
| One replica killed, still registered | the merchant routes resend a request whose connect was refused or timed out, choosing an instance again, up to 3 attempts; nothing that was sent is resent | yes: `DeadInstanceRetryIT` (refused and black-holed instances, every POST once). The kind sweep's chaos check covers it on a cluster, with no committed run yet ([docs/k8s.md](docs/k8s.md#the-sweep)) |
| A replica stopped on purpose (rolling update, scale-in) | it leaves Eureka first and serves 10 s more, then drains | yes: `DeregisterBeforeDrainTest` (deregisters before any lifecycle bean stops) |
| A migrating replica SIGKILLed | its advisory lock ends with its connection; the next pod migrates | yes: `AdvisoryLockIT` (a stale lock row does not block, two migrators serialise, a terminated holder's lock is released) |
| Upstream resets, or silent for 10 s | gateway 504 `UPSTREAM_OUTCOME_UNKNOWN` | yes |
| Redis down, unreachable, stalled, or away at the gateway's boot | merchant routes 503 `RATE_LIMITER_UNAVAILABLE` within the 250 ms timeout, nothing forwarded; health stays UP; admitted again once Redis answers; RateLimiterStoreDown pages | yes: stopped mid-run, paused, absent at boot; recovery within 2.5 s after a late start and after a kill and restart (red with a 10 s connect back-off, and with Lettuce's default reconnect delay) |
| Many concurrent requests for one merchant | compare-and-swap losers past `max-retries` are 429 contention, never 503 | yes: one key through two gateways |
| A non-canonical path (`%xx`, `;param`, dot segment, `//`) | 400 `MALFORMED_PATH` on any path, counted `refused_path` | yes: encoded spellings of an exhausted route are 400 or 404, never 200 |
| A merchant over its limit | 429 `RATE_LIMITED` with `Retry-After`, nothing forwarded | yes: burst of capacity + 1, and split across two gateways |
| config-svc or eureka-svc down | running services keep their config and last registry; a restarting service cannot boot | no (framework behaviour) |
| Postgres down | the affected service fails its requests; nothing is half-written, every posting is one transaction | no (by design) |
| Rate service slow, down or wrong | a timeout or I/O failure is tried once more after 200 ms, so a slow feed is refused in about 4.2 s (503); an error status at once (503), a broken rule with that rule (502); nothing stored, no default rate | yes: slow (two calls), 500 (one call, no retry), zero, negative, stale, out of band, jump, unreadable |
| Ledger slow, silent or unreachable during accept | `Post` retried up to 3 attempts (6.6 s at worst), then 202 PENDING and a dead letter; same-key retry re-posts only while the quote is live; the sweeper settles or voids | yes: answer never sent, answer lost, retry live, retry expired, success on the third attempt, worst case under 7 s, ledger killed mid-run (no conversion posted twice) |
| Ledger failing repeatedly | the operation's breaker opens (50% of 20 calls), calls return UNKNOWN at once for 10 s, each writes a `CIRCUIT_OPEN` dead letter | yes: opens at its threshold; live, the `Find` breaker opened in the rehearsed outage |
| Kafka down | dead letters cannot be published: counted (`..._publish_failed_total`) and paged critical; the caller is not slowed; the sweeper still settles the money | yes: broker stopped, counter moves, caller not slowed |
| Ledger refuses, then money arrives | the REFUSED marker makes the retry get the same 422 | yes |
| Duplicate request | replayed, never posted twice | yes: 20 concurrent deposits, 5 concurrent conversions |
| Concurrent debits of one account | serialised by the row lock; the losers get `INSUFFICIENT_FUNDS` | yes |
| Two sweepers at once | each claims different rows; each conversion voided once | yes |

## 8. Single repository and the 12-factor trade-off

All five services live in one repository with one Gradle build root, which departs from 12-factor's
"one codebase per app" (https://12factor.net/codebase) because the deliverable is one repository
that builds and runs with one command. The cost is contained: the services share no business code,
database or runtime, and each ships as its own jar. The only shared code is `observability/` (a
logback-to-OpenTelemetry bridge and its config), compiled into each jar rather than published as a
library, `persistence/` (the Liquibase advisory lock, compiled into ledger-svc and conversion-svc),
`discovery/` (deregister before the drain, compiled into the three Eureka clients), `architecture/`, the dependency-injection budget gate compiled into each service's
`test` task only, and `contracts/`, the one `.proto` file both conversion-svc and ledger-svc compile. Splitting into one repository per service later is mechanical.

The rest of 12-factor is followed: config from the environment with committed working defaults (a
clean clone runs with no `.env`), backing services attached by Uniform Resource Locator (URL), and no process state (quotes
live in the database).

The database choice (PostgreSQL rather than MySQL) is argued in
[SOLUTION.md, Trade-offs](SOLUTION.md#3-trade-offs).
