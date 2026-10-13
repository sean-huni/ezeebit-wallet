package io.ezeebit.conversion.data.model;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

// A conversion joined with its quote: everything an answer or a ledger posting needs, in one read.
public record ConversionEntity(UUID id, UUID merchantId, UUID quoteId, String idempotencyKey, String status,
		@Nullable String reason, Instant deadline, Instant created, @Nullable Instant completed, QuoteEntity quote) {
}
