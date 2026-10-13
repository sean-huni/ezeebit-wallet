package io.ezeebit.conversion.service.quote;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.data.model.QuoteEntity;
import io.ezeebit.conversion.data.repo.QuoteRepo;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import org.springframework.stereotype.Component;

// Two steps, so the caller can tag the pair on its span before an expiry refusal.
@Component
public class QuoteRedeemer {

	private final QuoteRepo quotes;
	private final QuoteReader reader;
	private final ConversionProperties properties;

	public QuoteRedeemer(final QuoteRepo quotes, final QuoteReader reader, final ConversionProperties properties) {
		this.quotes = quotes;
		this.reader = reader;
		this.properties = properties;
	}

	public QuoteEntity quote(final UUID merchantId, final UUID quoteId) {
		return quotes.findForMerchant(quoteId, merchantId)
				.orElseThrow(() -> new ConversionException(ErrorCode.QUOTE_NOT_FOUND, "quoteId", quoteId));
	}

	public Instant postingDeadline(final QuoteEntity quote, final UUID quoteId) {
		return reader.live(quote)
				.orElseThrow(() -> new ConversionException(ErrorCode.QUOTE_EXPIRED, "quoteId", quoteId))
				.postingDeadline(properties.postingGrace());
	}

}
