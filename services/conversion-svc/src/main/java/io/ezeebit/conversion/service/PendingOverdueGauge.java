package io.ezeebit.conversion.service;

import java.time.Clock;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

// A level read from the database on every export, never from heap state: every replica reports the same
// number, so dashboards and alerts take max() of it, never sum().
@Component
public class PendingOverdueGauge implements MeterBinder {

	public static final String NAME = "ezeebit.conversion.pending.overdue";

	private final ConversionRepo conversions;
	private final ConversionProperties properties;
	private final Clock clock;

	public PendingOverdueGauge(final ConversionRepo conversions, final ConversionProperties properties,
			final Clock clock) {
		this.conversions = conversions;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void bindTo(final MeterRegistry registry) {
		Gauge.builder(NAME, this, PendingOverdueGauge::read)
				.description("PENDING conversions past deadline plus max transaction time, so due for the sweeper")
				.register(registry);
	}

	public double read() {
		return conversions.countDue(clock.instant().minus(properties.maxTransactionTime()));
	}

}
