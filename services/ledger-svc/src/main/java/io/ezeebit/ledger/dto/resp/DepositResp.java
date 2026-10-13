package io.ezeebit.ledger.dto.resp;

import java.time.Instant;
import java.util.UUID;

public record DepositResp(UUID journalId, UUID merchantId, String currency, String amount, String balanceAfter,
		String reference, Instant created) {
}
