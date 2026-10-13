package io.ezeebit.conversion.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

// Faults on top of the assumed rate-service contract: GET /rates?base=&quote= answers {base, quote, rate, timestamp}.
final class RateFeed {

	private RateFeed() {
	}

	// Back to the committed docker/wiremock mappings: the normal rate for every supported pair.
	static void reset(final WireMockServer rates) {
		rates.resetToDefaultMappings();
	}

	static MappingBuilder pair(final String base, final String quote) {
		return get(urlPathEqualTo("/rates")).withQueryParam("base", equalTo(base)).withQueryParam("quote", equalTo(quote));
	}

	static MappingBuilder any() {
		return get(urlPathEqualTo("/rates"));
	}

	static ResponseDefinitionBuilder rate(final String rate, final String timestamp) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
				{"base":"{{request.query.base}}","quote":"{{request.query.quote}}","rate":"%s","timestamp":"%s"}"""
				.formatted(rate, timestamp));
	}

}
