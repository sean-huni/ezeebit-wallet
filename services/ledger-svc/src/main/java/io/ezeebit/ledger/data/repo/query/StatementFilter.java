package io.ezeebit.ledger.data.repo.query;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record StatementFilter(UUID merchantId, @Nullable String currency, @Nullable Instant from,
		@Nullable Instant to, long after, int limit) {
}
