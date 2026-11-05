package io.ezeebit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pyroscope.http.Format;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.config.Config;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class PyroscopeLauncherTest {

	@Test
	void mapsThePropertiesOntoTheSdkConfig() {
		Config config = PyroscopeLauncher.config(new ProfilingProperties(true, URI.create("http://pyroscope:4040"),
				"ledger-svc", "pod-1:8080:7", "wall", DataSize.ofKilobytes(512), Duration.ofMillis(10),
				Duration.ofMillis(20)));

		assertThat(config.applicationName).isEqualTo("ledger-svc");
		assertThat(config.serverAddress).isEqualTo("http://pyroscope:4040");
		assertThat(config.labels).isEqualTo(Map.of("instance", "pod-1:8080:7"));
		assertThat(config.profilingEvent).isEqualTo(EventType.WALL);
		assertThat(config.profilingAlloc).isEqualTo("524288");
		assertThat(config.profilingLock).isEqualTo("10000000");
		assertThat(config.profilingInterval).isEqualTo(Duration.ofMillis(20));
		assertThat(config.format).isEqualTo(Format.JFR);
	}

	@Test
	void reportsNotRunningAndStopsSafelyBeforeAnyStart() {
		PyroscopeLauncher launcher = new PyroscopeLauncher(new SimpleMeterRegistry(),
				new PushRecorder(Clock.systemUTC()));

		assertThat(launcher.running()).isFalse();
		launcher.stop();
		assertThat(launcher.running()).isFalse();
	}
}
