package io.ezeebit.conversion.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.common.ConversionMetrics;
import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.Settlement;
import io.ezeebit.conversion.outbound.ledger.LedgerClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// Resolves one PENDING conversion whose deadline + max transaction time has passed, so no posting can still commit.
@Service
public class PendingResolver {

	private final TransactionTemplate transaction;
	private final ConversionRepo conversions;
	private final LedgerClient ledger;
	private final ConversionSettler settler;
	private final ConversionProperties properties;
	private final Clock clock;
	private final ConversionMetrics metrics;

	public PendingResolver(final TransactionTemplate transaction, final ConversionRepo conversions,
			final LedgerClient ledger, final ConversionSettler settler, final ConversionProperties properties,
			final Clock clock, final ConversionMetrics metrics) {
		this.transaction = transaction;
		this.conversions = conversions;
		this.ledger = ledger;
		this.settler = settler;
		this.properties = properties;
		this.clock = clock;
		this.metrics = metrics;
	}

	// The row stays locked while the ledger is asked, so a second replica skips it instead of resolving it twice.
	// Counted only after execute returns, so a failed commit (the row back to PENDING) is never counted.
	public Optional<UUID> resolveNext(final List<UUID> skip) {
		Optional<Resolution> resolved = transaction.execute(status -> conversions
				.claimDue(clock.instant().minus(properties.maxTransactionTime()), skip)
				.map(this::resolve));
		resolved.ifPresent(resolution -> {
			metrics.sweep(resolution.answer());
			resolution.moved().ifPresent(settled -> metrics.conversion(settled.status(), settled.reason()));
		});
		return resolved.map(Resolution::id);
	}

	// Found: COMPLETED. Not found: a VOID in the ledger refuses any late posting, then REJECTED. Unknown: try later.
	private Resolution resolve(final ConversionEntity conversion) {
		LedgerAnswer answer = ledger.lookup(conversion.id(), conversion.merchantId());
		if (answer == LedgerAnswer.ABSENT) {
			answer = ledger.voidPosting(conversion.id(), conversion.merchantId());
		}
		return new Resolution(conversion.id(), answer, settler.settle(conversion, answer));
	}

	private record Resolution(UUID id, LedgerAnswer answer, Optional<Settlement> moved) {
	}

}
