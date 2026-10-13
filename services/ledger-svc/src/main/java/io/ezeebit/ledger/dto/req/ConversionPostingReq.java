package io.ezeebit.ledger.dto.req;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.ledger.api.json.StrictStringDeserializer;
import io.ezeebit.ledger.api.validation.SupportedInstant;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

// The conversionId is the idempotency key; the deadline is checked against the ledger's own clock.
public record ConversionPostingReq(
		@NotNull(message = "{validation.conversionId.required}") UUID conversionId,
		@NotNull(message = "{validation.merchantId.required}") UUID merchantId,
		@NotNull(message = "{validation.sellCurrency.required}") String sellCurrency,
		@NotNull(message = "{validation.sellAmount.required}")
		@JsonDeserialize(using = StrictStringDeserializer.class)
		@Pattern(regexp = DepositReq.DECIMAL, message = "{validation.amount.format}") String sellAmount,
		@NotNull(message = "{validation.buyCurrency.required}") String buyCurrency,
		@NotNull(message = "{validation.buyAmount.required}")
		@JsonDeserialize(using = StrictStringDeserializer.class)
		@Pattern(regexp = DepositReq.DECIMAL, message = "{validation.amount.format}") String buyAmount,
		@NotNull(message = "{validation.deadline.required}") @SupportedInstant Instant deadline) {
}
