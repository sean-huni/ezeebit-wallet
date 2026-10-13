package io.ezeebit.conversion.data.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuoteEntity(UUID id, UUID merchantId, String sellCurrency, String buyCurrency, BigDecimal sellAmount,
		BigDecimal buyAmount, BigDecimal rate, BigDecimal appliedRate, int spreadBps, Instant rateSourceTimestamp,
		Instant expiresAt, String status) {
}
