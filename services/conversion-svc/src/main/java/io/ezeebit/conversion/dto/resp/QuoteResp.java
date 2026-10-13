package io.ezeebit.conversion.dto.resp;

import java.time.Instant;
import java.util.UUID;

// rate is the market rate from the feed; appliedRate is what the merchant gets after the spread.
public record QuoteResp(UUID quoteId, UUID merchantId, String sellCurrency, String sellAmount, String buyCurrency,
		String buyAmount, String rate, String appliedRate, int spreadBps, Instant rateTimestamp, Instant expiresAt) {
}
