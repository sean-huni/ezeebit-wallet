package io.ezeebit.ledger.common.metrics;

import java.util.Locale;
import java.util.function.Supplier;

import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.exception.ErrorCode;
import io.micrometer.observation.Observation;

public interface LedgerMetrics {

	String DEPOSITS = "ezeebit.ledger.deposits";
	String CONVERSION_POSTINGS = "ezeebit.ledger.conversion.postings";
	String DEPOSIT_SPAN = "ezeebit.deposit";
	String POSTING_SPAN = "ezeebit.ledger.posting";

	enum Outcome {
		POSTED, REPLAYED, REFUSED, VOIDED;

		String tag() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	Observation depositSpan();

	Observation postingSpan(String operation);

	<T> T observe(Observation span, Supplier<T> step);

	void deposit(Observation span, Currency currency, Outcome outcome, ErrorCode code);

	void conversionPosting(Observation span, Outcome outcome, ErrorCode code);

}
