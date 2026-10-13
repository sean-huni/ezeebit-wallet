package io.ezeebit.conversion.service;

import java.util.Locale;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.domain.Currency;
import io.ezeebit.conversion.domain.PairPolicy;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import org.springframework.stereotype.Service;

// Currencies and pairs come from served configuration; nothing here is cached beyond the bound properties.
@Service
public class CurrencyCatalog {

	private final ConversionProperties properties;

	public CurrencyCatalog(final ConversionProperties properties) {
		this.properties = properties;
	}

	public Currency require(final String code, final String field) {
		Integer scale = code == null ? null : properties.currencies().get(code.toLowerCase(Locale.ROOT));
		if (scale == null || !code.equals(code.toUpperCase(Locale.ROOT))) {
			throw new ConversionException(ErrorCode.UNKNOWN_CURRENCY, field, code);
		}
		return new Currency(code, scale);
	}

	public PairPolicy policy(final Currency sell, final Currency buy) {
		if (sell.code().equals(buy.code())) {
			throw new ConversionException(ErrorCode.SAME_CURRENCY, "buy", sell.code());
		}
		ConversionProperties.Pair pair = properties.pairs()
				.get("%s-%s".formatted(sell.code(), buy.code()).toLowerCase(Locale.ROOT));
		if (pair == null) {
			throw new ConversionException(ErrorCode.UNSUPPORTED_PAIR, "buy", sell.code(), buy.code());
		}
		return new PairPolicy(pair.min(), pair.max(), pair.spreadBps());
	}

}
