package io.ezeebit.ledger.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.jdbc.core.simple.JdbcClient;

public class DepositSteps {

	private final LedgerClient ledger;
	private final ScenarioState state;
	private final JdbcClient jdbc;

	public DepositSteps(final LedgerClient ledger, final ScenarioState state, final JdbcClient jdbc) {
		this.ledger = ledger;
		this.state = state;
		this.jdbc = jdbc;
	}

	@Given("a merchant {string}")
	public void aMerchant(final String merchant) {
		state.merchant(merchant);
	}

	@When("the rail deposits {string} {word} for {string} with key {string}")
	public void theRailDeposits(final String amount, final String currency, final String merchant, final String key) {
		String body = DepositBody.of(state.merchant(merchant), currency, amount, "transfer %s".formatted(key));
		state.sent(key, body);
		state.record(ledger.deposit(key, body));
	}

	@Given("the rail has deposited {string} {word} for {string} with key {string}")
	public void theRailHasDeposited(final String amount, final String currency, final String merchant, final String key) {
		theRailDeposits(amount, currency, merchant, key);
		theDepositIsAccepted();
	}

	@When("the rail sends the deposit with key {string} again")
	public void theRailSendsAgain(final String key) {
		state.record(ledger.deposit(key, state.bodySentWith(key)));
	}

	@When("the rail deposits {string} {word} for {string} without an idempotency key")
	public void withoutKey(final String amount, final String currency, final String merchant) {
		state.record(ledger.deposit(null, DepositBody.of(state.merchant(merchant), currency, amount, "no key")));
	}

	@When("the rail sends the body {string} with key {string}")
	public void theRailSendsTheBody(final String body, final String key) {
		state.record(ledger.deposit(key, body));
	}

	@Then("the deposit is accepted")
	public void theDepositIsAccepted() {
		assertThat(state.last().status()).as(state.last().body()).isEqualTo(201);
	}

	@Then("the deposit is accepted with a balance after of {string}")
	public void acceptedWithBalanceAfter(final String balanceAfter) {
		theDepositIsAccepted();
		assertThat(state.last().<String>read("$.data.balanceAfter")).isEqualTo(balanceAfter);
	}

	@Then("the request is refused with {int} {word}")
	public void theRequestIsRefused(final int status, final String code) {
		assertThat(state.last().status()).as(state.last().body()).isEqualTo(status);
		assertThat(state.last().<String>read("$.code")).isEqualTo(code);
	}

	@Then("the problem detail reads {string}")
	public void theProblemDetailReads(final String detail) {
		assertThat(state.last().<String>read("$.detail")).isEqualTo(detail);
	}

	@Then("both answers are identical")
	public void bothAnswersAreIdentical() {
		var replies = state.replies();
		assertThat(replies.get(replies.size() - 1)).isEqualTo(replies.get(replies.size() - 2));
	}

	@Then("exactly {int} deposit(s) is/are recorded for {string} with key {string}")
	public void depositsRecorded(final int count, final String merchant, final String key) {
		Integer journals = jdbc.sql("SELECT count(*) FROM journal WHERE merchant_id = :merchant AND idempotency_key = :key")
				.param("merchant", state.merchant(merchant)).param("key", key).query(Integer.class).single();
		assertThat(journals).isEqualTo(count);
	}

}
