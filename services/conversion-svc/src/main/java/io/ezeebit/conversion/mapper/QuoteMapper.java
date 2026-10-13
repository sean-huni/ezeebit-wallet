package io.ezeebit.conversion.mapper;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.conversion.data.model.QuoteEntity;
import io.ezeebit.conversion.domain.Currency;
import io.ezeebit.conversion.domain.MarketRate;
import io.ezeebit.conversion.domain.Money;
import io.ezeebit.conversion.domain.Priced;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.dto.resp.QuoteResp;
import org.springframework.stereotype.Component;

@Component
public class QuoteMapper {

	private static final String ACTIVE = "ACTIVE";

	public Quote toDomain(final UUID merchantId, final Priced priced, final MarketRate rate, final int spreadBps,
			final Instant expiresAt) {
		return new Quote(UUID.randomUUID(), merchantId, priced.sell(), priced.buy(), rate.rate(), priced.appliedRate(),
				spreadBps, rate.timestamp(), expiresAt);
	}

	public Quote toDomain(final QuoteEntity entity, final Currency sell, final Currency buy) {
		return new Quote(entity.id(), entity.merchantId(), new Money(entity.sellAmount(), sell),
				new Money(entity.buyAmount(), buy), entity.rate(), entity.appliedRate(), entity.spreadBps(),
				entity.rateSourceTimestamp(), entity.expiresAt());
	}

	public QuoteEntity toEntity(final Quote quote) {
		return new QuoteEntity(quote.id(), quote.merchantId(), quote.sell().currency().code(),
				quote.buy().currency().code(), quote.sell().amount(), quote.buy().amount(), quote.rate(),
				quote.appliedRate(), quote.spreadBps(), quote.rateTimestamp(), quote.expiresAt(), ACTIVE);
	}

	public QuoteResp toResp(final Quote quote) {
		return new QuoteResp(quote.id(), quote.merchantId(), quote.sell().currency().code(), quote.sell().text(),
				quote.buy().currency().code(), quote.buy().text(), quote.rate().stripTrailingZeros().toPlainString(),
				quote.appliedRate().stripTrailingZeros().toPlainString(), quote.spreadBps(), quote.rateTimestamp(),
				quote.expiresAt());
	}

}
