# Controller API flow diagrams

The request flow of every public and internal endpoint of the Ezeebit wallet, drawn from the code on
`dev` on 2026-11-06. Class names are the real classes; read them in
[project-layout.md](../project-layout.md). Every diagram is plain text on fixed columns: a lifeline
that does not line up is a defect in this file.

## 1. High-level architecture

```text
                        ┌─────────────────────┐                         ┌─────────────────────┐
                        │ Client (merchant)   │                         │ Money-in rail       │
                        └──────────┬──────────┘                         └──────────┬──────────┘
                                   │ HTTPS                                         │ POST /internal/v1/deposits
                                   │ /api/v1/merchants/{id}/...                    │ direct, never via the gateway
                                   ▼                                               │
                      ┌──────────────────────────────────────────┐                 │
 ┌───────────────┐    │ gateway-svc :8080                        │                 │
 │ Redis         │    │  RateLimitFilter (Redis, per merchant)   │                 │
 │ one Bucket4j  │◄───┤  route table, RewritePath                │                 │
 │ bucket per    │    │  X-Ezeebit-Gateway proof header          │                 │
 │ merchant      │    │  RetryUnsent=3 (connect failures only)   │                 │
 └───────────────┘    └───────┬─────────────────────────────┬────┘                 │
                              │ lb://conversion-svc         │ lb://ledger-svc      │
                              │ (Eureka)                    │ (Eureka)             │
                              ▼                             ▼                      ▼
  ┌────────────────────────────────────────────┐        ┌───────────────────────────────────────┐
  │ conversion-svc :8082                       │        │ ledger-svc :8081 HTTP, :9081 gRPC     │
  │  QuoteCtrl, ConversionCtrl                 │        │  BalanceCtrl, StatementCtrl           │
  │  PendingConversionSweeper (every 10 s)     │ gRPC   │  DepositCtrl (/internal/** only)      │
  │  LedgerGrpcClient (Resilience4j)           ├───────►│  ConversionPostingRpc (Post)          │
  └──────┬──────────────┬──────────────┬───────┘        │  ConversionSettlementRpc (Find, Void) │
         │              │              │                └───────────────────┬───────────────────┘
         │              │              │                                    │
         ▼              ▼              ▼                                    ▼
┌───────────────┐ ┌───────────────┐ ┌───────────────┐               ┌───────────────┐
│ rate feed     │ │ Kafka         │ │ PostgreSQL    │               │ PostgreSQL    │
│ (WireMock)    │ │ dead letters  │ │ db conversion │               │ db ledger     │
└───────────────┘ └───────────────┘ └───────────────┘               └───────────────┘
```

- Each service owns its database: `ledger` and `conversion` live on one PostgreSQL server with
  separate users, and no service reads the other's tables.
- conversion-svc never calls the ledger over HTTP. It calls the two gRPC services in
  `contracts/ledger/v1/conversion_postings.proto` and resolves `discovery:///ledger-svc` to every
  instance's gRPC port through Eureka.
- Not drawn: `eureka-svc` (:8761) registers gateway-svc, ledger-svc and conversion-svc;
  `config-svc` (:8888) serves every service's `served/*.yml`.

## 2. Gateway route table

From `services/config-svc/src/main/resources/served/gateway-svc.yml`. Every merchant route accepts
`GET`, `HEAD` and `POST`, rewrites `/api/v1/(rest)` to `/v1/(rest)`, replaces the
`X-Ezeebit-Gateway` proof header and resends a request only when its connect failed (`RetryUnsent=3`).

| Client path | Backend | Backend path |
|---|---|---|
| `/api/v1/merchants/*/balances/**` | ledger-svc | `/v1/merchants/{id}/balances` |
| `/api/v1/merchants/*/entries/**` | ledger-svc | `/v1/merchants/{id}/entries` |
| `/api/v1/merchants/*/quotes/**` | conversion-svc | `/v1/merchants/{id}/quotes` |
| `/api/v1/merchants/*/conversions/**` | conversion-svc | `/v1/merchants/{id}/conversions[/{conversionId}]` |
| `GET /v3/api-docs/ledger` | ledger-svc | `SetPath /v3/api-docs/public`, not rate limited |
| `GET /v3/api-docs/conversion` | conversion-svc | `SetPath /v3/api-docs/public`, not rate limited |
| `/internal/**` | not routed | the money-in rail calls ledger-svc directly |

The services answer `403 GATEWAY_REQUIRED` (`GatewayProofFilter`) to a public route that arrives
without the gateway's proof header.

## 3. Get balances

```text
 Client                              gateway-svc                           ledger-svc                               ledger DB
    │                                     │                                     │                                       │
    │ GET /api/v1/merchants/{id}/         │                                     │                                       │
    │ balances                            │                                     │                                       │
    ├────────────────────────────────────►│                                     │                                       │
    │                                     │ RateLimitFilter: 1 token from       │                                       │
    │                                     │ the merchant's Redis bucket         │                                       │
    │                                     │ RewritePath /api/v1/ to /v1/        │                                       │
    │                                     │ add X-Ezeebit-Gateway proof         │                                       │
    │                                     │ GET /v1/merchants/{id}/balances     │                                       │
    │                                     ├────────────────────────────────────►│                                       │
    │                                     │                                     │ GatewayProofFilter: proof ok          │
    │                                     │                                     │ BalanceCtrl.balances()                │
    │                                     │                                     │ BalanceService.balances()             │
    │                                     │                                     │ AccountRepo.merchantAccounts()        │
    │                                     │                                     ├──────────────────────────────────────►│
    │                                     │                                     │ one account row per currency          │
    │                                     │                                     │◄──────────────────────────────────────┤
    │                                     │ 200 ApiResponse<List<BalanceResp>>  │                                       │
    │                                     │◄────────────────────────────────────┤                                       │
    │ 200 OK                              │                                     │                                       │
    │◄────────────────────────────────────┤                                     │                                       │
    │ {"data": [                          │                                     │                                       │
    │   {"currency": "USDT",              │                                     │                                       │
    │    "balance": "53.982730"}]}        │                                     │                                       │
    │                                     │                                     │                                       │
```

## 4. Get statement entries

```text
 Client                              gateway-svc                           ledger-svc                               ledger DB
    │                                     │                                     │                                       │
    │ GET /api/v1/merchants/{id}/         │                                     │                                       │
    │ entries?limit=100&after=42          │                                     │                                       │
    ├────────────────────────────────────►│                                     │                                       │
    │                                     │ RateLimitFilter, RewritePath,       │                                       │
    │                                     │ proof header                        │                                       │
    │                                     │ GET /v1/merchants/{id}/entries      │                                       │
    │                                     │ ?limit=100&after=42                 │                                       │
    │                                     ├────────────────────────────────────►│                                       │
    │                                     │                                     │ StatementCtrl.entries()               │
    │                                     │                                     │ StatementService.statement()          │
    │                                     │                                     │ limit 1 to 500, default 100           │
    │                                     │                                     │ after: last seq seen, default 0       │
    │                                     │                                     │ EntryRepo.statement(filter)           │
    │                                     │                                     │ seq > after, oldest first,            │
    │                                     │                                     │ optional currency, from, to           │
    │                                     │                                     ├──────────────────────────────────────►│
    │                                     │                                     │ up to limit statement lines           │
    │                                     │                                     │◄──────────────────────────────────────┤
    │                                     │                                     │ nextCursor: last seq when the         │
    │                                     │                                     │ page is full, otherwise null          │
    │                                     │ 200 ApiResponse<StatementResp>      │                                       │
    │                                     │◄────────────────────────────────────┤                                       │
    │ 200 OK                              │                                     │                                       │
    │◄────────────────────────────────────┤                                     │                                       │
    │ {"data": {"entries": [              │                                     │                                       │
    │   {"seq": 43, "type": "DEPOSIT",    │                                     │                                       │
    │    "amount": "1500.00",             │                                     │                                       │
    │    "balanceAfter": "1500.00"}],     │                                     │                                       │
    │   "nextCursor": "142"}}             │                                     │                                       │
    │                                     │                                     │                                       │
```

## 5. Create a quote

```text
 Client                              gateway-svc                         conversion-svc                             rate feed             conversion DB
    │                                     │                                     │                                       │                       │
    │ {"sell": "ZAR", "buy": "USDT",      │                                     │                                       │                       │
    │   "sellAmount": "1000.00"}          │                                     │                                       │                       │
    │ POST /api/v1/merchants/{id}/        │                                     │                                       │                       │
    │ quotes                              │                                     │                                       │                       │
    ├────────────────────────────────────►│                                     │                                       │                       │
    │                                     │ RateLimitFilter, RewritePath,       │                                       │                       │
    │                                     │ proof header                        │                                       │                       │
    │                                     │ POST /v1/merchants/{id}/quotes      │                                       │                       │
    │                                     ├────────────────────────────────────►│                                       │                       │
    │                                     │                                     │ QuoteCtrl.quote()                     │                       │
    │                                     │                                     │ QuoteService.quote()                  │                       │
    │                                     │                                     │ exactly one of sellAmount and         │                       │
    │                                     │                                     │ buyAmount; known pair                 │                       │
    │                                     │                                     │ RateCheck through RateSource port     │                       │
    │                                     │                                     │ RateClient: fetch the rate (HTTP)     │                       │
    │                                     │                                     ├──────────────────────────────────────►│                       │
    │                                     │                                     │ market rate and its timestamp         │                       │
    │                                     │                                     │◄──────────────────────────────────────┤                       │
    │                                     │                                     │ RateValidator: rate passes RateRules  │                       │
    │                                     │                                     │ QuotePricer: apply the spread         │                       │
    │                                     │                                     │ expiresAt = now + 15 s (quote-ttl)    │                       │
    │                                     │                                     │ QuoteWriter: INSERT quote             │                       │
    │                                     │                                     ├───────────────────────────────────────┼──────────────────────►│
    │                                     │ 201 ApiResponse<QuoteResp>          │                                       │                       │
    │                                     │◄────────────────────────────────────┤                                       │                       │
    │ 201 Created                         │                                     │                                       │                       │
    │◄────────────────────────────────────┤                                     │                                       │                       │
    │ {"data": {"quoteId": "...",         │                                     │                                       │                       │
    │   "buyAmount": "53.982730",         │                                     │                                       │                       │
    │   "rate": "0.054254",               │                                     │                                       │                       │
    │   "appliedRate": "0.05398273",      │                                     │                                       │                       │
    │   "spreadBps": 50,                  │                                     │                                       │                       │
    │   "expiresAt": "...T10:00:15Z"}}    │                                     │                                       │                       │
    │                                     │                                     │                                       │                       │
```

The quote moves no money, so it takes no `Idempotency-Key`. It is firm for 15 seconds
(`quote-ttl`), and a conversion may post until expiry plus 30 seconds (`posting-grace`).

## 6. Create a conversion: COMPLETED

The money moves in ledger-svc, in one transaction, keyed on the conversion id. The PENDING row is
written before the ledger is called, so a crash between the two leaves a row the sweeper resolves.

```text
 Client                            gateway-svc                       conversion-svc                             conversion DB              ledger-svc                                   ledger DB
    │                                   │                                   │                                         │                         │                                           │
    │ Idempotency-Key: convert-1        │                                   │                                         │                         │                                           │
    │ {"quoteId": "5e7d2c1b-..."}       │                                   │                                         │                         │                                           │
    │ POST /api/v1/merchants/{id}/      │                                   │                                         │                         │                                           │
    │ conversions                       │                                   │                                         │                         │                                           │
    ├──────────────────────────────────►│                                   │                                         │                         │                                           │
    │                                   │ RateLimitFilter, RewritePath,     │                                         │                         │                                           │
    │                                   │ proof header                      │                                         │                         │                                           │
    │                                   │ POST /v1/merchants/{id}/          │                                         │                         │                                           │
    │                                   │ conversions                       │                                         │                         │                                           │
    │                                   ├──────────────────────────────────►│                                         │                         │                                           │
    │                                   │                                   │ ConversionCtrl.convert()                │                         │                                           │
    │                                   │                                   │ ConversionService.accept()              │                         │                                           │
    │                                   │                                   │ ConversionAcceptance.acceptOnce():      │                         │                                           │
    │                                   │                                   │ 1. ConversionReplayer.existing          │                         │                                           │
    │                                   │                                   │    (merchant, Idempotency-Key)          │                         │                                           │
    │                                   │                                   ├────────────────────────────────────────►│                         │                                           │
    │                                   │                                   │ none: this is the first call            │                         │                                           │
    │                                   │                                   │◄────────────────────────────────────────┤                         │                                           │
    │                                   │                                   │ 2. QuoteLookup.find(quoteId)            │                         │                                           │
    │                                   │                                   │    deadline = expiry + 30 s grace       │                         │                                           │
    │                                   │                                   ├────────────────────────────────────────►│                         │                                           │
    │                                   │                                   │ quote: live and unused                  │                         │                                           │
    │                                   │                                   │◄────────────────────────────────────────┤                         │                                           │
    │                                   │                                   │ 3. ConversionStarter, one tx:           │                         │                                           │
    │                                   │                                   │    INSERT conversion PENDING            │                         │                                           │
    │                                   │                                   │    mark the quote used                  │                         │                                           │
    │                                   │                                   ├────────────────────────────────────────►│                         │                                           │
    │                                   │                                   │ 4. ConversionLedgerPoster calls         │                         │                                           │
    │                                   │                                   │    LedgerGrpcClient (retry and          │                         │                                           │
    │                                   │                                   │    breaker per operation)               │                         │                                           │
    │                                   │                                   │ gRPC ConversionPostings.Post            │                         │                                           │
    │                                   │                                   │ (conversion id, legs, deadline)         │                         │                                           │
    │                                   │                                   ├─────────────────────────────────────────┼────────────────────────►│                                           │
    │                                   │                                   │                                         │                         │ ConversionPostingRpc                      │
    │                                   │                                   │                                         │                         │ ConversionPostingService                  │
    │                                   │                                   │                                         │                         │ ConversionPostingRecorder                 │
    │                                   │                                   │                                         │                         │ ConversionPoster, ONE transaction:        │
    │                                   │                                   │                                         │                         │ 1. INSERT journal CONVERSION              │
    │                                   │                                   │                                         │                         │    unique (merchant, type, conv id)       │
    │                                   │                                   │                                         │                         │ 2. lock both accounts, id order           │
    │                                   │                                   │                                         │                         │ 3. INSERT 4 entries: merchant sell,       │
    │                                   │                                   │                                         │                         │    FX sell, FX buy, merchant buy          │
    │                                   │                                   │                                         │                         │ 4. UPDATE both merchant balances          │
    │                                   │                                   │                                         │                         │ 5. deadline passed: roll back             │
    │                                   │                                   │                                         │                         ├──────────────────────────────────────────►│
    │                                   │                                   │                                         │                         │ COMMIT                                    │
    │                                   │                                   │                                         │                         │◄──────────────────────────────────────────┤
    │                                   │                                   │ PostingResult: POSTED                   │                         │                                           │
    │                                   │                                   │◄────────────────────────────────────────┼─────────────────────────┤                                           │
    │                                   │                                   │ 5. ConversionSettler: UPDATE            │                         │                                           │
    │                                   │                                   │    PENDING to COMPLETED (CAS)           │                         │                                           │
    │                                   │                                   ├────────────────────────────────────────►│                         │                                           │
    │                                   │ 201 ApiResponse<ConversionResp>   │                                         │                         │                                           │
    │                                   │◄──────────────────────────────────┤                                         │                         │                                           │
    │ 201 Created                       │                                   │                                         │                         │                                           │
    │◄──────────────────────────────────┤                                   │                                         │                         │                                           │
    │ {"data": {"conversionId": "...",  │                                   │                                         │                         │                                           │
    │   "status": "COMPLETED",          │                                   │                                         │                         │                                           │
    │   "sellAmount": "1000.00",        │                                   │                                         │                         │                                           │
    │   "buyAmount": "53.982730"}}      │                                   │                                         │                         │                                           │
    │                                   │                                   │                                         │                         │                                           │
```

### 6a. Refused by the ledger: REJECTED

```text
 Client                            gateway-svc                       conversion-svc                             conversion DB              ledger-svc                                   ledger DB
    │                                   │                                   │                                         │                         │                                           │
    │                                   │                                   │ steps 1 to 4 as in section 6            │                         │                                           │
    │                                   │                                   │ gRPC ConversionPostings.Post            │                         │                                           │
    │                                   │                                   ├─────────────────────────────────────────┼────────────────────────►│                                           │
    │                                   │                                   │                                         │                         │ ConversionPoster: not enough in           │
    │                                   │                                   │                                         │                         │ the sell account, roll back               │
    │                                   │                                   │                                         │                         ├──────────────────────────────────────────►│
    │                                   │                                   │                                         │                         │ RefusalRecorder: INSERT journal           │
    │                                   │                                   │                                         │                         │ REFUSED, so a retry stays refused         │
    │                                   │                                   │                                         │                         ├──────────────────────────────────────────►│
    │                                   │                                   │ refused: INSUFFICIENT_FUNDS             │                         │                                           │
    │                                   │                                   │◄────────────────────────────────────────┼─────────────────────────┤                                           │
    │                                   │                                   │ ConversionSettler: UPDATE to            │                         │                                           │
    │                                   │                                   │    REJECTED, reason recorded            │                         │                                           │
    │                                   │                                   ├────────────────────────────────────────►│                         │                                           │
    │                                   │                                   │ ConversionReader.answer() throws        │                         │                                           │
    │                                   │                                   │ the reason's ErrorCode                  │                         │                                           │
    │                                   │ 422 problem+json                  │                                         │                         │                                           │
    │                                   │◄──────────────────────────────────┤                                         │                         │                                           │
    │ 422 INSUFFICIENT_FUNDS            │                                   │                                         │                         │                                           │
    │◄──────────────────────────────────┤                                   │                                         │                         │                                           │
    │                                   │                                   │                                         │                         │                                           │
```

`BALANCE_LIMIT_EXCEEDED` also answers 422; a refused input, a passed deadline or a void answers
409 `CONVERSION_REJECTED`.

### 6b. No answer from the ledger: PENDING, then the sweeper

```text
 Client                            gateway-svc                       conversion-svc                             conversion DB             Kafka              ledger-svc
    │                                   │                                   │                                         │                     │                     │
    │                                   │                                   │ steps 1 to 4 as in section 6:           │                     │                     │
    │                                   │                                   │ the row is PENDING already              │                     │                     │
    │                                   │                                   │ gRPC ConversionPostings.Post            │                     │                     │
    │                                   │                                   ├─────────────────────────────────────────┼─────────────────────┼────────────────────►│
    │                                   │                                   │ no answer: timeout, UNAVAILABLE         │                     │                     │
    │                                   │                                   │ or the breaker is open;                 │                     │                     │
    │                                   │                                   │ LedgerGuard retries run out             │                     │                     │
    │                                   │                                   │ one dead letter (record and             │                     │                     │
    │                                   │                                   │ alert only, nothing consumes it)        │                     │                     │
    │                                   │                                   ├─────────────────────────────────────────┼────────────────────►│                     │
    │                                   │                                   │ answer UNKNOWN: row stays PENDING       │                     │                     │
    │                                   │ 202 ApiResponse<ConversionResp>   │                                         │                     │                     │
    │                                   │◄──────────────────────────────────┤                                         │                     │                     │
    │ 202 Accepted                      │                                   │                                         │                     │                     │
    │◄──────────────────────────────────┤                                   │                                         │                     │                     │
    │ {"data": {"status": "PENDING"}}   │                                   │                                         │                     │                     │
    │ poll GET .../conversions/{id}     │                                   │                                         │                     │                     │
    │                                   │                                   │                                         │                     │                     │
    │                                   │                                   │ later: PendingConversionSweeper,        │                     │                     │
    │                                   │                                   │ every 10 s on every replica             │                     │                     │
    │                                   │                                   │ PendingResolver, one transaction:       │                     │                     │
    │                                   │                                   │ claim one due PENDING row               │                     │                     │
    │                                   │                                   │ (deadline + 30 s passed)                │                     │                     │
    │                                   │                                   │ FOR UPDATE OF c SKIP LOCKED             │                     │                     │
    │                                   │                                   ├────────────────────────────────────────►│                     │                     │
    │                                   │                                   │ the row, locked                         │                     │                     │
    │                                   │                                   │◄────────────────────────────────────────┤                     │                     │
    │                                   │                                   │ gRPC ConversionSettlement.Find          │                     │                     │
    │                                   │                                   ├─────────────────────────────────────────┼─────────────────────┼────────────────────►│
    │                                   │                                   │ POSTED, absent, or no answer            │                     │                     │
    │                                   │                                   │◄────────────────────────────────────────┼─────────────────────┼─────────────────────┤
    │                                   │                                   │ POSTED: settle COMPLETED                │                     │                     │
    │                                   │                                   │ absent: gRPC Void first, so a late      │                     │                     │
    │                                   │                                   │   Post is refused; then REJECTED        │                     │                     │
    │                                   │                                   │ no answer: leave PENDING for the        │                     │                     │
    │                                   │                                   │   next sweep                            │                     │                     │
    │                                   │                                   │ UPDATE status (CAS), COMMIT             │                     │                     │
    │                                   │                                   ├────────────────────────────────────────►│                     │                     │
    │                                   │                                   │                                         │                     │                     │
```

## 7. Get a conversion

```text
 Client                              gateway-svc                         conversion-svc                             conversion DB
    │                                     │                                     │                                         │
    │ GET /api/v1/merchants/{id}/         │                                     │                                         │
    │ conversions/{conversionId}          │                                     │                                         │
    ├────────────────────────────────────►│                                     │                                         │
    │                                     │ RateLimitFilter, RewritePath,       │                                         │
    │                                     │ proof header                        │                                         │
    │                                     │ GET /v1/merchants/{id}/             │                                         │
    │                                     │ conversions/{conversionId}          │                                         │
    │                                     ├────────────────────────────────────►│                                         │
    │                                     │                                     │ ConversionCtrl.find()                   │
    │                                     │                                     │ ConversionReader.find()                 │
    │                                     │                                     │ ConversionRepo.find(id, merchant)       │
    │                                     │                                     ├────────────────────────────────────────►│
    │                                     │                                     │ the row, or none                        │
    │                                     │                                     │◄────────────────────────────────────────┤
    │                                     │                                     │ none: 404 CONVERSION_NOT_FOUND          │
    │                                     │                                     │ REJECTED: 200 with status and reason    │
    │                                     │ 200 ApiResponse<ConversionResp>     │                                         │
    │                                     │◄────────────────────────────────────┤                                         │
    │ 200 OK                              │                                     │                                         │
    │◄────────────────────────────────────┤                                     │                                         │
    │ {"data": {"conversionId": "...",    │                                     │                                         │
    │   "status": "COMPLETED",            │                                     │                                         │
    │   "reason": null,                   │                                     │                                         │
    │   "completed": "...T10:00:05Z"}}    │                                     │                                         │
    │                                     │                                     │                                         │
```

## 8. Internal deposit (money-in rail)

```text
 Money-in rail                                ledger-svc                                   ledger DB
       │                                           │                                           │
       │ Idempotency-Key: rail-transfer-1          │                                           │
       │ {"merchantId": "3f2c8a10-...",            │                                           │
       │   "currency": "ZAR",                      │                                           │
       │   "amount": "1500.00",                    │                                           │
       │   "reference": "rail transfer 1"}         │                                           │
       │ POST /internal/v1/deposits                │                                           │
       │ direct: the gateway never routes it       │                                           │
       ├──────────────────────────────────────────►│                                           │
       │                                           │ DepositCtrl.deposit()                     │
       │                                           │ DepositService.deposit()                  │
       │                                           │ DepositIntake: currency, command          │
       │                                           │ DepositRecorder                           │
       │                                           │ DepositPoster, ONE transaction:           │
       │                                           │ 1. INSERT journal DEPOSIT                 │
       │                                           │    unique (merchant, type, key)           │
       │                                           │ 2. open the merchant account              │
       │                                           │    if absent, lock it                     │
       │                                           │ 3. INSERT 2 entries: merchant             │
       │                                           │    credit, deposit clearing               │
       │                                           │ 4. UPDATE the merchant balance            │
       │                                           ├──────────────────────────────────────────►│
       │                                           │ COMMIT                                    │
       │                                           │◄──────────────────────────────────────────┤
       │                                           │ duplicate key: DepositReplayer            │
       │                                           │   same request hash: first answer         │
       │                                           │   other body: 422                         │
       │                                           │   IDEMPOTENCY_KEY_REUSED                  │
       │                                           │ DepositReader: answer from rows           │
       │ 201 ApiResponse<DepositResp>              │                                           │
       │◄──────────────────────────────────────────┤                                           │
       │ {"data": {"journalId": "...",             │                                           │
       │   "amount": "1500.00",                    │                                           │
       │   "balanceAfter": "1500.00",              │                                           │
       │   "reference": "rail transfer 1"}}        │                                           │
       │                                           │                                           │
```

## 9. Who calls whom

| From | To | Protocol | What |
|---|---|---|---|
| Client | gateway-svc | HTTPS | every public route |
| gateway-svc | Redis | Redis protocol | one rate-limit bucket per merchant, shared by every replica |
| gateway-svc | ledger-svc, conversion-svc | HTTP, `lb://` through Eureka | routed requests with the proof header |
| conversion-svc | ledger-svc | gRPC | `ConversionPostings.Post`, `ConversionSettlement.Find` and `Void` |
| conversion-svc | rate feed | HTTP | the market rate for a quote |
| conversion-svc | Kafka | Kafka | `ezeebit.conversion.ledger.deadletter`, one record per ledger call given up on |
| ledger-svc, conversion-svc | PostgreSQL | JDBC | databases `ledger` and `conversion`, one each |
| money-in rail | ledger-svc | HTTP | `POST /internal/v1/deposits`, never through the gateway |

## 10. Exception handling

ledger-svc and conversion-svc carry the same four advices in `api/advice/`. Spring consults them in
`@Order` sequence and the first one with a matching `@ExceptionHandler` renders the error as an
RFC 9457 problem; `ErrorStatus` is the one table from error code to HTTP status.

```text
┌────────────────────────────────────────┐
│ ValidationExceptionHandler             │  bind, header, body, type errors
│ @Order(HIGHEST_PRECEDENCE)             │
└───────────────────┬────────────────────┘
                    ▼
┌────────────────────────────────────────┐
│ BusinessExceptionHandler               │  LedgerException / ConversionException
│ @Order(1)                              │
└───────────────────┬────────────────────┘
                    ▼
┌────────────────────────────────────────┐
│ RoutingExceptionHandler                │  no route, version, method, media
│ @Order(2)                              │
└───────────────────┬────────────────────┘
                    ▼
┌────────────────────────────────────────┐
│ GlobalExceptionHandler                 │  anything else: 500 INTERNAL_ERROR
│ @Order(LOWEST_PRECEDENCE)              │
└────────────────────────────────────────┘
```

gateway-svc has its own two:

- `EdgeRefusalAdvice`: 400 `MALFORMED_PATH` or `INVALID_MERCHANT_ID`, 429 `RATE_LIMITED`,
  503 `RATE_LIMITER_UNAVAILABLE`.
- `UpstreamUnavailableAdvice`: 503 `UPSTREAM_UNAVAILABLE` when the request was never sent,
  504 `UPSTREAM_OUTCOME_UNKNOWN` when it was sent and no answer came within 10 s.

## 11. Idempotency

There is no separate idempotency table: the key lives on the row the request creates, under a
unique constraint, so the first write and the duplicate check are one operation.

```text
 Caller                                  service                                    database
    │                                       │                                           │
    │ first call                            │                                           │
    │ POST, Idempotency-Key: abc            │                                           │
    ├──────────────────────────────────────►│                                           │
    │                                       │ INSERT the row carrying key abc           │
    │                                       │ (unique constraint on the key)            │
    │                                       ├──────────────────────────────────────────►│
    │                                       │ inserted, committed                       │
    │                                       │◄──────────────────────────────────────────┤
    │ 201, the first answer                 │                                           │
    │◄──────────────────────────────────────┤                                           │
    │                                       │                                           │
    │ retry: same key, same body            │                                           │
    │ POST, Idempotency-Key: abc            │                                           │
    ├──────────────────────────────────────►│                                           │
    │                                       │ INSERT the row carrying key abc           │
    │                                       ├──────────────────────────────────────────►│
    │                                       │ duplicate key                             │
    │                                       │◄──────────────────────────────────────────┤
    │                                       │ stored request matches:                   │
    │                                       │ answer from the stored rows               │
    │ 201, the same answer again            │                                           │
    │◄──────────────────────────────────────┤                                           │
    │                                       │                                           │
    │ same key, different body              │                                           │
    │ POST, Idempotency-Key: abc            │                                           │
    ├──────────────────────────────────────►│                                           │
    │                                       │ stored request differs                    │
    │ 422 IDEMPOTENCY_KEY_REUSED            │                                           │
    │◄──────────────────────────────────────┤                                           │
    │                                       │                                           │
```

| Endpoint | Key | Unique constraint | Replay compares |
|---|---|---|---|
| `POST /internal/v1/deposits` | `Idempotency-Key` header | `journal (merchant_id, type, idempotency_key)` | request hash |
| `POST /conversions` | `Idempotency-Key` header | `conversion (merchant_id, idempotency_key)` | quote id |
| gRPC `ConversionPostings.Post` | conversion id | `journal (merchant_id, type, idempotency_key)` | request hash |
| `POST /quotes`, every `GET` | none | none | moves no money |

conversion-svc looks the key up before it inserts; a concurrent duplicate still lands on the
constraint and is answered as a replay.

## 12. Rate limiting

```text
 Client                              gateway-svc                                      Redis             upstream
    │                                     │                                             │                   │
    │ any /api/v1/merchants/{id}/...      │                                             │                   │
    ├────────────────────────────────────►│                                             │                   │
    │                                     │ MerchantPath: merchant id from              │                   │
    │                                     │ the path (malformed: 400)                   │                   │
    │                                     │ Bucket4j: take 1 token, atomic              │                   │
    │                                     │ compare-and-swap on the key                 │                   │
    │                                     │ gateway:ratelimit:100-20-1000:{id}          │                   │
    │                                     ├────────────────────────────────────────────►│                   │
    │                                     │ consumed, or nanos until refill             │                   │
    │                                     │◄────────────────────────────────────────────┤                   │
    │                                     │ consumed: forward                           │                   │
    │                                     │ empty: 429 RATE_LIMITED,                    │                   │
    │                                     │   Retry-After = refill wait, rounded up     │                   │
    │                                     │ CAS lost 5 times: 429                       │                   │
    │                                     │ Redis down or over 250 ms: 503              │                   │
    │                                     │   RATE_LIMITER_UNAVAILABLE, Retry-After 1   │                   │
    │                                     │ forward, only when consumed                 │                   │
    │                                     ├─────────────────────────────────────────────┼──────────────────►│
    │                                     │ answer                                      │                   │
    │                                     │◄────────────────────────────────────────────┼───────────────────┤
    │ answer, or 429 / 503                │                                             │                   │
    │◄────────────────────────────────────┤                                             │                   │
    │                                     │                                             │                   │
```

Capacity 100 tokens per merchant, refilled at 20 a second (1200 a minute), all served settings
(`RATE_LIMIT_*`). The limits are part of the Redis key, so changing them starts fresh buckets
rather than reusing ones built under the old limits.
