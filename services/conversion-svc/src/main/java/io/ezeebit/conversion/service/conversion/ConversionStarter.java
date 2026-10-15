package io.ezeebit.conversion.service.conversion;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.ezeebit.conversion.data.repo.QuoteRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// PENDING is written before the ledger is called, so a crash between the two leaves a row the sweeper resolves.
@Service
public class ConversionStarter {

	private final ConversionRepo conversions;
	private final QuoteRepo quotes;

	public ConversionStarter(final ConversionRepo conversions, final QuoteRepo quotes) {
		this.conversions = conversions;
		this.quotes = quotes;
	}

	@Transactional
	public UUID start(final UUID merchantId, final String key, final UUID quoteId, final Instant deadline) {
		UUID id = UUID.randomUUID();
		conversions.insertPending(id, merchantId, quoteId, key, deadline);
		quotes.markUsed(quoteId);
		return id;
	}

}
