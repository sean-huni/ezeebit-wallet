package io.ezeebit.conversion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Every ordered pair of states, so a state added without a decision about its edges fails here.
class ConversionStatusTest {

	@Test
	void everyOrderedPairOfStatesHasTheDecidedAnswer() {
		for (ConversionStatus from : ConversionStatus.values()) {
			for (ConversionStatus to : ConversionStatus.values()) {
				assertThat(from.canMoveTo(to)).as("%s -> %s", from, to).isEqualTo(allowed(from, to));
			}
		}
	}

	@Test
	void onlyPendingCanMoveAndOnlyToAnOutcome() {
		assertThat(ConversionStatus.PENDING.canMoveTo(ConversionStatus.COMPLETED)).isTrue();
		assertThat(ConversionStatus.PENDING.canMoveTo(ConversionStatus.REJECTED)).isTrue();
		assertThat(ConversionStatus.COMPLETED.canMoveTo(ConversionStatus.REJECTED)).isFalse();
		assertThat(ConversionStatus.REJECTED.canMoveTo(ConversionStatus.COMPLETED)).isFalse();
	}

	private static boolean allowed(final ConversionStatus from, final ConversionStatus to) {
		return switch (from) {
			case PENDING -> to == ConversionStatus.COMPLETED || to == ConversionStatus.REJECTED;
			case COMPLETED, REJECTED -> false;
			default -> throw new AssertionError("no decision about the edges of %s".formatted(from));
		};
	}

}
