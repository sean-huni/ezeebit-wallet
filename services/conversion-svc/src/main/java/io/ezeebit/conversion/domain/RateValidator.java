package io.ezeebit.conversion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;

public interface RateValidator {

	void check(MarketRate rate, PairPolicy policy, Optional<BigDecimal> recent, Instant now);

	static ConversionException rejected(final RateRejection reason) {
		return new ConversionException(ErrorCode.RATE_REJECTED, null, reason.name()).with("reason", reason.name());
	}

}
