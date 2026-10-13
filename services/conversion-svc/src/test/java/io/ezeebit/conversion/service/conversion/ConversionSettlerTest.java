package io.ezeebit.conversion.service.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.mapper.ConversionMapper;
import io.ezeebit.conversion.outbound.ledger.LedgerClient;
import io.ezeebit.conversion.service.quote.QuoteReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ConversionSettlerTest {

	private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final ConversionRepo conversions = mock(ConversionRepo.class);
	private final LedgerClient ledger = mock(LedgerClient.class);
	private final ConversionSettler settler = new ConversionSettler(conversions, ledger, mock(QuoteReader.class),
			mock(ConversionMapper.class), Clock.fixed(NOW, ZoneOffset.UTC), new ConversionMetrics(registry));
	private final ConversionEntity pending = new ConversionEntity(UUID.randomUUID(), UUID.randomUUID(),
			UUID.randomUUID(), "k1", "PENDING", null, NOW.plusSeconds(45), NOW, null, null);

	@Test
	void aSettlementAnotherCallAlreadyMadeIsNotCountedAgain() {
		givenTheLedgerAnswers(LedgerAnswer.POSTED, 0);

		settler.postAndSettle(pending);

		assertThat(registry.find(ConversionMetrics.OUTCOMES).counters()).isEmpty();
	}

	@Test
	void theCallThatMovesTheRowCountsItOnce() {
		givenTheLedgerAnswers(LedgerAnswer.INSUFFICIENT_FUNDS, 1);

		settler.postAndSettle(pending);

		assertThat(registry.get(ConversionMetrics.OUTCOMES).tag("status", "REJECTED")
				.tag("reason", "INSUFFICIENT_FUNDS").counter().count()).isEqualTo(1.0);
	}

	private void givenTheLedgerAnswers(final LedgerAnswer answer, final int rowsMoved) {
		when(ledger.post(any())).thenReturn(answer);
		when(conversions.settle(any(), anyString(), any(), any())).thenReturn(rowsMoved);
		when(conversions.find(pending.id(), pending.merchantId())).thenReturn(Optional.of(pending));
	}

}
