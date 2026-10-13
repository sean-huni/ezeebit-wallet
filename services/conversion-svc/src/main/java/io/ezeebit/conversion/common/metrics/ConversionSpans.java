package io.ezeebit.conversion.common.metrics;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import io.ezeebit.conversion.exception.ConversionException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

// One span (and one timer) per business step. Low-cardinality values become metric tags, so each is a bounded enum:
// never a merchant, an id or an amount. Counts go in high-cardinality values, which reach the span only.
public class ConversionSpans {

	public static final String QUOTE = "ezeebit.quote";
	public static final String ACCEPT = "ezeebit.conversion.accept";
	public static final String SWEEP = "ezeebit.sweep";
	private static final List<String> SWEEP_ACTIONS = List.of("completed", "voided", "refused", "unresolved");
	private static final String NONE = "none";
	private static final String UNKNOWN = "unknown";
	private static final String OUTCOME = "outcome";
	private static final String CODE = "code";
	private static final String PAIR = "pair";

	private final ObservationRegistry observations;

	public ConversionSpans(final ObservationRegistry observations) {
		this.observations = observations;
	}

	// Each span starts as an error: a step that ends in neither an answer nor a refusal keeps it.
	public Observation quote(final String pair) {
		return Observation.createNotStarted(QUOTE, observations).contextualName("quote").lowCardinalityKeyValue(PAIR, pair)
				.lowCardinalityKeyValue(OUTCOME, "error").lowCardinalityKeyValue("reason", NONE)
				.lowCardinalityKeyValue("detail", NONE);
	}

	public Observation accept() {
		return Observation.createNotStarted(ACCEPT, observations).contextualName("conversion accept")
				.lowCardinalityKeyValue(PAIR, UNKNOWN).lowCardinalityKeyValue(OUTCOME, "error").lowCardinalityKeyValue(CODE, NONE);
	}

	public Observation sweep() {
		return Observation.createNotStarted(SWEEP, observations).contextualName("sweep").lowCardinalityKeyValue("result", "error");
	}

	// A refusal is tagged by the step, not recorded as an error: its message can echo the caller's input. A database or
	// transaction failure is recorded.
	public <T> T observe(final Observation span, final Supplier<T> step) {
		span.start();
		try (Observation.Scope scope = span.openScope()) {
			return step.get();
		}
		catch (DataAccessException | TransactionException failure) {
			span.error(failure);
			throw failure;
		}
		finally {
			span.stop();
		}
	}

	public void quoteIssued(final Observation span) {
		span.lowCardinalityKeyValue(OUTCOME, "issued");
	}

	public void quoteRefused(final Observation span, final ConversionException refusal) {
		span.lowCardinalityKeyValue(OUTCOME, "refused").lowCardinalityKeyValue("reason", refusal.code().name())
				.lowCardinalityKeyValue("detail", ConversionMetrics.detail(refusal));
	}

	public void pair(final Observation span, final String sell, final String buy) {
		span.lowCardinalityKeyValue(PAIR, "%s/%s".formatted(sell, buy));
	}

	public void accepted(final Observation span, final String status) {
		span.lowCardinalityKeyValue(OUTCOME, status.toLowerCase(Locale.ROOT));
	}

	// Rejected: the ledger refused a conversion that exists. Refused: the request never became one.
	public void acceptRefused(final Observation span, final ConversionException refusal) {
		span.lowCardinalityKeyValue(OUTCOME, refusal.properties().containsKey("conversionId") ? "rejected" : "refused")
				.lowCardinalityKeyValue(CODE, refusal.code().name());
	}

	public void swept(final Observation span, final Map<String, Integer> actions) {
		int claimed = actions.values().stream().mapToInt(Integer::intValue).sum();
		span.lowCardinalityKeyValue("result", claimed == 0 ? "idle" : "resolved")
				.highCardinalityKeyValue("sweep.claimed", Integer.toString(claimed));
		SWEEP_ACTIONS.forEach(action -> span.highCardinalityKeyValue("sweep.%s".formatted(action),
				Integer.toString(actions.getOrDefault(action, 0))));
	}

}
