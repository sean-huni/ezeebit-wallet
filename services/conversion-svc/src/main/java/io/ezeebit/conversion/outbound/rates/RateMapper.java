package io.ezeebit.conversion.outbound.rates;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import io.ezeebit.conversion.domain.MarketRate;
import io.ezeebit.conversion.domain.RateRejection;
import io.ezeebit.conversion.domain.RateValidator;
import org.springframework.stereotype.Component;

// Anything the feed sends that cannot be read as the pair asked for, at an exact decimal and an instant, is refused.
@Component
public class RateMapper {

	// The spread factor adds 4 decimals and rates are stored at 18: more than 14 would be rounded silently.
	private static final int MAX_RATE_DECIMALS = 14;

	public MarketRate toDomain(final RateDto dto, final String base, final String quote) {
		if (dto == null || !base.equals(dto.base()) || !quote.equals(dto.quote()) || dto.rate() == null
				|| dto.timestamp() == null) {
			throw RateValidator.rejected(RateRejection.RATE_MALFORMED);
		}
		try {
			BigDecimal rate = new BigDecimal(dto.rate());
			if (rate.stripTrailingZeros().scale() > MAX_RATE_DECIMALS) {
				throw RateValidator.rejected(RateRejection.RATE_MALFORMED);
			}
			return new MarketRate(base, quote, rate, Instant.parse(dto.timestamp()));
		}
		catch (NumberFormatException | DateTimeParseException ex) {
			throw RateValidator.rejected(RateRejection.RATE_MALFORMED);
		}
	}

}
