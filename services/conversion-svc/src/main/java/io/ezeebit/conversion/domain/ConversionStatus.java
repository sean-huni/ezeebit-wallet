package io.ezeebit.conversion.domain;

// PENDING -> COMPLETED | REJECTED, and nothing else. The exhaustive switch makes a new constant a compile error.
public enum ConversionStatus {

	PENDING, COMPLETED, REJECTED;

	public boolean canMoveTo(final ConversionStatus target) {
		return switch (this) {
			case PENDING -> target == COMPLETED || target == REJECTED;
			case COMPLETED, REJECTED -> false;
		};
	}

}
