package io.ezeebit.conversion.dto.resp;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record ConversionResp(UUID conversionId, UUID merchantId, UUID quoteId, String status, @Nullable String reason,
		String sellCurrency, String sellAmount, String buyCurrency, String buyAmount, String appliedRate,
		Instant created, @Nullable Instant completed) {
}
