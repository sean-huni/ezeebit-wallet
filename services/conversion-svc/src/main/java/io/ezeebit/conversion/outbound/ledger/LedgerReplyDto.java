package io.ezeebit.conversion.outbound.ledger;

import org.jspecify.annotations.Nullable;

// Only what this caller reads from either a success envelope or a problem detail.
public record LedgerReplyDto(@Nullable Data data, @Nullable String code) {

	public record Data(@Nullable String status) {
	}

}
