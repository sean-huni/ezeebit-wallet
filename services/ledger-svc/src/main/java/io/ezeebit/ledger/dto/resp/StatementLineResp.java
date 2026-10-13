package io.ezeebit.ledger.dto.resp;

import java.time.Instant;
import java.util.UUID;

public record StatementLineResp(long seq, UUID journalId, String type, String reference, String currency,
		String amount, String balanceAfter, Instant created) {
}
