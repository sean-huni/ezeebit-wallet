package io.ezeebit.conversion.service;

import java.time.Clock;

import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.ezeebit.conversion.domain.LedgerAnswer;
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

	public ConversionSettler(final ConversionRepo conversions, final LedgerClient ledger, final QuoteReader quotes,
			final ConversionMapper mapper, final Clock clock) {
		this.conversions = conversions;
		this.ledger = ledger;
		this.quotes = quotes;
		this.mapper = mapper;
		this.clock = clock;
	}

	// Idempotent on the conversion id at the ledger, so posting the same conversion again cannot move money twice.
	public ConversionEntity postAndSettle(final ConversionEntity conversion) {
		return settle(conversion, ledger.post(mapper.toPosting(conversion, quotes.of(conversion.quote()))));
	}

	public ConversionEntity settle(final ConversionEntity conversion, final LedgerAnswer answer) {
		answer.settlement().ifPresent(settlement -> conversions.settle(conversion.id(), settlement.status().name(),
				settlement.reason() == null ? null : settlement.reason().name(), clock.instant()));
		return conversions.find(conversion.id(), conversion.merchantId()).orElseThrow();
	}

}
