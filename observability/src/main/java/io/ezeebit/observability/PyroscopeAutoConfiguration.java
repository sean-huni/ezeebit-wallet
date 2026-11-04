package io.ezeebit.observability;

import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.otel.pyroscope.PyroscopeOtelSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "ezeebit.profiling", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ProfilingProperties.class)
public class PyroscopeAutoConfiguration {

	private static final Logger LOG = LoggerFactory.getLogger(PyroscopeAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean
	ProfilerLauncher profilerLauncher(final MeterRegistry registry) {
		return new PyroscopeLauncher(registry);
	}

	@Bean
	ProfilerStarter profilerStarter(final ProfilingProperties properties, final ProfilerLauncher launcher,
			final MeterRegistry registry) {
		return new ProfilerStarter(properties, launcher, registry);
	}

	// Boot's SDK adds every SpanProcessor bean. The processor loads async-profiler's native library when built,
	// so a load failure degrades to a no-op processor and is counted, never failing the context.
	@Bean
	SpanProcessor pyroscopeSpanProcessor(final MeterRegistry registry) {
		return spanProcessor(PyroscopeOtelSpanProcessor::new, registry);
	}

	static SpanProcessor spanProcessor(final Supplier<SpanProcessor> factory, final MeterRegistry registry) {
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
