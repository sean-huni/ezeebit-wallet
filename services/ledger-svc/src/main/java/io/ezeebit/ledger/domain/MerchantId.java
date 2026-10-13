package io.ezeebit.ledger.domain;

import java.util.Objects;
import java.util.UUID;

public record MerchantId(UUID value) {

	public MerchantId {
		Objects.requireNonNull(value, "merchant id");
	}

}
