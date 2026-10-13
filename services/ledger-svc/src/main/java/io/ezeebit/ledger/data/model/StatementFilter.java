package io.ezeebit.ledger.data.model;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record StatementFilter(UUID merchantId, @Nullable String currency, @Nullable Instant from,
		@Nullable Instant to, long after, int limit) {
}
