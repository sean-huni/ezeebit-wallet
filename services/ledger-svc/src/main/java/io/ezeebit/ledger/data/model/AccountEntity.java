package io.ezeebit.ledger.data.model;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountEntity(UUID id, UUID merchantId, String currency, String type, BigDecimal balance) {
}
