package io.ezeebit.ledger.domain;

import java.util.Objects;
import java.util.UUID;

// One currency side of a conversion: the merchant's account and the platform FX account it trades against.
public record ConversionLegs(UUID merchantAccount, UUID fxAccount, Money amount) {

	public ConversionLegs {
		Objects.requireNonNull(merchantAccount, "merchantAccount");
		Objects.requireNonNull(fxAccount, "fxAccount");
		Objects.requireNonNull(amount, "amount");
	}

}
