package io.ezeebit.ledger.dto.resp;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record StatementLineResp(@Schema(example = "2") long seq, @Schema(example = "9b1d4e2a-7c3f-4a5b-8e6d-1f2a3b4c5d6e") UUID journalId,
		@Schema(description = "DEPOSIT or CONVERSION.", example = "CONVERSION") String type,
		@Schema(example = "c4e8f1a2-6b3d-4e5f-9a7b-2d1c0e9f8a7b") String reference, @Schema(example = "ZAR") String currency,
		@Schema(description = "Signed: a debit is negative.", example = "-1000.00") String amount,
		@Schema(example = "500.00") String balanceAfter, @Schema(example = "2026-10-13T10:00:05Z") Instant created) {
}
