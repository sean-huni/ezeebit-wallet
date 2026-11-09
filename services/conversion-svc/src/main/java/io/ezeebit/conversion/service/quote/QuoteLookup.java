package io.ezeebit.conversion.service.quote;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.conversion.data.model.QuoteEntity;

public interface QuoteLookup {

	QuoteEntity find(UUID merchantId, UUID quoteId);

	Instant postingDeadline(QuoteEntity quote, UUID quoteId);

}
