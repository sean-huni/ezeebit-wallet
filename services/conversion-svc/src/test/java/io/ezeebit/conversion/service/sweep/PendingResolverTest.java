package io.ezeebit.conversion.service.sweep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.outbound.ledger.LedgerClient;
import io.ezeebit.conversion.service.conversion.ConversionSettler;
import io.ezeebit.conversion.service.quote.QuoteReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class PendingResolverTest {

	private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final ConversionMetrics metrics = new ConversionMetrics(registry, ObservationRegistry.NOOP);
	private final ConversionRepo conversions = mock(ConversionRepo.class);
	private final LedgerClient ledger = mock(LedgerClient.class);
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final ConversionEntity due = new ConversionEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			"k1", "PENDING", null, NOW.minusSeconds(120), NOW.minusSeconds(180), null, null);

	@Test
	void aSweepWhoseCommitFailsCountsNothing() {
		givenTheLedgerPostedIt();

		assertThatThrownBy(() -> resolver(new CommitFails()).resolveNext(List.of()))
				.isInstanceOf(TransactionSystemException.class);

		assertThat(registry.find(ConversionMetrics.SWEEPS).counters()).isEmpty();
		assertThat(registry.find(ConversionMetrics.OUTCOMES).counters()).isEmpty();
	}

	@Test
	void aCommittedSweepCountsItsActionAndTheOutcome() {
		givenTheLedgerPostedIt();

		assertThat(resolver(new CommitSucceeds()).resolveNext(List.of())).contains(new PendingResolver.Swept(due.id(), "completed"));

		assertThat(registry.get(ConversionMetrics.SWEEPS).tag("action", "completed").counter().count()).isEqualTo(1.0);
		assertThat(registry.get(ConversionMetrics.OUTCOMES).tag("status", "COMPLETED").counter().count())
				.isEqualTo(1.0);
	}

	private void givenTheLedgerPostedIt() {
		when(conversions.claimDue(any(), anyList())).thenReturn(Optional.of(due));
		when(ledger.lookup(due.id(), due.merchantId())).thenReturn(LedgerAnswer.POSTED);
		when(conversions.settle(any(), anyString(), any(), any())).thenReturn(1);
		when(conversions.find(due.id(), due.merchantId())).thenReturn(Optional.of(due));
	}

	private PendingResolver resolver(final AbstractPlatformTransactionManager manager) {
		ConversionProperties properties = new ConversionProperties(null, null, Duration.ofSeconds(30), null, null,
				null, null);
		ConversionSettler settler = new ConversionSettler(conversions, ledger, mock(QuoteReader.class), clock,
				metrics);
		return new PendingResolver(new TransactionTemplate(manager), conversions, ledger, settler, properties, clock,
				metrics);
	}

	private static class CommitSucceeds extends AbstractPlatformTransactionManager {

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(final Object transaction, final TransactionDefinition definition) {
		}

		@Override
		protected void doCommit(final DefaultTransactionStatus status) {
		}

		@Override
		protected void doRollback(final DefaultTransactionStatus status) {
		}

	}

	private static final class CommitFails extends CommitSucceeds {

		@Override
		protected void doCommit(final DefaultTransactionStatus status) {
			throw new TransactionSystemException("commit failed");
		}

	}

}
