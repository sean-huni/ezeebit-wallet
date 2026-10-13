package io.ezeebit.ledger.common.metrics;

import java.util.Locale;
import java.util.function.Supplier;

import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

// Records each money outcome on a counter and on its business span, with the same bounded tags: never a
// merchant, an id or an amount. Counters are flows: each replica counts its own and dashboards sum them.
@Component
public class LedgerMetrics {

	public static final String DEPOSITS = "ezeebit.ledger.deposits";
	public static final String CONVERSION_POSTINGS = "ezeebit.ledger.conversion.postings";
	public static final String DEPOSIT_SPAN = "ezeebit.deposit";
	public static final String POSTING_SPAN = "ezeebit.ledger.posting";
	private static final String NONE = "none";
	private static final String UNKNOWN = "unknown";
	private static final String CURRENCY = "currency";
	private static final String OUTCOME = "outcome";
	private static final String CODE = "code";

	public enum Outcome {
		POSTED, REPLAYED, REFUSED, VOIDED;

		String tag() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private final MeterRegistry registry;
	private final ObservationRegistry observations;

	public LedgerMetrics(final MeterRegistry registry, final ObservationRegistry observations) {
		this.registry = registry;
		this.observations = observations;
	}

	// Starts as an error: a step that ends in neither an answer nor a refusal keeps it.
	public Observation depositSpan() {
		return Observation.createNotStarted(DEPOSIT_SPAN, observations).contextualName("deposit")
				.lowCardinalityKeyValue(CURRENCY, UNKNOWN).lowCardinalityKeyValue(OUTCOME, "error")
				.lowCardinalityKeyValue(CODE, NONE);
	}

	public Observation postingSpan(final String operation) {
		return Observation.createNotStarted(POSTING_SPAN, observations).contextualName("ledger posting")
				.lowCardinalityKeyValue("operation", operation).lowCardinalityKeyValue(OUTCOME, "error")
				.lowCardinalityKeyValue(CODE, NONE);
	}

	// A refusal is tagged by the step, not recorded as an error: its message can echo the caller's input.
	public <T> T observe(final Observation span, final Supplier<T> step) {
		span.start();
		try (Observation.Scope scope = span.openScope()) {
			return step.get();
		}
		catch (DataAccessException failure) {
			span.error(failure);
			throw failure;
		}
		finally {
			span.stop();
		}
	}

	// A refusal can come before the currency is known: the caller's text never becomes a tag value.
	public void deposit(final Observation span, final Currency currency, final Outcome outcome, final ErrorCode code) {
		String currencyTag = currency == null ? UNKNOWN : currency.code().value();
		String codeTag = code == null ? NONE : code.name();
		registry.counter(DEPOSITS, CURRENCY, currencyTag, OUTCOME, outcome.tag(), CODE, codeTag).increment();
		span.lowCardinalityKeyValue(CURRENCY, currencyTag).lowCardinalityKeyValue(OUTCOME, outcome.tag())
				.lowCardinalityKeyValue(CODE, codeTag);
	}

	public void conversionPosting(final Observation span, final Outcome outcome, final ErrorCode code) {
		String codeTag = code == null ? NONE : code.name();
		registry.counter(CONVERSION_POSTINGS, OUTCOME, outcome.tag(), CODE, codeTag).increment();
		span.lowCardinalityKeyValue(OUTCOME, outcome.tag()).lowCardinalityKeyValue(CODE, codeTag);
	}

}
