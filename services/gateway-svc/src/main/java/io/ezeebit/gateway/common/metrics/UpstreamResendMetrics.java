package io.ezeebit.gateway.common.metrics;

import java.util.Collection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

// ezeebit_gateway_upstream_resend_total{route}: attempts resent because the connect failed. Each RetryUnsent route
// is registered at zero, so an alert over increase() never meets a series born mid-window.
public final class UpstreamResendMetrics {

	public static final String NAME = "ezeebit.gateway.upstream.resend";
	private static final String ROUTE = "route";

	private final MeterRegistry registry;

	public UpstreamResendMetrics(final MeterRegistry registry, final Collection<String> routes) {
		this.registry = registry;
		routes.forEach(this::counter);
	}

	public void resent(final String route) {
		counter(route).increment();
	}

	private Counter counter(final String route) {
		return Counter.builder(NAME)
				.description("Attempts resent to another instance because the previous connect failed")
				.tag(ROUTE, route)
				.register(registry);
	}

}
