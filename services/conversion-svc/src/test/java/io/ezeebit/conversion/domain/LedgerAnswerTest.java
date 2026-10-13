package io.ezeebit.conversion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class LedgerAnswerTest {

	@Test
	void onlyADefiniteAnswerSettlesAConversion() {
		for (LedgerAnswer answer : LedgerAnswer.values()) {
			assertThat(answer.settlement()).as(answer.name()).isEqualTo(expected(answer));
		}
	}

	@Test
	void everySettlementIsALegalMoveFromPending() {
		for (LedgerAnswer answer : LedgerAnswer.values()) {
			answer.settlement().ifPresent(settlement ->
					assertThat(ConversionStatus.PENDING.canMoveTo(settlement.status())).as(answer.name()).isTrue());
		}
	}

	private static Optional<Settlement> expected(final LedgerAnswer answer) {
		return switch (answer) {
			case POSTED -> Optional.of(new Settlement(ConversionStatus.COMPLETED, null));
			case INSUFFICIENT_FUNDS -> Optional.of(new Settlement(ConversionStatus.REJECTED, RejectionReason.INSUFFICIENT_FUNDS));
			case BALANCE_LIMIT_EXCEEDED -> Optional.of(new Settlement(ConversionStatus.REJECTED,
					RejectionReason.BALANCE_LIMIT_EXCEEDED));
			case INPUT_REFUSED -> Optional.of(new Settlement(ConversionStatus.REJECTED, RejectionReason.INPUT_REFUSED));
			case DEADLINE_PASSED -> Optional.of(new Settlement(ConversionStatus.REJECTED, RejectionReason.DEADLINE_PASSED));
			case VOIDED -> Optional.of(new Settlement(ConversionStatus.REJECTED, RejectionReason.VOIDED));
			case ABSENT, UNKNOWN -> Optional.empty();
			default -> throw new AssertionError("no decision about %s".formatted(answer));
		};
	}

}
