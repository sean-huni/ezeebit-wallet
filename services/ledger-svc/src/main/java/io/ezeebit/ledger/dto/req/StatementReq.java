package io.ezeebit.ledger.dto.req;

import java.time.Instant;

import io.ezeebit.ledger.api.validation.OrderedWindow;
import io.ezeebit.ledger.api.validation.SupportedInstant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.jspecify.annotations.Nullable;

// Query parameters of the statement; from is inclusive, to exclusive, after is the previous page's cursor.
@OrderedWindow
public record StatementReq(
		@Nullable String currency,
		@Nullable @SupportedInstant Instant from,
		@Nullable @SupportedInstant Instant to,
		@Nullable @Min(value = 1, message = "{validation.limit.range}") @Max(value = 500, message = "{validation.limit.range}") Integer limit,
		@Nullable @PositiveOrZero(message = "{validation.after.range}") Long after) {

	public static final int DEFAULT_LIMIT = 100;

}
