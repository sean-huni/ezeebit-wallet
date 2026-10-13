package io.ezeebit.ledger.dto.req;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record VoidReq(@NotNull(message = "{validation.merchantId.required}") UUID merchantId) {
}
