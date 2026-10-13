package io.ezeebit.conversion.service;

import java.time.Clock;
import java.util.Optional;

import io.ezeebit.conversion.common.ConversionMetrics;
import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.RejectionReason;
import io.ezeebit.conversion.domain.Settlement;
import io.ezeebit.conversion.mapper.ConversionMapper;
import io.ezeebit.conversion.outbound.ledger.LedgerClient;
import org.springframework.stereotype.Service;

// Turns a ledger answer into the conversion's outcome; an UNKNOWN answer leaves it PENDING for the sweeper.
@Service
public class ConversionSettler {

	private final ConversionRepo conversions;
	private final LedgerClient ledger;
	private final QuoteReader quotes;
	private final ConversionMapper mapper;
	private final Clock clock;
	private final ConversionMetrics metrics;

	public ConversionSettler(final ConversionRepo conversions, final LedgerClient ledger, final QuoteReader quotes,
			final ConversionMapper mapper, final Clock clock, final ConversionMetrics metrics) {
		this.conversions = conversions;
		this.ledger = ledger;
		this.quotes = quotes;
		this.mapper = mapper;
		this.clock = clock;
		this.metrics = metrics;
	}

	// The ledger keeps one journal per conversion id, POSTED, VOID or REFUSED, and replays it: posting again
	// returns the first outcome, so money moves at most once and never after a recorded refusal.
	// No transaction is open on this path, so the update has committed by the time it is counted.
	public ConversionEntity postAndSettle(final ConversionEntity conversion) {
		LedgerAnswer answer = ledger.post(mapper.toPosting(conversion, quotes.of(conversion.quote())));
		settle(conversion, answer).ifPresent(settled -> metrics.conversion(settled.status(), settled.reason()));
		return conversions.find(conversion.id(), conversion.merchantId()).orElseThrow();
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

}
