package io.ezeebit.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;

// Fail-open: a start failure is logged once and counted, never thrown. The SDK swallows some start failures and
// returns normally, so a launch is only a start when the profiler then reports running (cause=start if not).
// Push failures after a start are counted by PyroscopeLogBridge. Only the context that started it stops it.
public final class ProfilerStarter implements SmartInitializingSingleton, DisposableBean {

	private static final Logger LOG = LoggerFactory.getLogger(ProfilerStarter.class);

	private final ProfilingProperties properties;

	private final ProfilerLauncher launcher;

	private final MeterRegistry registry;

	private volatile boolean started;

	public ProfilerStarter(final ProfilingProperties properties, final ProfilerLauncher launcher,
			final MeterRegistry registry) {
		this.properties = properties;
		this.launcher = launcher;
		this.registry = registry;
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (launcher.running()) {
			LOG.info("Profiler already running in this JVM; not starting it again");
			return;
		}
		try {
			launcher.launch(properties);
		}
		catch (RuntimeException | LinkageError failure) {
			String cause = failure.getClass().getSimpleName();
			LOG.warn("Profiler did not start: {}", cause);
			registry.counter("ezeebit.profiling.failures", "cause", cause).increment();
			return;
		}
		if (!launcher.running()) {
			LOG.warn("Profiler did not start: the agent returned without running");
			registry.counter("ezeebit.profiling.failures", "cause", "start").increment();
			return;
		}
		started = true;
	}

	@Override
	public void destroy() {
		if (started) {
			launcher.stop();
		}
	}
}
