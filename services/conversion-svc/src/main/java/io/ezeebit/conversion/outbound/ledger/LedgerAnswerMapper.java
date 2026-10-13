package io.ezeebit.conversion.outbound.ledger;

import java.util.Set;

import io.ezeebit.conversion.domain.LedgerAnswer;
import org.springframework.stereotype.Component;

// Translates the ledger's status codes and problem codes into this context's vocabulary.
@Component
public class LedgerAnswerMapper {

	// The ledger refuses these for the posting's content alone: the same body is refused every time.
	private static final Set<String> INPUT_REFUSALS = Set.of("VALIDATION_FAILED", "MALFORMED_REQUEST",
			"UNKNOWN_CURRENCY", "SAME_CURRENCY", "AMOUNT_SCALE_EXCEEDED", "AMOUNT_NOT_POSITIVE", "AMOUNT_TOO_LARGE");

	public LedgerAnswer toDomain(final int status, final LedgerReplyDto reply) {
		String code = reply == null ? null : reply.code();
		LedgerReplyDto.Data data = reply == null ? null : reply.data();
		if (status == 200 || status == 201) {
			return data == null ? LedgerAnswer.UNKNOWN : stored(data);
		}
		if (code == null) {
			return LedgerAnswer.UNKNOWN;
		}
		if (status == 400 && INPUT_REFUSALS.contains(code)) {
			return LedgerAnswer.INPUT_REFUSED;
		}
		// IDEMPOTENCY_KEY_REUSED falls to UNKNOWN: something else holds the id, possibly a posting.
		// Only POSTING_NOT_FOUND is ABSENT, which leads to a void; a NOT_FOUND route 404 says nothing about the id.
		return switch (code) {
			case "INSUFFICIENT_FUNDS" -> LedgerAnswer.INSUFFICIENT_FUNDS;
			case "BALANCE_LIMIT_EXCEEDED" -> LedgerAnswer.BALANCE_LIMIT_EXCEEDED;
			case "POSTING_DEADLINE_PASSED" -> LedgerAnswer.DEADLINE_PASSED;
			case "CONVERSION_VOIDED" -> LedgerAnswer.VOIDED;
			case "CONVERSION_ALREADY_POSTED" -> LedgerAnswer.POSTED;
			case "POSTING_NOT_FOUND" -> LedgerAnswer.ABSENT;
			default -> LedgerAnswer.UNKNOWN;
		};
	}

	private static LedgerAnswer stored(final LedgerReplyDto.Data data) {
		if ("REFUSED".equals(data.status())) {
			return "INSUFFICIENT_FUNDS".equals(data.reason()) ? LedgerAnswer.INSUFFICIENT_FUNDS
					: "BALANCE_LIMIT_EXCEEDED".equals(data.reason()) ? LedgerAnswer.BALANCE_LIMIT_EXCEEDED
					: LedgerAnswer.UNKNOWN;
		}
		return "VOID".equals(data.status()) ? LedgerAnswer.VOIDED
				: "POSTED".equals(data.status()) ? LedgerAnswer.POSTED : LedgerAnswer.UNKNOWN;
	}

}
