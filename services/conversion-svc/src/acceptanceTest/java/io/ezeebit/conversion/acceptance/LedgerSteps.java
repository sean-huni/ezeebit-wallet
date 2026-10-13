package io.ezeebit.conversion.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

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
	// Longer than the 1 s ledger read timeout these tests run conversion-svc with.
	private static final int TOO_SLOW_MILLIS = 2_500;

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

	private static WireMockServer proxy() {
		return Externals.get().ledgerProxy();
	}

}
