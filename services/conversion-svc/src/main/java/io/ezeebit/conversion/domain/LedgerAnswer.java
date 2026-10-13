package io.ezeebit.conversion.domain;

import java.util.Optional;

// What the ledger said about one conversion. UNKNOWN (timeout, reset, 5xx) means the money may or may not have moved.
public enum LedgerAnswer {

	POSTED, INSUFFICIENT_FUNDS, DEADLINE_PASSED, VOIDED, ABSENT, UNKNOWN;

	// The conversion's outcome this answer decides; empty while the outcome is still open.
	public Optional<Settlement> settlement() {
		return switch (this) {
			case POSTED -> Optional.of(new Settlement(ConversionStatus.COMPLETED, null));
			case INSUFFICIENT_FUNDS -> Optional.of(new Settlement(ConversionStatus.REJECTED, RejectionReason.INSUFFICIENT_FUNDS));
			case DEADLINE_PASSED -> Optional.of(new Settlement(ConversionStatus.REJECTED, RejectionReason.DEADLINE_PASSED));
			case VOIDED -> Optional.of(new Settlement(ConversionStatus.REJECTED, RejectionReason.VOIDED));
			case ABSENT, UNKNOWN -> Optional.empty();
		};
	}

}
