package io.ezeebit.ledger.service.conversion;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.domain.Money;

public record ConversionPostingCommand(MerchantId merchant, UUID conversionId, Money sell, Money buy, Instant deadline,
		String requestHash) {
}
