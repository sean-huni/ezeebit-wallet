package io.ezeebit.conversion.acceptance;

import io.cucumber.java.Before;

// Every scenario starts with a healthy rate feed and a ledger proxy that passes everything through.
public class Hooks {

	@Before
	public void healthyDependencies() {
		Externals externals = Externals.get();
		RateFeed.reset(externals.rates());
		externals.ledgerProxy().reset();
	}

}
