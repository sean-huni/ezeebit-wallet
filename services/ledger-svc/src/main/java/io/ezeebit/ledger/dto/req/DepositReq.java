package io.ezeebit.ledger.dto.req;

import java.util.UUID;

import io.ezeebit.ledger.api.json.StrictStringDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

// amount is a decimal string, never a JSON number: no binary floating point anywhere on the path.
public record DepositReq(
		@NotNull(message = "{validation.merchantId.required}") UUID merchantId,
		@NotNull(message = "{validation.currency.required}") String currency,
		@NotNull(message = "{validation.amount.required}")
		@JsonDeserialize(using = StrictStringDeserializer.class)
		@Pattern(regexp = DECIMAL, message = "{validation.amount.format}") String amount,
		@NotBlank(message = "{validation.reference.required}")
		@Size(max = 128, message = "{validation.reference.size}")
		@Pattern(regexp = NO_CONTROL_CHARACTERS, message = "{validation.reference.control}") String reference) {

	// Postgres text cannot hold NUL, and no reference or key needs any control character.
	public static final String NO_CONTROL_CHARACTERS = "\\P{Cc}*";

	public static final String DECIMAL = "-?\\d{1,40}(\\.\\d{1,40})?";
}
