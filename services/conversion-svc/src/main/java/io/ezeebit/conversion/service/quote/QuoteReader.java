package io.ezeebit.conversion.service.quote;

import java.util.Optional;

import io.ezeebit.conversion.data.model.QuoteEntity;
import io.ezeebit.conversion.domain.Quote;

public interface QuoteReader {

	Quote of(QuoteEntity entity);

	Optional<Quote> live(QuoteEntity entity);

}
