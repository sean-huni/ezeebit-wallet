package io.ezeebit.ledger.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class BalanceSteps {

	private final LedgerClient ledger;
	private final ScenarioState state;

	public BalanceSteps(final LedgerClient ledger, final ScenarioState state) {
		this.ledger = ledger;
		this.state = state;
	}

	@Then("{string} holds {string} {word}")
	public void holds(final String merchant, final String balance, final String currency) {
		assertThat(balances(merchant)).contains(Map.of("currency", currency, "balance", balance));
	}

	@Then("{string} holds nothing")
	public void holdsNothing(final String merchant) {
		assertThat(balances(merchant)).isEmpty();
	}

	@Then("{string} holds exactly:")
	public void holdsExactly(final String merchant, final DataTable expected) {
		assertThat(balances(merchant)).isEqualTo(expected.asMaps());
	}

	@When("anyone reads the balances of {string} through API version {string}")
	public void readsThroughVersion(final String merchant, final String version) {
		state.record(ledger.get("/%s/merchants/%s/balances".formatted(version, state.merchant(merchant))));
	}

	@When("anyone reads the balances of merchant id {string}")
	public void readsByRawId(final String rawId) {
		state.record(ledger.get("/v1/merchants/%s/balances".formatted(rawId)));
	}

	private List<Map<String, String>> balances(final String merchant) {
		Reply reply = ledger.get("/v1/merchants/%s/balances".formatted(state.merchant(merchant)));
		assertThat(reply.status()).as(reply.body()).isEqualTo(200);
		return reply.read("$.data");
	}

}
