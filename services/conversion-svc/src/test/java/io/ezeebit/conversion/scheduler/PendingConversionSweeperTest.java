package io.ezeebit.conversion.scheduler;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.common.metrics.ConversionSpans;
import io.ezeebit.conversion.service.sweep.PendingResolver;
import io.ezeebit.conversion.service.sweep.PendingResolver.Swept;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;

class PendingConversionSweeperTest {

	private final TestObservationRegistry observations = TestObservationRegistry.create();
	private final PendingResolver resolver = mock(PendingResolver.class);

	@Test
	void oneRunIsOneSpanCountingWhatItDidWithEachRow() {
		when(resolver.resolveNext(anyList())).thenReturn(Optional.of(new Swept(UUID.randomUUID(), "completed")),
				Optional.of(new Swept(UUID.randomUUID(), "voided")), Optional.of(new Swept(UUID.randomUUID(), "completed")),
				Optional.empty());

		new PendingConversionSweeper(resolver, new ConversionMetrics(new SimpleMeterRegistry(), observations)).sweep();

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat()
				.hasNameEqualTo(ConversionSpans.SWEEP).hasBeenStopped().hasLowCardinalityKeyValue("result", "resolved")
				.hasHighCardinalityKeyValue("sweep.claimed", "3").hasHighCardinalityKeyValue("sweep.completed", "2")
				.hasHighCardinalityKeyValue("sweep.voided", "1");
	}

}
