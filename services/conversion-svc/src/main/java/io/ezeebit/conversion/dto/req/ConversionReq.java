package io.ezeebit.conversion.dto.req;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ConversionReq(
		@Schema(description = "From the quote answer.", example = "5e7d2c1b-9a8f-4e6d-b3c2-a1f0e9d8c7b6")
		@NotNull(message = "{validation.quoteId.required}") UUID quoteId) {

	// Postgres text cannot hold NUL, and no key needs any control character.
	public static final String NO_CONTROL_CHARACTERS = "\\P{Cc}*";

}
