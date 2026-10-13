package io.ezeebit.conversion.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import io.cucumber.java.en.Given;

// Each fault takes priority over the healthy feed for the rest of the scenario.
public class RateFeedSteps {

	private static final int FAULT = 1;
	// Longer than the 2 s read timeout served for the rate client.
	private static final int TOO_SLOW_MILLIS = 3_500;

	@Given("the rate service answers too slowly")
	public void tooSlow() {
		Externals.get().rates().stubFor(RateFeed.any().atPriority(FAULT)
				.willReturn(RateFeed.rate("0.054254", "{{now}}").withFixedDelay(TOO_SLOW_MILLIS)));
	}

	@Given("the rate service fails with status {int}")
	public void fails(final int status) {
		Externals.get().rates().stubFor(RateFeed.any().atPriority(FAULT).willReturn(aResponse().withStatus(status)));
	}

	@Given("the rate service quotes {word} in {word} at {string} now")
	public void quotesNow(final String base, final String quote, final String rate) {
		Externals.get().rates().stubFor(RateFeed.pair(base, quote).atPriority(FAULT)
				.willReturn(RateFeed.rate(rate, "{{now}}")));
	}

	@Given("the rate service quotes {word} in {word} at {string} {int} seconds ago")
	public void quotesEarlier(final String base, final String quote, final String rate, final int seconds) {
		Externals.get().rates().stubFor(RateFeed.pair(base, quote).atPriority(FAULT)
				.willReturn(RateFeed.rate(rate, "{{now offset='-%d seconds'}}".formatted(seconds))));
	}

	@Given("the rate service answers with something that is not a rate")
	public void notARate() {
		Externals.get().rates().stubFor(RateFeed.any().atPriority(FAULT).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("<html>maintenance</html>")));
	}

}
