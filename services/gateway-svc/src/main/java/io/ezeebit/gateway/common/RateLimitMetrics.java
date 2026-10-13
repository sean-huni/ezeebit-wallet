package io.ezeebit.gateway.common;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

// ezeebit_gateway_ratelimit_total{outcome}. No merchant tag: one series per merchant is unbounded cardinality.
// Every outcome is registered at zero, so an alert over increase() never meets a series born mid-window.
public final class RateLimitMetrics {

	public static final String NAME = "ezeebit.gateway.ratelimit";

	public enum Outcome { ALLOWED, LIMITED, UNAVAILABLE }

	private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

	public RateLimitMetrics(final MeterRegistry registry) {
		for (Outcome outcome : Outcome.values()) {
			counters.put(outcome, Counter.builder(NAME)
					.description("Merchant requests the edge rate limiter admitted, limited, or refused because Redis did not answer")
					.tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
					.register(registry));
		}
	}

	public void record(final Outcome outcome) {
		counters.get(outcome).increment();
	}

}
