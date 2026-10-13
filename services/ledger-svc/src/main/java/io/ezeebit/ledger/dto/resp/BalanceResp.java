package io.ezeebit.ledger.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;

public record BalanceResp(@Schema(example = "USDT") String currency, @Schema(example = "53.982730") String balance) {
}
