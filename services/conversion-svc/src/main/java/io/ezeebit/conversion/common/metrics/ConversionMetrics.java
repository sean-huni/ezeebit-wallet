package io.ezeebit.conversion.common.metrics;

import java.time.Duration;

import io.ezeebit.conversion.domain.ConversionStatus;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.RejectionReason;
import io.ezeebit.conversion.domain.Settlement;
import io.ezeebit.conversion.exception.ConversionException;

public interface ConversionMetrics {

	String QUOTES = "ezeebit.conversion.quotes";
	String RATE_CALLS = "ezeebit.conversion.rate.calls";
	String OUTCOMES = "ezeebit.conversion.outcomes";
	String LEFT_PENDING = "ezeebit.conversion.left.pending";
	String SWEEPS = "ezeebit.conversion.sweeps";
	String DEAD_LETTERS = "ezeebit.conversion.ledger.deadletter";
	String DEAD_LETTER_FAILURES = "ezeebit.conversion.ledger.deadletter.publish.failed";

	ConversionSpans spans();

	void quoteIssued(String pair);

	void quoteRefused(String pair, ConversionException refusal);

	void rateCall(Duration took, String outcome);

	void conversion(ConversionStatus status, RejectionReason reason);

	void leftPending();

	void sweep(LedgerAnswer answer);

	void deadLettered(String op, String reason);

	void deadLetterLost(String op);

	static String sweepAction(final LedgerAnswer answer) {
		return answer.settlement().map(ConversionMetrics::action).orElse("unresolved");
	}

	private static String action(final Settlement settlement) {
		if (settlement.status() == ConversionStatus.COMPLETED) {
			return "completed";
		}
		return settlement.reason() == RejectionReason.VOIDED ? "voided" : "refused";
	}

}
