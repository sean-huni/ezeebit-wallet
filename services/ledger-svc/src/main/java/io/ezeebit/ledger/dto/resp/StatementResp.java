package io.ezeebit.ledger.dto.resp;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

// nextCursor is null on the last page; otherwise pass it back as `after`.
public record StatementResp(List<StatementLineResp> entries,
		@Schema(description = "Null on the last page; otherwise pass it back as `after`.") @Nullable String nextCursor) {

	public StatementResp {
		entries = List.copyOf(entries);
	}

}
