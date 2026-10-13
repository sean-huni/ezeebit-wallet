package io.ezeebit.conversion.service;

import io.ezeebit.conversion.data.model.QuoteEntity;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.mapper.QuoteMapper;
import org.springframework.stereotype.Service;

@Service
public class QuoteReader {

	private final CurrencyCatalog catalog;
	private final QuoteMapper mapper;

	public QuoteReader(final CurrencyCatalog catalog, final QuoteMapper mapper) {
		this.catalog = catalog;
		this.mapper = mapper;
	}

	public Quote of(final QuoteEntity entity) {
		return mapper.toDomain(entity, catalog.require(entity.sellCurrency(), "sell"),
				catalog.require(entity.buyCurrency(), "buy"));
	}

}
