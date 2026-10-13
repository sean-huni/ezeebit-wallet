package io.ezeebit.conversion.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;

// A rate is used only if every rule passes; a refusal is never replaced by a cached or default rate.
public class RateValidator {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final RateRules rules;

	public RateValidator(final RateRules rules) {
		this.rules = rules;
	}

	public void check(final MarketRate rate, final PairPolicy policy, final Optional<BigDecimal> recent,
			final Instant now) {
		if (rate.rate().signum() <= 0) {
			throw rejected(RateRejection.RATE_NOT_POSITIVE);
		}
		if (Duration.between(rate.timestamp(), now).abs().compareTo(rules.maxAge()) > 0) {
			throw rejected(RateRejection.RATE_STALE);
		}
		if (rate.rate().compareTo(policy.min()) < 0 || rate.rate().compareTo(policy.max()) > 0) {
			throw rejected(RateRejection.RATE_OUT_OF_BAND);
		}
		if (recent.filter(previous -> jumpPercent(previous, rate.rate()).compareTo(rules.maxJumpPercent()) > 0)
				.isPresent()) {
			throw rejected(RateRejection.RATE_JUMP);
		}
	}

	public static ConversionException rejected(final RateRejection reason) {
		return new ConversionException(ErrorCode.RATE_REJECTED, null, reason.name()).with("reason", reason.name());
	}

	private static BigDecimal jumpPercent(final BigDecimal previous, final BigDecimal current) {
		return current.subtract(previous).abs().multiply(HUNDRED).divide(previous, MathContext.DECIMAL64);
	}

}
