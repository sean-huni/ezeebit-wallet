package io.ezeebit.conversion.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.ezeebit.conversion.scheduler.PendingConversionSweeper;
import org.springframework.jdbc.core.simple.JdbcClient;

// Faults are injected between conversion-svc and the ledger; the ledger itself is never altered.
public class LedgerSteps {

	private static final String POSTINGS = "/internal/v1/conversion-postings";
	private static final int FAULT = 1;
	// Longer than the 2 s ledger read timeout these tests run conversion-svc with.
	private static final int TOO_SLOW_MILLIS = 3_500;
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
		proxy().stubFor(post(urlEqualTo(POSTINGS)).atPriority(FAULT)
				.willReturn(aResponse().withStatus(504).withFixedDelay(TOO_SLOW_MILLIS)));
	}

	// The proxy forwards first and delays its reply after: the posting commits, the answer arrives too late.
	@Given("the ledger posts conversions but its answers are lost")
	public void answerLost() {
		proxy().stubFor(post(urlEqualTo(POSTINGS)).atPriority(FAULT)
				.willReturn(aResponse().proxiedFrom(Externals.get().ledgerUrl()).withFixedDelay(TOO_SLOW_MILLIS)));
	}

	@Given("the ledger's first answer takes {int} ms and its second {int} ms")
	public void answersInTurn(final int first, final int second) {
		proxy().stubFor(post(urlEqualTo(POSTINGS)).atPriority(FAULT).inScenario("turns").whenScenarioStateIs(STARTED)
				.willSetStateTo("second")
				.willReturn(aResponse().proxiedFrom(Externals.get().ledgerUrl()).withFixedDelay(first)));
		proxy().stubFor(post(urlEqualTo(POSTINGS)).atPriority(FAULT).inScenario("turns").whenScenarioStateIs("second")
				.willReturn(aResponse().proxiedFrom(Externals.get().ledgerUrl()).withFixedDelay(second)));
	}

	@Given("the ledger refuses every posting with {int} {word}")
	public void refusesEvery(final int status, final String code) {
		proxy().stubFor(post(urlEqualTo(POSTINGS)).atPriority(FAULT).willReturn(aResponse().withStatus(status)
				.withHeader("Content-Type", "application/problem+json")
				.withBody("{\"status\":%d,\"code\":\"%s\"}".formatted(status, code))));
	}

	@When("{string} accepts quote {string} with key {string}, and while that answer is on its way {string} {word} arrives and {string} sends the same request again")
	public void raceWithDeposit(final String merchant, final String quote, final String key, final String amount,
			final String currency, final String sameMerchant) {
		UUID merchantId = state.merchant(merchant);
		String path = "/api/v1/merchants/%s/conversions".formatted(merchantId);
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
		Reply conversion = http.conversion("GET", "/api/v1/merchants/%s/conversions/%s"
				.formatted(state.merchantOf(conversionId), conversionId), null, null);
		List<String> ledger = Externals.get().ledgerDb().sql("""
				SELECT status FROM journal WHERE type = 'CONVERSION' AND idempotency_key = :id""")
				.param("id", conversionId).query(String.class).list();
		boolean completed = "COMPLETED".equals(conversion.read("$.data.status"));
		assertThat(ledger.contains("POSTED")).as("conversion %s, ledger %s", conversion.body(), ledger).isEqualTo(completed);
	}

	@Given("the ledger answers normally again")
	public void healthy() {
		proxy().getStubMappings().stream().filter(stub -> Integer.valueOf(FAULT).equals(stub.getPriority()))
				.toList().forEach(proxy()::removeStub);
	}

	@Given("{string} has {int} PENDING conversions of {string} {word} for {word}")
	public void pendingConversions(final String merchant, final int count, final String amount, final String sell,
			final String buy) {
		UUID id = state.merchant(merchant);
		for (int i = 0; i < count; i++) {
			Reply quote = http.conversion("POST", "/api/v1/merchants/%s/quotes".formatted(id), null, """
					{"sell":"%s","buy":"%s","sellAmount":"%s"}""".formatted(sell, buy, amount));
			Reply accepted = http.conversion("POST", "/api/v1/merchants/%s/conversions".formatted(id), "pending-" + i,
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
		String conversionId = state.conversion();
		String body = proxy().findAll(postRequestedFor(urlEqualTo(POSTINGS))).stream()
				.map(LoggedRequest::getBodyAsString).filter(sent -> sent.contains(conversionId)).findFirst().orElseThrow();
		state.record(http.ledger("POST", POSTINGS, null, body));
	}

	@Then("the ledger holds exactly {int} conversion(s) for {string}")
	public void ledgerHolds(final int count, final String merchant) {
		assertThat(Externals.get().ledgerDb().sql("""
				SELECT count(*) FROM journal WHERE merchant_id = :merchant AND type = 'CONVERSION' AND status = 'POSTED'""")
				.param("merchant", state.merchant(merchant)).query(Integer.class).single()).isEqualTo(count);
	}

	@Then("the ledger was sent {int} posting(s)")
	public void postingsSent(final int count) {
		assertThat(proxy().findAll(postRequestedFor(urlEqualTo(POSTINGS)))).hasSize(count);
	}

	@Then("the ledger was asked to void each of them exactly once")
	public void voidedOnce() {
		assertThat(state.conversions()).isNotEmpty().allSatisfy(id -> assertThat(proxy()
				.findAll(postRequestedFor(urlEqualTo("%s/%s/void".formatted(POSTINGS, id))))).as(id).hasSize(1));
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

	private static WireMockServer proxy() {
		return Externals.get().ledgerProxy();
	}

}
