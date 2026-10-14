package io.ezeebit.conversion.outbound.rates;

import java.time.Duration;

import io.ezeebit.conversion.common.log.LogText;
import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.domain.MarketRate;
import io.ezeebit.conversion.domain.RateRejection;
import io.ezeebit.conversion.domain.RateValidator;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import io.ezeebit.conversion.service.port.RateSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Fails closed: a timeout, a refused connection or an error status is 503, never a cached or default rate.
@Component
public class RateClient implements RateSource {

	private static final Logger LOG = LoggerFactory.getLogger(RateClient.class);

	private final RateCall call;
	private final RateMapper mapper;
	private final ConversionMetrics metrics;

	public RateClient(final RateCall call, final RateMapper mapper, final ConversionMetrics metrics) {
		this.call = call;
		this.mapper = mapper;
		this.metrics = metrics;
	}

	@Override
	public MarketRate fetch(final String base, final String quote) {
		RateDto dto;
		// Started before the retry, so the timer and its one outcome cover every attempt of this fetch.
		long started = System.nanoTime();
		try {
			dto = call.get(base, quote);
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
