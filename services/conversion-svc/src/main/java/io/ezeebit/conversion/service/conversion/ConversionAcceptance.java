package io.ezeebit.conversion.service.conversion;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import io.ezeebit.conversion.data.model.QuoteEntity;
import io.ezeebit.conversion.dto.req.ConversionReq;
import io.ezeebit.conversion.dto.resp.ConversionResp;
import io.ezeebit.conversion.service.quote.QuoteLookup;
import org.springframework.stereotype.Component;

@Component
public class ConversionAcceptance {

	private final ConversionReplayer replayer;
	private final QuoteLookup quotes;
	private final ConversionOpener opener;

	public ConversionAcceptance(final ConversionReplayer replayer, final QuoteLookup quotes, final ConversionOpener opener) {
		this.replayer = replayer;
		this.quotes = quotes;
		this.opener = opener;
	}

	public ConversionResp acceptOnce(final UUID merchantId, final String key, final ConversionReq request,
			final BiConsumer<String, String> tagPair) {
		Optional<ConversionResp> replayed = replayer.existing(merchantId, key, request.quoteId(), tagPair);
		if (replayed.isPresent()) {
			return replayed.get();
		}
		QuoteEntity quote = quotes.find(merchantId, request.quoteId());
		tagPair.accept(quote.sellCurrency(), quote.buyCurrency());
		Instant deadline = quotes.postingDeadline(quote, request.quoteId());
		return opener.open(merchantId, key, quote.id(), deadline);
	}

}
