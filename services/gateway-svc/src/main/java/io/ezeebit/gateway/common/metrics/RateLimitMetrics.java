package io.ezeebit.gateway.common.metrics;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import io.ezeebit.gateway.domain.Admission;
import io.ezeebit.gateway.domain.LimitOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

// ezeebit_gateway_ratelimit_total{outcome}. No merchant tag: one series per merchant is unbounded cardinality.
// Every outcome is registered at zero, so an alert over increase() never meets a series born mid-window.
public final class RateLimitMetrics {

	public static final String NAME = "ezeebit.gateway.ratelimit";
	public static final String DECISION = "ezeebit.ratelimit.decision";
	private static final String OUTCOME = "outcome";

	private final Map<LimitOutcome, Counter> counters = new EnumMap<>(LimitOutcome.class);
	private final ObservationRegistry observations;

	public RateLimitMetrics(final MeterRegistry registry, final ObservationRegistry observations) {
		this.observations = observations;
		for (LimitOutcome outcome : LimitOutcome.values()) {
			counters.put(outcome, Counter.builder(NAME)
					.description("Edge limiter decisions: admitted, limited, contended, refused for the store, or refused for the path")
					.tag(OUTCOME, tag(outcome))
					.register(registry));
		}
	}

	public void record(final LimitOutcome outcome) {
		counters.get(outcome).increment();
	}

	// The bucket round trip to Redis as its own span and timer, ended before the request is forwarded.
	public Admission decide(final Supplier<Admission> decision) {
		Observation span = Observation.createNotStarted(DECISION, observations).contextualName("rate limit")
				.lowCardinalityKeyValue(OUTCOME, "error");
		return span.observe(() -> {
			Admission admission = decision.get();
			record(admission.outcome());
			span.lowCardinalityKeyValue(OUTCOME, tag(admission.outcome()));
			return admission;
		});
	}

	private static String tag(final LimitOutcome outcome) {
		return outcome.name().toLowerCase(Locale.ROOT);
	}

}
