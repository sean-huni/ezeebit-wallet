package io.ezeebit.observability;

import io.micrometer.core.instrument.MeterRegistry;
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
	@ConditionalOnMissingBean
	ProfilerLauncher profilerLauncher(final MeterRegistry registry) {
		return new PyroscopeLauncher(registry);
	}

	@Bean
	ProfilerStarter profilerStarter(final ProfilingProperties properties, final ProfilerLauncher launcher,
			final MeterRegistry registry) {
		return new ProfilerStarter(properties, launcher, registry);
	}
}
