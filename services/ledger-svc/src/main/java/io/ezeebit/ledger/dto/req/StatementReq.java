package io.ezeebit.ledger.dto.req;

import java.time.Instant;

import io.ezeebit.ledger.api.validation.OrderedWindow;
import io.ezeebit.ledger.api.validation.SupportedInstant;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.jspecify.annotations.Nullable;

// Query parameters of the statement; from is inclusive, to exclusive, after is the previous page's cursor.
@OrderedWindow
public record StatementReq(
		@Parameter(description = "Only this currency.", example = "ZAR") @Nullable String currency,
		@Parameter(description = "Inclusive.", example = "2026-10-01T00:00:00Z") @Nullable @SupportedInstant Instant from,
		@Parameter(description = "Exclusive.", example = "2026-11-01T00:00:00Z") @Nullable @SupportedInstant Instant to,
		@Parameter(description = "Page size, 1 to 500; default 100.", example = "100")
		@Nullable @Min(value = 1, message = "{validation.limit.range}")
		@Max(value = 500, message = "{validation.limit.range}") Integer limit,
		@Parameter(description = "The previous page's nextCursor.")
		@Nullable @PositiveOrZero(message = "{validation.after.range}") Long after) {

	public static final int DEFAULT_LIMIT = 100;

}
