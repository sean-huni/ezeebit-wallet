package io.ezeebit.observability;

import java.time.Clock;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.pyroscope.http.Format;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.javaagent.impl.PyroscopeExporter;
import io.pyroscope.javaagent.impl.QueuedExporter;

public final class PyroscopeLauncher implements ProfilerLauncher {

	private final MeterRegistry registry;

	private final PushRecorder recorder;

	PyroscopeLauncher(final MeterRegistry registry, final PushRecorder recorder) {
		this.registry = registry;
		this.recorder = recorder;
	}

	@Override
	public boolean running() {
		return PyroscopeAgent.isStarted();
	}

	@Override
	// The agent's own default exporter chain, with RecordingExporter around the uploader (Options.Builder.build).
	public void launch(final ProfilingProperties properties) {
		Config config = config(properties);
		PyroscopeLogBridge bridge = new PyroscopeLogBridge(registry, Clock.systemUTC(), recorder);
		RecordingExporter uploader = new RecordingExporter(new PyroscopeExporter(config, bridge), recorder);
		PyroscopeAgent.start(new PyroscopeAgent.Options.Builder(config)
				.setLogger(bridge)
				.setExporter(new QueuedExporter(config, uploader, bridge))
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
