package io.ezeebit.ledger.domain;

import java.util.regex.Pattern;

import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;

// Format only: whether the currency exists is the currency table's answer, not this type's.
public record CurrencyCode(String value) {

	private static final Pattern FORMAT = Pattern.compile("[A-Z]{3,5}");

	public CurrencyCode {
		if (value == null || !FORMAT.matcher(value).matches()) {
			throw new LedgerException(ErrorCode.UNKNOWN_CURRENCY, "currency", value);
		}
	}

}
