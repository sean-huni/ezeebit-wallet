package io.ezeebit.observability;

import java.util.Map;

import io.pyroscope.http.Format;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;

public final class PyroscopeLauncher implements ProfilerLauncher {

	@Override
	public void launch(final ProfilingProperties properties) {
		PyroscopeAgent.start(config(properties));
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
