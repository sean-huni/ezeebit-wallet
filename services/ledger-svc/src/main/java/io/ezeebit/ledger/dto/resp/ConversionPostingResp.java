package io.ezeebit.ledger.dto.resp;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

// status POSTED carries both sides; status VOID is a marker and carries no amounts.
public record ConversionPostingResp(UUID journalId, UUID conversionId, UUID merchantId, String status,
		@Nullable String sellCurrency, @Nullable String sellAmount, @Nullable String buyCurrency,
		@Nullable String buyAmount, Instant created) {
}
