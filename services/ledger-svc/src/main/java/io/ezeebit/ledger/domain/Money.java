package io.ezeebit.ledger.domain;

import java.math.BigDecimal;
import java.util.Objects;

import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;

public record Money(BigDecimal amount, CurrencyCode currency) {

	// NUMERIC(38,18) leaves 20 digits before the point.
	private static final int MAX_INTEGER_DIGITS = 20;

	public Money {
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(currency, "currency");
	}

	public static Money parse(final String raw, final Currency currency) {
		BigDecimal value = new BigDecimal(raw);
		BigDecimal significant = value.stripTrailingZeros();
		if (significant.scale() > currency.scale()) {
			throw new LedgerException(ErrorCode.AMOUNT_SCALE_EXCEEDED, "amount", raw, currency.code().value(),
					currency.scale());
		}
		if (value.signum() <= 0) {
			throw new LedgerException(ErrorCode.AMOUNT_NOT_POSITIVE, "amount", raw);
		}
		if (significant.precision() - significant.scale() > MAX_INTEGER_DIGITS) {
			throw new LedgerException(ErrorCode.AMOUNT_TOO_LARGE, "amount", raw, MAX_INTEGER_DIGITS);
		}
		return new Money(value, currency.code());
	}

	public static Money zero(final CurrencyCode currency) {
		return new Money(BigDecimal.ZERO, currency);
	}

	public Money plus(final Money other) {
		if (!currency.equals(other.currency)) {
			throw new LedgerException(ErrorCode.CURRENCY_MISMATCH, null, currency.value(), other.currency.value());
		}
		return new Money(amount.add(other.amount), currency);
	}

	public Money negate() {
		return new Money(amount.negate(), currency);
	}

	public boolean isZero() {
		return amount.signum() == 0;
	}

}
