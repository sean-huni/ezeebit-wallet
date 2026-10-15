package io.ezeebit.conversion.service.sweep;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.service.sweep.PendingLedgerCheck.Resolution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// Resolves one PENDING conversion whose deadline + max transaction time has passed, so no posting can still commit.
@Service
public class PendingResolver {

	private final TransactionTemplate transaction;
	private final PendingClaimer claimer;
	private final ConversionMetrics metrics;

	public PendingResolver(final TransactionTemplate transaction, final PendingClaimer claimer,
			final ConversionMetrics metrics) {
		this.transaction = transaction;
		this.claimer = claimer;
		this.metrics = metrics;
	}

	// The row stays locked while the ledger is asked, so a second replica skips it instead of resolving it twice.
	// Counted only after execute returns, so a failed commit (the row back to PENDING) is never counted.
	public Optional<Swept> resolveNext(final List<UUID> skip) {
		Optional<Resolution> resolved = transaction.execute(status -> claimer.claimAndResolve(skip));
		resolved.ifPresent(resolution -> {
			metrics.sweep(resolution.answer());
			resolution.moved().ifPresent(settled -> metrics.conversion(settled.status(), settled.reason()));
		});
		return resolved.map(resolution -> new Swept(resolution.id(), ConversionMetrics.sweepAction(resolution.answer())));
	}

	// The claimed row and what the sweep did with it: completed, voided, refused or unresolved.
	public record Swept(UUID id, String action) {
	}

}
