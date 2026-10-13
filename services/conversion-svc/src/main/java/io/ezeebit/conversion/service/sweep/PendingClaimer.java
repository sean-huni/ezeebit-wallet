package io.ezeebit.conversion.service.sweep;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.ezeebit.conversion.service.sweep.PendingLedgerCheck.Resolution;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PendingClaimer {

	private final ConversionRepo conversions;
	private final DueCutoff cutoff;
	private final PendingLedgerCheck check;

	public PendingClaimer(final ConversionRepo conversions, final DueCutoff cutoff, final PendingLedgerCheck check) {
		this.conversions = conversions;
		this.cutoff = cutoff;
		this.check = check;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Resolution> claimAndResolve(final List<UUID> skip) {
		return conversions.claimDue(cutoff.deadlineBefore(), skip).map(check::resolve);
	}

}
