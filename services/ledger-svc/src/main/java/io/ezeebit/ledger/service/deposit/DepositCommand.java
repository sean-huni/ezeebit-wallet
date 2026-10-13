package io.ezeebit.ledger.service.deposit;

import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.domain.Money;

public record DepositCommand(MerchantId merchant, Money amount, String reference, String idempotencyKey,
		String requestHash) {
}
