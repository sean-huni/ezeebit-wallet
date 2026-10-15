package io.ezeebit.conversion.dto.resp;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

// rate is the market rate from the feed; appliedRate is what the merchant gets after the spread.
public record QuoteResp(@Schema(example = "5e7d2c1b-9a8f-4e6d-b3c2-a1f0e9d8c7b6") UUID quoteId,
		@Schema(example = "3f2c8a10-5b7e-4c1d-9a2e-6d4f8b0c1e27") UUID merchantId,
		@Schema(example = "ZAR") String sellCurrency, @Schema(example = "1000.00") String sellAmount,
		@Schema(example = "USDT") String buyCurrency, @Schema(example = "53.982730") String buyAmount,
		@Schema(description = "Market rate from the rate feed.", example = "0.054254") String rate,
		@Schema(description = "rate after the spread: what the merchant gets.", example = "0.05398273") String appliedRate,
		@Schema(example = "50") int spreadBps, @Schema(example = "2026-10-13T10:00:00Z") Instant rateTimestamp,
		@Schema(description = "The quote is firm until then.", example = "2026-10-13T10:00:15Z") Instant expiresAt) {
}
