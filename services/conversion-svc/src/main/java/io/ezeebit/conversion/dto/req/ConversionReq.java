package io.ezeebit.conversion.dto.req;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record ConversionReq(@NotNull(message = "{validation.quoteId.required}") UUID quoteId) {

	// Postgres text cannot hold NUL, and no key needs any control character.
	public static final String NO_CONTROL_CHARACTERS = "\\P{Cc}*";

}
