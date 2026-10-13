package io.ezeebit.conversion.outbound.rates;

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

	public RateClient(@Qualifier("rateRestClient") final RestClient rates, final RateMapper mapper) {
		this.rates = rates;
		this.mapper = mapper;
	}

	public MarketRate fetch(final String base, final String quote) {
		RateDto dto;
		try {
			dto = rates.get().uri(uri -> uri.path("/rates").queryParam("base", base).queryParam("quote", quote).build())
					.retrieve().body(RateDto.class);
		}
		catch (ResourceAccessException | RestClientResponseException ex) {
			LOG.warn("rate service unavailable for {}/{}: {}", base, quote, ex.getMessage());
			throw new ConversionException(ErrorCode.RATE_UNAVAILABLE, null, base, quote);
		}
		catch (RestClientException ex) {
			LOG.warn("rate service answered unreadably for {}/{}: {}", base, quote, ex.getMessage());
			throw RateValidator.rejected(RateRejection.RATE_MALFORMED);
		}
		return mapper.toDomain(dto, base, quote);
	}

}
