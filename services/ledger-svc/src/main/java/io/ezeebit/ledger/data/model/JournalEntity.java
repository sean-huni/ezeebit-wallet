package io.ezeebit.ledger.data.model;

import java.util.UUID;

public record JournalEntity(UUID id, UUID merchantId, String type, String status, String idempotencyKey,
		String requestHash, String reference) {
}
