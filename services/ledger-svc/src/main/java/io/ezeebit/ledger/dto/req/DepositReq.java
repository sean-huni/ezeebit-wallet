package io.ezeebit.ledger.dto.req;

import java.util.UUID;

import io.ezeebit.ledger.api.json.StrictStringDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

// amount is a decimal string, never a JSON number: no binary floating point anywhere on the path.
public record DepositReq(
		@Schema(example = "3f2c8a10-5b7e-4c1d-9a2e-6d4f8b0c1e27") @NotNull(message = "{validation.merchantId.required}") UUID merchantId,
		@Schema(example = "ZAR") @NotNull(message = "{validation.currency.required}") String currency,
		@Schema(description = "A decimal string, never a JSON number.", example = "1500.00")
		@NotNull(message = "{validation.amount.required}")
		@JsonDeserialize(using = StrictStringDeserializer.class)
		@Pattern(regexp = DECIMAL, message = "{validation.amount.format}") String amount,
		@Schema(example = "rail transfer 1") @NotBlank(message = "{validation.reference.required}")
		@Size(max = 128, message = "{validation.reference.size}")
		@Pattern(regexp = NO_CONTROL_CHARACTERS, message = "{validation.reference.control}") String reference) {

	// Postgres text cannot hold NUL, and no reference or key needs any control character.
	public static final String NO_CONTROL_CHARACTERS = "\\P{Cc}*";

	public static final String DECIMAL = "-?\\d{1,40}(\\.\\d{1,40})?";
}
