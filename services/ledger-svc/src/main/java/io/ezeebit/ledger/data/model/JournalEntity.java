package io.ezeebit.ledger.data.model;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

// created is assigned by the database: null on the way in, set on the way out. reason is set on REFUSED only.
public record JournalEntity(UUID id, UUID merchantId, String type, String status, String idempotencyKey,
		String requestHash, String reference, @Nullable String reason, @Nullable Instant created) {
}
