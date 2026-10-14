package io.ezeebit.ledger.acceptance;

import io.cucumber.java.en.When;

public class RoutingSteps {

	private final LedgerClient ledger;
	private final ScenarioState state;

	public RoutingSteps(final LedgerClient ledger, final ScenarioState state) {
		this.ledger = ledger;
		this.state = state;
	}

	@When("the path {string} is requested")
	public void requested(final String path) {
		state.record(ledger.get(path));
	}

}
