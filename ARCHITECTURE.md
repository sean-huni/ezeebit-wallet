# Architecture

How the system is shaped and why. [README.md](README.md) covers running it;
[SOLUTION.md](SOLUTION.md) covers task choice, trade-offs, how Tasks 3 to 6 slot in, and the single
list of known gaps.

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
| "two different currencies must never be silently mixed" | modal + adverb | `Money` value object whose cross-currency arithmetic throws; the DB ties an entry's currency to its account's currency |
| "convert an amount of one currency they hold into another" | verb | conversion operation |
| "exchange-rate service already exists ... can be slow and its price keeps moving" | adjectives | call it, never build it; timeout; quotes expire |
| "which rate the merchant actually gets ... between quoting a price and completing the conversion" | verbs | two steps: quote (locks a rate for a short TTL), then accept; the merchant gets exactly the quoted rate or a clear rejection |
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
| Merchant | only "identified by an ID in the request"; "no real authentication or KYC" | dropped: a `MerchantId` value object |
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
```

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
  replica's wall clock, so NTP is a dependency: replicas skewed by d admit up to d x refill rate extra
  per bucket.
- *Every route is charged.* The limiter decides on the raw request URI only after proving it
  canonical, on EVERY request: a `%xx` escape, a `;param`, a `.` or `..` segment, `//`, or any
  character outside letters, digits, `_`, `.`, `-` is 400 `MALFORMED_PATH`. A canonical raw path is
  exactly the decoded, normalised path the route matcher sees, so `/%61pi/...` or `/api;x=1/...`
  cannot reach a route uncharged (a review measured both reaching the ledger before this rule).
  Every published route lives under `/api/v1/merchants/{id}/`, and `RouteCoverageIT` enumerates the
  served route table and fails on any route the limiter would not charge.
- *Bounded keys.* The key is `gateway:ratelimit:<capacity>-<refill>-<period ms>:merchant:<uuid>`,
  lower-cased, built only from a strict UUID; anything else is 400 before Redis is touched, so a
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
in clear over plain HTTP. It stops a mistaken or casual direct call to a service; the real control is
network policy or mTLS. `/internal/**` is not covered: it is service-to-service, bound to loopback
locally, and needs a network policy or mTLS in production
([SOLUTION.md section 7](SOLUTION.md#7-known-gaps-and-unfinished-work)).

**Upstream failures map to 503 or 504.** `UpstreamUnavailableAdvice` turns each failure shape into
an RFC 9457 problem detail with a stable `code` and i18n text that names no service:

- no registered instance, or a refused connection: **503 `UPSTREAM_UNAVAILABLE`**, the request never
  reached the service;
- any other upstream I/O failure (connection reset, or no answer within the 10 s proxy read timeout,
  `spring.http.clients.read-timeout`): **504 `UPSTREAM_OUTCOME_UNKNOWN`**, because the request may
  have been processed. The text tells the caller to check the resource's state before retrying.

Without the advice all three reached the client as 500, which reads as a gateway bug. All three
branches are tested in `UpstreamFailureIT`; the 504 one against an upstream that accepts the
connection and never answers.

**Registry timings.** Eureka's response cache and the clients' registry fetch are 5 s (default
30 s), and the gateway's load-balancer cache is off; with defaults a fresh fleet could not route for
up to a minute.

**Database per service.** `docker/postgres/initdb/01-databases.sql` creates role `ledger` owning
database `ledger` and role `conversion` owning database `conversion`, and revokes `CONNECT` from
`PUBLIC` on each. Neither role can connect to the other's database. The only path from conversion
to ledger data is the ledger's HTTP API; conversion stores merchant references as plain UUIDs.

**Observability.** Every service pushes metrics, traces and logs over OTLP to one Grafana LGTM
container. Levels such as "how many conversions are overdue" are read from the database on each
export, never held in a replica's heap, so every replica reports the same number. Dashboards,
alerts and rehearsal timings: [docs/observability.md](docs/observability.md).

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
| `currency` | `code` (PK: ZAR, NGN, KES, USDT, USDC), `kind` (FIAT, STABLECOIN), `scale` (2 for fiat, 6 for stablecoins) | an amount with more decimals than `scale` is rejected, never rounded |
| `account` | `id`, `merchant_id` (null for platform accounts), `currency` FK, `type` (MERCHANT, PLATFORM_DEPOSIT_CLEARING, PLATFORM_FX), `balance` NUMERIC(38,18) (MERCHANT only) | UNIQUE (`merchant_id`, `currency`, `type`); partial UNIQUE (`currency`, `type`) WHERE `merchant_id IS NULL` (Postgres treats NULLs as distinct); UNIQUE (`id`, `currency`); CHECK `balance >= 0` when MERCHANT |
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
control; today it runs as a SQL step in the acceptance suite, not as a scheduled job.

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
| `POST /internal/v1/conversion-postings` | the `conversionId` |
| `POST .../quotes` | none: a quote moves no money and is cheap to repeat |

The stored identity is (`merchant_id`, `type`, key). Same key and same request hash replays the
original result byte for byte; a different body is 422 `IDEMPOTENCY_KEY_REUSED`. The row is inserted
first and a unique violation means "replay", so concurrent duplicates give one posting and identical
answers. The key lives in the journal row, in the same transaction as the entries, so a crash cannot
separate them. Keys never expire.

The request hash is SHA-256 over the fields as sent, not the raw body, so re-serialised whitespace
replays while `"100.0"` and `"100.00"` are different requests.

### Wire format

Amounts are decimal strings; a JSON number is refused, so no binary float reaches the service. Too
many decimals for the currency is refused (`"10.500"` is fine for ZAR, `"10.001"` is not), as is
more than 20 digits before the point. Input Postgres cannot store (a NUL in `reference`, a statement
window outside 1970 to 9999) is 400 at the edge. `ErrorCode` carries only the stable wire code and
its message key; `api/advice/ErrorStatus` is the one table mapping each code to its HTTP status, an
exhaustive switch (a new code does not compile without a status), and `ErrorStatusTest` pins every
published status.

## 5. Conversion design (Task 2)

Code: `services/conversion-svc`, package `io.ezeebit.conversion`; schema changesets
`001-quote.xml`, `002-conversion.xml`. Ledger side: `ConversionPostingCtrl`, `ConversionPoster`,
`ConversionVoider`.

### Two steps: quote, then accept

| Table | Key columns | Constraints |
|---|---|---|
| `quote` | `id`, `merchant_id`, currencies, `sell_amount`, `buy_amount`, `rate` (market), `applied_rate` (after spread), `spread_bps`, `rate_source_timestamp`, `expires_at`, `status` | in the DB, not in memory, so any replica can accept it |
| `conversion` | `id`, `merchant_id`, `quote_id`, `idempotency_key`, `status` (PENDING, COMPLETED, REJECTED), `reason`, `created`, `completed` | UNIQUE (`quote_id`): a quote is single-use; UNIQUE (`merchant_id`, `idempotency_key`) |

**Quote** (`POST .../quotes` with `{sell, buy, sellAmount | buyAmount}`):

1. Call the exchange-rate service: 2 s connect and 2 s read timeouts, no retry.
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
3. Call the ledger's internal posting with `conversionId` as its key and a **deadline** = quote
   expiry + 30 s (1 s connect and 3 s read timeouts, no retry).
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

| Ledger answer | Conversion |
|---|---|
| 201 POSTED (first or replayed) | COMPLETED, 201 |
| 422 `INSUFFICIENT_FUNDS` or `BALANCE_LIMIT_EXCEEDED` | REJECTED, 422. Final: the ledger first commits a REFUSED journal (no entries) under the same identity, so that `conversionId` can never post later, even after the merchant is funded |
| 400 on the posting's content (`AMOUNT_SCALE_EXCEEDED`, `AMOUNT_TOO_LARGE`, `AMOUNT_NOT_POSITIVE`, `UNKNOWN_CURRENCY`, `SAME_CURRENCY`, `VALIDATION_FAILED`, `MALFORMED_REQUEST`) | REJECTED with reason `INPUT_REFUSED` at once (409 `CONVERSION_REJECTED`). Safe without a marker: the ledger decides it from the body alone, and conversion-svc only ever sends one body per `conversionId` |
| 409 `POSTING_DEADLINE_PASSED` / `CONVERSION_VOIDED` | REJECTED, reason `DEADLINE_PASSED` / `VOIDED` |
| timeout, reset, 5xx, `IDEMPOTENCY_KEY_REUSED`, any other code | stays PENDING (202) for the sweeper. `IDEMPOTENCY_KEY_REUSED` means another journal holds the id, possibly a POSTED one, so only the lookup can settle it |

At the ledger, a repeated posting is answered from what it holds for the `conversionId`: a VOID
marker answers 409 `CONVERSION_VOIDED` for any body; otherwise the request hash is checked first (a
different body is 422 `IDEMPOTENCY_KEY_REUSED`), and then a REFUSED marker replays its 422 and a
POSTED journal replays its 201.

### PENDING recovery, and why there is no saga

A same-key retry of a PENDING conversion re-posts only while its quote is live (the ledger is
idempotent on `conversionId`); after expiry it only returns the current state.

The sweeper (every 10 s) takes a PENDING conversion only after its deadline plus 30 s maximum
transaction time, when no posting can still commit. It claims one due row per transaction with
`SELECT ... FOR UPDATE OF c SKIP LOCKED`, so every replica can run it, and asks the ledger. The
lookup answers POSTED, VOID or REFUSED (with its reason), else 404 `POSTING_NOT_FOUND`. Found:
settle accordingly. `POSTING_NOT_FOUND`: write a VOID marker under the posting's identity, so a late
posting hits the unique index and is refused, then mark REJECTED. Only `POSTING_NOT_FOUND` leads to a
void: an unmapped-route 404 (`NOT_FOUND`) says nothing about the id, so the row stays PENDING.

A posting and a void race on one unique index and the first commit wins, so correctness does not
depend on the two databases' clocks. No saga is needed: the only money movement is one atomic
transaction in one database.

### The exchange-rate service contract (an assumption)

The brief says the service exists and can be called; it does not say how. This is the only place
the assumed contract is written down. The caller-owned DTO is `outbound/rates/RateDto`; WireMock
serves it from `docker/wiremock/mappings/rates.json`, and conversion-svc's acceptance tier loads the
same files.

```
GET /rates?base=ZAR&quote=USDT
200 {"base":"ZAR","quote":"USDT","rate":"0.054254","timestamp":"2026-10-13T10:00:00Z"}
```

- `rate` is how many units of `quote` one unit of `base` buys, as a decimal string. Both directions
  of every pair are served, so the platform never inverts a rate.
- `timestamp` is when the price was taken, as an ISO-8601 instant.
- An error status, an unknown pair, a refused connection or no answer within 2 s means "no rate"
  (503). An answer for another pair, an unreadable rate or timestamp, or a rate with more than 14
  decimals is a bad rate (502 `RATE_REJECTED`, reason `RATE_MALFORMED`).

### Notes on the build

- **Currency scales have two homes**: the ledger's `currency` table and conversion-svc's served
  config, because conversion-svc cannot read the ledger's database and the ledger has no currency
  endpoint yet. If they drift so that a quote carries more decimals than the ledger allows, the
  ledger refuses the posting with 400 `AMOUNT_SCALE_EXCEEDED` and the conversion goes REJECTED
  (`INPUT_REFUSED`) at once; no money moves.
- **Loopback only, locally.** Every service binds `${SERVER_ADDRESS:127.0.0.1}` and registers that
  address in Eureka. `/internal/**` moves money and has no authentication, so loopback is the local
  control; `LoopbackOnlyIT` requires a refusal on the host's LAN address.

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

The `/internal/v1/...` rows are never routed: the gateway answers 404 for them. The `/api/v1/...`
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
| POST | `/internal/v1/conversion-postings` | idempotent on `conversionId`; carries a deadline | 201 posted or replayed; 400 content errors; 409 `POSTING_DEADLINE_PASSED`, `CONVERSION_VOIDED` (any body); 422 `INSUFFICIENT_FUNDS`, `BALANCE_LIMIT_EXCEEDED` (recorded as REFUSED first, replayed for the same body), `IDEMPOTENCY_KEY_REUSED` |
| GET | `/internal/v1/conversion-postings/{conversionId}?merchantId=` (sweeper) | n/a | 200 `status` POSTED, VOID, or REFUSED with `reason`; 400 without `merchantId`; 404 `POSTING_NOT_FOUND` |
| POST | `/internal/v1/conversion-postings/{conversionId}/void` (sweeper) | identity (`merchantId`, CONVERSION, `conversionId`) | 201 voided, replayed, or the stored REFUSED journal; 409 `CONVERSION_ALREADY_POSTED` |

A path no controller maps answers 404 `NOT_FOUND`, distinct from `POSTING_NOT_FOUND`; that includes
any `/api/...` path sent straight to a service. An unsupported version on a service itself
(`/v2/...`) is 400 `UNSUPPORTED_API_VERSION`; through the gateway it is 404, because the route table
publishes v1 only.

## 7. Failure handling

| Dependency fails | Behaviour | Tested |
|---|---|---|
| A domain service down, unregistered or refusing | gateway 503 `UPSTREAM_UNAVAILABLE` | yes |
| Upstream resets, or silent for 10 s | gateway 504 `UPSTREAM_OUTCOME_UNKNOWN` | yes |
| Redis down, unreachable, stalled, or away at the gateway's boot | merchant routes 503 `RATE_LIMITER_UNAVAILABLE` within the 250 ms timeout, nothing forwarded; health stays UP; admitted again once Redis answers; RateLimiterStoreDown pages | yes: stopped mid-run, paused, absent at boot; recovery within 2.5 s after a late start and after a kill and restart (red with a 10 s connect back-off, and with Lettuce's default reconnect delay) |
| Many concurrent requests for one merchant | compare-and-swap losers past `max-retries` are 429 contention, never 503 | yes: one key through two gateways |
| A non-canonical path (`%xx`, `;param`, dot segment, `//`) | 400 `MALFORMED_PATH` on any path, counted `refused_path` | yes: encoded spellings of an exhausted route are 400 or 404, never 200 |
| A merchant over its limit | 429 `RATE_LIMITED` with `Retry-After`, nothing forwarded | yes: burst of capacity + 1, and split across two gateways |
| config-svc or eureka-svc down | running services keep their config and last registry; a restarting service cannot boot | no (framework behaviour) |
| Postgres down | the affected service fails its requests; nothing is half-written, every posting is one transaction | no (by design) |
| Rate service slow, down or wrong | quote refused in about 2 s, at most about 4 s (2 s connect plus 2 s read) (503) or with the broken rule (502); nothing stored, no default rate | yes: slow, 500, zero, negative, stale, out of band, jump, unreadable |
| Ledger slow or silent during accept | 202 PENDING; same-key retry re-posts only while the quote is live; the sweeper settles or voids | yes: answer never sent, answer lost, retry live, retry expired |
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
library. Splitting into one repository per service later is mechanical.

The rest of 12-factor is followed: config from the environment with committed working defaults (a
clean clone runs with no `.env`), backing services attached by URL, and no process state (quotes
live in the database).

The database choice (PostgreSQL rather than MySQL) is argued in
[SOLUTION.md, Trade-offs](SOLUTION.md#3-trade-offs).
