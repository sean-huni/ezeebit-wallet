package io.ezeebit.observability;

import java.time.Clock;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.otel.pyroscope.PyroscopeOtelSpanProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "ezeebit.profiling", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ProfilingProperties.class)
public class PyroscopeAutoConfiguration {

	@Bean
	PushRecorder profilingPushRecorder() {
		return new PushRecorder(Clock.systemUTC());
	}

	@Bean
	@ConditionalOnMissingBean
	ProfilerLauncher profilerLauncher(final MeterRegistry registry, final PushRecorder recorder) {
		return new PyroscopeLauncher(registry, recorder);
	}

	// Boot binds every MeterBinder bean to its registries.
	@Bean
	ProfilingGauges profilingGauges(final ProfilerLauncher launcher, final PushRecorder recorder) {
		return new ProfilingGauges(launcher, recorder);
	}

	@Bean
	ProfilerStarter profilerStarter(final ProfilingProperties properties, final ProfilerLauncher launcher,
			final MeterRegistry registry) {
		return new ProfilerStarter(properties, launcher, registry);
	}

	// Boot's SDK adds every SpanProcessor bean beside its own exporter.
	@Bean
	SpanProcessor pyroscopeSpanProcessor(final MeterRegistry registry) {
		return SpanProfileLink.create(PyroscopeOtelSpanProcessor::new, registry);
	}
}
