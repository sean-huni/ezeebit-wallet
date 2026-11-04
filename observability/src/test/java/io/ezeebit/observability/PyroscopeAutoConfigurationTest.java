package io.ezeebit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.otel.pyroscope.PyroscopeOtelSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PyroscopeAutoConfigurationTest {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private ApplicationContextRunner runner(final RecordingLauncher launcher) {
		return new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(PyroscopeAutoConfiguration.class))
				.withBean(MeterRegistry.class, () -> registry)
				.withBean(ProfilerLauncher.class, () -> launcher)
				.withPropertyValues("spring.application.name=svc",
						"ezeebit.profiling.server-address=http://pyroscope.test:4040",
						"ezeebit.profiling.instance=host:8080:1", "ezeebit.profiling.event=wall",
						"ezeebit.profiling.alloc=512KB", "ezeebit.profiling.lock=10ms",
						"ezeebit.profiling.application-name=${spring.application.name}");
	}

	@Test
	void startsTheProfilerWhenEnabled() {
		RecordingLauncher launcher = new RecordingLauncher(false, true, null);
		runner(launcher).withPropertyValues("ezeebit.profiling.enabled=true").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(launcher.launched).hasSize(1);
			assertThat(launcher.launched.getFirst().applicationName()).isEqualTo("svc");
			assertThat(launcher.launched.getFirst().serverAddress())
					.isEqualTo(URI.create("http://pyroscope.test:4040"));
		});
	}

	@Test
	void doesNotStartWhenDisabled() {
		RecordingLauncher launcher = new RecordingLauncher(false, true, null);
		runner(launcher).withPropertyValues("ezeebit.profiling.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(ProfilerStarter.class);
			assertThat(context).doesNotHaveBean(SpanProcessor.class);
			assertThat(launcher.launched).isEmpty();
		});
	}

	@Test
	void nativeLoadFailureDoesNotFailTheContext() {
		RecordingLauncher launcher = new RecordingLauncher(false, true, new UnsatisfiedLinkError("no libasyncProfiler"));
		runner(launcher).withPropertyValues("ezeebit.profiling.enabled=true").run(context -> {
			assertThat(context.getStartupFailure()).isNull();
			assertThat(registry.counter("ezeebit.profiling.failures", "cause", "UnsatisfiedLinkError").count())
					.isEqualTo(1.0);
		});
		assertThat(launcher.stops).isZero();
	}

	@Test
	void runtimeFailureDoesNotFailTheContext() {
		RecordingLauncher launcher = new RecordingLauncher(false, true, new IllegalStateException("collector refused"));
		runner(launcher).withPropertyValues("ezeebit.profiling.enabled=true").run(context -> {
			assertThat(context.getStartupFailure()).isNull();
			assertThat(registry.counter("ezeebit.profiling.failures", "cause", "IllegalStateException").count())
					.isEqualTo(1.0);
		});
	}

	@Test
	void closingTheContextStopsTheProfilerItStarted() {
		RecordingLauncher launcher = new RecordingLauncher(false, true, null);
		runner(launcher).withPropertyValues("ezeebit.profiling.enabled=true")
				.run(context -> assertThat(launcher.stops).isZero());
		assertThat(launcher.stops).isEqualTo(1);
	}

	@Test
	void aProfilerAlreadyRunningIsNotStartedAgainNorStopped() {
		RecordingLauncher launcher = new RecordingLauncher(true, true, null);
		runner(launcher).withPropertyValues("ezeebit.profiling.enabled=true").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(launcher.launched).isEmpty();
			assertThat(registry.find("ezeebit.profiling.failures").counter()).isNull();
		});
		assertThat(launcher.stops).isZero();
	}

	@Test
	void aLaunchThatReturnsWithoutRunningIsCountedAndNotStopped() {
		RecordingLauncher launcher = new RecordingLauncher(false, false, null);
		runner(launcher).withPropertyValues("ezeebit.profiling.enabled=true").run(context -> {
			assertThat(context.getStartupFailure()).isNull();
			assertThat(launcher.launched).hasSize(1);
			assertThat(registry.counter("ezeebit.profiling.failures", "cause", "start").count()).isEqualTo(1.0);
		});
		assertThat(launcher.stops).isZero();
	}

	@Test
	void registersTheProfilingGaugesWhenEnabled() {
		runner(new RecordingLauncher(false, true, null)).withPropertyValues("ezeebit.profiling.enabled=true")
				.run(context -> assertThat(context).hasSingleBean(ProfilingGauges.class));
	}

	@Test
	void registersNoProfilingGaugesWhenDisabled() {
		runner(new RecordingLauncher(false, true, null)).withPropertyValues("ezeebit.profiling.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(ProfilingGauges.class));
	}

	@Test
	void registersTheSpanProfileLinkWhenEnabled() {
		runner(new RecordingLauncher(false, true, null)).withPropertyValues("ezeebit.profiling.enabled=true")
				.run(context -> assertThat(context.getBean(SpanProcessor.class))
						.isInstanceOf(PyroscopeOtelSpanProcessor.class));
	}

	private static final class RecordingLauncher implements ProfilerLauncher {

		private final boolean alreadyRunning;

		private final boolean comesUp;

		private final Throwable failure;

		private final List<ProfilingProperties> launched = new ArrayList<>();

		private int stops;

		RecordingLauncher(final boolean alreadyRunning, final boolean comesUp, final Throwable failure) {
			this.alreadyRunning = alreadyRunning;
			this.comesUp = comesUp;
			this.failure = failure;
		}

		@Override
		public boolean running() {
			return alreadyRunning || comesUp && !launched.isEmpty();
		}

		@Override
		public void launch(final ProfilingProperties properties) {
			if (failure instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (failure instanceof LinkageError linkage) {
				throw linkage;
			}
			launched.add(properties);
		}

		@Override
		public void stop() {
			stops++;
		}
	}
}
