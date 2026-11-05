package io.ezeebit.observability;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "ezeebit.profiling")
public record ProfilingProperties(boolean enabled, URI serverAddress, String applicationName, String instance,
		String event, DataSize alloc, Duration lock, @DefaultValue("10ms") Duration interval) {

	public ProfilingProperties {
		if (interval.isZero() || interval.isNegative()) {
			throw new IllegalArgumentException(
					"ezeebit.profiling.interval must be greater than zero, was %s".formatted(interval));
		}
	}
}
