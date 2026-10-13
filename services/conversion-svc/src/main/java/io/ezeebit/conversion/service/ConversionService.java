package io.ezeebit.conversion.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.data.model.QuoteEntity;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.ezeebit.conversion.data.repo.QuoteRepo;
import io.ezeebit.conversion.domain.ConversionStatus;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.domain.RejectionReason;
import io.ezeebit.conversion.dto.req.ConversionReq;
import io.ezeebit.conversion.dto.resp.ConversionResp;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import io.ezeebit.conversion.mapper.ConversionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

// Not transactional itself: a duplicate key must be caught outside the transaction it rolled back.
@Service
public class ConversionService {

	private final ConversionRepo conversions;
	private final QuoteRepo quoteRepo;
	private final QuoteReader quotes;
	private final ConversionStarter starter;
	private final ConversionSettler settler;
	private final ConversionMapper mapper;
	private final ConversionProperties properties;
	private final Clock clock;

	public ConversionService(final ConversionRepo conversions, final QuoteRepo quoteRepo, final QuoteReader quotes,
			final ConversionStarter starter, final ConversionSettler settler, final ConversionMapper mapper,
			final ConversionProperties properties, final Clock clock) {
		this.conversions = conversions;
		this.quoteRepo = quoteRepo;
		this.quotes = quotes;
		this.starter = starter;
		this.settler = settler;
		this.mapper = mapper;
		this.properties = properties;
		this.clock = clock;
	}

	public ConversionResp accept(final UUID merchantId, final String key, final ConversionReq request) {
		Optional<ConversionEntity> existing = conversions.findByKey(merchantId, key);
		if (existing.isPresent()) {
			return replay(existing.get(), request.quoteId());
		}
		QuoteEntity quote = quoteRepo.findForMerchant(request.quoteId(), merchantId)
				.orElseThrow(() -> new ConversionException(ErrorCode.QUOTE_NOT_FOUND, "quoteId", request.quoteId()));
		Quote priced = quotes.of(quote);
		if (priced.expired(clock.instant())) {
			throw new ConversionException(ErrorCode.QUOTE_EXPIRED, "quoteId", request.quoteId());
		}
		try {
			starter.start(merchantId, key, quote.id(), priced.postingDeadline(properties.postingGrace()));
		}
		catch (DuplicateKeyException duplicate) {
			// Same key: a concurrent copy of this request won. Otherwise the quote is taken under another key.
			return replay(conversions.findByKey(merchantId, key).orElseThrow(
					() -> new ConversionException(ErrorCode.QUOTE_ALREADY_USED, "quoteId", request.quoteId())),
					request.quoteId());
		}
		return answer(settler.postAndSettle(conversions.findByKey(merchantId, key).orElseThrow()));
	}

	public ConversionResp find(final UUID merchantId, final UUID conversionId) {
		ConversionEntity conversion = conversions.find(conversionId, merchantId).orElseThrow(
				() -> new ConversionException(ErrorCode.CONVERSION_NOT_FOUND, "conversionId", conversionId));
		return mapper.toResp(conversion, quotes.of(conversion.quote()));
	}

	// Returns the current state. A PENDING conversion is posted again only while its quote is live: never at a
	// stale rate. A refusal is final at the ledger, so posting again cannot turn a REJECTED into money moved.
	private ConversionResp replay(final ConversionEntity conversion, final UUID quoteId) {
		if (!conversion.quoteId().equals(quoteId)) {
			throw new ConversionException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key", conversion.idempotencyKey());
		}
		boolean live = !quotes.of(conversion.quote()).expired(clock.instant());
		if (ConversionStatus.PENDING.name().equals(conversion.status()) && live) {
			return answer(settler.postAndSettle(conversion));
		}
		return answer(conversion);
	}

	private ConversionResp answer(final ConversionEntity conversion) {
		if (ConversionStatus.REJECTED.name().equals(conversion.status())) {
			throw new ConversionException(RejectionReason.valueOf(conversion.reason()).answer(), null, conversion.id(), conversion.reason())
					.with("conversionId", conversion.id()).with("reason", conversion.reason());
		}
		return mapper.toResp(conversion, quotes.of(conversion.quote()));
	}

}
