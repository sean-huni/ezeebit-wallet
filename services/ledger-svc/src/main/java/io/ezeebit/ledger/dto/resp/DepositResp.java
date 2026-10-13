package io.ezeebit.ledger.dto.resp;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record DepositResp(@Schema(example = "9b1d4e2a-7c3f-4a5b-8e6d-1f2a3b4c5d6e") UUID journalId,
		@Schema(example = "3f2c8a10-5b7e-4c1d-9a2e-6d4f8b0c1e27") UUID merchantId,
		@Schema(example = "ZAR") String currency, @Schema(example = "1500.00") String amount,
		@Schema(example = "1500.00") String balanceAfter, @Schema(example = "rail transfer 1") String reference,
		@Schema(example = "2026-10-13T10:00:00Z") Instant created) {
}
