package io.ezeebit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PyroscopeAutoConfigurationTest {

	private final List<ProfilingProperties> launched = new ArrayList<>();

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private ApplicationContextRunner runner(final ProfilerLauncher launcher) {
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
		runner(launched::add).withPropertyValues("ezeebit.profiling.enabled=true").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(launched).hasSize(1);
			assertThat(launched.getFirst().applicationName()).isEqualTo("svc");
			assertThat(launched.getFirst().serverAddress()).isEqualTo(URI.create("http://pyroscope.test:4040"));
		});
	}

	@Test
	void doesNotStartWhenDisabled() {
		runner(launched::add).withPropertyValues("ezeebit.profiling.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(ProfilerStarter.class);
			assertThat(launched).isEmpty();
		});
	}

	@Test
	void nativeLoadFailureDoesNotFailTheContext() {
		runner(properties -> {
			throw new UnsatisfiedLinkError("no libasyncProfiler");
		}).withPropertyValues("ezeebit.profiling.enabled=true").run(context -> {
			assertThat(context.getStartupFailure()).isNull();
			assertThat(registry.counter("ezeebit.profiling.failures", "cause", "UnsatisfiedLinkError").count())
					.isEqualTo(1.0);
		});
	}

	@Test
	void runtimeFailureDoesNotFailTheContext() {
		runner(properties -> {
			throw new IllegalStateException("collector refused");
		}).withPropertyValues("ezeebit.profiling.enabled=true").run(context -> {
			assertThat(context.getStartupFailure()).isNull();
			assertThat(registry.counter("ezeebit.profiling.failures", "cause", "IllegalStateException").count())
					.isEqualTo(1.0);
		});
	}
}
