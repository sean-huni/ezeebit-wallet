package io.ezeebit.ledger.dto.req;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record VoidReq(
		@Schema(example = "3f2c8a10-5b7e-4c1d-9a2e-6d4f8b0c1e27") @NotNull(message = "{validation.merchantId.required}") UUID merchantId) {
}
