# Ezeebit Wallet: Design + Walkthrough

**The brief in one line:** a merchant wallet that holds, converts and moves money in fiat (ZAR,
NGN, KES) and stablecoins (USDT, USDC), called over flaky mobile connections, where "a single lost,
duplicated, or mis-converted transaction is a serious incident".

| # | Section                                                            | Time | What it answers                                       |
|---|--------------------------------------------------------------------|------|-------------------------------------------------------|
| 1 | What I chose, and why                                              | 0:00 | which tasks                                           |
| 2 | How I turned the brief into a design                               | 0:40 | design and structure                                  |
| 3 | My architecture, patterns and principles                           | 1:50 | design and structure                                  |
| 4 | Why I keep decision records                                        | 3:10 | my key decisions                                      |
| 5 | My endpoints, a live demo, and failures                            | 3:50 | safety with money: a bad rate, a timeout, a duplicate |
| 6 | Where my judgement overrode the AI                                 | 6:30 | how I directed AI tools                               |
| 7 | How Tasks 3 to 6 slot into the same design                         | 7:50 | the remaining tasks                                   |
| 8 | My proof, lessons learnt, and what I change next                   | 8:30 | anything I would change                               |
| A | Appendix: chaos, a design cross-check, the database at 2+ replicas, memory, security |      | questions afterwards                           |

---

## The blueprint

Every section below points back to this map. The numbers (1) to (4) follow my live demo.

```text
                                         MERCHANT DASHBOARD
                   flaky mobile connections: the same request may arrive twice
                                                  |
                                                  |  money-moving POSTs carry an Idempotency-Key
                                                  v
+---------------------- gateway-svc :8080   (no business rules, no database) -----------------------+
|  routes /api/v1/... to /v1/...         per-merchant rate limit (Redis, fails closed)              |
|  /internal/** never routed: 404        resends only a request it never sent                       |
+-------------+----------------------------------------------------+--------------------------------+
              |  (2) quote, (3) convert                            |  (4) balances, statement
              v                                                    v
+---------- conversion-svc :8082 ----------+           +------- ledger-svc :8081, gRPC :9081 -------+
| TASK 2: owns pricing                     |           | TASK 1: the only holder of money           |
| quote : rate checked, 50 bps spread,     |           | ONE local transaction per movement:        |
|         15 s TTL, stored in the DB       |    gRPC   |  - lock merchant rows, ascending id        |
| accept: Idempotency-Key, quote used once |Post ----->|  - conversion: check funds + deadline      |
|         up to 3 tries of 2 s             |           |  - deposit 2 entries, conversion 4,        |
| unknown outcome: 202 PENDING             |<--------- |    zero-sum per currency                   |
| sweeper: Find; Void on POSTING_NOT_FOUND |Find/Void->|  - balance_after on merchant entries       |
|          (SKIP LOCKED, any replica)      |           |  - a refusal is recorded before it         |
|                                          |           |    is answered: final, never posts later   |
+-----+---------------+-------------+------+           +-------+-----------------+------------------+
      |               |             |                          |                 ^
      | GET /rates    |             | retries out              |                 | (1) deposit
      | 2 s, 1 retry  |             | or circuit open          |                 |
      v               v             v                          v                 |
[ rate feed ]  [ db: conversion ]  [ Kafka ]            [ db: ledger ]       [ money-in rail ]
 bad  -> 502     quote              dead letter:          currency            /internal/v1/deposits
 none -> 503     conversion         record +              account             key = rail's ref
 no default                         alert only,           journal             never via gateway
 rate                               never replayed        entry

NEVER   mix currencies ...... entry -> account composite FK on (id, currency) + Money
        overdraw ............ row lock + CHECK (balance >= 0)
        post twice .......... UNIQUE (merchant, type, idempotency key)
        rewrite history ..... entries insert-only; corrections are reversing journals

One Postgres server, two databases; neither service's role can connect to the other's.
Control plane: eureka-svc :8761 | config-svc :8888 | LGTM :3000 (metrics, logs, traces, profiles, alerts)
(1) to (4) = demo order
```

---

## 1. What I chose, and why

- Six tasks were offered; I chose two: **Task 1, hold money across currencies**, and **Task 2,
  convert between them**.
- They are the money model. Withdrawals, on-chain deposits, auto-settle and payout routing all post
  through the same ledger. If I get the ledger right, the rest is plumbing around it.
- My time, stated plainly: about 3 hours of my own attention, directing AI agents (Claude Code)
  under review gates. The agents' work ran well past the 2 to 3 hours. Everything I built beyond the
  brief is listed separately in `../../SOLUTION.md`.

## 2. How I turned the brief into a design

- I read the brief as grammar: nouns are candidate things, verbs are operations, and adverbs and
  modals ("completely", "must never") are **proof obligations** my design has to make true.

| The brief says                                                  | What makes it true in my design                                                            |
|-----------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| "trust these balances completely", "explain, at any later date" | append-only double-entry journal; every merchant entry carries `balance_after`             |
| "amounts can be large"                                          | `NUMERIC(38,18)` and `BigDecimal` from strings; a JSON number is refused                   |
| "two different currencies must never be silently mixed"         | a `Money` value object that throws, plus a composite foreign key the database enforces     |
| "can be slow and its price keeps moving"                        | a firm quote: 15 s TTL, 50 bps spread, rounding in the platform's favour, no fallback rate |
| "must never be able to convert more than they actually hold"    | a row lock plus a balance check; `CHECK (balance >= 0)` as the last line                   |
| "sometimes more than once"                                      | an idempotency key on every POST that moves money                                          |

- My first service list was Merchant, Ledger, Fiat, Crypto, Settlement. Four had no evidence in
  Tasks 1 and 2, so I dropped them; **Conversion was missing**, so I added it.
- Where the brief was ambiguous, I assumed and wrote it down (`../../SOLUTION.md` section 4): the rate
  service's contract, 2 decimals for fiat and 6 for stablecoins, deposits arrive pre-confirmed, the
  merchant id is in the path.

## 3. My architecture, patterns and principles

- **Shape:** gateway-svc in front; **ledger-svc** is the only thing that holds money; **conversion-svc** owns pricing;
  each owns its own database. config-svc and eureka-svc are the
  control plane. Java 25 on G1 with compact object headers, Spring Boot 4.1.2.
- **PostgreSQL, not MySQL**, for transactional DDL and one identical image in tests and compose. I
  use only portable features, so MySQL 8 is a driver and dialect change, not a redesign.
- **Patterns I used:**
    - **Double-entry ledger**, entries summing to zero per currency; **value objects** (`Money`,
      `MerchantId`); fiat and stablecoin are attributes, not services.
    - **Idempotent receiver**: the key lives in the journal row, in the same transaction; the same
      body replays, a different body is 422.
    - **Firm quote, then accept**; **row locks in ascending id order** (no overdraw, no deadlock); **compare-and-set**
      on conversion status.
    - **Ports and adapters**: `service/port/LedgerPostings` and `RateSource`; a test fails if my
      service layer imports an outbound class.
    - **Retry plus circuit breaker** (Resilience4j) on the gRPC call, a **dead-letter record** on
      Kafka, and a **reconciling sweeper instead of a saga**, because money moves in one local
      transaction.
- **Principles I enforce:** database per service; low coupling and high cohesion (my build fails on
  any bean with more than 3 injected dependencies); fail closed; 12-factor, with one repository as
  my stated exception.

## 4. Why I keep decision records

- I have no separate `docs/adr/` folder: my records live where the reasoning is read, in the
  trade-off table of `../../SOLUTION.md` section 3 and the decision tables of each design spec.
- Each one carries the ADR essentials: **decision, rejected alternative, reason, cost**.
    - Double-entry over an in-place balance: an in-place balance cannot explain itself.
    - A firm quote over "last look": the price shown is the price paid.
    - Dead letters are record-only: an automatic replay could race a posting that already committed.
- Why I bother: the rejected option is the one thing code cannot show. Decisions stop being
  re-argued, and each one records when I would reverse it.

## 5. My endpoints, a live demo, and failures

| Endpoint                                            | Purpose                                   | My design decision                                                                                       |
|-----------------------------------------------------|-------------------------------------------|----------------------------------------------------------------------------------------------------------|
| `POST /internal/v1/deposits` (ledger, never routed) | money in from the deposit rail            | unreachable through the gateway, so a merchant cannot credit itself; key = the rail's transfer reference |
| `GET /api/v1/merchants/{id}/balances`               | what the merchant holds, per currency     | the cached balance is updated in the same transaction as the entry                                       |
| `GET /api/v1/merchants/{id}/entries`                | the statement that explains every balance | keyset pages ordered by `seq` (assigned under the row lock), each row with `balanceAfter`                |
| `POST /api/v1/merchants/{id}/quotes`                | lock a price for 15 s                     | stored in the database, so any replica can accept it; no key needed, a quote moves no money              |
| `POST /api/v1/merchants/{id}/conversions`           | accept a quote                            | `Idempotency-Key` required; `UNIQUE (quote_id)` makes a quote single-use; 202 PENDING when unknown       |
| `GET /api/v1/merchants/{id}/conversions/{cid}`      | read a conversion, e.g. one left PENDING  | the client checks the outcome instead of re-sending the money call                                       |
| gRPC `Post`, `Find`, `Void` (ledger port 9081)      | conversion to ledger, internal            | one `.proto` compiled by both sides; round-robin over replicas via Eureka                                |

**Live demo** (the numbers match (1) to (4) on the blueprint):

```sh
M=$(uuidgen | tr 'A-Z' 'a-z')

# (1) deposit 1500.00 ZAR, straight to the ledger; run it twice: 201 both times, credited once
curl -s -X POST localhost:8081/internal/v1/deposits -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: rail-transfer-1' \
  -d "{\"merchantId\":\"$M\",\"currency\":\"ZAR\",\"amount\":\"1500.00\",\"reference\":\"rail transfer 1\"}"

# (2) quote 1000.00 ZAR to USDT through the gateway: rate 0.054254, applied 0.05398273, buys 53.982730
Q=$(curl -s -X POST localhost:8080/api/v1/merchants/$M/quotes -H 'Content-Type: application/json' \
  -d '{"sell":"ZAR","buy":"USDT","sellAmount":"1000.00"}' | tee /dev/stderr | grep -o '"quoteId":"[^"]*"' | cut -d'"' -f4)

# (3) convert within 15 s: 201 COMPLETED; the same key again returns the same answer
curl -s -X POST localhost:8080/api/v1/merchants/$M/conversions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: convert-1' -d "{\"quoteId\":\"$Q\"}"

# (4) read back: 500.00 ZAR and 53.982730 USDT; DEPOSIT 1500.00 then CONVERSION -1000.00
curl -s localhost:8080/api/v1/merchants/$M/balances
curl -s "localhost:8080/api/v1/merchants/$M/entries?currency=ZAR"

# control: the gateway never routes /internal
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/internal/x     # 404
```

**The three failures the brief names**, each covered by my tests:

- **A bad rate**: zero, negative, stale (over 10 s), outside the pair's band, or a jump over 5% is
  refused with 502 `RATE_REJECTED`. No quote, no money moved.
- **A timeout**: the rate feed gets 2 s and one retry, then 503 `RATE_UNAVAILABLE`, never a default
  rate. The ledger call retries up to 3 times, then answers 202 PENDING, and my sweeper settles or
  voids it.
- **A duplicate**: replayed from the stored answer, never posted twice; 20 concurrent copies of one
  deposit credit once.

## 6. Where my judgement overrode the AI

Each one: what the first version did, why it was wrong, and my fix. All are in `../../SOLUTION.md`
section 8.

- **Green health checks, no routing.** Every probe was green, yet for up to a minute the gateway's
  Eureka cache was empty and a stopped service reached the client as 500. My fix: 5s registry
  intervals and a gateway 503/504 mapping. I found it with a real request beside an unrouted control
  path.
- **A 404 that would have voided real money.** The sweeper voided a conversion on any 404, and a
  wrong path also answers 404. My fix: only the ledger's own `POSTING_NOT_FOUND` code leads to a void.
- **A concurrency test that could not fail.** 8 conversions from one balance all bought the same
  currency, so the target lock serialised them and the test stayed green with the source lock
  removed. My fix: a different target per request; now it goes red without the lock.
- **A refusal that was not final.** After an `INSUFFICIENT_FUNDS`, a same-key retry once the
  merchant was funded could post while conversion-svc had recorded REJECTED. My fix: the ledger
  commits a REFUSED journal before it answers.

## 7. How Tasks 3 to 6 slot into the same design

None of them changes my ledger's model; each is a new caller that posts journals.

- **Task 3, withdrawals** (`payout-svc`): funds move to a `PLATFORM_PAYOUT_PENDING` account under the
  same lock and balance check; on a rail failure, a reversing journal moves them back.
- **Task 4, incoming crypto**: (chain, transaction hash, log index) becomes the idempotency key of the
  same deposit, posted only after enough confirmations.
- **Task 5, auto-settle**: ledger deposit events through a transactional outbox; conversion runs
  quote and accept internally; if the rate feed is down, the stablecoin stays credited and pending.
- **Task 6, payout routing**: inside `payout-svc`, per-country partners with limits, timeouts and a
  circuit breaker each; failover only before a partner accepted; money stays pending until a
  terminal outcome.

## 8. My proof, lessons learnt, and what I change next

**Proof:**

- 831 tests at release 0.4.0, 0 failures. I saw every concurrency, idempotency and
  currency-integrity test fail with its control removed before I trusted it.
- On a kind Kubernetes cluster at 2 replicas, my 0.4.0 sweep killed 3 pods mid-traffic over 1609
  requests: 3 merchant failures, all inside the 20s budget, and **803 COMPLETED conversions against
  803 POSTED journals, none posted twice**. The same run paged Slack from kind.

**Lessons learnt:**

- **Green probes are not routing.** I prove a route with a real request beside an unrouted control.
- **A test is only trusted once I have seen it fail** with its control removed.
- **More replicas scale connections, not the database.** My pool budget is gated (70 of 100 at full
  autoscale), but one Postgres pod is still my single failure domain.

**What I change next:**

- **December 2026: highly available Postgres on Kubernetes.** CloudNativePG with a primary, 2
  synchronous replicas and automatic failover, plus PgBouncer in transaction mode. It keeps every SQL
  feature my money path relies on (`FOR UPDATE`, `SKIP LOCKED`, the composite key, the database
  clock), and a dead primary is replaced by promotion with no lost commit. The plan is written on
  the `feat-ha-postgres-plan` branch, paused until December.
- A scheduled reconciliation job with an alert; today it is a test and a manual query.
- Authentication on `/internal/**` and mTLS on the gRPC seam.
- My sweeper keeps its row lock while it asks the ledger (up to about 15 s per row). I will claim the
  row with a timed lease, call the ledger outside any transaction, then settle with compare-and-set.

**My close:** the balances can be trusted because every claim has a test that fails without it.

---

## Appendix A: chaos as a standing rehearsal

Chaos is not my defence; my design is. Chaos is the rehearsal that proves the defences hold against
failures I know will happen: a pod killed mid-request, a replica gone, a dependency down.

```sh
./k8s/kube.sh chaos status   # paused or not, last run, last victims
./k8s/kube.sh chaos on       # one random pod killed every 10 minutes
./k8s/kube.sh chaos off      # paused again (how it ships)
./k8s/kube.sh chaos run      # one kill now
```

- Least privilege: it can list and delete pods in the app and data namespaces only; never
  monitoring, never Secrets. Every sweep asks the API server to prove it (Appendix E).
- A run counts as evidence only with traffic during the kills, a failure when it killed fewer pods
  than asked, and the reconciliation afterwards (my 803 = 803 at 0.4.0).

## Appendix B: my design against a classic high-contention problem

Seat booking under a rush (many buyers, small stock, the same few seconds) is the same problem class
as one balance under many concurrent debits. I checked my wallet against its lessons.

| Lesson                                      | In my wallet                                                                                                  | Verdict                                                            |
|---------------------------------------------|---------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| L1 never hold a DB lock across a slow call  | accept commits PENDING in a short transaction, then calls the ledger outside it                               | applied on the money path; my sweeper is the exception (section 8) |
| L2 one conditional write decides the winner | `UPDATE ... WHERE status = 'PENDING'`; `UNIQUE (quote_id)` makes a quote single-use                           | applied                                                            |
| L3 hold with a status and a timer, DB clock | a 15 s quote; the deadline is checked on the ledger's own clock; a late posting hits the VOID marker          | applied: correctness never waits on the sweeper                    |
| L4 waiting room in front of the rush        | not built; a per-merchant Redis bucket (burst 100, then 20 a second), fails closed                            | deliberate: I add it when a load test shows the primary saturating |
| L5 split reads from writes                  | not built; balances read from the primary                                                                     | deliberate: same trigger, a measurement first                      |
| L6 idempotency key on the full identity     | UNIQUE (merchant, type, key), insert first, hash checked; a lost reply: same message resent, then 202 PENDING | applied; keys never expire, on purpose, for money                  |
| C1 the slow step finishes after the timer   | a posting after the deadline is refused; the sweeper voids only on `POSTING_NOT_FOUND`                        | applied                                                            |
| C2 per-user rate limit                      | keyed on the merchant id in the path, not a verified identity                                                 | partial: authentication is out of scope in the brief               |

Still to do: contention spread across two running conversion-svc instances; today my two-sweeper
proof runs in one process.

## Appendix C: is the database the bottleneck at 2+ replicas?

My answer: connections, no, and my build proves it; write throughput, eventually; availability, yes,
and that is the real gap my December upgrade closes.

- **Connections are gated.** Measured on my kind fleet (2026-11-02): `max_connections` 100, 20 + 20
  in use (2 pods x a pool of 10, per service). Full autoscale needs (3 + 3) x 10 + 10 = 70.
  `../../k8s/gates/pool-budget.sh` enforces it.
- **Writes go to one primary.** More app pods add connections, not database capacity. My contention
  stays narrow: the lock is per merchant, and platform accounts are never locked, so a payday burst
  does not serialise a currency.
- **Availability is the gap.** One Postgres pod hosts both databases: one failure domain, no failover.

My upgrade path, each step only when a measurement asks for it:

1. **December 2026: CloudNativePG**: a primary and 2 synchronous replicas, automatic failover with
   no lost commit.
2. Separate servers for `ledger` and `conversion`: already separate databases, so configuration, not
   code.
3. **PgBouncer** in transaction mode; my Liquibase migrations bypass it, because their advisory lock
   is session-level.
4. Read replicas for statements, last: a stale balance is fine to show, never to decide on.

Why not CockroachDB: it writes on every node and survives node loss with no failover step, but it
is Serializable only, so a busy merchant's requests would abort with `40001` and retry instead of
queueing on a row lock; every write pays a consensus round; my advisory lock needs replacing; and it
needs an enterprise licence check. It earns that cost only when writes must be accepted in several
regions at once.

## Appendix D: memory, observed in Grafana and what it points to

Measured on my kind fleet on 2026-11-05, idle, 8 pods, read through Grafana's Prometheus
(`docs/observability/verification/2026-11-05-memory-snapshots.txt`). Each cell: SerialGC before 0.4.0,
then G1 with compact object headers in 0.4.0.

| MiB per pod    | Working set | Heap committed | Class metadata |
|----------------|-------------|----------------|----------------|
| conversion-svc | 375 to 351  | 129 to 96      | 118 to 124     |
| ledger-svc     | 367 to 348  | 123 to 93      | 117 to 122     |
| gateway-svc    | 360 to 317  | 85 to 81       | 107 to 111     |
| eureka-svc     | 360 to 329  | 97 to 87       | 96 to 100      |
| config-svc     | 374 to 272  | 83 to 72       | 75 to 78       |
| 8 pods         | 2938 to 2633 | -305 MiB     | -10%           |

- **What I observed:** conversion-svc is the heaviest pod. G1 cut its committed heap by a quarter,
  but class metadata (Metaspace plus class space) is now its largest pool and G1 did not touch it.
- **What it points to**, all estimates until I port one service:
    - Spring AOT plus the JDK 25 AOT cache: about 5 to 10% less memory, mostly faster startup; the
      bean graph is fixed at build time, so served config can no longer switch a bean.
    - Quarkus JVM for conversion-svc first: build-time wiring loads fewer classes, about 351 to
      210 to 245 MiB per pod.
    - Kubernetes discovery and ConfigMaps instead of eureka-svc and config-svc: 601 MiB, an
      architecture change rather than a framework one.
    - Native images: 40 to 110 MiB per pod, at the cost of Serial GC on GraalVM CE, lower peak
      throughput and slow builds.
- **My next step:** port conversion-svc and compare its working set under the sweep's traffic. The
  1 GiB limit is not under pressure, so the gain is pods per node, not stability.

## Appendix E: security gates, SAST, SCA, DAST and live probes

| Kind | Tool | Fails when |
|------|------|------------|
| SAST | SpotBugs 4.10.5 with Find Security Bugs 1.14.0 | any finding without a written exclusion; `checkSpotbugsReport` fails if Find Security Bugs did not run |
| SAST | Checkstyle 14.3.0 | any violation |
| SAST | gitleaks 8.30.1 and trivy secret scan (`checkSecrets`) | a secret anywhere in history or the tree, or no evidence of the scan |
| SAST | SonarQube, opt-in | runs only with `SONAR_TOKEN` |
| SCA | trivy 0.74.0 (`checkSupplyChain`): images, CycloneDX SBOMs, misconfiguration | a fixable HIGH or CRITICAL in a service layer |
| SCA | `checkVersions`, on demand since 0.5.0: Maven modules on the runtime classpaths against OSV, version pins against Maven Central and the Gradle Plugin Portal | an advisory with a fix that is HIGH, CRITICAL or carries no severity, on a shipped module; an unreachable source is exit 2, never a pass |
| Format | Spotless 8.11.0 (`spotlessCheck` in `check`, `spotlessApply` to fix), not a security scan | an import-order, unused-import or whitespace violation |
| Live | kind sweep `policy` | an unnamed pod reaches Postgres, or ledger-svc cannot |
| Live | kind sweep `rbac`: the chaos monkey's ServiceAccount, via `kubectl auth can-i` | it can touch monitoring, read a Secret, patch a Deployment or create a pod; judged only after it is shown able to delete app and data pods |
| Live | gateway tests and the sweep's burst | `/internal/**` not 404; Redis down not 503; a burst above capacity plus refill |
| DAST | ZAP 2.17.0 API scan (`security/dast.sh`): every operation in the public specs, through the gateway | any High alert (throttled under the per-merchant limit, or it reads 429s as SQL injection) |
| DAST | Schemathesis 4.32.4: valid and invalid requests from the same specs | any 5xx answer |
| DAST | Nuclei v3.11.1: Spring, actuator, exposure and misconfiguration templates | any finding at low or above |

- The live probes test controls I designed; the DAST scanners look for the ones I did not think of,
  and on their first run they found two:
  - **Any HTTP method reached the gateway's proxy client.** QUERY and FOO answered 500 with a stack
    trace per request, PATCH and DELETE were forwarded. Fixed with `Method=GET,HEAD,POST` on both
    merchant routes; anything else now stops at the edge. The independent review caught that my first
    version left HEAD out, which RFC 9110 requires wherever GET is served.
  - **A multipart Content-Type without a boundary answered 500 on every route** of ledger and
    conversion, and the same class turned up in config and eureka when I swept every service. Nothing
    takes multipart, so parsing is off: reads ignore the header, JSON writes answer 415.
  - The second was hidden behind the first: Schemathesis reports one failure per operation.
- Why I trust a clean run: `dast.sh control` scans a stub with one planted defect per scanner (an SQL
  error, a 500, an exposed `/actuator/env`) and fails unless each is found by name. And a reach probe,
  sent through the scanners' own route, fails the run if no service answered.
- It runs only against loopback, before a release, because it needs a live fleet and writes rows.
