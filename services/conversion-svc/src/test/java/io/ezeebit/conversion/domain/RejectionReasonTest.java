package io.ezeebit.conversion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.ezeebit.conversion.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class RejectionReasonTest {

	@Test
	void aBalanceRefusalAnswersWithItsOwnCodeAndEverythingElseAsRejected() {
		assertThat(RejectionReason.INSUFFICIENT_FUNDS.answer()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
		assertThat(RejectionReason.BALANCE_LIMIT_EXCEEDED.answer()).isEqualTo(ErrorCode.BALANCE_LIMIT_EXCEEDED);
		assertThat(RejectionReason.INPUT_REFUSED.answer()).isEqualTo(ErrorCode.CONVERSION_REJECTED);
		assertThat(RejectionReason.DEADLINE_PASSED.answer()).isEqualTo(ErrorCode.CONVERSION_REJECTED);
		assertThat(RejectionReason.VOIDED.answer()).isEqualTo(ErrorCode.CONVERSION_REJECTED);
	}

}
