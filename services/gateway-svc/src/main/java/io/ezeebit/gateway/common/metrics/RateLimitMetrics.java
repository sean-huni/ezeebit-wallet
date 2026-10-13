package io.ezeebit.gateway.common.metrics;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import io.ezeebit.gateway.domain.LimitOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

// ezeebit_gateway_ratelimit_total{outcome}. No merchant tag: one series per merchant is unbounded cardinality.
// Every outcome is registered at zero, so an alert over increase() never meets a series born mid-window.
public final class RateLimitMetrics {

	public static final String NAME = "ezeebit.gateway.ratelimit";

	private final Map<LimitOutcome, Counter> counters = new EnumMap<>(LimitOutcome.class);

	public RateLimitMetrics(final MeterRegistry registry) {
		for (LimitOutcome outcome : LimitOutcome.values()) {
			counters.put(outcome, Counter.builder(NAME)
					.description("Edge limiter decisions: admitted, limited, contended, refused for the store, or refused for the path")
					.tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
					.register(registry));
		}
	}

	public void record(final LimitOutcome outcome) {
		counters.get(outcome).increment();
	}

}
