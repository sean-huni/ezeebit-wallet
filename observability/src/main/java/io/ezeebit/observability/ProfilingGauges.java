package io.ezeebit.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

// Exported only while profiling is enabled, so a JVM without these series is itself the signal ProfilesAbsent reads.
final class ProfilingGauges implements MeterBinder {

	private final ProfilerLauncher launcher;

	private final PushRecorder recorder;

	ProfilingGauges(final ProfilerLauncher launcher, final PushRecorder recorder) {
		this.launcher = launcher;
		this.recorder = recorder;
	}

	@Override
	public void bindTo(final MeterRegistry registry) {
		Gauge.builder("ezeebit.profiling.running", launcher, l -> l.running() ? 1 : 0)
				.description("1 while the Pyroscope profiler runs in this JVM, else 0")
				.register(registry);
		Gauge.builder("ezeebit.profiling.last.push", recorder, PushRecorder::lastPushEpochSeconds)
				.baseUnit("seconds")
				.description("Epoch seconds of the last upload Pyroscope accepted, or of this JVM's profiler setup")
				.register(registry);
	}
}
