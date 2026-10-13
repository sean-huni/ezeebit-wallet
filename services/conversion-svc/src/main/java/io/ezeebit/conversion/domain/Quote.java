package io.ezeebit.conversion.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// A firm price: the merchant gets exactly these amounts if it accepts before expiresAt.
public record Quote(UUID id, UUID merchantId, Money sell, Money buy, BigDecimal rate, BigDecimal appliedRate,
		int spreadBps, Instant rateTimestamp, Instant expiresAt) {

	public boolean expired(final Instant now) {
		return !now.isBefore(expiresAt);
	}

	public Instant postingDeadline(final Duration grace) {
		return expiresAt.plus(grace);
	}

}
