# Conversion to Ledger over gRPC: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** conversion-svc reaches ledger-svc over gRPC through Eureka, with Resilience4j retries and circuit breakers, a Kafka dead-letter topic for exhausted calls, and Grafana alerts to Slack; ledger's REST conversion-posting endpoints are removed.

**Architecture:** One `.proto` contract under `contracts/`, compiled into both services the way `observability/` is today. Ledger exposes two gRPC services over its existing posting services. Conversion's service layer depends on ports it owns (`LedgerPostings`, `RateSource`, `DeadLetters`); `outbound/` adapters implement them. A Eureka-backed gRPC name resolver plus `round_robin` spreads calls over ledger replicas.

**Tech Stack:** Spring Boot 4.1.1 (`spring-boot-starter-grpc-server`/`-client`, `spring-boot-grpc-test`, `spring-boot-starter-kafka`), grpc-java 1.83.1 and protobuf-java 4.35.1 (Boot-managed), Gradle protobuf plugin 0.10.0, Resilience4j 2.4.0 (`resilience4j-spring-boot4`), Kafka broker `apache/kafka:4.3.1`, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-10-14-ledger-grpc-design.md` (read it before Task 1).

## Global Constraints

- Branch `feat-ledger-grpc` off current `dev`. Never commit to `dev` or `main`; never move any ref but your own branch. Promotion is a separate step after the full gate.
- Java 25, tabs, `final` on every method parameter (not record components), no string `+` concatenation (use `.formatted`), no fully qualified names in code except a genuine clash (`com.google.rpc.Status` vs `io.grpc.Status`), no em dash anywhere.
- At most 3 injected dependencies per bean (`architecture/` gate fails the build at 4).
- Comments: only a WHY the code cannot say; no block over 5 lines.
- Every new version is pinned in `gradle.properties` with a "checked 2026-10-14" note and its source.
- Same Kafka image string in `compose.yaml` and Testcontainers (`kafkaImage` in `gradle.properties`, enforced by a pin test).
- Retry budgets: `Post` 2s per attempt, 3 attempts, 200ms backoff x2 (worst 6.6s); `Find`/`Void` 2s, 3 attempts, 500ms x2 (worst 7.5s). Gateway read timeout stays 10s.
- Retry only `UNAVAILABLE` and `DEADLINE_EXCEEDED`. Circuit breaker per operation: 50% over 20 calls, 10s open, 3 half-open calls.
- Dead-letter topic `ezeebit.conversion.ledger.deadletter`, key = conversion id, record-and-alert only; nothing consumes it.
- Ports: `LEDGER_GRPC_PORT` default 9081, `KAFKA_PORT` default 9092, both bound to `127.0.0.1`.
- Metrics: `ezeebit.conversion.ledger.deadletter` (tags `op`, `reason`), `ezeebit.conversion.ledger.deadletter.publish.failed` (tag `op`).
- Every behaviour test is seen RED before GREEN; paste the red output (first 5 lines) into the task's commit message body.
- Run commands from the repo root: `cd /Users/sean/env/repo/be/java/spring/ezeebit-wallet && ...`. Quote globs (`--include='*.java'`).
- Each implementer: foreground commands only, 10 minutes or less each; report defects outside your files instead of fixing them.

## Review Focus

1. A retried `Post` whose first attempt DID commit must replay, not double-post or 409: proven in Task 1 before any retry exists, and end to end in Task 14's chaos audit.
2. A ledger that answers `OK` with a stored `REFUSED` result (a replayed refusal) must map to the refusal, not to COMPLETED: pinned in Task 6's mapper test and Task 4's IT case 3.
3. Kafka down while retries are exhausted must not slow the merchant's request or throw: pinned in Task 10 case 3.
4. One ledger replica dying behind round_robin must not fail calls a second replica can serve: pinned in Task 5's two-server test.
5. `IDEMPOTENCY_KEY_REUSED` and unknown reasons must stay UNKNOWN (sweeper settles), never REJECTED: pinned in Task 6's mapper test.

---

## File map

```
contracts/ledger/v1/conversion_postings.proto          NEW  the contract (Task 2)
build.gradle                                            MOD  protobuf plugin, contracts srcDir, checkSingleProto
gradle.properties                                       MOD  protobufPluginVersion, protocVersion, grpcVersion, resilience4jVersion, kafkaImage
compose.yaml                                            MOD  kafka service (Task 9)
fleet.sh                                                MOD  Messaging group, gRPC port check, real conversion (Task 13)
docker/lgtm/generate.py + generated files               MOD  3 rules, panels (Task 12)

ledger-svc
  grpc/ConversionPostingRpc.java                        NEW  Post
  grpc/ConversionSettlementRpc.java                     NEW  Find, Void
  grpc/PostingMessages.java                             NEW  proto <-> ConversionPostingReq/Resp, validates
  grpc/GrpcErrors.java                                  NEW  GrpcExceptionHandler: LedgerException -> Status + ErrorInfo
  ctl/ConversionPostingCtrl.java, dto/req/VoidReq.java  DEL  (Task 4)
  acceptance/ConversionPostingSteps.java                MOD  calls gRPC (Task 4)

conversion-svc
  service/port/LedgerPostings.java, LedgerPosting.java  NEW  (Task 3)
  service/port/RateSource.java                          NEW  (Task 3)
  service/port/DeadLetters.java, DeadLetter.java        NEW  (Task 10)
  outbound/ledger/LedgerGrpcClient.java                 NEW  implements LedgerPostings (Task 6)
  outbound/ledger/LedgerReplyMapper.java                NEW  proto reply -> LedgerAnswer (Task 6)
  outbound/ledger/LedgerGuard.java, Operation.java      NEW  Retry + CircuitBreaker + dead letter (Task 8)
  outbound/discovery/DiscoveryNameResolver{,Provider}   NEW  "discovery:///name" (Task 5)
  outbound/deadletter/KafkaDeadLetterPublisher.java     NEW  (Task 10)
  outbound/ledger/{LedgerClient,LedgerAnswerMapper,LedgerReplyDto,PostingDto,PostingMapper}.java  DEL (Task 6)
  acceptance/LedgerFaultProxy.java                      NEW  gRPC forwarding proxy with faults (Task 7)
```

---

### Task 1: Prove the ledger replays a repeated conversion posting

Retries in Task 8 are only safe if this holds. It may already be covered; if so, cite the test and add nothing.

**Files:**
- Search: `services/ledger-svc/src/acceptanceTest/resources/features/conversion-postings.feature`, `services/ledger-svc/src/test/java/io/ezeebit/ledger/service/conversion/`
- Create (only if absent): `services/ledger-svc/src/acceptanceTest/java/io/ezeebit/ledger/service/ConversionPostingReplayIT.java`

**Interfaces:**
- Consumes: `ConversionPostingService.post(ConversionPostingReq) : ConversionPostingResp`
- Produces: evidence only.

- [ ] **Step 1: Search for an existing replay test**

Run: `grep -n -i -E 'again|replay|same conversion' services/ledger-svc/src/acceptanceTest/resources/features/conversion-postings.feature; echo "exit=$?"`
If a scenario posts the same conversion id twice and asserts one journal and the same body, write its name into the task report and skip to Step 5.

- [ ] **Step 2: Write the test (only if none exists)**

```java
package io.ezeebit.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.ledger.TestcontainersConfiguration;
import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.dto.req.DepositReq;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.service.conversion.ConversionPostingService;
import io.ezeebit.ledger.service.deposit.DepositService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConversionPostingReplayIT {

	@Autowired
	private ConversionPostingService postings;

	@Autowired
	private DepositService deposits;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void theSamePostingTwiceMovesMoneyOnceAndAnswersTheSame() {
		UUID merchant = UUID.randomUUID();
		deposits.deposit(UUID.randomUUID().toString(), new DepositReq(merchant, "ZAR", "1000.00", "seed"));
		ConversionPostingReq request = new ConversionPostingReq(UUID.randomUUID(), merchant, "ZAR", "100.00", "USDT",
				"5.398273", Instant.now().plusSeconds(30));

		ConversionPostingResp first = postings.post(request);
		ConversionPostingResp second = postings.post(request);

		assertThat(second).isEqualTo(first);
		assertThat(jdbc.sql("select count(*) from journal where conversion_id = ?").param(request.conversionId())
				.query(Long.class).single()).isEqualTo(1L);
	}

}
```

Before running, open `DepositReq` and `DepositService.deposit` and adjust the arguments to their real order and types; open the Liquibase changelog under `services/ledger-svc/src/main/resources/db/` and use the real journal table and conversion-id column names. Do not guess them.

- [ ] **Step 3: Run it**

Run: `./gradlew :services:ledger-svc:integrationTest --tests '*ConversionPostingReplayIT' --console=plain`
Expected: PASS. This proves existing behaviour; a FAIL is a real defect: stop and report it, do not continue.

- [ ] **Step 4: Red-proof the assertion**

Temporarily change `isEqualTo(1L)` to `isEqualTo(2L)`, run, confirm FAIL with `expected: 2L but was: 1L`, revert.

- [ ] **Step 5: Commit**

```bash
git add services/ledger-svc/src/acceptanceTest/java/io/ezeebit/ledger/service/ConversionPostingReplayIT.java
git commit -m "Prove a repeated conversion posting replays: the precondition for retrying Post"
```

---

### Task 2: The contract and its build wiring

**Files:**
- Create: `contracts/ledger/v1/conversion_postings.proto`
- Modify: `build.gradle` (root), `gradle.properties`, possibly `config/spotbugs/exclude.xml`

**Interfaces:**
- Produces (generated, package `io.ezeebit.contracts.ledger.v1`): `ConversionPostingsGrpc` (`Post`), `ConversionSettlementGrpc` (`Find`, `Void`), messages `PostingRequest`, `PostingLookup`, `PostingResult`, enum `PostingStatus`.

- [ ] **Step 1: Write the proto**

```proto
syntax = "proto3";

package ezeebit.ledger.v1;

option java_multiple_files = true;
option java_package = "io.ezeebit.contracts.ledger.v1";

// Called by conversion-svc only. Post is idempotent on conversion_id: a repeat replays the stored result.
service ConversionPostings {
  rpc Post(PostingRequest) returns (PostingResult);
}

// The sweeper's two questions about a conversion whose outcome is unknown.
service ConversionSettlement {
  rpc Find(PostingLookup) returns (PostingResult);
  rpc Void(PostingLookup) returns (PostingResult);
}

message PostingRequest {
  string conversion_id = 1;
  string merchant_id = 2;
  string sell_currency = 3;
  string sell_amount = 4;     // decimal text at the currency's scale, e.g. "1000.00"
  string buy_currency = 5;
  string buy_amount = 6;
  string deadline = 7;        // ISO-8601 instant, checked against the ledger's clock
}

message PostingLookup {
  string conversion_id = 1;
  string merchant_id = 2;
}

enum PostingStatus {
  POSTING_STATUS_UNSPECIFIED = 0;
  POSTED = 1;
  VOID = 2;
  REFUSED = 3;
}

message PostingResult {
  string journal_id = 1;
  string conversion_id = 2;
  string merchant_id = 3;
  PostingStatus status = 4;
  string reason = 5;          // set when REFUSED: INSUFFICIENT_FUNDS or BALANCE_LIMIT_EXCEEDED
  string sell_currency = 6;
  string sell_amount = 7;
  string buy_currency = 8;
  string buy_amount = 9;
  string created = 10;
}
```

Strings for ids, amounts and instants are deliberate: the ledger validates these as text (`DepositReq.DECIMAL`, `@SupportedInstant`), and a proto `double` would lose money precision.

- [ ] **Step 2: Pin versions in `gradle.properties`**

```properties
# Gradle protobuf plugin: latest release on the plugin portal (com.google.protobuf maven-metadata.xml), checked 2026-10-14.
protobufPluginVersion=0.10.0
# protoc matches Boot 4.1.1's managed protobuf-java.version; grpc matches its grpc-java.version (spring-boot-dependencies-4.1.1.pom).
protocVersion=4.35.1
grpcVersion=1.83.1
```

- [ ] **Step 3: Wire the plugin into the two services and add the single-copy gate**

In the root `build.gradle` `plugins {}` block add `id 'com.google.protobuf' version "${protobufPluginVersion}" apply false`. After the `tieredServices` block add:

```groovy
// One contract, compiled by both ends: a hand-written mirror on either side is the drift class this replaces.
def grpcServices = ['ledger-svc', 'conversion-svc']
configure(subprojects.findAll { it.name in grpcServices }) {
	apply plugin: 'com.google.protobuf'
	sourceSets {
		main {
			proto { srcDir rootProject.file('contracts') }
		}
	}
	protobuf {
		protoc { artifact = "com.google.protobuf:protoc:${protocVersion}" }
		plugins {
			grpc { artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}" }
		}
		generateProtoTasks {
			all()*.plugins { grpc {} }
		}
	}
	dependencies {
		implementation 'io.grpc:grpc-protobuf'
		implementation 'io.grpc:grpc-stub'
		implementation 'com.google.api.grpc:proto-google-common-protos'
		compileOnly 'org.apache.tomcat:annotations-api:6.0.53'
	}
}

def checkSingleProto = tasks.register('checkSingleProto') {
	group = 'verification'
	description = 'Fails when a .proto file exists anywhere but contracts/.'
	doLast {
		def strays = fileTree('services') { include '**/*.proto'; exclude '**/build/**' }.files
		if (!strays.isEmpty()) {
			throw new GradleException("checkSingleProto: .proto outside contracts/: ${strays*.path}")
		}
		def contracts = fileTree('contracts') { include '**/*.proto' }.files
		if (contracts.isEmpty()) {
			throw new GradleException('checkSingleProto: contracts/ holds no .proto, so nothing is being checked')
		}
		logger.lifecycle("checkSingleProto: ${contracts.size()} contract(s), 0 strays")
	}
}
```

Add `checkSingleProto` to the root `tasks.named('check') { dependsOn ... }` line. If `proto-google-common-protos` or `annotations-api` is not version-managed (the build fails with `Could not find ...`), pin it in `gradle.properties` from Maven Central metadata with a dated note. Generated sources land under `build/generated/sources/proto`; confirm Checkstyle and SpotBugs skip them (run `./gradlew :services:ledger-svc:checkstyleMain spotbugsMain`), and if not add `<Match><Package name="~io\.ezeebit\.contracts\..*"/></Match>` to `config/spotbugs/exclude.xml` and a Checkstyle `BeforeExecutionExclusionFileFilter` for `[/\\]generated[/\\]`.

- [ ] **Step 4: Red-proof the gate**

Run: `mkdir -p services/ledger-svc/src/main/proto && cp contracts/ledger/v1/conversion_postings.proto services/ledger-svc/src/main/proto/ && ./gradlew checkSingleProto --console=plain; echo "exit=$?"`
Expected: `checkSingleProto: .proto outside contracts/: [...]`, exit=1. Then delete `services/ledger-svc/src/main/proto`.

- [ ] **Step 5: Compile**

Run: `./gradlew :services:ledger-svc:compileJava :services:conversion-svc:compileJava checkSingleProto --console=plain; echo "exit=$?"`
Expected: exit=0, `checkSingleProto: 1 contract(s), 0 strays`, and `services/ledger-svc/build/generated/sources/proto/main/grpc/io/ezeebit/contracts/ledger/v1/ConversionPostingsGrpc.java` exists.

- [ ] **Step 6: Commit** `contracts/`, `build.gradle`, `gradle.properties` and any exclude file, red output in the body.

---

### Task 3: Conversion's ports (still over REST)

Pure refactor: the service layer stops importing `outbound/`. All existing tests stay green.

**Files:**
- Create: `services/conversion-svc/src/main/java/io/ezeebit/conversion/service/port/{LedgerPostings,LedgerPosting,RateSource}.java`
- Modify: `service/conversion/ConversionLedgerPoster.java`, `service/sweep/PendingLedgerCheck.java`, every `service/**` importer of `RateClient`, `outbound/ledger/LedgerClient.java`, `outbound/rates/RateClient.java`, their unit tests
- Test: `services/conversion-svc/src/test/java/io/ezeebit/conversion/architecture/ServiceImportsNoOutboundTest.java`

**Interfaces:**
- Produces:

```java
package io.ezeebit.conversion.service.port;

import java.util.UUID;

import io.ezeebit.conversion.domain.LedgerAnswer;

// What the service layer needs from a ledger, in this context's words. Every transport failure is UNKNOWN.
public interface LedgerPostings {

	LedgerAnswer post(LedgerPosting posting);

	LedgerAnswer lookup(UUID conversionId, UUID merchantId);

	LedgerAnswer voidPosting(UUID conversionId, UUID merchantId);

}
```

```java
package io.ezeebit.conversion.service.port;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.conversion.domain.Quote;

public record LedgerPosting(UUID conversionId, UUID merchantId, Quote quote, Instant deadline) {
}
```

```java
package io.ezeebit.conversion.service.port;

import io.ezeebit.conversion.domain.MarketRate;

// Fails closed: no rate is a ConversionException, never a cached or default rate.
public interface RateSource {

	MarketRate fetch(String base, String quote);

}
```

- [ ] **Step 1: Write the failing architecture test**

```java
package io.ezeebit.conversion.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ServiceImportsNoOutboundTest {

	@Test
	void theServiceLayerDependsOnPortsNotAdapters() throws IOException {
		Path service = Path.of("src/main/java/io/ezeebit/conversion/service");
		assertThat(service).isDirectory();
		List<String> offenders;
		try (Stream<Path> files = Files.walk(service)) {
			offenders = files.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> read(p).contains("import io.ezeebit.conversion.outbound."))
					.map(Path::toString).toList();
		}
		assertThat(offenders).as("service/ classes importing outbound/").isEmpty();
	}

	private static String read(final Path file) {
		try {
			return Files.readString(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
```

- [ ] **Step 2: Run, expect FAIL** naming at least `ConversionLedgerPoster.java`, `PendingLedgerCheck.java` and the `RateClient` importer(s).

Run: `./gradlew :services:conversion-svc:test --tests '*ServiceImportsNoOutboundTest' --console=plain`

- [ ] **Step 3: Implement**

`LedgerClient implements LedgerPostings`; replace its `post(UUID, UUID, Quote, Instant)` with `post(final LedgerPosting posting)` calling `postings.toPosting(posting.conversionId(), posting.merchantId(), posting.quote(), posting.deadline())`. `RateClient implements RateSource`. In `ConversionLedgerPoster` change the field to `LedgerPostings ledger` and the call to:

```java
LedgerAnswer answer = ledger.post(new LedgerPosting(conversion.id(), conversion.merchantId(),
		quotes.of(conversion.quote()), conversion.deadline()));
```

In `PendingLedgerCheck` change the field type to `LedgerPostings`. Every service importing `RateClient` takes `RateSource`. Update `ConversionLedgerPosterTest` and `PendingResolverTest` to mock the port.

- [ ] **Step 4: Run** `./gradlew :services:conversion-svc:test --console=plain`. Expected: all PASS, count = previous + 1.

- [ ] **Step 5: Commit.**

---

### Task 4: Ledger serves gRPC; REST conversion endpoints removed

**Files:**
- Modify: `services/ledger-svc/build.gradle`, `services/ledger-svc/src/main/resources/application.yml`
- Create: `services/ledger-svc/src/main/java/io/ezeebit/ledger/grpc/{ConversionPostingRpc,ConversionSettlementRpc,PostingMessages,GrpcErrors}.java`
- Delete: `ctl/ConversionPostingCtrl.java`, `dto/req/VoidReq.java` (and `VoidReq.class` in `MessagesBundleTest`)
- Modify: `acceptanceTest/.../acceptance/ConversionPostingSteps.java`, `GatewayProofIT.java`, `LoopbackOnlyIT.java`, `docs/openapi/ledger-svc-internal.json` (regenerated)
- Test: `services/ledger-svc/src/acceptanceTest/java/io/ezeebit/ledger/grpc/ConversionPostingRpcIT.java`, `services/ledger-svc/src/test/java/io/ezeebit/ledger/grpc/GrpcErrorsTest.java`

**Interfaces:**
- Consumes: generated stubs (Task 2); `ConversionPostingService.post(ConversionPostingReq)`, `ConversionVoidService.voidPosting(UUID, UUID)`, `ConversionPostingReader.find(UUID, UUID)`, all returning `ConversionPostingResp`.
- Produces: gRPC on `${LEDGER_GRPC_PORT:9081}` at `${SERVER_ADDRESS:127.0.0.1}`; Eureka metadata `grpcPort`. Status mapping per the spec's table; `ErrorInfo.reason` = the `ErrorCode` name, `ErrorInfo.domain` = `ledger.ezeebit.io`.

- [ ] **Step 1: Dependencies and config**

`services/ledger-svc/build.gradle` add:
```groovy
	implementation 'org.springframework.boot:spring-boot-starter-grpc-server'
	testImplementation 'org.springframework.boot:spring-boot-grpc-test'
	acceptanceTestImplementation 'org.springframework.boot:spring-boot-grpc-test'
	acceptanceTestImplementation 'org.springframework.boot:spring-boot-starter-grpc-client'
```
Merge into the existing `spring:` and `eureka:` keys of `application.yml` (do not duplicate keys):
```yaml
spring:
  grpc:
    server:
      port: ${LEDGER_GRPC_PORT:9081}
      # Loopback by default, like the HTTP port: these calls move money and carry no credential.
      address: ${SERVER_ADDRESS:127.0.0.1}
eureka:
  instance:
    metadata-map:
      grpcPort: ${spring.grpc.server.port}
```

- [ ] **Step 2: Write `GrpcErrorsTest` (failing)**

```java
package io.ezeebit.ledger.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.rpc.ErrorInfo;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GrpcErrorsTest {

	@ParameterizedTest
	@CsvSource({ "INSUFFICIENT_FUNDS,FAILED_PRECONDITION", "BALANCE_LIMIT_EXCEEDED,FAILED_PRECONDITION",
			"POSTING_DEADLINE_PASSED,FAILED_PRECONDITION", "CONVERSION_VOIDED,FAILED_PRECONDITION",
			"IDEMPOTENCY_KEY_REUSED,FAILED_PRECONDITION", "CONVERSION_ALREADY_POSTED,ALREADY_EXISTS",
			"POSTING_NOT_FOUND,NOT_FOUND", "VALIDATION_FAILED,INVALID_ARGUMENT", "MALFORMED_REQUEST,INVALID_ARGUMENT",
			"UNKNOWN_CURRENCY,INVALID_ARGUMENT", "SAME_CURRENCY,INVALID_ARGUMENT", "AMOUNT_SCALE_EXCEEDED,INVALID_ARGUMENT",
			"AMOUNT_NOT_POSITIVE,INVALID_ARGUMENT", "AMOUNT_TOO_LARGE,INVALID_ARGUMENT", "CURRENCY_MISMATCH,INTERNAL",
			"UNBALANCED_POSTING,INTERNAL", "INTERNAL_ERROR,INTERNAL" })
	void eachCodeTravelsAsItsStatusAndReason(final ErrorCode code, final Status.Code status) throws Exception {
		StatusException ex = new GrpcErrors().handleException(new LedgerException(code, null));

		assertThat(ex.getStatus().getCode()).isEqualTo(status);
		ErrorInfo info = StatusProto.fromThrowable(ex).getDetails(0).unpack(ErrorInfo.class);
		assertThat(info.getReason()).isEqualTo(code.name());
		assertThat(info.getDomain()).isEqualTo("ledger.ezeebit.io");
	}

	@Test
	void anythingElseIsInternalAndSaysNothing() {
		StatusException ex = new GrpcErrors().handleException(new IllegalStateException("secret detail"));

		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
		assertThat(ex.getStatus().getDescription()).isNull();
	}

}
```

`IDEMPOTENCY_KEY_REUSED` travels as `FAILED_PRECONDITION` with its reason; the client maps that reason to UNKNOWN (Task 6), exactly as today's `LedgerAnswerMapper.java:28` does.

- [ ] **Step 3: Run, expect FAIL** (`GrpcErrors` does not exist).

Run: `./gradlew :services:ledger-svc:test --tests '*GrpcErrorsTest' --console=plain`

- [ ] **Step 4: Implement `GrpcErrors`**

```java
package io.ezeebit.ledger.grpc;

import java.util.Set;

import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.ErrorInfo;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

// The code travels as ErrorInfo.reason so the caller decides on the code, never on the status alone.
@Component
public class GrpcErrors implements GrpcExceptionHandler {

	static final String DOMAIN = "ledger.ezeebit.io";
	private static final Set<ErrorCode> REFUSALS = Set.of(ErrorCode.INSUFFICIENT_FUNDS, ErrorCode.BALANCE_LIMIT_EXCEEDED,
			ErrorCode.POSTING_DEADLINE_PASSED, ErrorCode.CONVERSION_VOIDED, ErrorCode.IDEMPOTENCY_KEY_REUSED);
	private static final Set<ErrorCode> INPUT = Set.of(ErrorCode.VALIDATION_FAILED, ErrorCode.MALFORMED_REQUEST,
			ErrorCode.UNKNOWN_CURRENCY, ErrorCode.SAME_CURRENCY, ErrorCode.AMOUNT_SCALE_EXCEEDED,
			ErrorCode.AMOUNT_NOT_POSITIVE, ErrorCode.AMOUNT_TOO_LARGE);

	@Override
	public StatusException handleException(final Throwable exception) {
		if (!(exception instanceof LedgerException ledger)) {
			return Status.INTERNAL.asException();
		}
		ErrorInfo info = ErrorInfo.newBuilder().setReason(ledger.code().name()).setDomain(DOMAIN).build();
		// Qualified: com.google.rpc.Status (the wire detail) clashes with io.grpc.Status.
		com.google.rpc.Status status = com.google.rpc.Status.newBuilder().setCode(code(ledger.code()).getNumber())
				.setMessage(ledger.code().name()).addDetails(Any.pack(info)).build();
		return StatusProto.toStatusException(status);
	}

	private static Code code(final ErrorCode code) {
		if (REFUSALS.contains(code)) {
			return Code.FAILED_PRECONDITION;
		}
		if (INPUT.contains(code)) {
			return Code.INVALID_ARGUMENT;
		}
		return switch (code) {
			case CONVERSION_ALREADY_POSTED -> Code.ALREADY_EXISTS;
			case POSTING_NOT_FOUND -> Code.NOT_FOUND;
			default -> Code.INTERNAL;
		};
	}

}
```

- [ ] **Step 5: Run `GrpcErrorsTest`, expect PASS.**

- [ ] **Step 6: Implement `PostingMessages` (proto <-> DTO, validating)**

```java
package io.ezeebit.ledger.grpc;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.contracts.ledger.v1.PostingStatus;
import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

// The same bean validation the REST body had: the DTO's annotations stay the one home of the rules.
@Component
class PostingMessages {

	private final Validator validator;

	PostingMessages(final Validator validator) {
		this.validator = validator;
	}

	ConversionPostingReq toRequest(final PostingRequest message) {
		ConversionPostingReq request = new ConversionPostingReq(uuid(message.getConversionId(), "conversionId"),
				uuid(message.getMerchantId(), "merchantId"), message.getSellCurrency(), message.getSellAmount(),
				message.getBuyCurrency(), message.getBuyAmount(), instant(message.getDeadline()));
		Set<ConstraintViolation<ConversionPostingReq>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, violations.iterator().next().getPropertyPath().toString());
		}
		return request;
	}

	UUID uuid(final String text, final String field) {
		try {
			return UUID.fromString(text);
		}
		catch (IllegalArgumentException ex) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, field);
		}
	}

	PostingResult toResult(final ConversionPostingResp resp) {
		PostingResult.Builder result = PostingResult.newBuilder().setJournalId(resp.journalId().toString())
				.setConversionId(resp.conversionId().toString()).setMerchantId(resp.merchantId().toString())
				.setStatus(PostingStatus.valueOf(resp.status())).setCreated(resp.created().toString());
		if (resp.reason() != null) {
			result.setReason(resp.reason());
		}
		if (resp.sellCurrency() != null) {
			result.setSellCurrency(resp.sellCurrency()).setSellAmount(resp.sellAmount())
					.setBuyCurrency(resp.buyCurrency()).setBuyAmount(resp.buyAmount());
		}
		return result.build();
	}

	private static Instant instant(final String text) {
		try {
			return Instant.parse(text);
		}
		catch (DateTimeParseException ex) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, "deadline");
		}
	}

}
```

`ConversionPostingResp.status()` is `POSTED`, `VOID` or `REFUSED` (its `@Schema`); `PostingStatus.valueOf` throws on anything else, and that surfaces as `INTERNAL`, which is right. `@SupportedInstant` is a bean-validation constraint and runs inside `validator.validate`. The REST path's `StrictStringDeserializer` has no equivalent need: proto strings are already strings.

- [ ] **Step 7: Implement the two RPC classes**

```java
package io.ezeebit.ledger.grpc;

import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.ledger.service.conversion.ConversionPostingService;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class ConversionPostingRpc extends ConversionPostingsGrpc.ConversionPostingsImplBase {

	private final ConversionPostingService postings;
	private final PostingMessages messages;

	public ConversionPostingRpc(final ConversionPostingService postings, final PostingMessages messages) {
		this.postings = postings;
		this.messages = messages;
	}

	@Override
	public void post(final PostingRequest request, final StreamObserver<PostingResult> reply) {
		reply.onNext(messages.toResult(postings.post(messages.toRequest(request))));
		reply.onCompleted();
	}

}
```

```java
package io.ezeebit.ledger.grpc;

import io.ezeebit.contracts.ledger.v1.ConversionSettlementGrpc;
import io.ezeebit.contracts.ledger.v1.PostingLookup;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.ledger.service.conversion.ConversionVoidService;
import io.ezeebit.ledger.service.conversion.answer.ConversionPostingReader;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class ConversionSettlementRpc extends ConversionSettlementGrpc.ConversionSettlementImplBase {

	private final ConversionVoidService voids;
	private final ConversionPostingReader reader;
	private final PostingMessages messages;

	public ConversionSettlementRpc(final ConversionVoidService voids, final ConversionPostingReader reader,
			final PostingMessages messages) {
		this.voids = voids;
		this.reader = reader;
		this.messages = messages;
	}

	@Override
	public void find(final PostingLookup lookup, final StreamObserver<PostingResult> reply) {
		reply.onNext(messages.toResult(reader.find(messages.uuid(lookup.getConversionId(), "conversionId"),
				messages.uuid(lookup.getMerchantId(), "merchantId"))));
		reply.onCompleted();
	}

	@Override
	public void void_(final PostingLookup lookup, final StreamObserver<PostingResult> reply) {
		reply.onNext(messages.toResult(voids.voidPosting(messages.uuid(lookup.getConversionId(), "conversionId"),
				messages.uuid(lookup.getMerchantId(), "merchantId"))));
		reply.onCompleted();
	}

}
```

`void` is a Java keyword, so grpc-java generates `void_`; confirm the name in the generated `ConversionSettlementImplBase` before compiling. Exceptions thrown inside these methods reach `GrpcErrors` through Spring gRPC's `GrpcExceptionHandlerInterceptor`.

- [ ] **Step 8: Write `ConversionPostingRpcIT` (in-process transport, real Postgres)**

`@SpringBootTest(properties = "spring.grpc.server.inprocess.name=ledger-it")`, `@AutoConfigureTestGrpcTransport`, `@Import(TestcontainersConfiguration.class)`, channel `InProcessChannelBuilder.forName("ledger-it").directExecutor().build()`. One `@Test` each, seeding money through `DepositService` as in Task 1:
1. `post` returns `POSTED` with both amounts.
2. `post` twice with the same request returns equal `PostingResult`s.
3. `post` with insufficient funds throws `StatusRuntimeException` `FAILED_PRECONDITION` with `ErrorInfo.reason` `INSUFFICIENT_FUNDS`; posting it again returns `OK` with `status=REFUSED`, `reason=INSUFFICIENT_FUNDS`, or the same error again: assert whichever the service does (read `RefusalRecorder` and `ConversionReplayer` first) and write the observed behaviour into Task 6's mapper test row 3.
4. `post` with `sell_amount="1.234"` for ZAR: `INVALID_ARGUMENT`, with the reason the service actually raises (read `ConversionPostingIntake` first).
5. `post` with `conversion_id="nope"`: `INVALID_ARGUMENT`, `VALIDATION_FAILED`.
6. `find` on an unknown id: `NOT_FOUND`, `POSTING_NOT_FOUND`.
7. `void` on an unknown id returns `VOID`; a later `post` of it: `FAILED_PRECONDITION`, `CONVERSION_VOIDED`.

Before Step 7 exists the stub calls fail `UNIMPLEMENTED` (red); after it, green: `./gradlew :services:ledger-svc:integrationTest --tests '*ConversionPostingRpcIT' --console=plain`.

- [ ] **Step 9: Remove REST and move the ledger's own acceptance steps to gRPC**

Delete `ConversionPostingCtrl.java` and `VoidReq.java`; drop `VoidReq.class` from both lists in `MessagesBundleTest`. In `ConversionPostingSteps.java`, replace each HTTP call to `/internal/v1/conversion-postings` with the matching blocking stub call over a `ManagedChannel` to `127.0.0.1:<local gRPC port>` (the `local.grpc.server.port` property), translating `StatusRuntimeException` into the same `(status, code)` pair the steps assert today: read the reason from `ErrorInfo`, and the HTTP status from today's `api/advice/ErrorStatus.java` for that reason (`ErrorStatus` keeps existing for deposits, so call it). The 17 scenarios in `conversion-postings.feature` do not change. In `GatewayProofIT`, drop the conversion-postings assertions and keep the deposit ones. Regenerate: `./gradlew generateOpenApiSnapshots --console=plain`, then check `git diff docs/openapi/ledger-svc-internal.json` removes only the conversion-postings paths and their schemas.

- [ ] **Step 10: Loopback proof for the gRPC port**

Add a test to `LoopbackOnlyIT` asserting the gRPC server is unreachable on the machine's non-loopback address (mirror the existing HTTP test's method, using `local.grpc.server.port`). Red-proof by adding `spring.grpc.server.address=0.0.0.0` to the test's properties once and watching it fail.

- [ ] **Step 11: Run the ledger's full tiers**

Run: `./gradlew :services:ledger-svc:check --console=plain; echo "exit=$?"`
Expected: exit=0; all 17 conversion-postings scenarios PASS.

- [ ] **Step 12: Commit.**

---

### Task 5: A Eureka-backed gRPC name resolver

**Files:**
- Create: `services/conversion-svc/src/main/java/io/ezeebit/conversion/outbound/discovery/{DiscoveryNameResolverProvider,DiscoveryNameResolver}.java`
- Modify: `services/conversion-svc/build.gradle` (Task 6 adds the client starter; this task needs `testImplementation 'io.grpc:grpc-netty-shaded'` and `implementation 'io.grpc:grpc-api'` if not already transitive)
- Test: `services/conversion-svc/src/test/java/io/ezeebit/conversion/outbound/discovery/DiscoveryNameResolverTest.java`

**Interfaces:**
- Consumes: Spring Cloud `DiscoveryClient.getInstances(String) : List<ServiceInstance>`; instance metadata key `grpcPort`.
- Produces: target scheme `discovery:///<service-name>`; bean `DiscoveryNameResolverProvider`, registered with `NameResolverRegistry.getDefaultRegistry()` in its constructor.

- [ ] **Step 1: Write the failing test**

```java
package io.ezeebit.conversion.outbound.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;

class DiscoveryNameResolverTest {

	@Test
	void callsSpreadOverEveryInstanceAndSurviveOneStopping() throws Exception {
		AtomicInteger a = new AtomicInteger();
		AtomicInteger b = new AtomicInteger();
		Server one = ServerBuilder.forPort(0).addService(counting(a)).build().start();
		Server two = ServerBuilder.forPort(0).addService(counting(b)).build().start();
		new DiscoveryNameResolverProvider(discovery(one.getPort(), two.getPort()));
		ManagedChannel channel = ManagedChannelBuilder.forTarget("discovery:///ledger-svc").usePlaintext()
				.defaultLoadBalancingPolicy("round_robin").build();
		ConversionPostingsGrpc.ConversionPostingsBlockingStub stub = ConversionPostingsGrpc.newBlockingStub(channel);

		for (int i = 0; i < 20; i++) {
			stub.post(PostingRequest.getDefaultInstance());
		}
		assertThat(a.get()).as("instance one").isPositive();
		assertThat(b.get()).as("instance two").isPositive();

		one.shutdownNow().awaitTermination();
		int before = b.get();
		for (int i = 0; i < 5; i++) {
			stub.post(PostingRequest.getDefaultInstance());
		}
		assertThat(b.get() - before).as("calls served after one instance stopped").isEqualTo(5);
		channel.shutdownNow();
		two.shutdownNow();
	}

	private static ConversionPostingsGrpc.ConversionPostingsImplBase counting(final AtomicInteger calls) {
		return new ConversionPostingsGrpc.ConversionPostingsImplBase() {
			@Override
			public void post(final PostingRequest request, final StreamObserver<PostingResult> reply) {
				calls.incrementAndGet();
				reply.onNext(PostingResult.getDefaultInstance());
				reply.onCompleted();
			}
		};
	}

	private static SimpleDiscoveryClient discovery(final int portOne, final int portTwo) {
		SimpleDiscoveryProperties properties = new SimpleDiscoveryProperties();
		properties.setInstances(Map.of("ledger-svc", List.of(instance(portOne), instance(portTwo))));
		return new SimpleDiscoveryClient(properties);
	}

	private static DefaultServiceInstance instance(final int grpcPort) {
		return new DefaultServiceInstance("ledger-%d".formatted(grpcPort), "ledger-svc", "127.0.0.1", 1, false,
				Map.of("grpcPort", String.valueOf(grpcPort)));
	}

}
```

The instance's HTTP port is deliberately wrong (`1`): the resolver must read `grpcPort`, and this proves it. If the first post after the stop fails transiently before round_robin drops the dead subchannel, the assertion fails: that is a real finding (a lost call during failover), so do not loosen it; fix it with `refresh()` on failure or with Task 8's retry, and say which in the report.

- [ ] **Step 2: Run, expect FAIL** (class missing).

- [ ] **Step 3: Implement**

```java
package io.ezeebit.conversion.outbound.discovery;

import java.net.URI;

import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

// gRPC has no Eureka resolver of its own; "discovery:///ledger-svc" reads every instance's grpcPort from the registry.
@Component
public class DiscoveryNameResolverProvider extends NameResolverProvider {

	static final String SCHEME = "discovery";

	private final DiscoveryClient discovery;

	public DiscoveryNameResolverProvider(final DiscoveryClient discovery) {
		this.discovery = discovery;
		NameResolverRegistry.getDefaultRegistry().register(this);
	}

	@Override
	public NameResolver newNameResolver(final URI target, final NameResolver.Args args) {
		return SCHEME.equals(target.getScheme())
				? new DiscoveryNameResolver(discovery, target.getPath().substring(1), args) : null;
	}

	@Override
	public String getDefaultScheme() {
		return SCHEME;
	}

	@Override
	protected boolean isAvailable() {
		return true;
	}

	@Override
	protected int priority() {
		return 6;
	}

}
```

```java
package io.ezeebit.conversion.outbound.discovery;

import java.net.InetSocketAddress;
import java.util.List;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

// Re-reads the registry on every refresh, which gRPC requests after a connection fails.
final class DiscoveryNameResolver extends NameResolver {

	private final DiscoveryClient discovery;
	private final String service;
	private final Args args;
	private Listener2 listener;

	DiscoveryNameResolver(final DiscoveryClient discovery, final String service, final Args args) {
		this.discovery = discovery;
		this.service = service;
		this.args = args;
	}

	@Override
	public String getServiceAuthority() {
		return service;
	}

	@Override
	public void start(final Listener2 listener) {
		this.listener = listener;
		resolve();
	}

	@Override
	public void refresh() {
		args.getSynchronizationContext().execute(this::resolve);
	}

	@Override
	public void shutdown() {
		listener = null;
	}

	private void resolve() {
		if (listener == null) {
			return;
		}
		List<EquivalentAddressGroup> addresses = discovery.getInstances(service).stream()
				.filter(i -> i.getMetadata().containsKey("grpcPort")).map(DiscoveryNameResolver::address).toList();
		if (addresses.isEmpty()) {
			listener.onError(Status.UNAVAILABLE.withDescription("no %s instance with a grpcPort".formatted(service)));
			return;
		}
		listener.onResult(ResolutionResult.newBuilder().setAddresses(addresses).build());
	}

	private static EquivalentAddressGroup address(final ServiceInstance instance) {
		return new EquivalentAddressGroup(
				new InetSocketAddress(instance.getHost(), Integer.parseInt(instance.getMetadata().get("grpcPort"))));
	}

}
```

Registration in the constructor is a global side effect: it is how grpc-java finds a resolver without `META-INF/services`, and it must happen before the first channel is built (Task 6 makes the channel bean depend on this bean). Check `NameResolver.Listener2.onResult` signature in grpc-api 1.83.1 (`javap`); newer versions also offer `onResult2` returning a `Status`; use whichever is not deprecated.

- [ ] **Step 4: Run, expect PASS. Red-proof**: change `"grpcPort"` to `"port"` in `address()` and the filter, run, expect FAIL with `no ledger-svc instance with a grpcPort`, revert.

- [ ] **Step 5: Commit.**

---

### Task 6: Conversion calls the ledger over gRPC (no resilience yet)

**Files:**
- Modify: `services/conversion-svc/build.gradle`, `config/ClientConfig.java`, `config/properties/ConversionProperties.java`, `services/config-svc/src/main/resources/served/conversion-svc.yml`
- Create: `outbound/ledger/LedgerGrpcClient.java`, `outbound/ledger/LedgerReplyMapper.java`
- Delete: `outbound/ledger/{LedgerClient,LedgerAnswerMapper,LedgerReplyDto,PostingDto,PostingMapper}.java` and their unit tests
- Test: `services/conversion-svc/src/test/java/io/ezeebit/conversion/outbound/ledger/{LedgerReplyMapperTest,LedgerGrpcClientTest}.java`

**Interfaces:**
- Consumes: `LedgerPostings`, `LedgerPosting` (Task 3); generated stubs (Task 2); `DiscoveryNameResolverProvider` (Task 5).
- Produces: `LedgerGrpcClient implements LedgerPostings`; bean `ManagedChannel ledgerChannel`; `LedgerReplyMapper.fromResult(PostingResult) : LedgerAnswer`, `LedgerReplyMapper.fromStatus(StatusRuntimeException) : LedgerAnswer`; package-private `@FunctionalInterface LedgerGrpcClient.Attempt { PostingResult run(); }` used by Task 8.

- [ ] **Step 1: Write `LedgerReplyMapperTest` (failing)**, one parameterised case per row:

| Input | Expected |
|---|---|
| `PostingResult` status `POSTED` | `POSTED` |
| status `VOID` | `VOIDED` |
| status `REFUSED`, reason `INSUFFICIENT_FUNDS` | `INSUFFICIENT_FUNDS` |
| status `REFUSED`, reason `BALANCE_LIMIT_EXCEEDED` | `BALANCE_LIMIT_EXCEEDED` |
| status `REFUSED`, reason `OTHER` | `UNKNOWN` |
| status `POSTING_STATUS_UNSPECIFIED` | `UNKNOWN` |
| `FAILED_PRECONDITION` + `INSUFFICIENT_FUNDS` | `INSUFFICIENT_FUNDS` |
| `FAILED_PRECONDITION` + `BALANCE_LIMIT_EXCEEDED` | `BALANCE_LIMIT_EXCEEDED` |
| `FAILED_PRECONDITION` + `POSTING_DEADLINE_PASSED` | `DEADLINE_PASSED` |
| `FAILED_PRECONDITION` + `CONVERSION_VOIDED` | `VOIDED` |
| `FAILED_PRECONDITION` + `IDEMPOTENCY_KEY_REUSED` | `UNKNOWN` |
| `ALREADY_EXISTS` + `CONVERSION_ALREADY_POSTED` | `POSTED` |
| `NOT_FOUND` + `POSTING_NOT_FOUND` | `ABSENT` |
| `NOT_FOUND` with no `ErrorInfo` | `UNKNOWN` |
| `INVALID_ARGUMENT` + each of `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `UNKNOWN_CURRENCY`, `SAME_CURRENCY`, `AMOUNT_SCALE_EXCEEDED`, `AMOUNT_NOT_POSITIVE`, `AMOUNT_TOO_LARGE` | `INPUT_REFUSED` |
| `INTERNAL`, `UNAVAILABLE`, `DEADLINE_EXCEEDED` | `UNKNOWN` |

Build each exception with `StatusProto.toStatusRuntimeException(...)` carrying `Any.pack(ErrorInfo.newBuilder().setReason(r).setDomain("ledger.ezeebit.io").build())`.

- [ ] **Step 2: Run, expect FAIL. Implement `LedgerReplyMapper`** (`@Component`). `fromResult` switches on `status` (`default -> UNKNOWN`). `fromStatus` reads the first `ErrorInfo` detail via `StatusProto.fromThrowable(ex)` (null-safe) and switches on its reason with `default -> UNKNOWN`; `INVALID_ARGUMENT` plus a reason in the input set (copied verbatim from today's `LedgerAnswerMapper.java:13-14`) is `INPUT_REFUSED`. Run, expect PASS.

- [ ] **Step 3: Write `LedgerGrpcClientTest` (failing)** against an in-process fake ledger (`InProcessServerBuilder.forName(name).directExecutor().addService(fake)`, channel `InProcessChannelBuilder.forName(name).directExecutor()`): `post` sends every field from `LedgerPosting` (assert the captured message: ids as text, `quote.sell().text()`, `quote.buy().text()`, currency codes, `deadline.toString()`) and maps the reply; `lookup` and `voidPosting` send both ids; a fake throwing `Status.UNAVAILABLE` yields `UNKNOWN`. Build the `Quote` the way `ConversionLedgerPosterTest` does today.

- [ ] **Step 4: Implement `LedgerGrpcClient`**

```java
package io.ezeebit.conversion.outbound.ledger;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.ConversionSettlementGrpc;
import io.ezeebit.contracts.ledger.v1.PostingLookup;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.service.port.LedgerPosting;
import io.ezeebit.conversion.service.port.LedgerPostings;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class LedgerGrpcClient implements LedgerPostings {

	private static final long ATTEMPT_MILLIS = 2_000;

	private final ManagedChannel channel;
	private final LedgerReplyMapper replies;

	public LedgerGrpcClient(@Qualifier("ledgerChannel") final ManagedChannel channel, final LedgerReplyMapper replies) {
		this.channel = channel;
		this.replies = replies;
	}

	@Override
	public LedgerAnswer post(final LedgerPosting posting) {
		PostingRequest message = PostingRequest.newBuilder().setConversionId(posting.conversionId().toString())
				.setMerchantId(posting.merchantId().toString())
				.setSellCurrency(posting.quote().sell().currency().code()).setSellAmount(posting.quote().sell().text())
				.setBuyCurrency(posting.quote().buy().currency().code()).setBuyAmount(posting.quote().buy().text())
				.setDeadline(posting.deadline().toString()).build();
		return call(() -> ConversionPostingsGrpc.newBlockingStub(channel)
				.withDeadlineAfter(ATTEMPT_MILLIS, TimeUnit.MILLISECONDS).post(message));
	}

	@Override
	public LedgerAnswer lookup(final UUID conversionId, final UUID merchantId) {
		return call(() -> settlement().find(lookup(conversionId, merchantId)));
	}

	@Override
	public LedgerAnswer voidPosting(final UUID conversionId, final UUID merchantId) {
		return call(() -> settlement().void_(lookup(conversionId, merchantId)));
	}

	private ConversionSettlementGrpc.ConversionSettlementBlockingStub settlement() {
		return ConversionSettlementGrpc.newBlockingStub(channel).withDeadlineAfter(ATTEMPT_MILLIS, TimeUnit.MILLISECONDS);
	}

	private static PostingLookup lookup(final UUID conversionId, final UUID merchantId) {
		return PostingLookup.newBuilder().setConversionId(conversionId.toString()).setMerchantId(merchantId.toString())
				.build();
	}

	private LedgerAnswer call(final Attempt attempt) {
		try {
			return replies.fromResult(attempt.run());
		}
		catch (StatusRuntimeException ex) {
			return replies.fromStatus(ex);
		}
	}

	@FunctionalInterface
	interface Attempt {

		PostingResult run();

	}

}
```

- [ ] **Step 5: Wire the channel and drop the REST client**

`services/conversion-svc/build.gradle` add `implementation 'org.springframework.boot:spring-boot-starter-grpc-client'` and `testImplementation 'io.grpc:grpc-inprocess'` (Boot-managed). In `ClientConfig` delete `ledgerRestClient` and the `DeferringLoadBalancerInterceptor` import, and add:

```java
	// round_robin over every instance the registry names: HTTP/2 otherwise pins all calls to one replica.
	// The resolver parameter only orders its registration before this channel is built.
	@Bean(destroyMethod = "shutdownNow")
	ManagedChannel ledgerChannel(final GrpcChannelFactory channels, final DiscoveryNameResolverProvider resolver,
			final ConversionProperties properties) {
		return channels.createChannel(properties.ledger().target(), ChannelBuilderOptions.defaults()
				.withCustomizer((target, builder) -> builder.defaultLoadBalancingPolicy("round_robin").usePlaintext()));
	}
```

Verify the `ChannelBuilderOptions` API before compiling: `javap -cp <spring-grpc-core-1.1.1.jar from the Gradle cache> org.springframework.grpc.client.ChannelBuilderOptions`, and adapt the call to the real method names. In `ConversionProperties.Ledger` replace `(String url, Duration connectTimeout, Duration readTimeout)` with `(String target)`. In `served/conversion-svc.yml` replace the `ledger:` block with:

```yaml
  ledger:
    # A Eureka service name; DiscoveryNameResolverProvider reads each instance's grpcPort.
    target: discovery:///ledger-svc
```

Delete `spring.cloud.loadbalancer.cache.enabled: false` and its comment only if `grep -rn 'LoadBalancer\|lb://' services/conversion-svc/src` shows nothing left using it.

- [ ] **Step 6: Run unit tests** `./gradlew :services:conversion-svc:test --console=plain`. Expected PASS. The acceptance tier fails until Task 7 (its proxy speaks HTTP); do not run it here.

- [ ] **Step 7: Commit.**

---

### Task 7: Acceptance harness: a gRPC fault proxy in front of the real ledger

The 8 `ledger-failures` scenarios and the tracing scenarios inject faults through a WireMock HTTP proxy, which cannot carry gRPC. Replace it with a small forwarding gRPC server in test code. No `.feature` file changes in this task.

**Files:**
- Create: `services/conversion-svc/src/acceptanceTest/java/io/ezeebit/conversion/acceptance/LedgerFaultProxy.java`
- Modify: `Externals.java`, `LedgerProcess.java`, `CucumberSpringConfig.java`, `Hooks.java`, `LedgerSteps.java`, `TracingSteps.java`

**Interfaces:**
- Produces:

```java
final class LedgerFaultProxy {
	LedgerFaultProxy(String ledgerHost, int ledgerGrpcPort);
	void start() throws IOException;
	int port();
	void stop();
	void clearFaults();                     // forward everything again; keep the journal
	void reset();                           // clearFaults() and clear the journal (each scenario's Before hook)
	void post(Fault fault);                 // applied to every Post
	void posts(Fault first, Fault later);   // the first Post, then every later Post
	int calls(String method);               // "Post", "Find" or "Void" since reset()
	int distinctPosts();                    // distinct conversion_id values posted since reset()
	record Fault(Duration delay, Mode mode, Status.Code code, String reason) {
		enum Mode { FORWARD_AFTER_DELAY, FORWARD_THEN_HANG, REFUSE }
		static Fault delayed(Duration delay);
		static Fault swallowed(Duration hang);
		static Fault refuse(Status.Code code, String reason);
	}
}
```

- [ ] **Step 1: Implement the proxy**: a `ServerBuilder.forPort(0)` server (bind `127.0.0.1`) with `ConversionPostingsImplBase` and `ConversionSettlementImplBase` that forward to blocking stubs on a plaintext channel to the real ledger. For `Post`, apply the current fault: `FORWARD_AFTER_DELAY` sleeps then forwards; `FORWARD_THEN_HANG` forwards, then sleeps `delay` before replying (the ledger posts, the caller's 2s deadline expires: the "answers are lost" case); `REFUSE` replies `StatusProto.toStatusRuntimeException` with `ErrorInfo(reason, "ledger.ezeebit.io")` and does not forward. Pass errors from the real ledger through unchanged (`Status` and trailers). Count calls per method in a `ConcurrentHashMap<String, AtomicInteger>` and posted conversion ids in a concurrent set. Keep it under about 120 lines.

- [ ] **Step 2: Rewire the harness**
  - `LedgerProcess`: add `--spring.grpc.server.port=%d` with a second free port and expose `grpcPort()`; health stays HTTP `/actuator/health`.
  - `Externals`: replace the WireMock `ledgerProxy` with `LedgerFaultProxy`, started after the ledger and pointed at `127.0.0.1:ledger.grpcPort()`; expose `ledgerGrpcPort()` for steps that must reach the real ledger directly.
  - `CucumberSpringConfig`: replace the `...instances.ledger-svc[0].uri` property with `instances.ledger-svc[0].uri` = `http://127.0.0.1:1` and add `instances.ledger-svc[0].metadata.grpcPort` = the proxy's port; delete the `conversion.ledger.read-timeout=2s` property (the per-attempt deadline is in code now).
  - `Hooks`: the WireMock reset and catch-all stub become `Externals.get().ledgerProxy().reset()`.
  - `LedgerSteps`, one mapping per existing step:

| Step text | Proxy call |
|---|---|
| the ledger does not answer in time | `post(Fault.delayed(Duration.ofSeconds(3)))` |
| the ledger posts conversions but its answers are lost | `post(Fault.swallowed(Duration.ofSeconds(3)))` |
| the ledger's first answer takes 500 ms and its second 700 ms | `posts(delayed(500ms), delayed(700ms))` |
| the ledger refuses every posting with 400 AMOUNT_TOO_LARGE | `post(refuse(INVALID_ARGUMENT, "AMOUNT_TOO_LARGE"))` |
| the ledger refuses every posting with 422 IDEMPOTENCY_KEY_REUSED | `post(refuse(FAILED_PRECONDITION, "IDEMPOTENCY_KEY_REUSED"))` |
| the ledger answers normally again | `clearFaults()` |
| the ledger was sent N posting(s) | `calls("Post")` |
| the ledger was asked to void each of them exactly once | `calls("Void")` equals the conversion count |
| the lost posting finally reaches the ledger | a direct `Post` to `ledgerGrpcPort()`, its `StatusRuntimeException` translated to `(409, CONVERSION_VOIDED)` style with the table from Task 4 Step 9 |

  The step text's HTTP-style numbers (`400`, `422`) stay as words in the feature; the step definition ignores the number and uses the reason.
  - `TracingSteps`: replace its HTTP reference to conversion-postings with the gRPC client span. Read the assertion it makes first; assert the span named `ezeebit.ledger.v1.ConversionPostings/Post` (or the name the gRPC observation convention produces: print the recorded span names once and use the real one).

- [ ] **Step 3: Run the whole acceptance tier**

Run: `./gradlew :services:conversion-svc:acceptanceTest --console=plain; echo "exit=$?"`
Expected: exit=0, every scenario in all 7 feature files PASS, scenario count equal to before Task 6. If a scenario fails, the fault mapping is wrong, not the feature: fix the step, never the `.feature`.

- [ ] **Step 4: Run `./gradlew :services:conversion-svc:check --console=plain`**, expect exit=0, then commit.

---

### Task 8: Resilience4j retries and circuit breakers on the ledger seam

**Files:**
- Modify: `gradle.properties`, `services/conversion-svc/build.gradle`, `served/conversion-svc.yml`, `outbound/ledger/LedgerGrpcClient.java`
- Create: `outbound/ledger/LedgerGuard.java`, `outbound/ledger/Operation.java`, `service/port/DeadLetters.java`, `service/port/DeadLetter.java`
- Modify: `services/conversion-svc/src/acceptanceTest/resources/features/ledger-failures.feature` (one step, Step 7)
- Test: `services/conversion-svc/src/test/java/io/ezeebit/conversion/outbound/ledger/LedgerGuardTest.java`

**Interfaces:**
- Consumes: `LedgerGrpcClient.Attempt` (Task 6).
- Produces:
  - `enum Operation { POST, FIND, VOID }` (package `outbound.ledger`), each with the instance name `ledgerPost`, `ledgerFind`, `ledgerVoid`.
  - `LedgerGuard.call(Operation op, UUID conversionId, UUID merchantId, Message request, Attempt attempt) : PostingResult`; throws the final `StatusRuntimeException`, or `Status.UNAVAILABLE` "circuit open" when the breaker refuses.
  - `DeadLetters` and `DeadLetter` (shown in Task 10's interface block; create them here so the guard compiles, and give Task 10 only the Kafka adapter). Until Task 10, the production `DeadLetters` bean is a `@Component` `LoggingDeadLetters` in `outbound/deadletter/` that only logs; Task 10 replaces it.

- [ ] **Step 1: Pin and add the dependency**

```properties
# Resilience4j: latest release on Maven Central (resilience4j-spring-boot4 maven-metadata.xml, 2.4.0), checked 2026-10-14.
resilience4jVersion=2.4.0
```

`implementation "io.github.resilience4j:resilience4j-spring-boot4:${resilience4jVersion}"` in `services/conversion-svc/build.gradle`.

- [ ] **Step 2: Configure in `served/conversion-svc.yml`**

```yaml
resilience4j:
  retry:
    instances:
      ledgerPost: { max-attempts: 3, wait-duration: 200ms, enable-exponential-backoff: true, exponential-backoff-multiplier: 2 }
      ledgerFind: { max-attempts: 3, wait-duration: 500ms, enable-exponential-backoff: true, exponential-backoff-multiplier: 2 }
      ledgerVoid: { max-attempts: 3, wait-duration: 500ms, enable-exponential-backoff: true, exponential-backoff-multiplier: 2 }
  circuitbreaker:
    configs:
      ledger:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
    instances:
      ledgerPost: { base-config: ledger }
      ledgerFind: { base-config: ledger }
      ledgerVoid: { base-config: ledger }
```

The retry predicate on gRPC status codes cannot be written in YAML: `LedgerGuard` builds each `Retry` as `RetryConfig.from(retries.retry(name).getRetryConfig()).retryOnException(LedgerGuard::isTransient).build()` and caches it. Verify every property name above against the `resilience4j-spring-boot4` 2.4.0 configuration metadata (`unzip -p <jar> META-INF/spring-configuration-metadata.json | python3 -m json.tool | grep -E 'instances|configs' | head`) before relying on it.

- [ ] **Step 3: Write `LedgerGuardTest` (failing)**, building real `RetryRegistry` and `CircuitBreakerRegistry` in the test with the values above and a recording `DeadLetters`:
  1. attempt throws `UNAVAILABLE` twice then returns a result: result returned, 3 attempts, 0 dead letters.
  2. attempt always throws `UNAVAILABLE`: `StatusRuntimeException` rethrown after exactly 3 attempts; 1 dead letter with `op=POST`, `reason=RETRIES_EXHAUSTED`, `lastStatus=UNAVAILABLE`, `attempts=3`, `request` JSON containing the conversion id.
  3. attempt throws `FAILED_PRECONDITION`: rethrown after 1 attempt; 0 dead letters.
  4. 20 guarded calls each exhausting on `UNAVAILABLE` (20 breaker failures): the 21st throws a `StatusRuntimeException` with `UNAVAILABLE` and description `circuit open` without invoking the attempt, and records a dead letter with `reason=CIRCUIT_OPEN`.
  5. worst case: attempt sleeps 2s then throws `DEADLINE_EXCEEDED` every time; total elapsed for `POST` under 7s (`System.nanoTime`). Use a smaller registry wait in a copy of the config only if the real values make the test exceed 10s; the 7s assertion must use the real values.

- [ ] **Step 4: Implement `LedgerGuard`** (`@Component`, exactly 3 dependencies: `RetryRegistry`, `CircuitBreakerRegistry`, `DeadLetters`). Order: `CircuitBreaker` outside, `Retry` inside (`Decorators.ofSupplier(attempt::run).withRetry(retry).withCircuitBreaker(breaker).get()`), so one exhausted retry sequence counts as one breaker failure. Count attempts with a local `AtomicInteger` incremented inside the supplier. On the final `StatusRuntimeException` where `isTransient` is true, record `RETRIES_EXHAUSTED`; on `CallNotPermittedException`, record `CIRCUIT_OPEN` and throw `Status.UNAVAILABLE.withDescription("circuit open").asRuntimeException()`. `isTransient(t)` is `t instanceof StatusRuntimeException s && (s.getStatus().getCode() == UNAVAILABLE || ... == DEADLINE_EXCEEDED)`. The `DeadLetter.request` is `JsonFormat.printer().print(request)` (`implementation 'com.google.protobuf:protobuf-java-util'`); the trace id comes from `Span.current().getSpanContext().getTraceId()` (OpenTelemetry API, already on the classpath via the OTel starter), so the guard stays at 3 dependencies. `record()` must never throw: wrap the call.

- [ ] **Step 5: Route `LedgerGrpcClient` through the guard**: it now takes `channel`, `replies`, `guard` (3). Each public method passes its `Operation`, ids and message to `guard.call(...)` inside the existing try/catch. Update `LedgerGrpcClientTest` to construct a guard with permissive registries.

- [ ] **Step 6: Run** `LedgerGuardTest` and `LedgerGrpcClientTest`, expect PASS.

- [ ] **Step 7: Update the one scenario whose count changes**

With 3 attempts, "A retry after the quote expired ... the ledger was sent 1 posting" now sees 3 `Post` calls for one conversion. Change that line to "And the ledger was asked to post 1 conversion" with a step counting `distinctPosts()`. This keeps the scenario's meaning (one conversion, never reposted at a stale rate) and is the only `.feature` edit in the plan. The two "does not answer in time" scenarios now take about 6.6s each before PENDING; that is expected.

Run: `./gradlew :services:conversion-svc:acceptanceTest --console=plain; echo "exit=$?"`, expect exit=0, then `./gradlew :services:conversion-svc:check --console=plain`.

- [ ] **Step 8: Commit** with Step 3's red output in the body.

---

### Task 9: Kafka in the fleet and in Testcontainers

**Files:**
- Modify: `compose.yaml`, `gradle.properties`, `services/conversion-svc/build.gradle`
- Test: `services/conversion-svc/src/test/java/io/ezeebit/conversion/config/KafkaPinTest.java`

- [ ] **Step 1: Check the image base**

Run: `docker run --rm --entrypoint cat apache/kafka:4.3.1 /etc/os-release | head -3; echo "exit=$?"`
If it is not Alpine, run `curl -s 'https://hub.docker.com/v2/repositories/apache/kafka/tags?name=alpine&page_size=20' | python3 -c "import json,sys;print([t['name'] for t in json.load(sys.stdin)['results']])"`; if no Alpine tag exists, note that beside the pin (the rule allows a non-Alpine image when none exists).

- [ ] **Step 2: Pin with digest**

Run: `docker buildx imagetools inspect apache/kafka:4.3.1 | sed -n 1,4p` and take the index digest.

```properties
# The one home of the Kafka pin: compose.yaml carries the same string (KafkaPinTest) and Testcontainers reads it.
# 4.3.1 is the latest stable tag on Docker Hub, amd64+arm64, checked 2026-10-14. Base OS: <Step 1 result>.
kafkaImage=apache/kafka:4.3.1@sha256:<digest from Step 2>
```

- [ ] **Step 3: Write `KafkaPinTest` (failing)**, the same shape as `services/gateway-svc/src/test/java/io/ezeebit/gateway/config/RedisPinTest.java`, reading `System.getProperty("kafka.image")` and `compose.file`, pattern `apache/kafka:[0-9.]+@sha256:[0-9a-f]{64}`. Pass both system properties to every `Test` task in `services/conversion-svc/build.gradle`, copying how `services/gateway-svc/build.gradle` passes `redis.image` and `compose.file`. Run, expect FAIL (compose lacks the line).

- [ ] **Step 4: Add the compose service**

```yaml
  kafka:
    # Dead-letter records from conversion-svc. One KRaft broker, loopback only. Pin: gradle.properties kafkaImage (KafkaPinTest).
    image: '<kafkaImage value>'
    environment:
      - 'KAFKA_NODE_ID=1'
      - 'KAFKA_PROCESS_ROLES=broker,controller'
      - 'KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093'
      - 'KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:${KAFKA_PORT:-9092}'
      - 'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER'
      - 'KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093'
      - 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT'
      - 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1'
      - 'KAFKA_AUTO_CREATE_TOPICS_ENABLE=false'
    ports:
      - '127.0.0.1:${KAFKA_PORT:-9092}:9092'
    healthcheck:
      # Healthy only once the dead-letter topic exists: creating it is idempotent, and a broker that cannot is not ready.
      test: ['CMD-SHELL', '/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic ezeebit.conversion.ledger.deadletter --partitions 1 --replication-factor 1 --config retention.ms=604800000 && /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic ezeebit.conversion.ledger.deadletter']
      interval: 3s
      timeout: 10s
      retries: 40
```

Confirm `/opt/kafka/bin` in the image first (`docker run --rm --entrypoint ls apache/kafka:4.3.1 /opt/kafka/bin | head`). A non-default `KAFKA_PORT` changes the advertised listener so host clients reconnect to the published port; the in-container healthcheck stays on 9092. The advertised `localhost` makes the broker host-only, which matches the loopback-only rule.

- [ ] **Step 5: Run** `KafkaPinTest` (expect PASS), then, isolated from the developer's fleet:
`COMPOSE_PROJECT_NAME=ezeebit-wallet-kafkacheck KAFKA_PORT=19092 docker compose up -d --wait kafka; echo "exit=$?"; COMPOSE_PROJECT_NAME=ezeebit-wallet-kafkacheck docker compose down --volumes`
Expected: exit=0.

- [ ] **Step 6: Commit.**

---

### Task 10: The Kafka dead-letter publisher

**Files:**
- Create: `outbound/deadletter/KafkaDeadLetterPublisher.java`; delete `outbound/deadletter/LoggingDeadLetters.java` (Task 8)
- Modify: `services/conversion-svc/build.gradle`, `served/conversion-svc.yml`, `common/metrics/ConversionMetrics.java`, `config/properties/ConversionProperties.java`
- Test: `services/conversion-svc/src/acceptanceTest/java/io/ezeebit/conversion/outbound/deadletter/KafkaDeadLetterPublisherIT.java`

**Interfaces:**
- Consumes (created in Task 8):

```java
package io.ezeebit.conversion.service.port;

// Record-and-alert only: nothing consumes these, the sweeper settles the conversion. Never throws, never blocks.
public interface DeadLetters {

	void record(DeadLetter letter);

}
```

```java
package io.ezeebit.conversion.service.port;

import java.time.Instant;
import java.util.UUID;

public record DeadLetter(String op, UUID conversionId, UUID merchantId, String request, int attempts, String lastStatus,
		String lastReason, String reason, Instant at, String traceId) {
}
```

- Produces: `KafkaDeadLetterPublisher implements DeadLetters`; counters `ezeebit.conversion.ledger.deadletter{op,reason}` and `ezeebit.conversion.ledger.deadletter.publish.failed{op}`.

- [ ] **Step 1: Dependencies and config**

`implementation 'org.springframework.boot:spring-boot-starter-kafka'`, `acceptanceTestImplementation 'org.testcontainers:testcontainers-kafka'`. Pass `kafka.image` to the acceptance tasks too (Task 9 did it for `Test` tasks; the `integrationTest` task is a `Test`, check it inherits). Merge into `served/conversion-svc.yml`:

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:${KAFKA_PORT:9092}}
    producer:
      acks: all
      properties:
        enable.idempotence: true
        delivery.timeout.ms: 2000
        request.timeout.ms: 1500
        linger.ms: 0
        # send() blocks for metadata up to this: the merchant's request must never wait on a dead broker.
        max.block.ms: 250
conversion:
  dead-letter-topic: ezeebit.conversion.ledger.deadletter
```

Add `String deadLetterTopic` to `ConversionProperties`.

- [ ] **Step 2: Write `KafkaDeadLetterPublisherIT` (failing)** on `new KafkaContainer(System.getProperty("kafka.image"))` (`org.testcontainers.kafka.KafkaContainer`, which supports `apache/kafka`), creating the topic with `AdminClient` before the test:
  1. `record(letter)`, then a consumer reads exactly one record within 10s: key = conversion id, JSON fields equal to the letter's.
  2. `ezeebit.conversion.ledger.deadletter{op=POST,reason=RETRIES_EXHAUSTED}` = 1.
  3. With the container stopped: `record(letter)` returns in under 500ms without throwing, and within 5s `ezeebit.conversion.ledger.deadletter.publish.failed{op=POST}` = 1.

- [ ] **Step 3: Implement** `KafkaDeadLetterPublisher` (`@Component`, 3 dependencies: `KafkaTemplate<String, String>`, `ConversionMetrics`, `ConversionProperties`). Serialise the letter with a Jackson `JsonMapper` held in a `private static final` field (not injected). `template.send(topic, key, json).whenComplete((r, ex) -> { if (ex != null) metrics.deadLetterLost(op); })`, the whole call inside `try { ... } catch (KafkaException ex)` because a `max.block.ms` timeout throws synchronously; count `deadLettered` before sending (the attempt to record is the event the alert is about). Log at WARN with `LogText.oneLine`. Add to `ConversionMetrics` (use its existing registry field):

```java
	public static final String DEAD_LETTERS = "ezeebit.conversion.ledger.deadletter";
	public static final String DEAD_LETTER_FAILURES = "ezeebit.conversion.ledger.deadletter.publish.failed";

	public void deadLettered(final String op, final String reason) {
		registry.counter(DEAD_LETTERS, "op", op, "reason", reason).increment();
	}

	public void deadLetterLost(final String op) {
		registry.counter(DEAD_LETTER_FAILURES, "op", op).increment();
	}
```

Delete `LoggingDeadLetters`.

- [ ] **Step 4: Run the IT, expect PASS. Red-proof case 3**: set `max.block.ms: 60000` in the test's properties once, expect FAIL on the 500ms assertion, revert.

- [ ] **Step 5: Run** `./gradlew :services:conversion-svc:check --console=plain`. The Spring contexts in other tests now need a Kafka bootstrap: they get `localhost:9092` with nothing listening, which must not fail context start (the producer connects lazily). If any context fails, set `spring.kafka.bootstrap-servers` in the test system properties in the root `build.gradle` test block to `127.0.0.1:1`. Expect exit=0, commit.

---

### Task 11: Resilience4j on the rate feed (REST stays)

**Files:**
- Modify: `served/conversion-svc.yml`, `outbound/rates/RateClient.java`; create `outbound/rates/RateCall.java`
- Test: `services/conversion-svc/src/acceptanceTest/resources/features/rate-failures.feature` (2 new scenarios), `RateFeedSteps.java`

Budget: the rate fetch runs on the quote request: 2 attempts x 2s + 200ms = 4.2s, inside the gateway's 10s. No dead letters and no breaker here: a missing rate refuses the quote (`RATE_UNAVAILABLE`), already the right outcome.

- [ ] **Step 1: Add the scenarios (failing)**, reusing existing phrasings from `quotes.feature` and `rate-failures.feature` (read both first and adapt the wording to steps that exist):

```gherkin
  Scenario: A rate feed that answers late once is retried and the quote succeeds
    Given the rate feed answers its first request after 3 seconds and then normally
    When "Acme" asks for a quote to sell "100.00" ZAR for USDT
    Then the quote is created
    And the rate feed was asked 2 times

  Scenario: A rate feed error is not retried and is never dead-lettered
    Given the rate feed answers 500
    When "Acme" asks for a quote to sell "100.00" ZAR for USDT
    Then the request is refused with 503 RATE_UNAVAILABLE
    And the rate feed was asked 1 time
    And no dead letter was recorded
```

New step definitions use WireMock scenario states (first stub `withFixedDelay(3000)`, then the normal mapping); "no dead letter was recorded" asserts the dead-letter counter is absent or 0.

- [ ] **Step 2: Implement**: move the HTTP call `rates.get().uri(...).retrieve().body(RateDto.class)` into a package-private `@Component RateCall` (dependencies: the `rateRestClient`, `RetryRegistry`), decorated with `Retry.decorateSupplier(registry.retry("rates"), ...)`. `RateClient` keeps `RateCall`, `RateMapper`, `ConversionMetrics` (3). Configure `resilience4j.retry.instances.rates: { max-attempts: 2, wait-duration: 200ms, retry-exceptions: [org.springframework.web.client.ResourceAccessException] }`. A 5xx is `RestClientResponseException`, not retried.

- [ ] **Step 3: Run the rate scenarios, expect PASS; run `:services:conversion-svc:check`; commit.**

---

### Task 12: Alert rules and dashboards

**Files:**
- Modify: `docker/lgtm/generate.py`, `docker/lgtm/alerting/promtool/tests.yml`; regenerated `docker/lgtm/alerting/rules.yaml`, `docker/lgtm/alerting/promtool/rules.yml`, `docker/lgtm/dashboards/*.json`

- [ ] **Step 1: Find the real metric names** with the fleet up (after Task 13, or with Tasks 1 to 11 merged on the branch and `./fleet.sh up`), forcing one dead letter by stopping ledger and converting once:
`curl -s 'localhost:3000/api/datasources/proxy/uid/prometheus/api/v1/label/__name__/values' | tr ',' '\n' | grep -iE 'deadletter|kafka_producer|grpc_client|resilience4j' ; echo "exit=$?"`
Use exactly what this prints in Steps 2 and 4.

- [ ] **Step 2: Add three rules to `RULES`** in the existing dict shape (`ServiceDown` at `generate.py:49` is the model; every key it has, these have):
  - `ConversionLedgerDeadLettered`, warning, `for: 0s`, expr `sum by (op, reason) ` + `increase_new('<deadletter counter>', '5m')`, threshold 0, summary `{{ $labels.op }} to the ledger was dead-lettered ({{ $labels.reason }})`, runbook: read the topic with `docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic ezeebit.conversion.ledger.deadletter --from-beginning`; the sweeper settles the conversion; check ledger-svc with `./fleet.sh status`.
  - `DeadLetterPublishFailing`, critical, `for: 0s`, same form on the publish-failed counter, by `op`.
  - `KafkaAbsent`, critical, `for: 60s`, expr `(max by (job) (<producer connection gauge>{job="conversion-svc"}) == 0) or absent_over_time(<producer connection gauge>{job="conversion-svc"}[2m])` (the second arm pages a producer that never connected; `absent_over_time` per `_global/observability.md` section 2).
- [ ] **Step 3: promtool cases**: one firing and one silent case per rule in `tests.yml` (series tables in the style of the existing cases). Run `python3 docker/lgtm/generate.py && docker/lgtm/test-rules.sh; echo "exit=$?"`, expect exit=0. Red-proof each rule: copy the promtool rules file, flip that rule's threshold, run `docker/lgtm/test-rules.sh <copy>`, expect FAIL naming the rule; keep the three outputs for the commit body.
- [ ] **Step 4: Dashboards**: in `fleet()` add a collapsed row "Ledger gRPC" with rate, error ratio and p95 per gRPC method from conversion-svc's client metrics, plus a stat of the circuit-breaker state per `name=~"ledger.*"`; add a "Dead letters" timeseries of `sum by (op, reason) (increase(<deadletter counter>[5m]))`. Each new panel's description says what an empty result means.
- [ ] **Step 5: Run** `./gradlew checkDashboards --console=plain; echo "exit=$?"` (exit=0); commit generator and generated files together.

---

### Task 13: fleet.sh and a new dated sweep

**Files:**
- Modify: `fleet.sh`
- Create: `docs/fleet/verification/2026-10-14-fleet-sweep.zsh` (from `2026-10-13-fleet-sweep.zsh`) and its log

- [ ] **Step 1: fleet.sh**
  - `SUPPORT_GROUPS`: add `'Messaging:kafka'`; `CONTAINERS`: add `kafka:9092`. Check the RESULT count is computed, not typed: `grep -n '15' fleet.sh; echo "exit=$?"`.
  - `up_once`: `docker compose up -d --wait postgres wiremock redis kafka`.
  - `core_row` for ledger-svc: after the health check, `lsof -nP -a -p <ledger pid> -iTCP:${LEDGER_GRPC_PORT:-9081} -sTCP:LISTEN` must succeed; otherwise state `DEGRADED`, detail `gRPC port <port> not listening`.
  - `routes_once`: after the existing checks, for a fresh merchant UUID: deposit `100.00` ZAR to ledger `POST /internal/v1/deposits` (with `Idempotency-Key` and the body from the README demo), quote through the gateway, convert, and require `"status":"COMPLETED"`; otherwise the gateway row is `DEGRADED` with detail `a conversion did not complete: <status or error code>`. Copy the request bodies from the README's Demo section, do not invent them.
- [ ] **Step 2: Sweep**: copy the 2026-10-13 sweep into the new file; add `kafka` to case 3's container loop and `messaging` group check; add case 4b "kafka stopped: up bounces once" mirroring case 4 with `docker compose stop kafka`; change `all 15 checks` to `all 16 checks`; set `export KAFKA_PORT=${KAFKA_PORT:-9092}` beside the `REDIS_PORT` line. Commit fleet.sh and the sweep, then run it:
`zsh docs/fleet/verification/2026-10-14-fleet-sweep.zsh > docs/fleet/verification/2026-10-14-fleet-sweep.log 2>&1; echo "sweep_exit=$?" >> docs/fleet/verification/2026-10-14-fleet-sweep.log; tail -3 docs/fleet/verification/2026-10-14-fleet-sweep.log`
Expected: `SWEEP: N passed, 0 failed` and `sweep_exit=0`. Commit the log.

---

### Task 14: Full verification (no new code unless something fails)

- [ ] **Step 1: Full gate**

Run: `./gradlew --console=plain --no-build-cache --rerun-tasks clean build > build/gate.log 2>&1; echo "gate_exit=$?" >> build/gate.log; tail -5 build/gate.log`
Expected: `BUILD SUCCESSFUL` and `gate_exit=0`. Count tests: `find services -path '*test-results*' -name 'TEST-*.xml' -exec grep -ho 'tests="[0-9]*"' {} + | awk -F'"' '{s+=$2} END {print s}'`, must exceed 538.

- [ ] **Step 2: Fleet up and down twice**: `./fleet.sh up; ./fleet.sh status; ./fleet.sh down`, twice, then a final `up`, all with `.env` settings; every `up` and `status` exits 0 with `RESULT: healthy, all 16 checks passed`. Log to `docs/fleet/verification/2026-10-14-cycles.log` with each exit code.

- [ ] **Step 3: Rehearsed outage and chaos audit** (Slack paging is allowed in dev):
  1. Record `t0`; `kill -9 $(<.fleet/ledger-svc.pid)`.
  2. Run 5 conversions for fresh merchants through the gateway; each answers 504 `UPSTREAM_OUTCOME_UNKNOWN` or 201 with `PENDING`.
  3. Record when `ConversionLedgerDeadLettered` goes pending and firing (`curl -s localhost:3000/api/prometheus/grafana/api/v1/alerts`) and when `grafana_alerting_notifications_total{integration="slack"}` increments.
  4. Read the topic: at least 5 records, every field present.
  5. `./fleet.sh up` (its one bounce restarts ledger); wait deadline + 30s + one sweep interval.
  6. Audit in the `conversion` and `ledger` databases: zero PENDING conversions; on the ledger's conversion journals `count(*) = count(distinct conversion_id)`; every COMPLETED conversion has exactly one POSTED journal; books balance (reuse the statement the acceptance step "the ledger's books balance" runs).
  7. Write the timing table and every audit query with its output to `docs/observability/verification/2026-10-14-ledger-grpc-outage.md`.

- [ ] **Step 4: Screenshots** of the new dashboard row and the firing alert, using a 2026-10-14 copy of the 2026-10-13 capture script in `docs/observability/verification/`; open and inspect each PNG.

- [ ] **Step 5: Critical reviewer** (session model) over `git diff dev...feat-ledger-grpc`, the spec and this plan, briefed on completeness first: every spec section has code and a test; every fault path was run; `grep -rn 'conversion-postings' services fleet.sh README.md ARCHITECTURE.md` shows no HTTP use left; the dependency-injection gate output. Fix confirmed findings and re-run Step 1.

---

### Task 15: Documentation, after Task 14 passes

- [ ] `README.md`: API reference note (ledger's internal conversion calls are gRPC, contract at `contracts/ledger/v1/conversion_postings.proto`); variables rows `LEDGER_GRPC_PORT`, `KAFKA_PORT`, `KAFKA_BOOTSTRAP`; sample `.env` adds `# KAFKA_PORT=9092`; fleet sample output and text say Messaging and 16 checks; free-ports list adds 9081 and 9092; troubleshooting rows for `gRPC port 9081 not listening`, `a conversion did not complete`, and reading the dead-letter topic; one sentence on the Kubernetes security gap.
- [ ] `ARCHITECTURE.md`: replace the conversion-postings HTTP flow with the gRPC flow, the two services and the status table.
- [ ] `docs/observability.md`: the three rules, the new panels, the outage timing (link Task 14's file).
- [ ] `EVIDENCE.md`: new screenshots and the outage timing row.
- [ ] Fleet diagram: split `docs/fleet/fleet-architecture.html` into an overview and a detail (Conversion, Ledger, Kafka, the gRPC and dead-letter paths); run the diagram self-check and screenshot both.
- [ ] Memory: update or delete `ports-refactor-pending.md` (the ledger and rate seams are now behind ports).
- [ ] Em-dash check on every touched doc (`grep -n "$(printf '\xe2\x80\x94')" <files>`); curl any new external URL for a 200. Commit, then hand back for promotion to `dev` (merge `dev` into the branch first, full gate, `--no-ff`).
