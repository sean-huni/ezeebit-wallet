package io.ezeebit.observability;

import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// The Pyroscope span processor loads async-profiler's native library when built. Fail-open: a load failure of any
// type degrades to a no-op processor and is counted, never failing the context. Grafana's Tempo profile link
// exists only on spans this processor tags (pyroscope.profile.id).
final class SpanProfileLink {

	private static final Logger LOG = LoggerFactory.getLogger(SpanProfileLink.class);

	private SpanProfileLink() {
	}

	static SpanProcessor create(final Supplier<SpanProcessor> factory, final MeterRegistry registry) {
		try {
			return factory.get();
		}
		catch (RuntimeException | LinkageError failure) {
			String cause = failure.getClass().getSimpleName();
			LOG.warn("Span-to-profile link disabled: {}", cause);
			registry.counter("ezeebit.profiling.failures", "cause", "link-" + cause).increment();
			return SpanProcessor.composite();
		}
	}
}
