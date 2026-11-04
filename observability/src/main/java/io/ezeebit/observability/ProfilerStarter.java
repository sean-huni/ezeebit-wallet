package io.ezeebit.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

// Fail-open: a missing native library or an unreachable server must never stop a service, only be counted.
public final class ProfilerStarter implements SmartInitializingSingleton {

	private static final Logger LOG = LoggerFactory.getLogger(ProfilerStarter.class);

	private final ProfilingProperties properties;

	private final ProfilerLauncher launcher;

	private final MeterRegistry registry;

	public ProfilerStarter(final ProfilingProperties properties, final ProfilerLauncher launcher,
			final MeterRegistry registry) {
		this.properties = properties;
		this.launcher = launcher;
		this.registry = registry;
	}

	@Override
	public void afterSingletonsInstantiated() {
		try {
			launcher.launch(properties);
		}
		catch (RuntimeException | LinkageError failure) {
			String cause = failure.getClass().getSimpleName();
			LOG.warn("Profiler did not start: {}", cause);
			registry.counter("ezeebit.profiling.failures", "cause", cause).increment();
		}
	}
}
