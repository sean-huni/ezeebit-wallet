package io.ezeebit.conversion.service.sweep;

import java.time.Clock;
import java.time.Instant;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import org.springframework.stereotype.Component;

// The one definition of "due": past its deadline plus max transaction time, so no posting can still commit.
@Component
public class DueCutoff {

	private final ConversionProperties properties;
	private final Clock clock;

	public DueCutoff(final ConversionProperties properties, final Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public Instant deadlineBefore() {
		return clock.instant().minus(properties.maxTransactionTime());
	}

}
