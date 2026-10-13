# Project layout

A map for finding any class without searching: the repository to service level, then each service's
packages with every class in them, what each package is for, and which packages depend on which. The
trees and counts below are generated from the code (commands in [Measuring it](#measuring-it)), not
drawn from memory.

## Repository

```
ezeebit-wallet/
├── services/        the five Spring Boot services, one Gradle project each (below)
├── docs/            quality gates, supply chain, CI, observability, and this layout
├── config/          checkstyle/checkstyle.xml and spotbugs/exclude.xml, read by every service
├── security/        the supply-chain gate: pinned scanners, scan.sh, report.py, trivy-ignore.yaml
├── docker/          local backing services: Postgres init SQL, WireMock rate feed, Grafana LGTM
├── observability/   OTLP settings and the logback appender, compiled into every service
├── .circleci/       the CI job: the same Gradle commands a laptop runs
├── build.gradle     shared conventions: test tiers, quality gates, image builds
└── compose.yaml, fleet.sh, settings.gradle, gradle.properties, README.md, ARCHITECTURE.md, SOLUTION.md
```

| Service | Package root | One line |
|---|---|---|
| `services/ledger-svc` | `io.ezeebit.ledger` | Task 1: double-entry ledger, deposits, balances, statement, conversion postings |
| `services/conversion-svc` | `io.ezeebit.conversion` | Task 2: quotes and conversions, calls the rate feed and the ledger |
| `services/gateway-svc` | `io.ezeebit.gateway` | edge: routes, Redis rate limit per merchant, path rewrite, proof header |
| `services/config-svc` | `io.ezeebit.config` | Spring Cloud Config Server; `src/main/resources/served/*.yml` is the fleet's configuration |
| `services/eureka-svc` | `io.ezeebit.eureka` | service registry; one application class |

Every Java service uses the same layer-first packages, so a package name means the same thing in
each one:

| Package | Holds | Class suffix |
|---|---|---|
| `ctl/` | controllers: validate, delegate, wrap the envelope | `*Ctrl` |
| `api/advice/`, `api/filter/`, `api/json/`, `api/validation/` | boundary plumbing: error rendering, servlet filters, JSON reading rules, constraint annotations | `*ExceptionHandler`, `*Advice`, `*Filter`, `*Validator` |
| `dto/req/`, `dto/resp/` | request and response records (the wire contract) | `*Req`, `*Resp` |
| `service/` + `service/<use case>/` | one subpackage per use case; shared helpers stay in `service/` | `*Service` |
| `domain/` | value objects, enums, state rules; no framework imports | none |
| `exception/` | the service's exception and its `ErrorCode` (wire code and message key only) | `*Exception` |
| `data/model/`, `data/repo/` (+ `data/repo/query/`) | row records, SQL repositories, query parameter shapes | `*Entity`, `*Repo` |
| `mapper/` | entity and domain to response | `*Mapper` |
| `outbound/<context>/` | one anti-corruption layer per called service: client, caller-owned DTOs, translation | `*Client`, `*Dto`, `*Mapper` |
| `scheduler/` | timed triggers that delegate to a service | `*Sweeper`, `*Scheduler` |
| `common/log/`, `common/metrics/` | cross-cutting: log-safe text, Micrometer counters and gauges | `*Metrics` |
| `config/` (+ `config/properties/`) | Spring wiring and bound configuration | `*Config`, `*Properties` |

How to read each service: the tree lists every main class beside its package, with a note in
parentheses where the package's job is not obvious. Under it, `a -> b(n)` means classes in package
`a` have `n` import lines naming a class in package `b` (main code only). Arrows point from the user
to the used.

## ledger-svc

84 classes in 23 packages. The three use cases each own a subpackage of `service/`; the domain
depends on nothing but its own exception.

```text
io.ezeebit.ledger     LedgerApplication
├── ctl/              BalanceCtrl, ConversionPostingCtrl, DepositCtrl, DocExamples, StatementCtrl
├── api/
│   ├── doc/          OperationErrors, ProblemResponses, ProblemSchemas, PublishedPaths (the OpenAPI spec: error responses derived from ErrorStatus, /{version} named v1)
│   ├── advice/       BusinessExceptionHandler, ErrorStatus, GlobalExceptionHandler, ProblemFactory, RoutingExceptionHandler, ValidationExceptionHandler, Violation, ViolationReader (RFC 9457 errors; ErrorStatus is the code-to-status table)
│   ├── filter/       GatewayProofFilter (refuses public routes the gateway did not forward)
│   ├── json/         StrictStringDeserializer (money only from JSON strings)
│   └── validation/   OrderedWindow, OrderedWindowValidator, SupportedInstant, SupportedInstantValidator
├── dto/
│   ├── req/          ConversionPostingReq, DepositReq, StatementReq, VoidReq
│   └── resp/         ApiResponse, BalanceResp, ConversionPostingResp, DepositResp, StatementLineResp, StatementResp
├── service/          CurrencyCatalog (shared by the use cases)
│   ├── conversion/   ConversionPoster, ConversionPostingCommand, ConversionPostingService, ConversionReplayer, ConversionVoider, RefusalRecorder
│   ├── deposit/      DepositCommand, DepositPoster, DepositReader, DepositReplayer, DepositService
│   ├── idempotency/  RequestFingerprint (request hashing for replay comparison)
│   └── statement/    BalanceService, StatementService
├── mapper/           AmountFormat, BalanceMapper, ConversionPostingMapper, CurrencyMapper, EntryMapper, StatementMapper (entity and domain to response)
├── domain/           AccountType, ConversionLegs, Currency, CurrencyCode, CurrencyKind, JournalStatus, JournalType, MerchantId, Money, Posting, PostingLine (no framework imports)
├── exception/        ErrorCode, LedgerException (codes only, no HTTP status)
├── data/
│   ├── model/        AccountEntity, CurrencyEntity, EntryEntity, JournalEntity, StatementLineEntity
│   └── repo/         AccountRepo, CurrencyRepo, DbClockRepo, EntryRepo, JournalRepo
│       └── query/    StatementFilter (query parameter shapes)
├── common/
│   ├── log/          LogText (log-safe caller text)
│   └── metrics/      LedgerMetrics
└── config/           ApiVersionConfig, GatewayProofConfig, OpenApiConfig
```

Dependencies (`from -> to(import lines)`):

```text
api.advice         -> common.log(2), exception(6)
api.doc            -> api.advice(1), exception(25)
api.filter         -> exception(2)
api.validation     -> dto.req(1)
common.metrics     -> domain(1), exception(1)
config             -> api.doc(2), api.filter(1)
ctl                -> dto.req(4), dto.resp(8), service.conversion(1), service.deposit(1), service.statement(2)
data.repo          -> data.model(5), data.repo.query(1)
domain             -> exception(6)
dto.req            -> api.json(2), api.validation(3)
mapper             -> data.model(6), domain(8), dto.resp(4)
service            -> data.repo(1), domain(2), exception(2), mapper(1)
service.conversion -> common.metrics(2), data.model(5), data.repo(8), domain(20), dto.req(2), dto.resp(1), exception(9), mapper(2), service(1), service.idempotency(2)
service.deposit    -> common.metrics(2), data.model(3), data.repo(5), domain(14), dto.req(1), dto.resp(2), exception(3), mapper(2), service(1), service.idempotency(2)
service.statement  -> data.model(1), data.repo(2), data.repo.query(1), domain(2), dto.req(1), dto.resp(2), mapper(2), service(2)
```

| Package | One job | Key classes |
|---|---|---|
| `ctl` | HTTP endpoints | `DepositCtrl`, `ConversionPostingCtrl` (internal), `BalanceCtrl`, `StatementCtrl` |
| `api.doc` | shape the OpenAPI spec: each operation's error codes, their problem responses, literal v1 paths | `OperationErrors`, `ProblemResponses`, `ProblemSchemas`, `PublishedPaths` |
| `api.advice` | render every error as RFC 9457; the one code-to-status table | `ErrorStatus`, `ProblemFactory`, `BusinessExceptionHandler`, `ValidationExceptionHandler`, `RoutingExceptionHandler`, `GlobalExceptionHandler` |
| `api.filter` | refuse public routes not forwarded by the gateway | `GatewayProofFilter` |
| `api.json` | read money only from JSON strings | `StrictStringDeserializer` |
| `api.validation` | request constraints the annotations cannot say | `OrderedWindow`, `SupportedInstant` and validators |
| `dto.req`, `dto.resp` | wire contract | `DepositReq`, `ConversionPostingReq`, `StatementReq`, `VoidReq`; `ApiResponse`, `DepositResp`, `BalanceResp`, `StatementResp` |
| `service` | currency lookups shared by the use cases | `CurrencyCatalog` |
| `service.deposit` | post a deposit once per idempotency key | `DepositService`, `DepositPoster`, `DepositReplayer`, `DepositReader`, `DepositCommand` |
| `service.conversion` | post, void or refuse a conversion once per conversion id | `ConversionPostingService`, `ConversionPoster`, `ConversionVoider`, `ConversionReplayer`, `RefusalRecorder`, `ConversionPostingCommand` |
| `service.statement` | read balances and the statement | `BalanceService`, `StatementService` |
| `service.idempotency` | fingerprint a request for replay comparison | `RequestFingerprint` |
| `mapper` | entities and domain values to response records | `StatementMapper`, `EntryMapper`, `BalanceMapper`, `ConversionPostingMapper`, `CurrencyMapper`, `AmountFormat` |
| `domain` | money, balanced postings, identifiers, journal states | `Money`, `Posting`, `PostingLine`, `ConversionLegs`, `MerchantId`, `CurrencyCode`, `JournalStatus` |
| `exception` | the error a rule throws, as a code | `LedgerException`, `ErrorCode` |
| `data.repo` | SQL over the ledger tables, and the database clock | `JournalRepo`, `EntryRepo`, `AccountRepo`, `CurrencyRepo`, `DbClockRepo` |
| `data.repo.query` | statement query parameters | `StatementFilter` |
| `data.model` | row records | `JournalEntity`, `EntryEntity`, `AccountEntity`, `CurrencyEntity`, `StatementLineEntity` |
| `common.log` | make caller text safe for one log line | `LogText` |
| `common.metrics` | ledger counters | `LedgerMetrics` |
| `config` | API versioning, proof filter registration, the two spec groups | `ApiVersionConfig`, `GatewayProofConfig`, `OpenApiConfig` |

## conversion-svc

73 classes in 25 packages. Each called service has its own anti-corruption layer under
`outbound/`, which owns the translation both ways; nothing else imports another context's shape.

```text
io.ezeebit.conversion  ConversionApplication
├── ctl/               ConversionCtrl, DocExamples, QuoteCtrl
├── api/
│   ├── doc/           OperationErrors, ProblemResponses, ProblemSchemas, PublishedPaths (the OpenAPI spec: error responses derived from ErrorStatus, /{version} named v1)
│   ├── advice/        BusinessExceptionHandler, ErrorStatus, GlobalExceptionHandler, ProblemFactory, RoutingExceptionHandler, ValidationExceptionHandler, Violation, ViolationReader (RFC 9457 errors; ErrorStatus is the code-to-status table)
│   ├── filter/        GatewayProofFilter (refuses public routes the gateway did not forward)
│   ├── json/          StrictStringDeserializer (money only from JSON strings)
│   └── validation/    ExactlyOneAmount, ExactlyOneAmountValidator
├── dto/
│   ├── req/           ConversionReq, QuoteReq
│   └── resp/          ApiResponse, ConversionResp, QuoteResp
├── service/           CurrencyCatalog (shared by the use cases)
│   ├── conversion/    ConversionService, ConversionSettler, ConversionStarter
│   ├── quote/         QuoteReader, QuoteService
│   └── sweep/         PendingOverdueGauge, PendingResolver (resolves overdue PENDING conversions)
├── scheduler/         PendingConversionSweeper
├── mapper/            ConversionMapper, QuoteMapper
├── domain/            ConversionStatus, Currency, LedgerAnswer, MarketRate, Money, PairPolicy, Priced, Pricing, Quote, RateRejection, RateRules, RateValidator, RejectionReason, Settlement, Side (no framework imports)
├── exception/         ConversionException, ErrorCode (codes only, no HTTP status)
├── outbound/
│   ├── ledger/        LedgerAnswerMapper, LedgerClient, LedgerReplyDto, PostingDto, PostingMapper (ledger ACL: request, reply, translation both ways)
│   └── rates/         RateClient, RateDto, RateMapper (rate-feed ACL)
├── data/
│   ├── model/         ConversionEntity, QuoteEntity
│   └── repo/          ConversionRepo, QuoteRepo, Timestamps
├── common/
│   ├── log/           LogText (log-safe caller and upstream text)
│   └── metrics/       ConversionMetrics
└── config/            ApiVersionConfig, ClientConfig, GatewayProofConfig, OpenApiConfig
    └── properties/    ConversionProperties (served pricing policy)
```

Dependencies (`from -> to(import lines)`):

```text
api.advice         -> common.log(2), exception(6)
api.doc            -> api.advice(1), exception(28)
api.filter         -> exception(2)
api.validation     -> dto.req(1)
common.metrics     -> domain(4), exception(2)
config             -> api.doc(2), api.filter(1), config.properties(1), domain(2)
ctl                -> dto.req(2), dto.resp(4), service.conversion(1), service.quote(1)
data.repo          -> data.model(2)
domain             -> exception(7)
dto.req            -> api.json(1), api.validation(1)
mapper             -> data.model(2), domain(6), dto.resp(2)
outbound.ledger    -> common.log(1), domain(4)
outbound.rates     -> common.log(1), common.metrics(1), domain(6), exception(2)
scheduler          -> service.sweep(1)
service            -> config.properties(1), domain(2), exception(2)
service.conversion -> common.metrics(2), config.properties(1), data.model(3), data.repo(5), domain(6), dto.req(1), dto.resp(1), exception(2), mapper(1), outbound.ledger(1), service.quote(2)
service.quote      -> common.metrics(1), config.properties(1), data.model(1), data.repo(1), domain(10), dto.req(1), dto.resp(1), exception(1), mapper(2), outbound.rates(1), service(2)
service.sweep      -> common.metrics(1), config.properties(2), data.model(1), data.repo(2), domain(2), outbound.ledger(1), service.conversion(1)
```

| Package | One job | Key classes |
|---|---|---|
| `ctl` | HTTP endpoints | `QuoteCtrl`, `ConversionCtrl` |
| `api.doc` | shape the OpenAPI spec: each operation's error codes, their problem responses, literal v1 paths | `OperationErrors`, `ProblemResponses`, `ProblemSchemas`, `PublishedPaths` |
| `api.advice` | render every error as RFC 9457; the one code-to-status table | `ErrorStatus`, `ProblemFactory`, `BusinessExceptionHandler`, `ValidationExceptionHandler`, `RoutingExceptionHandler`, `GlobalExceptionHandler` |
| `api.filter` | refuse public routes not forwarded by the gateway | `GatewayProofFilter` |
| `api.json` | read money only from JSON strings | `StrictStringDeserializer` |
| `api.validation` | exactly one of sell or buy amount | `ExactlyOneAmount`, `ExactlyOneAmountValidator` |
| `dto.req`, `dto.resp` | wire contract | `QuoteReq`, `ConversionReq`; `ApiResponse`, `QuoteResp`, `ConversionResp` |
| `service` | currencies and pair policies from served configuration | `CurrencyCatalog` |
| `service.quote` | price and store a firm quote; read it back | `QuoteService`, `QuoteReader` |
| `service.conversion` | accept a quote, post to the ledger, settle the outcome | `ConversionService`, `ConversionStarter`, `ConversionSettler` |
| `service.sweep` | resolve PENDING conversions past their deadline, and report how many are overdue | `PendingResolver`, `PendingOverdueGauge` |
| `scheduler` | trigger the sweep on every replica | `PendingConversionSweeper` |
| `mapper` | domain and entities to response records and rows | `QuoteMapper`, `ConversionMapper` |
| `domain` | pricing, rate rules, quote, conversion states, ledger answers | `Pricing`, `RateValidator`, `Quote`, `Money`, `ConversionStatus`, `LedgerAnswer`, `Settlement` |
| `exception` | the error a rule throws, as a code | `ConversionException`, `ErrorCode` |
| `outbound.ledger` | ledger ACL: call it, build its posting request, translate its answers | `LedgerClient`, `PostingMapper`, `PostingDto`, `LedgerAnswerMapper`, `LedgerReplyDto` |
| `outbound.rates` | rate-feed ACL: call it, translate its rate | `RateClient`, `RateDto`, `RateMapper` |
| `data.repo` | SQL over quotes and conversions | `QuoteRepo`, `ConversionRepo`, `Timestamps` |
| `data.model` | row records | `QuoteEntity`, `ConversionEntity` |
| `common.log` | make caller and upstream text safe for one log line | `LogText` |
| `common.metrics` | conversion counters | `ConversionMetrics` |
| `config`, `config.properties` | HTTP clients, API versioning, proof filter; served pricing policy | `ClientConfig`, `ApiVersionConfig`, `GatewayProofConfig`; `ConversionProperties` |

## gateway-svc

15 classes in 10 packages. Routing itself is configuration (`served/gateway-svc.yml`); the code is
the rate limiter and the error envelope.

```text
io.ezeebit.gateway   GatewayApplication
├── api/
│   ├── advice/      EdgeRefusalAdvice, ProblemFactory, UpstreamUnavailableAdvice
│   └── filter/      MerchantPath, RateLimitFilter (MerchantPath classifies the path for the limiter)
├── service/         MerchantBuckets (one Redis bucket per merchant, shared by replicas)
├── scheduler/       StorePingScheduler (Redis store ping)
├── domain/          Admission, LimitOutcome
├── exception/       EdgeRefusalException, ErrorCode (web layer only; ErrorCode keeps its status)
├── common/
│   └── metrics/     RateLimitMetrics
└── config/          RateLimitConfig
    └── properties/  RateLimitProperties (served limits)
```

Dependencies (`from -> to(import lines)`):

```text
api.advice     -> exception(3)
api.filter     -> common.metrics(1), domain(2), exception(2), service(1)
common.metrics -> domain(1)
config         -> api.filter(1), common.metrics(1), config.properties(1), scheduler(1), service(1)
scheduler      -> service(1)
service        -> config.properties(1), domain(1)
```

| Package | One job | Key classes |
|---|---|---|
| `api.filter` | classify the request path, charge or refuse the merchant's bucket | `RateLimitFilter`, `MerchantPath` |
| `api.advice` | render edge refusals and upstream failures as RFC 9457 | `EdgeRefusalAdvice`, `UpstreamUnavailableAdvice`, `ProblemFactory` |
| `service` | one Redis bucket per merchant, shared by every replica | `MerchantBuckets` |
| `scheduler` | report whether Redis answers | `StorePingScheduler` |
| `domain` | the limiter's decisions | `Admission`, `LimitOutcome` |
| `exception` | the refusal and its code | `EdgeRefusalException`, `ErrorCode` |
| `common.metrics` | rate-limit counters | `RateLimitMetrics` |
| `config`, `config.properties` | limiter wiring; served limits | `RateLimitConfig`; `RateLimitProperties` |

`ErrorCode` here still carries its HTTP status. Only the web layer (`api.filter`, `api.advice`)
throws or reads it and `domain` does not import `exception`, so the status never reaches a rule; a
separate table would add a class and remove no dependency.

## config-svc and eureka-svc

One application class each: `ConfigApplication` (Config Server over `served/*.yml`) and
`EurekaApplication` (registry).

## Cohesion and coupling, before and after

Measured on 2026-10-13 with the commands in [Measuring it](#measuring-it): BEFORE at `4e1af9a`
(dev after the gateway edge), AFTER at the refactor. Main code only. The AFTER trees are in the
service sections above; the BEFORE trees are here.

### BEFORE trees

ledger-svc:

```text
io.ezeebit.ledger    LedgerApplication
├── ctl/             BalanceCtrl, ConversionPostingCtrl, DepositCtrl, StatementCtrl
├── api/
│   ├── advice/      BusinessExceptionHandler, GlobalExceptionHandler, ProblemFactory, RoutingExceptionHandler, ValidationExceptionHandler, Violation, ViolationReader
│   ├── filter/      GatewayProofFilter
│   └── validation/  OrderedWindow, OrderedWindowValidator, SupportedInstant, SupportedInstantValidator
├── dto/
│   ├── req/         ConversionPostingReq, DepositReq, StatementReq, VoidReq
│   └── resp/        ApiResponse, BalanceResp, ConversionPostingResp, DepositResp, StatementLineResp, StatementResp
├── service/         BalanceService, ConversionPoster, ConversionPostingCommand, ConversionPostingService, ConversionReplayer, ConversionVoider, CurrencyCatalog, DepositCommand, DepositPoster, DepositReader, DepositReplayer, DepositService, RefusalRecorder, StatementService (three use cases, a catalogue)
├── mapper/          AmountFormat, BalanceMapper, ConversionPostingMapper, CurrencyMapper, EntryMapper, StatementMapper
├── domain/          AccountType, ConversionLegs, Currency, CurrencyCode, CurrencyKind, JournalStatus, JournalType, MerchantId, Money, Posting, PostingLine
├── exception/       ErrorCode, LedgerException (ErrorCode carries the HTTP status)
├── data/
│   ├── model/       AccountEntity, CurrencyEntity, EntryEntity, JournalEntity, StatementFilter, StatementLineEntity
│   └── repo/        AccountRepo, CurrencyRepo, DbClockRepo, EntryRepo, JournalRepo
├── common/          LedgerMetrics, LogText, RequestFingerprint, StrictStringDeserializer (logging, metrics, hashing, JSON reading)
└── config/          ApiVersionConfig, GatewayProofConfig
```

conversion-svc:

```text
io.ezeebit.conversion  ConversionApplication
├── ctl/               ConversionCtrl, QuoteCtrl
├── api/
│   ├── advice/        BusinessExceptionHandler, GlobalExceptionHandler, ProblemFactory, RoutingExceptionHandler, ValidationExceptionHandler, Violation, ViolationReader
│   ├── filter/        GatewayProofFilter
│   └── validation/    ExactlyOneAmount, ExactlyOneAmountValidator
├── dto/
│   ├── req/           ConversionReq, QuoteReq
│   └── resp/          ApiResponse, ConversionResp, QuoteResp
├── service/           ConversionService, ConversionSettler, ConversionStarter, CurrencyCatalog, PendingOverdueGauge, PendingResolver, QuoteReader, QuoteService (quote, conversion, sweep, catalogue)
├── scheduler/         PendingConversionSweeper
├── mapper/            ConversionMapper, QuoteMapper (also builds the ledger's PostingDto)
├── domain/            ConversionStatus, Currency, LedgerAnswer, MarketRate, Money, PairPolicy, Priced, Pricing, Quote, RateRejection, RateRules, RateValidator, RejectionReason, Settlement, Side
├── exception/         ConversionException, ErrorCode (ErrorCode carries the HTTP status)
├── outbound/
│   ├── ledger/        LedgerAnswerMapper, LedgerClient, LedgerReplyDto, PostingDto
│   └── rates/         RateClient, RateDto, RateMapper
├── data/
│   ├── model/         ConversionEntity, QuoteEntity
│   └── repo/          ConversionRepo, QuoteRepo, Timestamps
├── common/            ConversionMetrics, LogText, StrictStringDeserializer (logging, metrics, JSON reading)
└── config/            ApiVersionConfig, ClientConfig, GatewayProofConfig
    └── properties/    ConversionProperties
```

gateway-svc:

```text
io.ezeebit.gateway   GatewayApplication
├── api/
│   ├── advice/      EdgeRefusalAdvice, ProblemFactory, UpstreamUnavailableAdvice
│   └── filter/      RateLimitFilter
├── service/         MerchantBuckets
├── scheduler/       StorePingScheduler
├── domain/          Admission, LimitOutcome
├── exception/       EdgeRefusalException, ErrorCode
├── common/          MerchantPath, RateLimitMetrics (path classification and metrics)
└── config/          RateLimitConfig
    └── properties/  RateLimitProperties
```

### Cohesion: classes per package, where it changed

| Service | Package BEFORE (classes) | AFTER (classes) | Why |
|---|---|---|---|
| ledger | `service` (14): three use cases, a catalogue and a fingerprint | `service` (1), `service.deposit` (5), `service.conversion` (6), `service.statement` (2), `service.idempotency` (1) | one use case per package |
| ledger | `common` (4): logging, metrics, request hashing, JSON reading | `common.log` (1), `common.metrics` (1); `RequestFingerprint` to `service.idempotency`; `StrictStringDeserializer` to `api.json` (1) | `common` needed "and" four times |
| ledger | `data.model` (6) held a query shape beside the rows | `data.model` (5), `data.repo.query` (1) | `StatementFilter` is a query parameter, not a row |
| ledger | `api.advice` (7) | `api.advice` (8) | `ErrorStatus` added (coupling fix below) |
| conversion | `service` (8): quote, conversion, sweep, catalogue | `service` (1), `service.quote` (2), `service.conversion` (3), `service.sweep` (2) | one use case per package |
| conversion | `common` (3): logging, metrics, JSON reading | `common.log` (1), `common.metrics` (1), `api.json` (1) | as ledger |
| conversion | `api.advice` (7), `outbound.ledger` (4) | `api.advice` (8), `outbound.ledger` (5) | `ErrorStatus`, `PostingMapper` added |
| gateway | `common` (2): path classification and metrics | `common.metrics` (1); `MerchantPath` to `api.filter` (2) | the path classifier serves only the filter |

Totals: ledger 77 to 78 classes, conversion 65 to 67, gateway 15 to 15. Largest package that
mixes use cases: 14 classes before, none after (the largest `service.*` package is 6).

### Coupling: the edges that changed

| Service | Coupling | BEFORE | AFTER |
|---|---|---|---|
| ledger | `ErrorCode` (thrown by `domain`) carries an HTTP status | yes | no: `api.advice.ErrorStatus` owns the table |
| conversion | `ErrorCode` (thrown by `domain`) carries an HTTP status | yes | no: `api.advice.ErrorStatus` owns the table |
| conversion | `mapper -> outbound.ledger` (the general mapper built the ledger's request) | 1 | 0 |
| conversion | `outbound.ledger -> domain` (the ACL translates from this context's `Quote`) | 2 | 4 |
| conversion | `service -> mapper` | 4 | 3 (`ConversionSettler` no longer needs `ConversionMapper`) |
| ledger | `service -> common` (one edge, four meanings) | 8 | `service.* -> common.metrics` 4, `service.* -> service.idempotency` 4 |
| ledger | `dto.req -> common` | 2 | `dto.req -> api.json` 2 |
| both | `api.advice -> common` | 2 | `api.advice -> common.log` 2 |
| gateway | `api.filter -> common` | 3 | `api.filter -> common.metrics` 1 (`MerchantPath` now sits beside its only user) |

Unchanged and checked: framework imports in `domain/` are 0 in all three services (conversion's
`Settlement` imports only the JSpecify `@Nullable` annotation). `domain -> exception` stays (ledger
6, conversion 7): a rule throws its code, and the code now names no HTTP status. The use-case
subpackages form no cycle: `service.conversion` and `service.deposit` use `service.idempotency`
and `service`; in conversion, `service.sweep` uses `service.conversion`, which uses `service.quote`.

The status table is pinned by `ErrorStatusTest` in each service: the full code-to-status map as it
was published before the move, so changing any status goes red (proved by remapping
`GATEWAY_REQUIRED` to 401). A new `ErrorCode` constant does not compile until `ErrorStatus` gives
it a status, because the switch has no default.

## The deliberate trade-off: services return `dto.resp`

`service.* -> dto.resp` stays (ledger 5 imports, conversion 2). The layering standard keeps
controllers lean and puts mapping in the service layer, so a use case returns the response record
and the controller only wraps it. The cost: a second delivery mechanism (a message consumer, a gRPC
endpoint) would have to take the HTTP response shape or add its own mapping. It is accepted because
both services have exactly one delivery mechanism, and the mapping itself lives in `mapper/`, so
moving the call site later is a local change.

## Where the OpenAPI wiring lives

| Piece | Home |
|---|---|
| springdoc version | `springdocVersion` in the root `gradle.properties`; `webmvc-api` starter on ledger-svc and conversion-svc, `webmvc-ui` on gateway-svc only |
| spec groups, title, server | `config/OpenApiConfig` per service: `public` (`/v1/merchants/**`) and, on ledger-svc only, `internal` (`/internal/**`) |
| error responses | `api/doc/OperationErrors` lists each operation's codes; `ProblemResponses` groups them by `ErrorStatus` into problem responses |
| springdoc settings | each service's `src/main/resources/application.yml` (`springdoc:`); the server URL is `ezeebit.openapi.public-url` in the served `application.yml` |
| who may read a spec | `GatewayProofFilter`: the public spec needs the proof header; the internal spec follows `/internal/**` |
| Swagger UI and doc routes | the served `gateway-svc.yml`: `springdoc.swagger-ui.urls` and the `*-api-docs` routes; the allow-list is in `RouteCoverageIT` |
| snapshots | `docs/openapi/*.json`, written by `./gradlew generateOpenApiSnapshots`, compared by `OpenApiContractIT` in `check` |

## Where do I put X

| I am adding | Put it in | Also touch |
|---|---|---|
| an endpoint | a method on the `*Ctrl` in `ctl/` (a new `*Ctrl` for a new resource); request and response records in `dto/req`, `dto/resp` | its spec annotations (`@Operation` with an `operationId` and summary, `@Parameter` examples, `@Schema` examples on the records); its codes in `api/doc/OperationErrors` (spec generation fails without them); the expected statuses in `OpenApiContractIT`; `./gradlew generateOpenApiSnapshots`; the gateway route in `config-svc/.../served/gateway-svc.yml` if it is public; ARCHITECTURE section 6 |
| a business rule | `domain/` when it needs no I/O; otherwise the use case's `service/<use case>/` class | a test beside it under `src/test` in the same package |
| a new use case | a new `service/<use case>/` subpackage; shared helpers stay in `service/` | its controller method; its metric |
| a query | a method on the `*Repo` in `data/repo/`; a parameter shape with several fields goes in `data/repo/query/`; a new row type in `data/model/` as `*Entity` | the integration tier (`src/acceptanceTest`) if it is new SQL |
| an outbound client | `outbound/<called service>/`: the `*Client`, caller-owned `*Dto` records, and the `*Mapper` that translates both ways | the client bean in `config/ClientConfig`; callers pass domain values, never DTOs |
| a metric | the service's `common/metrics/*Metrics` (bounded tags only) | `docs/observability.md` metric table; a panel in `docker/lgtm/dashboards/` if it should be charted |
| an error code | a constant in `exception/ErrorCode` with its message key | its status in `api/advice/ErrorStatus` (compilation fails until you do), the row in `ErrorStatusTest`, the text in `i18n/messages.properties`, the operations that can answer it in `api/doc/OperationErrors` (`OpenApiContractIT` fails while no operation lists it), then `./gradlew generateOpenApiSnapshots` |
| a migration | a new numbered changeset in `src/main/resources/db/changelog/2026/10/` (or a new month folder), included from `db.changelog-2026-10.xml` | never edit an applied changeset |
| configuration a service reads | `config/properties/*Properties` bound from `config-svc/.../served/<service>.yml` | the served file is where the values live; config-svc serves it to every replica |
| a scheduled job | a trigger in `scheduler/` that only calls a service class | it must be safe on every replica at once |

## Measuring it

Run from the repository root (zsh or bash). It prints, per service, the classes per package and
every package-to-package import edge with its count; the trees and dependency lists above carry the
same numbers. For BEFORE, run it in a checkout of `4e1af9a`.

```sh
for s in ledger conversion gateway; do
  echo "== $s-svc: classes per package"
  git ls-files "services/$s-svc/src/main/java/*.java" \
    | awk -F"/io/ezeebit/$s/" '{n=split($2,p,"/"); pkg=""; for(i=1;i<n;i++) pkg=pkg (i>1?".":"") p[i]; print (pkg==""?"(root)":pkg)}' \
    | sort | uniq -c
  echo "== $s-svc: import edges (count from -> to)"
  git ls-files "services/$s-svc/src/main/java/*.java" | xargs awk -v s="$s" '
    FNR==1 { split(FILENAME, a, "/io/ezeebit/" s "/"); n=split(a[2],p,"/"); from="";
             for(i=1;i<n;i++) from=from (i>1?".":"") p[i]; if(from=="") from="(root)" }
    /^import (static )?io\.ezeebit\./ { sub(/^import (static )?io\.ezeebit\./,""); sub(/^[a-z]+\./,"");
             m=split($0,q,"."); to=""; for(i=1;i<=m && q[i] ~ /^[a-z]/;i++) to=to (i>1?".":"") q[i];
             if (to!=from) print from " -> " to }' | sort | uniq -c
done
```
