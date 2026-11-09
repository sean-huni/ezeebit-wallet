package io.ezeebit.conversion.service.quote;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.data.model.QuoteEntity;
import io.ezeebit.conversion.data.repo.QuoteRepo;
import io.ezeebit.conversion.domain.MarketRate;
import io.ezeebit.conversion.domain.Priced;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.dto.resp.QuoteResp;
import io.ezeebit.conversion.mapper.QuoteMapper;
import org.springframework.stereotype.Component;

@Component
class QuoteWriter {

	private final QuoteRepo quotes;
	private final QuoteMapper mapper;
	private final ConversionProperties properties;

	QuoteWriter(final QuoteRepo quotes, final QuoteMapper mapper, final ConversionProperties properties) {
		this.quotes = quotes;
		this.mapper = mapper;
		this.properties = properties;
	}

	public QuoteResp store(final UUID merchantId, final Priced priced, final MarketRate rate, final int spreadBps,
			final Instant now) {
		Duration ttl = properties.quoteTtl();
		Instant expiresAt = now.plus(ttl);
		Quote quote = mapper.toDomain(merchantId, priced, rate, spreadBps, expiresAt);
		QuoteEntity entity = mapper.toEntity(quote);
		quotes.insert(entity);
		return mapper.toResp(quote);
	}

}
