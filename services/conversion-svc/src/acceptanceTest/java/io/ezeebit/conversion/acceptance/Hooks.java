package io.ezeebit.conversion.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

import io.cucumber.java.Before;

// Every scenario starts with a healthy rate feed and a ledger proxy that passes everything through.
public class Hooks {

	@Before
	public void healthyDependencies() {
		Externals externals = Externals.get();
		RateFeed.reset(externals.rates());
		externals.ledgerProxy().resetAll();
		externals.ledgerProxy().stubFor(any(anyUrl()).atPriority(10)
				.willReturn(aResponse().proxiedFrom(externals.ledgerUrl())));
	}

}
