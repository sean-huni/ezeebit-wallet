package io.ezeebit.conversion.service.quote;

import java.time.Clock;
import java.util.Optional;

import io.ezeebit.conversion.data.model.QuoteEntity;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.mapper.QuoteMapper;
import io.ezeebit.conversion.service.CurrencyCatalog;
import org.springframework.stereotype.Service;

@Service
public class QuoteReader {

	private final CurrencyCatalog catalog;
	private final QuoteMapper mapper;
	private final Clock clock;

	public QuoteReader(final CurrencyCatalog catalog, final QuoteMapper mapper, final Clock clock) {
		this.catalog = catalog;
		this.mapper = mapper;
		this.clock = clock;
	}

	public Quote of(final QuoteEntity entity) {
		return mapper.toDomain(entity, catalog.require(entity.sellCurrency(), "sell"),
				catalog.require(entity.buyCurrency(), "buy"));
	}

	public Optional<Quote> live(final QuoteEntity entity) {
		Quote quote = of(entity);
		return quote.expired(clock.instant()) ? Optional.empty() : Optional.of(quote);
	}

}
