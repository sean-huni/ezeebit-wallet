# Solution

Which tasks I chose, the design and its trade-offs, the assumptions I made, how the remaining tasks
fit, what I would do with more time, what is unfinished, and how I used artificial intelligence (AI).
[README.md](README.md) explains how to run it; [ARCHITECTURE.md](ARCHITECTURE.md) holds the
detailed design (data model, flows, application programming interface (API), failure handling).

Walkthrough video: link to be added.

## Time and scope

I spent about 3 hours of my own attention, directing AI agents (Claude Code) that wrote most of the
code and docs under review gates: tests seen failing before they were trusted, reviews of each
branch, and a final review of the whole repository. The agents' work ran from 02:44 on 13 October 2026
to the early hours of 16 October (commit timestamps in South African Standard Time, Coordinated
Universal Time (UTC) plus 2 hours; `git log` has the exact times), well past the brief's two to three
hours. Release `0.1.0` was tagged on 13 October at 10:19 and the rest landed on `dev` after it;
release `0.2.0` (gateway, gRPC conversion-to-ledger, Kafka dead letters, Slack alerts) was tagged on
15 October at 14:26. The brief says
to stop and write up the rest; I kept building the extras listed below, which a reviewer can
ignore. Section 8 says where I accepted, changed or rejected what the agents produced.

**The submission is Tasks 1 and 2**: the ledger and conversion services, the gateway and the local
fleet that runs them.

**Beyond the brief**, built because my normal delivery bar includes them. A reviewer can ignore all
of it:

| Addition | Merge on `dev` | Where it is described |
|---|---|---|
| Local observability: the Grafana LGTM image (`grafana/otel-lgtm`: Grafana, Prometheus, Loki, Tempo, Pyroscope and an OpenTelemetry collector), OpenTelemetry Protocol (OTLP) export, generated and provisioned dashboards, alert rules with promtool tests, optional Slack delivery | `feat-observability`, `feat-business-observability`, `feat-lgtm-otlp` | [docs/observability.md](docs/observability.md) |
| Static application security testing (SAST) and coverage gates: SpotBugs with Find Security Bugs, Checkstyle, JaCoCo floors, opt-in SonarQube | `feat-sast` | [docs/quality-gates.md](docs/quality-gates.md) |
| Supply-chain gate: secrets scanning, Paketo images, software bills of materials (SBOMs), trivy | `feat-supply-chain` | [docs/supply-chain.md](docs/supply-chain.md) |
| CircleCI pipeline running the same Gradle commands | `feat-ci` | [docs/ci.md](docs/ci.md) |
| OpenAPI: per-service specs with derived problem responses, one Swagger user interface (UI) on the gateway, snapshots checked in `check` | `feat-openapi` | [README](README.md#api-reference), [ARCHITECTURE.md](ARCHITECTURE.md#openapi) |
| Gateway edge: per-merchant Redis rate limiter (replica-safe, fail-closed), `/api/v1` to `/v1` path rewrite, gateway proof header on the services' public routes | `feat-gateway-edge` | [ARCHITECTURE.md section 3](ARCHITECTURE.md#3-runtime-topology), [README](README.md#rate-limiting) |
| Use-case packages and a map of every package and class | `refactor-ledger-packages` | [docs/project-layout.md](docs/project-layout.md) |
| Dependency-injection budget: the build fails on any bean with more than 3 injected dependencies; 8 beans split by job to meet it | `bugfix-di-flooding` | [docs/quality-gates.md](docs/quality-gates.md#architecture-gates) |
| Conversion to ledger over gRPC instead of REST, Resilience4j retries and circuit breakers, a Kafka dead-letter topic, Slack alert delivery | `feat-ledger-grpc` | [ARCHITECTURE.md](ARCHITECTURE.md#conversion-to-ledger-over-grpc), [docs/observability.md](docs/observability.md) |

Each landed as its own merge on `dev`, so `git show --stat <merge>` shows exactly what it
added. Later work, these documents included, was built on top of them, so reverting one now means
resolving conflicts rather than a one-command revert.

## 1. Tasks chosen, and status

**Chosen: Task 1 (hold a merchant's money across currencies) and Task 2 (convert one currency into
another). Both are built and tested.**

Why these two: they are the core money model. Every other task ends up posting through the same
ledger. A withdrawal (Task 3) is a ledger debit, an on-chain payment (Task 4) is a ledger deposit,
auto-settlement (Task 5) is a conversion triggered by a deposit, and payout routing (Task 6) sits
behind the withdrawal. If the ledger and conversion are right, the other four are mostly plumbing
around them. If they are wrong, nothing built on top can be trusted.

What exists:

- **Task 1, the ledger.** `POST /internal/v1/deposits` (Idempotency-Key required, called by the
  money-in rail, never routed) posts a balanced journal: merchant `+X`, platform deposit clearing
  `-X`, in one transaction, with `balance_after` on the merchant entry. Balances and the statement
  are served through the gateway. Five currencies: South African rand (ZAR),
  Nigerian naira (NGN) and Kenyan shilling (KES) at 2 decimals, Tether (USDT) and USD Coin (USDC)
  at 6. An amount with more decimals than its currency allows is refused, never rounded. A replayed
  deposit returns the identical answer and credits once; the same key with a different body is 422.
- **Task 2, conversion.** A quote calls the exchange-rate service (2 s timeouts, no fallback rate),
  validates the rate, applies a 50 basis-point (bps) spread, rounds in the platform's favour and lives 15 s.
  Accepting it asks the ledger, over gRPC Remote Procedure Call (gRPC) with up to 3 attempts, to post
  four entries in one transaction under row locks, with funds and a deadline checked. An answer that
  never arrives leaves the conversion PENDING (202), records a dead letter on a Kafka topic that pages
  Slack, and leaves the money to a sweeper, which finds the posting or voids it so a late arrival is refused. A refusal is recorded
  in the ledger before it is answered, so money arriving later can never let that conversion post.
- **Five Spring Boot 4.1.1 services** (Java 25, Spring Cloud 2025.1.3): two business services
  (ledger-svc, conversion-svc) behind the gateway (gateway-svc), and config-svc and eureka-svc as the
  control plane (configuration and discovery); each business service owns its own PostgreSQL database, plus `./fleet.sh` to run them. A WireMock
  container stands in for the rate service.

**Tests.** **610 tests, 0 failures, 0 skipped**, summed from the JUnit Extensible Markup Language (XML)
reports `services/*/build/test-results/*/*.xml` last written 2026-10-15 at 14:23 local (the build for
release `0.2.0`; 538 on 2026-10-13, before the gRPC change). Those reports are not committed; a
committed gate log with the counts arrives with release `0.3.0`. The
[2026-10-14 gate log](docs/fleet/verification/2026-10-14-gate.log) records `BUILD SUCCESSFUL` for
`./gradlew --no-build-cache --rerun-tasks clean build` at `3b92e9d`, but no test counts. Each unit count includes the 13 tests of the shared
dependency-injection gate, which fails the build when any bean takes more than 3 injected
dependencies (`DependencyBudgetTest`, `InjectionCounterTest`; see
[docs/quality-gates.md](docs/quality-gates.md#architecture-gates)).

| Service | Unit (`test`) | Integration (`integrationTest`) | Acceptance (`acceptanceTest`, Cucumber scenarios) |
|---|---|---|---|
| ledger-svc | 107 | 42 | 61 |
| conversion-svc | 173 | 31 | 49 |
| gateway-svc | 65 | 54 | |
| config-svc | 14 | | |
| eureka-svc | 14 | | |

conversion-svc's acceptance scenarios run against the real ledger jar as its own process, with
WireMock scripting rate-feed faults and a small gRPC forwarding proxy scripting ledger faults. Across both services' suites: 50 concurrent deposits sum
exactly, 20 concurrent copies of one deposit credit once, and 8 concurrent conversions from one
balance succeed exactly as often as the funds allow. Each concurrency, idempotency and
currency-integrity test was seen failing with its control removed (row lock, unique constraint,
composite foreign key, request-hash check) before it was trusted. A review found three inputs that
answered 500 (a balance past the column's 20 digits, a NUL in `reference`, a statement window
outside the timestamp range); each is now refused with 422 or 400, by a scenario first seen
failing.

## 2. Design on one page

Two domain services, each owning its own database, behind one gateway. Details in
[ARCHITECTURE.md](ARCHITECTURE.md).

- **ledger-svc (Task 1) is the only thing that holds money.** A double-entry journal: every movement
  is a journal whose entries sum to zero per currency; entries are insert-only and carry
  `balance_after`, so the statement explains every balance. A composite foreign key ties each
  entry's currency to its account's, so currencies cannot be mixed even by an application bug.
- **conversion-svc (Task 2) owns pricing**: a firm quote with a short expiry, then accept. The
  merchant gets exactly the quoted rate or a clear rejection.
- **The money moves in one place.** Accepting a quote asks the ledger to post four entries in one
  local transaction, under merchant row locks, with funds and a deadline checked. No distributed
  transaction, no saga.
- **Duplicates are expected.** Every POST that moves money carries an idempotency key stored with
  the journal in the same transaction; a duplicate replays the stored answer.
- **A PENDING conversion is resolved, never guessed.** A sweeper asks the ledger once no posting can
  still commit: found means settled, not found means it writes a VOID marker so a late posting is
  refused.

## 3. Trade-offs

| Decision | Alternative | Why I chose it |
|---|---|---|
| Two domain services (ledger, conversion) | One service with two modules | Different invariants, rates of change and failure domains ([ARCHITECTURE.md section 2](ARCHITECTURE.md#2-services)). One modular service would also have been defensible at this volume. |
| Only the ledger writes money | Conversion posts its own balance changes | One place to audit, one transaction per movement. |
| Double-entry journal with a cached balance | A balance column updated in place | The brief asks to "trust these balances completely" and explain them "at any later date". An in-place balance cannot explain itself. Cost: more rows and more care on every posting. |
| Firm quote with a short time to live (TTL) and a spread | "Last look" (re-price at accept) | The price shown is the price paid, or a clear "quote expired". Last look protects the platform more but makes the price non-firm. I would revisit it if measured slippage exceeded the spread. |
| PostgreSQL | MySQL (Ezeebit's stack) | The design only uses portable features: exact `NUMERIC`, `SELECT ... FOR UPDATE`, unique and `CHECK` constraints. I picked Postgres for transactional data definition language (DDL) (a failed migration rolls back cleanly) and one identical image (`postgres:18.6-alpine`) in tests and compose. Moving to MySQL 8 is a driver, datasource and dialect change, not a redesign. |
| All services in one repository | One repository per service (12-factor) | The deliverable is one repository that builds and runs with one command. Argued in [ARCHITECTURE.md section 8](ARCHITECTURE.md#8-single-repository-and-the-12-factor-trade-off). |
| Eureka, Config Server and Gateway for two domain services | Fixed Uniform Resource Locators (URLs), no gateway | Real overhead: three extra processes, a boot order, registry caches to tune. Kept because the platform will grow into payouts, chain listening and settlement, and the gateway is one route table and one place to keep `/internal/**` unreachable. If the fleet stayed this small, I would drop Eureka and the Config Server first. |
| Redis-backed rate limit per merchant at the gateway, failing closed | No limiter; or in-heap buckets per gateway | Not needed by Tasks 1 and 2 (idempotency and concurrency live in the database). Added beyond the brief because a payday burst from one merchant should not starve the rest. In-heap buckets would multiply the limit by the replica count. Failing closed makes Redis part of the edge's critical path: without it no merchant call succeeds, which I prefer to an edge that silently stops limiting. |
| Merchant identity as a path variable | A token carrying the merchant id | The brief says to assume the merchant is identified by an ID in the request. In production it would come from the verified token, never from the path. |

## 4. Assumptions

1. **Currency scales.** ZAR, NGN and KES at 2 decimals; USDT and USDC at 6. Too many decimals is
   refused, never rounded. FIAT or STABLECOIN is an attribute of a currency, not a service.
2. **Rate service contract.** `GET /rates?base=ZAR&quote=USDT` answers
   `{"base","quote","rate","timestamp"}`, both directions served
   ([ARCHITECTURE.md section 5](ARCHITECTURE.md#the-exchange-rate-service-contract-an-assumption)).
   A bad or missing rate is refused, never replaced by a cached or default one.
3. **Quote TTL** 15 s: short enough that the market cannot move far, long enough to press "confirm"
   on a mobile connection.
4. **Spread and bands** are placeholders (50 bps on every pair; wide bands around today's rates):
   the real numbers are business decisions.
5. **Rounding** always in the platform's favour, by at most one minor unit, never enough to move the
   delivered rate more than 1% from the stated one.
6. **Timing.** Posting deadline = quote expiry + 30 s; the sweeper settles 30 s after that.
7. **Platform accounts may go negative**: deposit clearing while money is owed in from outside; the
   foreign exchange (FX) account per currency is the platform's open position. Hedging it is out of scope.
8. **Deposits arrive pre-confirmed, and only from inside**, keyed on (merchant, the rail's transfer
   reference). Unconfirmed funds belong to Task 4.
9. **Accounts are opened on first use**, by a deposit or a conversion credit.

## 5. How Tasks 3 to 6 fit the same design

None of these needs a change to the ledger's model. Each one is a new caller that posts journals.

- **Task 3, withdrawals:** a new `payout-svc`. On request, the ledger moves the amount from the
  merchant's account to a `PLATFORM_PAYOUT_PENDING` account in one journal, under the same row lock
  and balance check, so a merchant cannot withdraw more than they hold even when several requests
  arrive at once. The `Idempotency-Key` stops a duplicate request from paying out twice. When the
  rail reports success, the pending amount moves to clearing; on failure, a reversing journal moves
  it back. Entries are never edited, only reversed.
- **Task 4, incoming crypto:** a chain listener that receives the confirmation service's
  notifications. Each payment is identified by (chain, transaction hash, log index), and that
  identity becomes the idempotency key of the same ledger deposit used by Task 1, so a repeated
  notification cannot credit twice. The deposit is posted only after a set number of confirmations;
  before that it can be shown as pending without being spendable.
- **Task 5, auto-settle:** a rule (merchant, stablecoin, percentage) owned by conversion-svc. The
  ledger publishes deposit events through a transactional outbox; conversion reacts by running quote
  and accept internally. If the rate service is down, the deposit stays credited in the stablecoin
  and the conversion is retried, shown as pending. The merchant never sees money disappear between
  the two steps.
- **Task 6, payout routing:** inside `payout-svc`. A per-country list of partners with limits and
  health, a timeout and circuit breaker per partner, and failover only before a partner has accepted
  the request. The funds stay in `PLATFORM_PAYOUT_PENDING` until a terminal outcome, so a partner
  failure can never lose or duplicate money.

The candidates I removed from my first service list map here: fiat and crypto handling become the
payout and chain-listener paths, settlement becomes Task 5, and a merchant service becomes a
profile and know-your-customer (KYC) context once authentication exists.

## 6. What I would do with more time

In order:

1. **A scheduled reconciliation job** (every merchant balance equals the sum of its entries; every
   journal sums to zero per currency) with an alert on any difference.
2. **Authentication on `/internal/**`**: network policy admitting only named callers, or mutual Transport Layer Security (mTLS) with
   caller identity.
3. **The platform FX position per currency** as a metric and a panel.
4. **A ledger currency endpoint**, so conversion-svc reads scales from the ledger instead of
   keeping its own copy.
5. **A two-instance run of conversion-svc** to prove the sweeper live across processes.
6. **A transactional outbox** for ledger events, which Task 5 depends on.
7. **Mutual Transport Layer Security (mTLS) on the gRPC seam.** The conversion-to-ledger messages are
   already generated from one `.proto` (`contracts/ledger/v1/`), so no hand-written mirror is left
   between the services; the channel itself is still plaintext.
8. **Quote retention**, and a MySQL 8 run of the same suite to back the portability claim.

## 7. Known gaps and unfinished work

This is the single list; the other documents link here.

**Security and deployment**

- `/internal/**` and ledger-svc's gRPC port (9081) have no authentication and the gRPC channel is
  plaintext. Locally every service binds loopback; production needs a network policy or mTLS before
  these endpoints are deployable. On Kubernetes the gRPC port needs mTLS or a service mesh plus a
  NetworkPolicy naming conversion-svc as its only client. The gateway proof header covers neither.
- Kafka runs as one broker with replication 1. A dead letter Kafka does not take is counted and
  paged (DeadLetterPublishFailing) and stays in conversion-svc's log only; nothing consumes the topic
  by design, so it is a record, not a queue.
- The gateway proof header (`X-Ezeebit-Gateway`) is a shared secret, not authentication: it travels
  in clear over plain Hypertext Transfer Protocol (HTTP), every service and anyone who can read config-svc's served files holds
  it, and the committed default is public. It only stops a direct call to a service that does not
  know it. Production needs network policy or mTLS between the gateway and the services, and the
  secret rotated per environment.
- Redis runs as one unreplicated container with no persistence; it holds only rate-limit buckets,
  so a restart resets every merchant's bucket to full. Because the limiter fails closed, Redis being
  down stops every merchant call (paged as RateLimiterUnavailable).
- A changed rate limit starts a new bucket per merchant (the limits are part of the key), so each
  active merchant gets one full bucket at the change, and two buckets while a rolling deploy runs
  old and new limits side by side.
- Bucket4j refills from each gateway's wall clock: unsynchronised clocks admit extra tokens.
- The rate-limit key is the merchant id from the path, which the client chooses. With no
  authentication a caller can spread load over many merchant ids; a limit keyed on the verified
  caller needs the token the brief puts out of scope. Only the shape of the key is bounded: a universally unique identifier (UUID).
- One Postgres container hosts both databases: logically separate, one shared failure domain.
- config-svc and eureka-svc run as single instances.
- CircleCI has never run: the repository is pushed to GitHub but not connected to CircleCI, and no
  check run or status exists on `origin/dev` (checked 2026-10-16; [docs/ci.md](docs/ci.md)).
- Tomcat, Jackson (3 and 2) and FreeMarker are pinned above Boot 4.1.1's managed versions to clear
  fixable Common Vulnerabilities and Exposures (CVEs); each override must be dropped once Boot catches up, or it will hold a version back.
  lz4-java is pinned at 1.11.4 in conversion-svc for CVE-2026-106451, pulled at 1.10.1 by kafka-clients
  4.2.1: a transitive override rather than a Boot property, to be dropped once Boot manages a kafka-clients that brings a fixed lz4.
- trivy has no compose checks, so the misconfiguration stage reports rather than inspects.

**Money correctness**

- Reconciliation is a test assertion and a manual query, not a scheduled job with an alert.
- A broken ledger invariant (a 5xx `CURRENCY_MISMATCH` or `UNBALANCED_POSTING`) pages as
  LedgerInvariantBroken, proven by promtool only, never by a rehearsed outage.
- Rate drift: each quote's rate is checked only against the pair's recent quote, so a slow drift of
  under 5% per quote is bounded only by the static band.
- Currency scales live in the ledger and in conversion-svc's config. Drift fails safe (the
  conversion is REJECTED, no money moves) but should not be possible.
- The platform FX position per currency is visible by query only; nothing reports or hedges it.
- No transactional outbox for ledger events (needed by Task 5, not by Tasks 1 and 2).

**Operations and monitoring**

- `ezeebit_conversion_pending_overdue` exports NaN when its query fails, and ConversionsStuckPending
  reads NaN as Normal.
- HighErrorRate has no minimum-traffic guard: one 500 in a quiet minute reads as 100% and fires.
- The sweeper holds the claimed row's lock, and so a pooled connection, while it calls the ledger:
  up to about 15 s per row (a `Find` and a `Void`, each up to 3 attempts of 2 s with backoff, 7.5 s).
- The sweeper's two-replica behaviour is proven in-process (two concurrent sweeps on one database),
  not with two running conversion-svc instances.
- Nothing reports the age of the oldest PENDING conversion.
- Alerts reach one Slack channel, not an on-call rota; nothing watches the monitoring stack itself.
- RateLimiterUnavailable and RateLimiterStoreDown were each rehearsed once locally; neither has a
  `promtool` case. `docker/lgtm/test-rules.sh` unit-tests 8 of the 12 rules (ServiceDown,
  RateFeedRefusing, LatencySLOBurn, DbPoolSaturated, LedgerInvariantBroken,
  ConversionLedgerDeadLettered, DeadLetterPublishFailing, KafkaAbsent) and is run by hand, not by the
  Gradle build or the CircleCI pipeline.
- KafkaAbsent cannot see a broker that is down before conversion-svc's first dead letter, because
  the producer starts with that record; `./fleet.sh status` (the `kafka` row) is the check then.

**Not built**

- Tasks 3 to 6 (section 5). Quotes are never deleted.

## 8. AI usage notes

I used Claude Code throughout. I directed the work and reviewed what it produced.

**What I decided:** choosing Tasks 1 and 2 for the time box; keeping the design simple rather than
spreading it over many services; one repository; the initial service list and the final service
set. I also told it to check every proposed service against the text of the brief before building
anything.

**Accepted:** it went through the brief word by word (nouns as candidate entities, verbs as
operations, adjectives, adverbs and modals as things that need proof) and built the requirement
table in ARCHITECTURE.md section 1. Against that table it flagged four of my five proposed services
(Merchant, Fiat, Crypto, Settlement) as not traceable to Tasks 1 and 2, and pointed out that a
Conversion service was missing from my list. I agreed and changed the service set. It also wrote
the services and tests and drafted the documentation, which I reviewed.

**Changed, because verification disagreed with the first version:**

- The first fleet had every health check green and still could not route: for up to a minute the
  gateway saw an empty registry (Eureka's 30 s default caches), and a stopped service reached the
  client as HTTP 500. Found by stopping one service and comparing its route with a sibling's and an
  unrouted path. Fixed with 5 s registry intervals (verified by a live probe, not an automated test) and the
  gateway's 503/504 mapping, now tested.
- The first concurrent-conversion test sent every conversion to the same target currency, and
  stayed green with the source-account lock removed, because the shared target lock serialised the
  requests first. It now buys a different currency per request and goes red without the source lock.
- The ledger client was first wired through a `@LoadBalanced RestClient.Builder` bean. Every test
  passed (tests run with Eureka off); the live smoke run answered 503 for every quote, because
  Eureka's own client picked up that builder and conversion-svc never registered. A test now boots
  with Eureka on and goes red with the old bean.
- A review found that a ledger refusal was not final: a same-key retry after a deposit could post
  while conversion-svc recorded REJECTED. The ledger now commits a REFUSED journal before answering.
  The same review found quotes that answered 500 (a computed amount too large to store) and tiny
  quotes whose rounding made the stated rate untrue; both are now 400.
- The final review found that the sweeper would void a conversion on any 404 from the ledger,
  including a misrouted path, and that a refusal was replayed before the request hash was checked.
  The ledger now answers a missing posting with `POSTING_NOT_FOUND`, the only code that leads to a
  void, and checks the hash first. It also found the 504 branch untested and no gateway read
  timeout; both are now in place.
- A "slow rate service" test measured 5 s for a refusal configured at 2. The service was right: the
  test's own HTTP client silently retried every 503. The test client is now the Java Development Kit (JDK) one.
- The alerting paged on a plain restart, from a Pending alert Grafana had persisted at shutdown.
  Reproduced with Slack disconnected and fixed by moving Grafana's database to tmpfs.

**Rejected:** an early table of services and datastores that included Redis rate limiting, MongoDB
for notifications, and read replicas for settlement. Nothing in Tasks 1 and 2 needs any of them.
Redis rate limiting was later added at the gateway as an extra beyond the brief (section 3); the
other two stay rejected.
