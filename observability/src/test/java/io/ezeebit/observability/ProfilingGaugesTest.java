package io.ezeebit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ProfilingGaugesTest {

	private static final Instant CREATED = Instant.parse("2026-11-04T10:00:00Z");

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private final PushRecorder recorder = new PushRecorder(Clock.fixed(CREATED, ZoneOffset.UTC));

	private double gauge(final String name) {
		return registry.get(name).gauge().value();
	}

	@Test
	void runningReadsOneWhileTheProfilerRuns() {
		new ProfilingGauges(new FixedLauncher(true), recorder).bindTo(registry);

		assertThat(gauge("ezeebit.profiling.running")).isEqualTo(1.0);
	}

	@Test
	void runningReadsZeroWhenTheProfilerIsNotRunning() {
		new ProfilingGauges(new FixedLauncher(false), recorder).bindTo(registry);

		assertThat(gauge("ezeebit.profiling.running")).isEqualTo(0.0);
	}

	@Test
	void lastPushReadsTheRecordersEpochSeconds() {
		new ProfilingGauges(new FixedLauncher(true), recorder).bindTo(registry);

		assertThat(gauge("ezeebit.profiling.last.push")).isEqualTo(CREATED.getEpochSecond());
	}

	private record FixedLauncher(boolean running) implements ProfilerLauncher {

		@Override
		public void launch(final ProfilingProperties properties) {
		}

		@Override
		public void stop() {
		}
	}
}
