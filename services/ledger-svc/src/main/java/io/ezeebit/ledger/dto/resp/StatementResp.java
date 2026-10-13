package io.ezeebit.ledger.dto.resp;

import java.util.List;

import org.jspecify.annotations.Nullable;

// nextCursor is null on the last page; otherwise pass it back as `after`.
public record StatementResp(List<StatementLineResp> entries, @Nullable String nextCursor) {
}
