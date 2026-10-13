package io.ezeebit.ledger.dto.resp;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

// status POSTED carries both sides; VOID and REFUSED are markers without amounts, REFUSED with its reason.
public record ConversionPostingResp(UUID journalId, UUID conversionId, UUID merchantId,
		@Schema(description = "POSTED, VOID or REFUSED.", example = "POSTED") String status,
		@Schema(description = "Why a REFUSED posting was refused.") @Nullable String reason, @Nullable String sellCurrency,
				@Nullable String sellAmount, @Nullable String buyCurrency,
		@Nullable String buyAmount, Instant created) {
}
