package io.ezeebit.conversion.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;

// Always held at its currency's scale, so two equal amounts are equal records.
public record Money(BigDecimal amount, Currency currency) {

	// The ledger stores NUMERIC(38,18): 20 digits before the point.
	private static final int MAX_INTEGER_DIGITS = 20;

	public Money {
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(currency, "currency");
		amount = amount.setScale(currency.scale(), RoundingMode.UNNECESSARY);
	}

	public static Money parse(final String raw, final Currency currency) {
		BigDecimal value = new BigDecimal(raw);
		if (value.stripTrailingZeros().scale() > currency.scale()) {
			throw new ConversionException(ErrorCode.AMOUNT_SCALE_EXCEEDED, "amount", raw, currency.code(),
					currency.scale());
		}
		if (value.signum() <= 0) {
			throw new ConversionException(ErrorCode.AMOUNT_NOT_POSITIVE, "amount", raw);
		}
		BigDecimal significant = value.stripTrailingZeros();
		if (significant.precision() - significant.scale() > MAX_INTEGER_DIGITS) {
			throw new ConversionException(ErrorCode.AMOUNT_TOO_LARGE, "amount", raw, MAX_INTEGER_DIGITS);
		}
		return new Money(value.setScale(currency.scale(), RoundingMode.UNNECESSARY), currency);
	}

	public String text() {
		return amount.toPlainString();
	}

}
