# Solution

This document covers which tasks I chose, the design and its trade-offs, the assumptions I made
where the brief is ambiguous, how the remaining tasks fit the same design, what I would do next,
and how I used AI tools. `README.md` explains how to run it; `ARCHITECTURE.md` holds the detailed
design (data model, flows, failure handling).

Walkthrough video: link to be added.

## 1. Tasks chosen, and status

**Chosen: Task 1 (hold a merchant's money across currencies) and Task 2 (convert one currency
into another).**

Why these two: they are the core money model. Every other task ends up posting through the same
ledger. A withdrawal (Task 3) is a ledger debit, an on-chain payment (Task 4) is a ledger deposit,
auto-settlement (Task 5) is a conversion triggered by a deposit, and payout routing (Task 6) sits
behind the withdrawal. If the ledger and conversion are right, the other four are mostly plumbing
around them. If they are wrong, nothing built on top can be trusted.

**Status in this repository: a runnable skeleton with the design written down. The business
endpoints are designed, not implemented.**

What exists and is verified:

- Five Spring Boot 4.1.1 services (Java 25, Spring Cloud 2025.1.3) in one Gradle 9.8.1 build:
  `gateway-svc` (8080), `ledger-svc` (8081), `conversion-svc` (8082), `config-svc` (8888),
  `eureka-svc` (8761).
- One PostgreSQL 18.6 (Alpine) container with a separate database and owning role per domain
  service (`ledger`, `conversion`), with `CONNECT` revoked from `PUBLIC`, so neither role can
  even connect to the other's database. Liquibase runs on boot in both; the changelogs hold no
  changesets yet.
- The gateway's published route table: balances and entries route to the ledger; quotes and
  conversions route to conversion; `/internal/**` is never routed. Deposits are internal
  (`POST /internal/v1/deposits`, called by the money-in rail), so a merchant cannot credit itself.
- Upstream failures are mapped by the gateway, not left as 500: no instance or a refused
  connection is 503 (covered by two tests); any other I/O failure (reset, timeout) is 504
  "outcome unknown: retry with the same Idempotency-Key" (not tested yet). Details name no service.
- `./fleet.sh up | status | down` brings the whole fleet up in dependency order and checks health.
- `./gradlew clean build` passes: 5 test suites, 6 tests, 0 failures. The ledger and conversion
  tests boot against a real Postgres via Testcontainers, so Docker is required for the build.

What does not exist yet: the deposit, balance, statement, quote and conversion endpoints, their
tables, and their tests. Calling `/api/v1/merchants/{id}/balances` through the gateway today
returns 404 from the ledger service (it is up, the endpoint is not built). I chose to spend the
time box on getting the money model and its failure behaviour right on paper, plus a skeleton
that the implementation drops into, rather than on a rushed implementation.

## 2. Design on one page

Two domain services, each owning its own database, behind one gateway.

- **ledger-svc (Task 1)** is the only thing that holds money. It is a double-entry journal:
  every movement is one `journal` row with entries that sum to zero per currency. One `account`
  exists per (merchant, currency, type). Entries are insert-only and each carries `balance_after`,
  so "how did this balance reach its current value" is answered by reading the merchant's
  statement. A merchant `account.balance` is a cache maintained in the same transaction and
  reconcilable against the sum of entries; platform accounts cache no balance and derive it on read. Amounts are `NUMERIC(38,18)` / `BigDecimal`, never floating point.
  A composite foreign key ties each entry's currency to its account's currency, so two currencies
  cannot be mixed even by a bug in the application.
- **conversion-svc (Task 2)** owns pricing. It is a two-step flow: `POST .../quotes` calls the
  existing rate service with a short timeout, validates the rate, applies a spread in the
  platform's favour, rounds the merchant's side in the platform's favour, and stores a quote with a short expiry.
  `POST .../conversions {quoteId}` accepts it: the merchant gets exactly the quoted rate, or a
  clear rejection if the quote expired (409 `QUOTE_EXPIRED`) or was already used under a different
  key (409 `QUOTE_ALREADY_USED`).
- **The money moves in one place.** Accepting a quote calls a ledger internal endpoint that, in
  one local transaction, refuses the posting if its deadline (quote expiry plus about 30s, checked
  on the ledger's own DB clock) has passed, opens the merchant's target account if absent, locks
  the merchant's source and target accounts in ascending id order, checks funds, and posts four
  entries
  (merchant source debit, platform FX source credit, platform FX target debit, merchant target
  credit). There is no distributed transaction and no saga, because there is only one money write.
- **Duplicates are expected.** Every POST that moves money carries an idempotency key: a deposit
  uses the rail's transfer reference, a conversion the merchant's `Idempotency-Key`, the internal
  conversion posting the conversion id. A quote needs none: it moves no money. The journal holds
  the key, unique per (merchant, type, key), in the same transaction as the entries; the row is
  inserted first and a unique violation replays the stored result, so concurrent duplicates never
  500. Same key and a different body is rejected with 422.
- **A PENDING conversion is resolved, never guessed.** After the posting deadline plus the maximum
  transaction time, a sweeper asks the ledger by conversion id: found means COMPLETED; not found
  means it writes a VOID marker under the same identity (a late posting then conflicts and is
  refused) and marks REJECTED. A same-key retry returns the current state and never re-posts at a
  stale rate.
- **Concurrency** is handled with `SELECT ... FOR UPDATE` on the merchant account rows only, locked
  in ascending id order, plus `CHECK (balance >= 0)` on merchant accounts as the last line of
  defence. Platform accounts are never locked: every posting writes them, so a lock would serialise
  the whole platform per currency.
- **The edge** is Spring Cloud Gateway; services find each other through Eureka and read their
  config from a Spring Cloud Config Server.

The data model, endpoint contracts, and failure tables are in `ARCHITECTURE.md`.

## 3. Trade-offs

| Decision | Alternative | Why I chose it |
|---|---|---|
| Two domain services (ledger, conversion) | One service with two modules | The two have different invariants (non-negative balance and zero-sum journal versus quote expiry, spread and rate sanity), change for different reasons (pricing policy moves faster than ledger rules), and fail differently: a slow or dead rate feed must never stop deposits. That is the bar I use for a separate service, and both clear it. A single modular service would also have been a defensible answer at this volume. |
| Conversion is separate from the ledger, and only the ledger writes money | Conversion posts its own balance changes | One writer of money means one place to audit, one transaction per movement, and no cross-service consistency problem. Conversion asks the ledger to post; it never holds a balance. |
| Double-entry journal with a cached balance | A balance column updated in place | The brief asks to "trust these balances completely" and explain them "at any later date". An in-place balance cannot explain itself. The journal can, and the cached balance keeps reads cheap and is checked against the entries. Cost: more rows and more care on every posting. |
| Firm quote with a short TTL and a spread | "Last look" (re-price at accept and reject if the market moved) | A firm quote is easier for a merchant to understand: the price shown is the price paid, or a clear "quote expired". The spread and short TTL bound the platform's exposure during the window. Last look protects the platform more but makes the merchant's price non-firm. I would revisit it if measured slippage on accepted quotes exceeded the spread. |
| PostgreSQL | MySQL (Ezeebit's stack) | The design only uses portable features: exact `NUMERIC`, row locks with `SELECT ... FOR UPDATE`, unique and `CHECK` constraints. MySQL 8 would work equally well. I picked Postgres for transactional DDL (a failed migration rolls back cleanly) and one identical image in tests and in compose. Moving to MySQL is a driver, datasource and dialect change, not a redesign. |
| All services in one repository with one build | One repository per service (12-factor "one codebase per app", https://12factor.net/codebase) | The deliverable is one repository that must build and run with one command. The services still share no code and no database, and each ships as its own jar, so splitting them later is mechanical. This is a deliberate departure from 12-factor and I would split for a real team. |
| Eureka, Config Server and Gateway for two domain services | Fixed URLs in each service's config, no gateway | This is real overhead for two services: three extra processes, a boot order, and registry caches I had to tune (see section 7). I kept them because the brief describes a platform that will grow into payouts, chain listening and settlement, and the gateway gives one published route table and a single place to keep `/internal/**` unreachable. If the fleet stayed this small, I would drop Eureka and the Config Server first. |
| No Redis and no rate limiting | Redis-backed rate limiting at the gateway | Nothing in Tasks 1 and 2 requires it. Idempotency and concurrency are enforced in the database, which is the only state that has to agree across replicas. Rate limiting is the first reason I would add Redis, and it would have to be shared across gateway replicas, not per instance. |
| Merchant identity as a path variable | A token carrying the merchant id | The brief says to assume the merchant is already identified by an ID in the request, with no real authentication. In production the merchant id would come from the verified token, never from the path. |

## 4. Assumptions

The brief leaves these open. I made a choice for each so the design is concrete.

1. **Currency scales.** ZAR, NGN and KES at 2 decimals; USDT and USDC at 6. An amount with more
   decimals than its currency allows is rejected, never silently rounded. Storage is
   `NUMERIC(38,18)` so a larger scale can be added without a migration of existing data.
2. **Currency kinds.** A currency is either FIAT or STABLECOIN; this is an attribute on the
   currency, not a separate service.
3. **Rate service contract.** I assume it returns a mid rate for a currency pair plus the
   timestamp of that price. A rate is accepted only if it is positive, fresh (source timestamp
   within a few seconds), and inside a per-pair sanity band (min/max, from config-svc). If the
   pair's most recent quote is younger than about 5 minutes, the rate must also be within a set
   percentage of its market rate, read from the quote table; otherwise band and freshness alone
   apply, so a run of refusals cannot lock a pair out for ever. Repeated refusals for a pair are a
   future alert, once observability exists.
4. **Rate service failure.** The call has a short timeout (around 2 seconds). On timeout or a bad
   rate the quote request fails with 503 or 502 and a reason. There is never a fallback to a
   cached or default rate.
5. **Quote TTL.** Around 15 seconds. Short enough that the market cannot move far, long enough for
   a person to press "confirm" on a mobile connection. This is a configuration value, not a
   constant.
6. **Spread.** A configurable value in basis points per currency pair, always applied in the
   platform's favour, and stored on the quote with the market rate so every conversion records
   both. The exact number is a business decision; I have not picked one.
7. **Rounding.** Always in the platform's favour, by at most one minor unit. Quoted by sell
   amount: the amount the merchant receives rounds down to the buy currency's scale. Quoted by buy
   amount: the amount the merchant pays rounds up to the sell currency's scale.
8. **Quotes are single-use.** A quote can be accepted once (enforced by a unique constraint on the
   conversion's quote id). Re-sending the same accept with the same `Idempotency-Key` re-checks
   quote expiry and returns the conversion's current state rather than failing, never re-posting
   at a stale rate; the same quote under a different key is 409 `QUOTE_ALREADY_USED`.
9. **Merchant id in the path:** `/api/v1/merchants/{merchantId}/...`, as the brief allows.
10. **Platform accounts may go negative.** The deposit clearing account is the source side of a
    deposit, so its balance is negative while money is owed in from outside. The platform FX
    account per currency is the platform's open position. Only merchant accounts carry the
    non-negative constraint and a cached, locked balance; platform balances are summed on read.
11. **Deposits arrive pre-confirmed for Task 1, and only from inside.** The internal deposit
    endpoint (`POST /internal/v1/deposits`, never routed) credits money that has already been
    confirmed by the rail that brought it in, keyed on (merchantId, the rail's transfer reference). Pending or unconfirmed funds belong to
    Task 4.
12. **Accounts are opened on first use.** A merchant's account for a currency is created the first
    time money arrives in that currency, by a deposit or by a conversion credit
    (`INSERT ... ON CONFLICT DO NOTHING`, then lock). There is no separate "open account" call.
13. **Hedging is out of scope.** The platform FX balances show the platform's exposure per
    currency; offsetting that exposure is a treasury concern outside this slice.

## 5. How Tasks 3 to 6 fit the same design

None of these needs a change to the ledger's model. Each one is a new caller that posts journals.

- **Task 3, withdrawals:** a new `payout-svc`. On request, the ledger moves the amount from the
  merchant's account to a `PLATFORM_PAYOUT_PENDING` account in one journal, under the same row
  lock and balance check, so a merchant cannot withdraw more than they hold even when several
  requests arrive at once. The `Idempotency-Key` stops a duplicate request from paying out twice.
  When the rail reports success, the pending amount moves to clearing; when it reports failure, a
  reversing journal moves it back to the merchant. Entries are never edited, only reversed.
- **Task 4, incoming crypto:** a chain listener that receives the confirmation service's
  notifications. Each payment is identified by (chain, transaction hash, log index), and that
  identity becomes the idempotency key of the same ledger deposit operation used by Task 1, so a
  repeated notification cannot credit twice. The deposit is posted only after a set number of
  confirmations; before that it can be shown as pending without being spendable.
- **Task 5, auto-settle:** a rule (merchant, stablecoin, percentage) owned by conversion-svc. The
  ledger publishes deposit events through a transactional outbox; conversion reacts by running
  quote and accept internally. If the rate service is down, the deposit stays credited in the
  stablecoin and the conversion is retried, shown to the merchant as a pending conversion. The
  merchant never sees money disappear between the two steps.
- **Task 6, payout routing:** inside `payout-svc`. A per-country list of partners with their
  limits and health, a timeout and circuit breaker per partner, and failover only before a partner
  has accepted the request. The funds stay in `PLATFORM_PAYOUT_PENDING` until a terminal outcome,
  so a partner failure can never lose or duplicate money.

The candidates I removed from my first service list map here: fiat and crypto handling become the
payout and chain-listener paths (Tasks 3, 4 and 6), settlement becomes Task 5, and a merchant
service becomes a profile and KYC context once authentication exists.

## 6. What I would do with more time

In order:

1. **Implement the designed endpoints:** the ledger tables and Liquibase changesets, deposit,
   balances, statement, the internal conversion posting, then quote and accept.
2. **A concurrency test:** N parallel debits against one account, asserting that exactly
   `floor(balance / amount)` succeed and the balance never goes below zero.
3. **Idempotency tests:** same key and same body replays; same key and a different body is a 422;
   a retried conversion after a ledger timeout posts once.
4. **A rate service stub** for local runs and tests, including slow, stale and wildly wrong rates,
   so the failure paths are exercised and not only described.
5. **A stale-conversion sweeper** that resolves conversions left `PENDING` by a ledger timeout,
   after the posting deadline plus the maximum transaction time: COMPLETED if the ledger holds the
   posting, otherwise a VOID marker in the ledger and REJECTED.
6. **A reconciliation job** that checks every merchant account balance against the sum of its
   entries (platform balances are derived on read, so there is nothing to reconcile) and that every
   journal sums to zero per currency, and alerts on any difference.
7. **A transactional outbox** for ledger events, which Task 5 depends on.
8. **Observability:** OTLP export, Grafana dashboards and alerting. This is the planned next step.
9. **An OpenAPI contract** published by each service.
10. **A gateway upstream read timeout.** None is set yet, so a hung upstream hangs the request
    rather than answering 504.
11. **A MySQL portability check:** run the same suite against MySQL 8 to back up the claim in
    section 3 with a result.

## 7. AI usage notes

I used Claude Code throughout. I directed the work and reviewed what it produced; the notes below
say where I accepted, changed, or rejected its output.

**What I decided:** choosing Tasks 1 and 2 for the time box; keeping the design simple rather than
spreading it over many services; keeping everything in one repository; the initial service list;
and the final service set. I also told it to check every proposed service against the text of the
brief before building anything.

**Accepted:** it went through the brief word by word (nouns as candidate entities, verbs as
operations, adjectives, adverbs and modals as things that need proof) and built a requirement
table from it. Against that table it flagged four of my five proposed services (Merchant, Fiat,
Crypto, Settlement) as not traceable to Tasks 1 and 2, and pointed out that a Conversion service
was missing from my list. I agreed and changed the service set. It also scaffolded the Gradle
build and the services, and drafted the documentation, which I reviewed and edited.

**Changed, because verification disagreed with the first version:** the first fleet had every
health check green and still could not route. For up to about a minute after start, the gateway
saw an empty registry because of Eureka's default 30-second caches, and a stopped service reached
the client as HTTP 500, which reads as a gateway bug. This was found by a targeted probe: stop one
service, then compare its route against a sibling service's route and against an unrouted path.
The stopped service's route went from 404 to 503 once fixed, the sibling stayed 404, and the
unrouted control stayed 404 from the gateway. The fixes were 5-second registry and response-cache
intervals and a 503 mapping for unavailable upstreams (later split: 504 when the outcome is unknown). The 503 mapping is covered by two gateway
tests that I saw fail (at 500) before the fix; the shorter intervals were verified with the same
live probe, not by an automated test.

**Rejected:** an early table of services and datastores that included Redis rate limiting, MongoDB
for notifications, and read replicas for settlement. Nothing in Tasks 1 and 2 needs any of them,
so I left them out.
