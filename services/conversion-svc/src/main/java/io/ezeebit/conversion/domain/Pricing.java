package io.ezeebit.conversion.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;

// Rate = units of the buy currency per one unit of the sell currency. Every rounding favours the platform.
public final class Pricing {

	private static final int BASIS_POINTS = 10_000;
	// Rounding to a minor unit may move the rate the merchant really gets by at most this much.
	private static final BigDecimal MAX_ROUNDING_DEVIATION = new BigDecimal("0.01");

	private Pricing() {
	}

	public static Priced price(final Money fixed, final Side side, final Currency other, final BigDecimal marketRate,
			final int spreadBps) {
		BigDecimal applied = marketRate.multiply(BigDecimal.valueOf(BASIS_POINTS - spreadBps)).movePointLeft(4)
				.stripTrailingZeros();
		Priced priced = switch (side) {
			case SELL -> new Priced(fixed, new Money(
					fixed.amount().multiply(applied).setScale(other.scale(), RoundingMode.FLOOR), other), applied);
			case BUY -> new Priced(new Money(
					fixed.amount().divide(applied, other.scale(), RoundingMode.CEILING), other), fixed, applied);
		};
		priced.sell().storable();
		priced.buy().storable();
		return faithful(priced);
	}

	// A quote states appliedRate; the amounts after rounding must deliver it, or the quote says something untrue.
	private static Priced faithful(final Priced priced) {
		if (priced.sell().amount().signum() <= 0 || priced.buy().amount().signum() <= 0) {
			throw tooSmall(priced);
		}
		BigDecimal effective = priced.buy().amount().divide(priced.sell().amount(), MathContext.DECIMAL64);
		BigDecimal deviation = effective.subtract(priced.appliedRate()).abs()
				.divide(priced.appliedRate(), MathContext.DECIMAL64);
		if (deviation.compareTo(MAX_ROUNDING_DEVIATION) > 0) {
			throw tooSmall(priced);
		}
		return priced;
	}

	private static ConversionException tooSmall(final Priced priced) {
		return new ConversionException(ErrorCode.AMOUNT_TOO_SMALL, "amount", priced.buy().currency().code());
	}

}
