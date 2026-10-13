package io.ezeebit.conversion.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.outbound.ledger.LedgerAnswerMapper;
import io.ezeebit.conversion.outbound.ledger.LedgerReplyDto;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LedgerAnswerMapperTest {

	private final LedgerAnswerMapper mapper = new LedgerAnswerMapper();

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = {
			"201, POSTED, null, POSTED",
			"200, POSTED, null, POSTED",
			"201, VOID, null, VOIDED",
			"200, VOID, null, VOIDED",
			"201, null, null, UNKNOWN",
			"422, null, INSUFFICIENT_FUNDS, INSUFFICIENT_FUNDS",
			"409, null, POSTING_DEADLINE_PASSED, DEADLINE_PASSED",
			"409, null, CONVERSION_VOIDED, VOIDED",
			"409, null, CONVERSION_ALREADY_POSTED, POSTED",
			"404, null, NOT_FOUND, ABSENT",
			"400, null, VALIDATION_FAILED, UNKNOWN",
			"503, null, null, UNKNOWN",
			"500, null, INTERNAL_ERROR, UNKNOWN" })
	void eachLedgerReplyMeansOneThing(final int status, final String state, final String code, final LedgerAnswer answer) {
		LedgerReplyDto reply = new LedgerReplyDto(state == null ? null : new LedgerReplyDto.Data(state), code);

		assertThat(mapper.toDomain(status, reply)).isEqualTo(answer);
	}

	@ParameterizedTest
	@CsvSource({ "201", "404", "504" })
	void noReadableBodyIsUnknownUnlessNothingElseCanBeSaid(final int status) {
		assertThat(mapper.toDomain(status, null)).isEqualTo(LedgerAnswer.UNKNOWN);
	}

}
