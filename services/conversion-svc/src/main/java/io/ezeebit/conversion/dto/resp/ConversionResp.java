package io.ezeebit.conversion.dto.resp;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record ConversionResp(@Schema(example = "c4e8f1a2-6b3d-4e5f-9a7b-2d1c0e9f8a7b") UUID conversionId,
		@Schema(example = "3f2c8a10-5b7e-4c1d-9a2e-6d4f8b0c1e27") UUID merchantId,
		@Schema(example = "5e7d2c1b-9a8f-4e6d-b3c2-a1f0e9d8c7b6") UUID quoteId,
		@Schema(description = "COMPLETED, PENDING or REJECTED.", example = "COMPLETED") String status,
		@Schema(description = "Why a REJECTED conversion was rejected.") @Nullable String reason,
		@Schema(example = "ZAR") String sellCurrency, @Schema(example = "1000.00") String sellAmount,
		@Schema(example = "USDT") String buyCurrency, @Schema(example = "53.982730") String buyAmount,
		@Schema(example = "0.05398273") String appliedRate, @Schema(example = "2026-10-13T10:00:05Z") Instant created,
		@Schema(example = "2026-10-13T10:00:05Z") @Nullable Instant completed) {
}
