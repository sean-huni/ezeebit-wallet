package io.ezeebit.ledger.dto.req;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// amount is a decimal string, never a JSON number: no binary floating point anywhere on the path.
public record DepositReq(
		@NotNull(message = "{validation.merchantId.required}") UUID merchantId,
		@NotNull(message = "{validation.currency.required}") String currency,
		@NotNull(message = "{validation.amount.required}")
		@Pattern(regexp = "-?\\d{1,40}(\\.\\d{1,40})?", message = "{validation.amount.format}") String amount,
		@NotBlank(message = "{validation.reference.required}")
		@Size(max = 128, message = "{validation.reference.size}") String reference) {
}
