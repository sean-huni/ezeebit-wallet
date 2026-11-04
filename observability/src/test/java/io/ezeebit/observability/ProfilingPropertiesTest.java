package io.ezeebit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ProfilingPropertiesTest {

	@Test
	void theSamplingIntervalDefaultsToTenMillisecondsWhenUnset() {
		Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("ezeebit.profiling.event", "wall")));

		assertThat(binder.bindOrCreate("ezeebit.profiling", ProfilingProperties.class).interval())
				.isEqualTo(Duration.ofMillis(10));
	}

	@Test
	void theSharedYmlTakesTheIntervalFromPyroscopeInterval() throws IOException {
		assertThat(sharedYmlInterval(Map.of())).isEqualTo(Duration.ofMillis(10));
		assertThat(sharedYmlInterval(Map.of("PYROSCOPE_INTERVAL", "50ms"))).isEqualTo(Duration.ofMillis(50));
	}

	private static Duration sharedYmlInterval(final Map<String, Object> env) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("env", env));
		new YamlPropertySourceLoader().load("shared", new ClassPathResource("config/application.yml"))
				.forEach(environment.getPropertySources()::addLast);
		return Binder.get(environment).bind("ezeebit.profiling.interval", Duration.class).get();
	}
}
