package io.ezeebit.conversion.outbound.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.ezeebit.conversion.domain.LedgerAnswer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LedgerAnswerMapperTest {

	private final LedgerAnswerMapper mapper = new LedgerAnswerMapper();

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = {
			"200, REFUSED:INSUFFICIENT_FUNDS, null, INSUFFICIENT_FUNDS",
			"201, REFUSED:BALANCE_LIMIT_EXCEEDED, null, BALANCE_LIMIT_EXCEEDED",
			"200, REFUSED:SOMETHING_NEW, null, UNKNOWN",
			"422, null, BALANCE_LIMIT_EXCEEDED, BALANCE_LIMIT_EXCEEDED",
			"400, null, AMOUNT_TOO_LARGE, INPUT_REFUSED",
			"400, null, AMOUNT_SCALE_EXCEEDED, INPUT_REFUSED",
			"400, null, UNKNOWN_CURRENCY, INPUT_REFUSED",
			"422, null, IDEMPOTENCY_KEY_REUSED, UNKNOWN",
			"201, POSTED, null, POSTED",
			"200, POSTED, null, POSTED",
			"201, VOID, null, VOIDED",
			"200, VOID, null, VOIDED",
			"201, null, null, UNKNOWN",
			"422, null, INSUFFICIENT_FUNDS, INSUFFICIENT_FUNDS",
			"409, null, POSTING_DEADLINE_PASSED, DEADLINE_PASSED",
			"409, null, CONVERSION_VOIDED, VOIDED",
			"409, null, CONVERSION_ALREADY_POSTED, POSTED",
			"404, null, POSTING_NOT_FOUND, ABSENT",
			"404, null, NOT_FOUND, UNKNOWN",
			"400, null, VALIDATION_FAILED, INPUT_REFUSED",
			"503, null, null, UNKNOWN",
			"500, null, INTERNAL_ERROR, UNKNOWN" })
	void eachLedgerReplyMeansOneThing(final int status, final String state, final String code, final LedgerAnswer answer) {
		String[] stateAndReason = state == null ? null : state.split(":");
		LedgerReplyDto reply = new LedgerReplyDto(stateAndReason == null ? null : new LedgerReplyDto.Data(stateAndReason[0],
				stateAndReason.length > 1 ? stateAndReason[1] : null), code);

		assertThat(mapper.toDomain(status, reply)).isEqualTo(answer);
	}

	@ParameterizedTest
	@CsvSource({ "201", "404", "504" })
	void noReadableBodyIsUnknownUnlessNothingElseCanBeSaid(final int status) {
		assertThat(mapper.toDomain(status, null)).isEqualTo(LedgerAnswer.UNKNOWN);
	}

}
