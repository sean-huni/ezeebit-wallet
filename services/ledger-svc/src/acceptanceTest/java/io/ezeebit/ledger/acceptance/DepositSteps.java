package io.ezeebit.ledger.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class DepositSteps {

	private final LedgerClient ledger;
	private final ScenarioState state;

	public DepositSteps(final LedgerClient ledger, final ScenarioState state) {
		this.ledger = ledger;
		this.state = state;
	}

	@Given("a merchant {string}")
	public void aMerchant(final String merchant) {
		state.merchant(merchant);
	}

	@When("the rail deposits {string} {word} for {string} with key {string}")
	public void theRailDeposits(final String amount, final String currency, final String merchant, final String key) {
		String body = DepositBody.of(state.merchant(merchant), currency, amount, "transfer %s".formatted(key));
		state.sent(key, body);
		Reply reply = ledger.deposit(key, body);
		state.answered(key, reply);
		state.record(reply);
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

	@When("the rail deposits {string} {word} for {string} with key {string} and a reference containing a NUL character")
	public void referenceWithNul(final String amount, final String currency, final String merchant, final String key) {
		state.record(ledger.deposit(key, DepositBody.of(state.merchant(merchant), currency, amount, "bad\\u0000ref")));
	}

	@When("the rail deposits {string} {word} for {string} with a key containing a tab")
	public void keyWithTab(final String amount, final String currency, final String merchant) {
		state.record(ledger.deposit("bad\tkey", DepositBody.of(state.merchant(merchant), currency, amount, "tab key")));
	}

	@When("the rail sends an amount of {double} as a JSON number for {string} with key {string}")
	public void amountAsNumber(final double ignored, final String merchant, final String key) {
		String body = """
				{"merchantId":"%s","currency":"ZAR","amount":10.00,"reference":"number"}""".formatted(state.merchant(merchant));
		state.record(ledger.deposit(key, body));
	}

	@When("the rail sends a deposit whose merchantId is {string} with key {string}")
	public void merchantIdOf(final String merchantId, final String key) {
		String id = "null".equals(merchantId) ? "null" : "\"%s\"".formatted(merchantId);
		String body = """
				{"merchantId":%s,"currency":"ZAR","amount":"10.00","reference":"r"}""".formatted(id);
		state.record(ledger.deposit(key, body));
	}

	@Then("the problem names the field {string}")
	public void theProblemNamesTheField(final String field) {
		assertThat(state.last().<List<String>>read("$.errors[*].field")).as(state.last().body()).contains(field);
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
}
