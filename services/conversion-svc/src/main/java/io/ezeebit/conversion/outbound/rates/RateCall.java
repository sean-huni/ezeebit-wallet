package io.ezeebit.conversion.outbound.rates;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// One fetch, retried per the served "rates" instance: a timeout is worth a second try, an error status is not.
@Component
class RateCall {

	private final RestClient rates;
	private final Retry retry;

	RateCall(@Qualifier("rateRestClient") final RestClient rates, final RetryRegistry registry) {
		this.rates = rates;
		this.retry = registry.retry("rates");
	}

	RateDto get(final String base, final String quote) {
		// A template, so the client timer's uri tag is the route and not one value per pair.
		return Retry.decorateSupplier(retry,
				() -> rates.get().uri("/rates?base={base}&quote={quote}", base, quote).retrieve().body(RateDto.class))
				.get();
	}

}
