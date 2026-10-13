package io.ezeebit.ledger.data.model;

import java.math.BigDecimal;
import java.util.UUID;

// balanceAfter is null on platform entries: platform balances are derived on read.
public record EntryEntity(UUID id, UUID journalId, UUID accountId, String currency, BigDecimal amount,
		BigDecimal balanceAfter) {
}
