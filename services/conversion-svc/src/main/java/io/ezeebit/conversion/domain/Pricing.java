package io.ezeebit.conversion.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;

// Rate = units of the buy currency per one unit of the sell currency. Every rounding favours the platform.
public final class Pricing {

	private static final int BASIS_POINTS = 10_000;

	private Pricing() {
	}

	public static Priced price(final Money fixed, final Side side, final Currency other, final BigDecimal marketRate,
			final int spreadBps) {
		BigDecimal applied = marketRate.multiply(BigDecimal.valueOf(BASIS_POINTS - spreadBps)).movePointLeft(4)
				.stripTrailingZeros();
		return switch (side) {
			case SELL -> new Priced(fixed, positive(new Money(
					fixed.amount().multiply(applied).setScale(other.scale(), RoundingMode.FLOOR), other)), applied);
			case BUY -> new Priced(positive(new Money(
					fixed.amount().divide(applied, other.scale(), RoundingMode.CEILING), other)), fixed, applied);
		};
	}

	private static Money positive(final Money money) {
		if (money.amount().signum() <= 0) {
			throw new ConversionException(ErrorCode.AMOUNT_TOO_SMALL, "amount", money.currency().code());
		}
		return money;
	}

}
