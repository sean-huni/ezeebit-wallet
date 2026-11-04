package io.ezeebit.observability;

import java.time.Clock;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.pyroscope.http.Format;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;

public final class PyroscopeLauncher implements ProfilerLauncher {

	private final MeterRegistry registry;

	public PyroscopeLauncher(final MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public boolean running() {
		return PyroscopeAgent.isStarted();
	}

	@Override
	public void launch(final ProfilingProperties properties) {
		PyroscopeAgent.start(new PyroscopeAgent.Options.Builder(config(properties))
				.setLogger(new PyroscopeLogBridge(registry, Clock.systemUTC()))
				.build());
	}

	@Override
	public void stop() {
		PyroscopeAgent.stop();
	}

	// async-profiler reads alloc as bytes and lock as nanoseconds when given bare numbers.
	static Config config(final ProfilingProperties properties) {
		return new Config.Builder()
				.setApplicationName(properties.applicationName())
				.setServerAddress(properties.serverAddress().toString())
				.setLabels(Map.of("instance", properties.instance()))
				.setProfilingEvent(EventType.fromId(properties.event()))
				.setProfilingAlloc(Long.toString(properties.alloc().toBytes()))
				.setProfilingLock(Long.toString(properties.lock().toNanos()))
				.setFormat(Format.JFR)
				.build();
	}
}
