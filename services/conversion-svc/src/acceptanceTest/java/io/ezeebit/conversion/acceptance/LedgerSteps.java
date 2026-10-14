package io.ezeebit.conversion.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.conversion.acceptance.LedgerFaultProxy.Fault;
import io.ezeebit.conversion.scheduler.PendingConversionSweeper;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.springframework.jdbc.core.simple.JdbcClient;

// Faults are injected between conversion-svc and the ledger; the ledger itself is never altered.
public class LedgerSteps {

	// Longer than conversion-svc's 2 s deadline on each ledger call.
	private static final Duration TOO_SLOW = Duration.ofSeconds(3);
	// The ledger's own reason-to-status table (ledger-svc api/advice/ErrorStatus) for the refusals a Post can meet.
	private static final Map<String, Integer> HTTP_STATUS = Map.of("CONVERSION_VOIDED", 409, "POSTING_DEADLINE_PASSED",
			409, "CONVERSION_ALREADY_POSTED", 409, "IDEMPOTENCY_KEY_REUSED", 422, "INSUFFICIENT_FUNDS", 422,
			"BALANCE_LIMIT_EXCEEDED", 422);
	// The step text keeps the ledger's HTTP-era numbers; over gRPC the reason decides the status.
	private static final Map<String, Status.Code> GRPC_CODE = Map.of("AMOUNT_TOO_LARGE", Status.Code.INVALID_ARGUMENT,
			"IDEMPOTENCY_KEY_REUSED", Status.Code.FAILED_PRECONDITION);
	// The first request has reached the ledger and been answered there; its reply is still delayed.
	private static final long RACE_HEAD_START_MILLIS = 250;

	private final Http http;
	private final ScenarioState state;
	private final JdbcClient conversionDb;
	private final PendingConversionSweeper sweeper;

	public LedgerSteps(final Http http, final ScenarioState state, final JdbcClient conversionDb,
			final PendingConversionSweeper sweeper) {
		this.http = http;
		this.state = state;
		this.conversionDb = conversionDb;
		this.sweeper = sweeper;
	}

	@Given("the ledger does not answer in time")
	public void neverReaches() {
		proxy().post(Fault.unanswered(TOO_SLOW));
	}

	@Given("the ledger posts conversions but its answers are lost")
	public void answerLost() {
		proxy().post(Fault.answeredLate(TOO_SLOW));
	}

	// Each posting reaches the ledger at once; only its answer waits, so the race below sees the ledger's first verdict.
	@Given("the ledger's first answer takes {int} ms and its second {int} ms")
	public void answersInTurn(final int first, final int second) {
		proxy().posts(Fault.answeredLate(Duration.ofMillis(first)), Fault.answeredLate(Duration.ofMillis(second)));
	}

	@Given("the ledger refuses every posting with {int} {word}")
	public void refusesEvery(final int status, final String code) {
		Status.Code grpc = GRPC_CODE.get(code);
		assertThat(grpc).as("no gRPC status mapped for %s", code).isNotNull();
		proxy().post(Fault.refuse(grpc, code));
	}

	@When("{string} accepts quote {string} with key {string}, and while that answer is on its way"
			+ " {string} {word} arrives and {string} sends the same request again")
	public void raceWithDeposit(final String merchant, final String quote, final String key, final String amount,
			final String currency, final String sameMerchant) {
		UUID merchantId = state.merchant(merchant);
		String path = "/v1/merchants/%s/conversions".formatted(merchantId);
		String body = "{\"quoteId\":\"%s\"}".formatted(state.quoteId(quote));
		String deposit = """
				{"merchantId":"%s","currency":"%s","amount":"%s","reference":"late"}""".formatted(merchantId, currency, amount);
		CompletableFuture<Reply> first = CompletableFuture.supplyAsync(() -> http.conversion("POST", path, key, body));
		pause(RACE_HEAD_START_MILLIS);
		Reply funded = http.ledger("POST", "/internal/v1/deposits", "late-" + merchantId, deposit);
		assertThat(funded.status()).as(funded.body()).isEqualTo(201);
		Reply second = http.conversion("POST", path, key, body);
		Reply firstReply = first.join();
		state.recordAll(List.of(firstReply, second));
		Arrays.asList(firstReply, second).forEach(reply -> {
			if (reply.body().contains("\"conversionId\"")) {
				state.converted(reply.status() < 300 ? reply.read("$.data.conversionId") : reply.read("$.conversionId"),
						merchantId);
			}
		});
	}

	@Then("both answers are refused with {int} {word}")
	public void bothRefused(final int status, final String code) {
		assertThat(state.replies()).hasSize(2).allSatisfy(reply -> {
			assertThat(reply.status()).as(reply.body()).isEqualTo(status);
			assertThat(reply.<String>read("$.code")).isEqualTo(code);
		});
	}

	// COMPLETED exactly when the ledger holds the posting; anything else exactly when it does not.
	@Then("the conversion and the ledger agree")
	public void conversionAndLedgerAgree() {
		String conversionId = state.conversion();
		Reply conversion = http.conversion("GET", "/v1/merchants/%s/conversions/%s"
				.formatted(state.merchantOf(conversionId), conversionId), null, null);
		List<String> ledger = Externals.get().ledgerDb().sql("""
				SELECT status FROM journal WHERE type = 'CONVERSION' AND idempotency_key = :id""")
				.param("id", conversionId).query(String.class).list();
		boolean completed = "COMPLETED".equals(conversion.read("$.data.status"));
		assertThat(ledger.contains("POSTED")).as("conversion %s, ledger %s", conversion.body(), ledger).isEqualTo(completed);
	}

	@Given("the ledger answers normally again")
	public void healthy() {
		proxy().clearFaults();
	}

	@Given("{string} has {int} PENDING conversions of {string} {word} for {word}")
	public void pendingConversions(final String merchant, final int count, final String amount, final String sell,
			final String buy) {
		UUID id = state.merchant(merchant);
		for (int i = 0; i < count; i++) {
			Reply quote = http.conversion("POST", "/v1/merchants/%s/quotes".formatted(id), null, """
					{"sell":"%s","buy":"%s","sellAmount":"%s"}""".formatted(sell, buy, amount));
			Reply accepted = http.conversion("POST", "/v1/merchants/%s/conversions".formatted(id), "pending-" + i,
					"{\"quoteId\":\"%s\"}".formatted(quote.<String>read("$.data.quoteId")));
			assertThat(accepted.status()).as(accepted.body()).isEqualTo(202);
			state.converted(accepted.read("$.data.conversionId"), id);
		}
	}

	@When("the posting deadline of the conversion has passed")
	public void deadlinePassed() {
		passDeadline(state.conversion());
	}

	@Given("the posting deadline of every conversion has passed")
	public void everyDeadlinePassed() {
		state.conversions().forEach(this::passDeadline);
	}

	@When("the sweeper runs")
	public void sweeperRuns() {
		sweeper.sweep();
	}

	@When("two sweepers run at the same time")
	public void twoSweepers() {
		AtOnce.run(2, i -> {
			sweeper.sweep();
			return i;
		});
	}

	@When("the lost posting finally reaches the ledger")
	public void latePosting() {
		PostingRequest sent = proxy().posting(state.conversion()).orElseThrow();
		ManagedChannel ledger = Grpc.newChannelBuilderForAddress("127.0.0.1", Externals.get().ledgerGrpcPort(),
				InsecureChannelCredentials.create()).build();
		try {
			ConversionPostingsGrpc.newBlockingStub(ledger).post(sent);
			state.record(new Reply(201, "{}", 0));
		}
		catch (StatusRuntimeException refused) {
			String reason = StatusProto.fromThrowable(refused).getDetailsList().stream()
					.filter(detail -> detail.is(ErrorInfo.class)).map(LedgerSteps::reason).findFirst().orElseThrow();
			Integer status = HTTP_STATUS.get(reason);
			assertThat(status).as("no HTTP status mapped for %s", reason).isNotNull();
			state.record(new Reply(status, "{\"status\":%d,\"code\":\"%s\"}".formatted(status, reason), 0));
		}
		finally {
			ledger.shutdownNow();
		}
	}

	@Then("the ledger holds exactly {int} conversion(s) for {string}")
	public void ledgerHolds(final int count, final String merchant) {
		assertThat(Externals.get().ledgerDb().sql("""
				SELECT count(*) FROM journal WHERE merchant_id = :merchant AND type = 'CONVERSION' AND status = 'POSTED'""")
				.param("merchant", state.merchant(merchant)).query(Integer.class).single()).isEqualTo(count);
	}

	@Then("the ledger was sent {int} posting(s)")
	public void postingsSent(final int count) {
		assertThat(proxy().calls("Post")).isEqualTo(count);
	}

	@Then("the ledger was asked to void each of them exactly once")
	public void voidedOnce() {
		assertThat(state.conversions()).isNotEmpty()
				.allSatisfy(id -> assertThat(proxy().calls("Void", id)).as(id).isEqualTo(1));
	}

	@Then("the ledger's books balance")
	public void booksBalance() {
		JdbcClient ledger = Externals.get().ledgerDb();
		assertThat(ledger.sql("""
				SELECT count(*) FROM (SELECT journal_id, currency FROM entry
				GROUP BY journal_id, currency HAVING sum(amount) <> 0) unbalanced""").query(Integer.class).single())
				.as("journals that do not sum to zero per currency").isZero();
		assertThat(ledger.sql("""
				SELECT count(*) FROM account a WHERE a.type = 'MERCHANT'
				AND a.balance <> (SELECT coalesce(sum(e.amount), 0) FROM entry e WHERE e.account_id = a.id)""")
				.query(Integer.class).single()).as("merchant balances off their entries").isZero();
		assertThat(ledger.sql("SELECT count(*) FROM account WHERE type = 'MERCHANT' AND balance < 0")
				.query(Integer.class).single()).as("negative merchant balances").isZero();
	}

	private void passDeadline(final String conversionId) {
		conversionDb.sql("UPDATE conversion SET deadline = now() - interval '1 hour' WHERE id = :id")
				.param("id", UUID.fromString(conversionId)).update();
	}

	private static void pause(final long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private static String reason(final Any detail) {
		try {
			return detail.unpack(ErrorInfo.class).getReason();
		}
		catch (InvalidProtocolBufferException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static LedgerFaultProxy proxy() {
		return Externals.get().ledgerProxy();
	}

}
