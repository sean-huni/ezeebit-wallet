package io.ezeebit.ledger.domain;

import java.util.Objects;

public record Currency(CurrencyCode code, CurrencyKind kind, int scale) {

	// NUMERIC(38,18) storage: a scale above 18 could not be stored exactly.
	public static final int MAX_SCALE = 18;

	public Currency {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(kind, "kind");
		if (scale < 0 || scale > MAX_SCALE) {
			throw new IllegalArgumentException("scale %d outside 0..%d".formatted(scale, MAX_SCALE));
		}
	}

}
