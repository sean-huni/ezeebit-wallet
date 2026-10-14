# Conversion to Ledger over gRPC, with retries, a dead-letter topic and Slack alerts

Date: 2026-10-14. Status: design approved in conversation, awaiting review of this written spec.
Branch for implementation: `feat-ledger-grpc` (off `dev` at `87ac6c0` or later).

## 1. Goal and decisions

conversion-svc calls ledger-svc three times today, over HTTP/1.1 and JSON
(`outbound/ledger/LedgerClient.java:38`, `:43`, `:48`). This change moves those three calls to gRPC,
adds Resilience4j retries and circuit breakers, records exhausted calls on a Kafka dead-letter topic,
and pages Slack through the existing Grafana alerting.

Decisions made by Sean on 2026-10-14, each with the option rejected:

| # | Decision | Rejected |
|---|---|---|
| D1 | The dead-letter topic is **record and alert only**. Nothing consumes it automatically; the sweeper stays the only settler of a PENDING conversion | Automatic replay: a dead-lettered posting may have committed, and after its deadline the ledger refuses it anyway |
| D2 | Estate rule: **gRPC is the default for synchronous calls between services we own**; REST stays for external and public APIs; an internal REST call needs a `[!CONVENTION-OVERRIDE]` | "REST is always inferior", stated absolutely |
| D3 | WireMock (stand-in for an external rate provider) **stays REST**, gains Resilience4j only | Moving the stand-in to gRPC: it would stop resembling what it stands in for |
| D4 | Conversion finds Ledger's gRPC endpoint **through Eureka**, with round-robin over replicas | A fixed address: every call pinned to one replica |
| D5 | Ledger's REST conversion-posting endpoints are **removed in the same change** | Running both for a transition: conversion-svc is their only caller |
| D6 | Slack is paged by **Grafana alert rules on metrics**, not by the application | Each JVM posting to Slack with the webhook secret |
| D7 | Approach A: Resilience4j around a **caller-owned port**, dead letters published direct to Kafka | gRPC native retry (no exhaustion signal); an outbox table (durability not worth a table here) |

Out of scope: the deposit endpoint `POST /internal/{version}/deposits` (`DepositCtrl.java:25`) is the
money-in rail and stays REST, under D2's external-API clause. The gateway and its routes are unchanged.

## 2. Components and data flow

```
conversion-svc                                          ledger-svc
service/conversion/ConversionLedgerPoster ─┐
service/sweep/PendingLedgerCheck ──────────┤
                                           ▼
service/port/LedgerPostings            interface owned by conversion-svc
                                           ▲ implements
outbound/ledger/LedgerGrpcClient       Resilience4j Retry + CircuitBreaker per operation
        │  gRPC, HTTP/2, Eureka-resolved, round_robin
        └─────────────────────────────────────────────►  grpc/ConversionPostingGrpcService
                                                          -> ConversionPostingService (unchanged)
                                                          -> ConversionVoidService (unchanged)
outbound/deadletter/KafkaDeadLetterPublisher  -> ezeebit.conversion.ledger.deadletter
        └─ ezeebit_conversion_ledger_deadletter_total{op,reason} -> Grafana rule -> Slack
```

| Unit | One job | Depends on |
|---|---|---|
| `ledger/v1/conversion_postings.proto` | The contract: `Post`, `Find`, `Void` and their messages | nothing |
| ledger `grpc/ConversionPostingGrpcService` | Translate gRPC to the two existing services and back | the two services, an error translator |
| ledger `grpc/GrpcErrorTranslator` | Map an `ErrorCode` to a gRPC status plus `google.rpc.ErrorInfo` | nothing |
| conversion `service/port/LedgerPostings` | What the service layer needs from a ledger, in conversion's words | domain types only |
| conversion `outbound/ledger/LedgerGrpcClient` | Implement the port over gRPC, decorated by Resilience4j | generated stub, answer mapper, dead-letter port |
| conversion `outbound/ledger/EurekaNameResolver` (or the library's, if one exists) | Turn `ledger-svc` into the gRPC addresses of every instance | `DiscoveryClient` |
| conversion `service/port/DeadLetters` + `outbound/deadletter/KafkaDeadLetterPublisher` | Publish one record per exhausted call, count it, never block the caller | `KafkaTemplate`, metrics |

- The `.proto` file lives once, in a shared source set both services compile; a build check fails if
  either service holds its own copy. The generated messages replace `PostingDto`, `LedgerReplyDto`
  and their hand-written mappers.
- This also clears the open item in memory `ports-refactor-pending`: `service/` stops importing a
  concrete `outbound/` client on the ledger seam. The rate seam gets the same port in this change.
- Every new bean stays at or under 3 injected dependencies (`rules/be/java/dependency-injection.md`).
- `ConversionPostingCtrl`, its request and response DTOs, and their tests are deleted (D5).

## 3. Errors, retries and dead letters

The gateway's proxy read timeout is 10s (`served/gateway-svc.yml:6`); past it the merchant gets
`504 UPSTREAM_OUTCOME_UNKNOWN`. Retries on the request path must finish inside it.

| Operation | Deadline per attempt | Attempts | Backoff | Worst case | Caller |
|---|---|---|---|---|---|
| `Post` | 2s | 3 | 200ms, x2 | 6.6s | merchant convert request |
| `Find`, `Void` | 2s | 3 | 500ms, x2 | 7.5s | sweeper (10s interval) |

These replace `conversion.ledger.connect-timeout` and `read-timeout` in `served/conversion-svc.yml`.

Status mapping. The ledger's existing `ErrorCode` travels as `ErrorInfo.reason`; the client decides on
the code, the status only selects retry or not:

| Ledger outcome | gRPC status | Conversion outcome | Retried |
|---|---|---|---|
| Posted, or replayed for the same conversion id | OK | COMPLETED | n/a |
| `INSUFFICIENT_FUNDS`, `BALANCE_LIMIT_EXCEEDED`, `POSTING_DEADLINE_PASSED`, `CONVERSION_VOIDED` | `FAILED_PRECONDITION` | REJECTED | no |
| `CONVERSION_ALREADY_POSTED` | `ALREADY_EXISTS` | POSTED, so COMPLETED (as `LedgerAnswerMapper.java:35` today) | no |
| `POSTING_NOT_FOUND` | `NOT_FOUND` | sweeper voids | no |
| Validation codes | `INVALID_ARGUMENT` | INPUT_REFUSED (UNKNOWN on find and void, as today) | no |
| `INTERNAL_ERROR`, `CURRENCY_MISMATCH`, `UNBALANCED_POSTING` | `INTERNAL` | UNKNOWN | no |
| Unreachable, timed out | `UNAVAILABLE`, `DEADLINE_EXCEEDED` | UNKNOWN after the last attempt | yes |

Retrying `Post` is safe: a repeat for the same conversion id replays the stored result
(`ConversionReplayer`, `RefusalRecorder`). The implementation plan's first task proves this with a
test before any retry is configured.

Circuit breaker, one per operation: opens at a 50% failure rate over the last 20 calls, stays open
10s, half-open with 3 trial calls. While open a call returns UNKNOWN at once, the conversion stays
PENDING, the merchant gets the existing 504.

Dead-letter record, written when retries are exhausted or the circuit is open, never for a refusal:

| Field | Value |
|---|---|
| key | conversion id |
| `op` | `POST`, `FIND` or `VOID` |
| `conversionId`, `merchantId` | as sent |
| `request` | the request message as sent, JSON-encoded |
| `attempts`, `lastStatus`, `lastReason` | the final gRPC status and `ErrorInfo.reason`, if any |
| `at`, `traceId` | time of the last attempt, the current trace |

Publishing: asynchronous, `acks=all`, `delivery.timeout.ms=2000`, idempotent producer. A failed
publish increments `ezeebit_conversion_ledger_deadletter_publish_failed_total` and never reaches the
caller. Topic: `ezeebit.conversion.ledger.deadletter`, 1 partition and replication 1 locally,
created by compose at startup, 7-day retention.

## 4. Ports, security, observability, fleet

- Ports: `LEDGER_GRPC_PORT` (9081) and `KAFKA_PORT` (9092), each `127.0.0.1` only, added to the
  README variables table and the sample `.env`. Ledger publishes `grpcPort` in its Eureka metadata.
- Security: unchanged posture. The gRPC server binds `SERVER_ADDRESS` (127.0.0.1), plaintext,
  exactly as `/internal/**` does today (`GatewayProofFilter` guards only `/{version}/merchants/**`).
  `[!CONVENTION-OVERRIDE]` On Kubernetes this needs mTLS or a mesh plus a NetworkPolicy naming
  conversion-svc as the only client. Not built here; the README states it as a gap.
- Traces: Spring gRPC observation interceptors on both sides, so the conversion-to-ledger span
  continues into Tempo as it does today.
- Metrics: gRPC client and server metrics, Resilience4j state, Kafka producer metrics.
- Dashboards (generated, never hand-edited): a "Ledger gRPC" row (rate, errors, p95 per method,
  breaker state) and a "Dead letters" panel; `checkDashboards` regenerated.
- Alert rules, each with a `promtool` test seen red:
  - `ConversionLedgerDeadLettered` (warning): any increase of the dead-letter counter in 5m.
  - `DeadLetterPublishFailing` (critical): any increase of the publish-failed counter.
  - `KafkaAbsent` (critical): conversion's producer reports zero connections for 60s.
- Kafka image: `apache/kafka:4.3.1` (latest stable on Docker Hub, 2026-10-14, amd64 and arm64), one
  KRaft broker, a compose health check, the same tag in compose and Testcontainers (a pin test, as
  `RedisPinTest` does for Redis). The client is Boot 4.1.1's managed `kafka-clients` 4.2.1; a 4.2
  client talks to a 4.3 broker. Whether the image is Alpine-based is checked in the plan's first
  Kafka task (`/etc/os-release`); if no Alpine variant exists the reason is noted beside the pin.
- fleet.sh: Kafka under SUPPORTING > Messaging, typed Docker container, so status grows from 15 to
  16 checks; ledger's row also proves the gRPC port listens; the route check adds one real
  conversion (deposit, quote, convert for a fresh merchant id) that must end COMPLETED.

## 5. Verification

Tests, red before green, each red run kept as evidence:

1. Contract: one `.proto`, a build check that fails on a second copy.
2. Ledger gRPC server, in-process, real Postgres: post, replay, every refusal code to its status and
   reason, find, void.
3. Conversion client against a fake gRPC ledger: success on the third attempt after `UNAVAILABLE`, no
   retry on `FAILED_PRECONDITION`, breaker opens at its threshold, worst-case `Post` under 7s.
4. Dead letters, real Kafka in a container: one record per exhausted call with every field; Kafka
   stopped means the publish-failed counter moves and the caller is not slowed.
5. Two ledger instances in Eureka: calls reach both. Fewer than 2 observed is a failure.
6. `promtool test rules` for the three rules, each red-proofed.
7. Chaos: kill ledger mid-conversion; the breaker opens, a dead letter lands, Slack is paged; restart,
   the sweeper settles every PENDING conversion; audit no duplicate posting, one posting per
   conversion id at most, zero PENDING left.
8. Rates over REST: a WireMock delay forces a retry; a WireMock 500 is refused with no dead letter.

Gates before merging to `dev`, in order: the full gate
(`./gradlew --console=plain --no-build-cache --rerun-tasks clean build`, test count rising from 538);
a new dated `fleet.sh` sweep with its log committed; fleet up and down twice; a rehearsed outage with
its timing table; dashboard screenshots inspected; a critical reviewer over the whole branch.

## 6. Documentation, after the gates pass

README (API reference, variables, sample `.env`, fleet table, troubleshooting), `ARCHITECTURE.md`
(flow and contract), `docs/observability.md` (panels and rules), `EVIDENCE.md` (screenshots and the
outage timing), and the fleet diagram, split into an overview and a detail because Kafka makes 10
nodes. The estate rule (D2) goes into `~/.claude/rules/_global/microservices.md` with a memory entry
once this spec is approved, since it governs future work and not this repository's code.

## 7. Risks

| Risk | Control |
|---|---|
| No Eureka name resolver for gRPC exists in the library | Write one (small class), proven by test 5 |
| HTTP/2 connections pin to one replica | round_robin over resolved addresses; test 5 |
| Kafka down loses dead-letter records | Counted and paged critical; the sweeper still settles the money |
| A retried post lands twice | Ledger replay on conversion id; proven first, before retries are added |
| The fleet grows a broker | Health check, status row, absence alert, pinned image, like every supporting service |
