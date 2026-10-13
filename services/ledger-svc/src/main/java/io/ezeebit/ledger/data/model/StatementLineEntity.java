package io.ezeebit.ledger.data.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// One merchant entry with the journal that explains it.
public record StatementLineEntity(long seq, UUID journalId, String type, String reference, String currency,
		BigDecimal amount, BigDecimal balanceAfter, Instant created) {
}
