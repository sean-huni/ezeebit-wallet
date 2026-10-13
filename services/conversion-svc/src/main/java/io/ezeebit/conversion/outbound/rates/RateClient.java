package io.ezeebit.conversion.outbound.rates;

import java.time.Duration;

import io.ezeebit.conversion.common.ConversionMetrics;
import io.ezeebit.conversion.common.LogText;
import io.ezeebit.conversion.domain.MarketRate;
import io.ezeebit.conversion.domain.RateRejection;
import io.ezeebit.conversion.domain.RateValidator;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Fails closed: a timeout, a refused connection or an error status is 503, never a cached or default rate.
@Component
public class RateClient {

	private static final Logger LOG = LoggerFactory.getLogger(RateClient.class);

	private final RestClient rates;
	private final RateMapper mapper;
	private final ConversionMetrics metrics;

	public RateClient(@Qualifier("rateRestClient") final RestClient rates, final RateMapper mapper,
			final ConversionMetrics metrics) {
		this.rates = rates;
		this.mapper = mapper;
		this.metrics = metrics;
	}

	public MarketRate fetch(final String base, final String quote) {
		RateDto dto;
		long started = System.nanoTime();
		try {
			dto = rates.get().uri(uri -> uri.path("/rates").queryParam("base", base).queryParam("quote", quote).build())
					.retrieve().body(RateDto.class);
		}
		catch (ResourceAccessException | RestClientResponseException ex) {
			metrics.rateCall(since(started), "unavailable");
			LOG.warn("rate service unavailable for {}/{}: {}", LogText.oneLine(base), LogText.oneLine(quote),
					LogText.oneLine(ex.getMessage()));
			throw new ConversionException(ErrorCode.RATE_UNAVAILABLE, null, base, quote);
		}
		catch (RestClientException ex) {
			metrics.rateCall(since(started), "unreadable");
			LOG.warn("rate service answered unreadably for {}/{}: {}", LogText.oneLine(base), LogText.oneLine(quote),
					LogText.oneLine(ex.getMessage()));
			throw RateValidator.rejected(RateRejection.RATE_MALFORMED);
		}
		metrics.rateCall(since(started), "answered");
		return mapper.toDomain(dto, base, quote);
	}

	private static Duration since(final long started) {
		return Duration.ofNanos(System.nanoTime() - started);
	}

}
