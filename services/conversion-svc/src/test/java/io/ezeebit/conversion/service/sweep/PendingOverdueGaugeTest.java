package io.ezeebit.conversion.service.sweep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PendingOverdueGaugeTest {

	private final ConversionRepo conversions = mock(ConversionRepo.class);
	private final PendingOverdueGauge gauge = new PendingOverdueGauge(conversions, new DueCutoff(
			new ConversionProperties(null, null, Duration.ofSeconds(30), null, null, null, null),
			Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC)));

	@AfterEach
	void stop() {
		gauge.close();
	}

	@Test
	void readsTheCountFromTheDatabase() {
		when(conversions.countDue(any())).thenReturn(3L);

		assertThat(gauge.read()).isEqualTo(3.0);
	}

	@Test
	void aDatabaseThatDoesNotAnswerGivesNaNWithinAboutTwoSecondsNotTheConnectionTimeout() {
		when(conversions.countDue(any())).thenAnswer(call -> {
			Thread.sleep(10_000);
			return 1L;
		});

		long started = System.nanoTime();
		double value = gauge.read();

		assertThat(value).isNaN();
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void aFailedQueryGivesNaN() {
		when(conversions.countDue(any())).thenThrow(new IllegalStateException("database down"));

		assertThat(gauge.read()).isNaN();
	}

}
