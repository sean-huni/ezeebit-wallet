package io.ezeebit.ledger.dto.resp;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

// status POSTED carries both sides; VOID and REFUSED are markers without amounts, REFUSED with its reason.
public record ConversionPostingResp(UUID journalId, UUID conversionId, UUID merchantId, String status,
		@Nullable String reason, @Nullable String sellCurrency, @Nullable String sellAmount, @Nullable String buyCurrency,
		@Nullable String buyAmount, Instant created) {
}
