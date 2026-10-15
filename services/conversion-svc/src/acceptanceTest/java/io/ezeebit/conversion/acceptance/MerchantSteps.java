package io.ezeebit.conversion.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

// Money arrives through the ledger's own deposit endpoint, exactly as the money-in rail sends it.
public class MerchantSteps {

	private final Http http;
	private final ScenarioState state;

	public MerchantSteps(final Http http, final ScenarioState state) {
		this.http = http;
		this.state = state;
	}

	@Given("a merchant {string}")
	public void aMerchant(final String merchant) {
		state.merchant(merchant);
	}

	@Given("a merchant {string} holding {string} {word}")
	public void aMerchantHolding(final String merchant, final String amount, final String currency) {
		UUID id = state.merchant(merchant);
		Reply deposit = http.ledger("POST", "/internal/v1/deposits", "seed-" + id, """
				{"merchantId":"%s","currency":"%s","amount":"%s","reference":"seed"}""".formatted(id, currency, amount));
		assertThat(deposit.status()).as(deposit.body()).isEqualTo(201);
	}

	@Then("{string} holds {string} {word}")
	public void holds(final String merchant, final String balance, final String currency) {
		assertThat(balances(merchant)).contains(Map.of("currency", currency, "balance", balance));
	}

	@Then("{string} holds exactly:")
	public void holdsExactly(final String merchant, final DataTable expected) {
		assertThat(balances(merchant)).isEqualTo(expected.asMaps());
	}

	@Then("the request is refused with {int} {word}")
	public void refused(final int status, final String code) {
		assertThat(state.last().status()).as(state.last().body()).isEqualTo(status);
		assertThat(state.last().<String>read("$.code")).isEqualTo(code);
	}

	@Then("the problem names the field {string}")
	public void namesTheField(final String field) {
		assertThat(state.last().<List<String>>read("$.errors[*].field")).as(state.last().body()).contains(field);
	}

	private List<Map<String, String>> balances(final String merchant) {
		Reply reply = http.ledger("GET", "/v1/merchants/%s/balances".formatted(state.merchant(merchant)), null, null);
		assertThat(reply.status()).as(reply.body()).isEqualTo(200);
		return reply.read("$.data");
	}

}
