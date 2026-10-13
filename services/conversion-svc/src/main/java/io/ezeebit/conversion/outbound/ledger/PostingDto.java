package io.ezeebit.conversion.outbound.ledger;

import java.time.Instant;
import java.util.UUID;

// The ledger's conversion-posting request, as this caller sends it.
public record PostingDto(UUID conversionId, UUID merchantId, String sellCurrency, String sellAmount, String buyCurrency,
		String buyAmount, Instant deadline) {
}
