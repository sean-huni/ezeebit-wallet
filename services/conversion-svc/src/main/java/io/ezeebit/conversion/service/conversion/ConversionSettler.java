package io.ezeebit.conversion.service.conversion;

import java.time.Clock;
import java.util.Optional;

import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.RejectionReason;
import io.ezeebit.conversion.domain.Settlement;
import org.springframework.stereotype.Service;

// Turns a ledger answer into the conversion's outcome; an UNKNOWN answer leaves it PENDING for the sweeper.
@Service
public class ConversionSettler {

	private final ConversionRepo conversions;
	private final Clock clock;
	private final ConversionMetrics metrics;

	public ConversionSettler(final ConversionRepo conversions, final Clock clock, final ConversionMetrics metrics) {
		this.conversions = conversions;
		this.clock = clock;
		this.metrics = metrics;
	}

	// Present only when this call's compare-and-set moved the row, so each conversion is counted once.
	// Counting is the caller's job: only the caller knows when the update has committed.
	public Optional<Settlement> settle(final ConversionEntity conversion, final LedgerAnswer answer) {
		return answer.settlement().filter(settlement -> {
			RejectionReason reason = settlement.reason();
			return conversions.settle(conversion.id(), settlement.status().name(), reason == null ? null : reason.name(),
					clock.instant()) == 1;
		});
	}

	// For a caller with no transaction open: the update has committed by the time it is counted.
	public ConversionEntity settleCommitted(final ConversionEntity conversion, final LedgerAnswer answer) {
		settle(conversion, answer).ifPresent(settled -> metrics.conversion(settled.status(), settled.reason()));
		return conversions.find(conversion.id(), conversion.merchantId()).orElseThrow();
	}

}
