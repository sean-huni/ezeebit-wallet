package io.ezeebit.conversion.outbound.ledger;

import io.ezeebit.conversion.domain.LedgerAnswer;
import org.springframework.stereotype.Component;

// Translates the ledger's status codes and problem codes into this context's vocabulary.
@Component
public class LedgerAnswerMapper {

	public LedgerAnswer toDomain(final int status, final LedgerReplyDto reply) {
		String code = reply == null ? null : reply.code();
		String state = reply == null || reply.data() == null ? null : reply.data().status();
		if (status == 200 || status == 201) {
			return "VOID".equals(state) ? LedgerAnswer.VOIDED
					: "POSTED".equals(state) ? LedgerAnswer.POSTED : LedgerAnswer.UNKNOWN;
		}
		if (code == null) {
			return LedgerAnswer.UNKNOWN;
		}
		return switch (code) {
			case "INSUFFICIENT_FUNDS" -> LedgerAnswer.INSUFFICIENT_FUNDS;
			case "POSTING_DEADLINE_PASSED" -> LedgerAnswer.DEADLINE_PASSED;
			case "CONVERSION_VOIDED" -> LedgerAnswer.VOIDED;
			case "CONVERSION_ALREADY_POSTED" -> LedgerAnswer.POSTED;
			case "NOT_FOUND" -> LedgerAnswer.ABSENT;
			default -> LedgerAnswer.UNKNOWN;
		};
	}

}
