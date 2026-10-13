package io.ezeebit.conversion.service.conversion;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.domain.ConversionStatus;
import io.ezeebit.conversion.dto.resp.ConversionResp;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import io.ezeebit.conversion.service.quote.QuoteReader;
import org.springframework.stereotype.Component;

@Component
public class ConversionReplayer {

	private final ConversionReader reader;
	private final QuoteReader quotes;
	private final ConversionLedgerPoster poster;

	public ConversionReplayer(final ConversionReader reader, final QuoteReader quotes, final ConversionLedgerPoster poster) {
		this.reader = reader;
		this.quotes = quotes;
		this.poster = poster;
	}

	public Optional<ConversionResp> existing(final UUID merchantId, final String key, final UUID quoteId,
			final BiConsumer<String, String> tagPair) {
		return reader.byKey(merchantId, key).map(existing -> {
			tagPair.accept(existing.quote().sellCurrency(), existing.quote().buyCurrency());
			return replay(existing, quoteId);
		});
	}

	// Same key: a concurrent copy of this request won. Otherwise the quote is taken under another key.
	public ConversionResp afterDuplicate(final UUID merchantId, final String key, final UUID quoteId) {
		return replay(reader.byKey(merchantId, key).orElseThrow(
				() -> new ConversionException(ErrorCode.QUOTE_ALREADY_USED, "quoteId", quoteId)), quoteId);
	}

	// Returns the current state. A PENDING conversion is posted again only while its quote is live: never at a
	// stale rate. A refusal is final at the ledger, so posting again cannot turn a REJECTED into money moved.
	private ConversionResp replay(final ConversionEntity conversion, final UUID quoteId) {
		if (!conversion.quoteId().equals(quoteId)) {
			throw new ConversionException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key", conversion.idempotencyKey());
		}
		boolean live = quotes.live(conversion.quote()).isPresent();
		if (ConversionStatus.PENDING.name().equals(conversion.status()) && live) {
			return reader.answer(poster.postAndSettle(conversion));
		}
		return reader.answer(conversion);
	}

}
