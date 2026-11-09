package io.ezeebit.conversion.mapper;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.conversion.data.model.QuoteEntity;
import io.ezeebit.conversion.domain.Currency;
import io.ezeebit.conversion.domain.MarketRate;
import io.ezeebit.conversion.domain.Priced;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.dto.resp.QuoteResp;

public interface QuoteMapper {

	Quote toDomain(UUID merchantId, Priced priced, MarketRate rate, int spreadBps, Instant expiresAt);

	Quote toDomain(QuoteEntity entity, Currency sell, Currency buy);

	QuoteEntity toEntity(Quote quote);

	QuoteResp toResp(Quote quote);

}
