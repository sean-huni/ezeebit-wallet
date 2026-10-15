package io.ezeebit.ledger.dto.req;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.ledger.api.json.StrictStringDeserializer;
import io.ezeebit.ledger.api.validation.SupportedInstant;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

// The conversionId is the idempotency key; the deadline is checked against the ledger's own clock.
public record ConversionPostingReq(
		@Schema(example = "c4e8f1a2-6b3d-4e5f-9a7b-2d1c0e9f8a7b")
		@NotNull(message = "{validation.conversionId.required}") UUID conversionId,
		@Schema(example = "3f2c8a10-5b7e-4c1d-9a2e-6d4f8b0c1e27") @NotNull(message = "{validation.merchantId.required}") UUID merchantId,
		@Schema(example = "ZAR") @NotNull(message = "{validation.sellCurrency.required}") String sellCurrency,
		@Schema(example = "1000.00") @NotNull(message = "{validation.sellAmount.required}")
		@JsonDeserialize(using = StrictStringDeserializer.class)
		@Pattern(regexp = DepositReq.DECIMAL, message = "{validation.amount.format}") String sellAmount,
		@Schema(example = "USDT") @NotNull(message = "{validation.buyCurrency.required}") String buyCurrency,
		@Schema(example = "53.982730") @NotNull(message = "{validation.buyAmount.required}")
		@JsonDeserialize(using = StrictStringDeserializer.class)
		@Pattern(regexp = DepositReq.DECIMAL, message = "{validation.amount.format}") String buyAmount,
		@Schema(description = "Checked against the ledger's clock; after it the posting is refused.", example = "2026-10-13T10:00:15Z")
		@NotNull(message = "{validation.deadline.required}") @SupportedInstant Instant deadline) {
}
