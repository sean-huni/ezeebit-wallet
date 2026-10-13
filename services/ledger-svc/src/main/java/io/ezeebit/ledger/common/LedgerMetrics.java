package io.ezeebit.ledger.common;

import java.util.Locale;

import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// Counters are flows, so each replica counts its own and dashboards sum them. Tags are bounded enums only.
@Component
public class LedgerMetrics {

	public static final String DEPOSITS = "ezeebit.ledger.deposits";
	public static final String CONVERSION_POSTINGS = "ezeebit.ledger.conversion.postings";
	private static final String NONE = "none";
	private static final String UNKNOWN = "unknown";

	public enum Outcome {
		POSTED, REPLAYED, REFUSED, VOIDED;

		String tag() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private final MeterRegistry registry;

	public LedgerMetrics(final MeterRegistry registry) {
		this.registry = registry;
	}

	// A refusal can come before the currency is known: the caller's text never becomes a tag value.
	public void deposit(final Currency currency, final Outcome outcome, final ErrorCode code) {
		registry.counter(DEPOSITS, "currency", currency == null ? UNKNOWN : currency.code().value(),
				"outcome", outcome.tag(), "code", code == null ? NONE : code.name()).increment();
	}

	public void conversionPosting(final Outcome outcome, final ErrorCode code) {
		registry.counter(CONVERSION_POSTINGS, "outcome", outcome.tag(), "code", code == null ? NONE : code.name())
				.increment();
	}

}
