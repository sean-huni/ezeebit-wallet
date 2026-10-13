package io.ezeebit.conversion.service;

import java.util.Locale;
import java.util.regex.Pattern;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.domain.Currency;
import io.ezeebit.conversion.domain.PairPolicy;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import org.springframework.stereotype.Service;

// Currencies and pairs come from served configuration; nothing here is cached beyond the bound properties.
@Service
public class CurrencyCatalog {

	// ASCII only: a letter like U+212A would otherwise lower-case onto a configured code.
	private static final Pattern FORMAT = Pattern.compile("[A-Z]{3,5}");

	private final ConversionProperties properties;

	public CurrencyCatalog(final ConversionProperties properties) {
		this.properties = properties;
	}

	public Currency require(final String code, final String field) {
		if (!isKnown(code)) {
			throw new ConversionException(ErrorCode.UNKNOWN_CURRENCY, field, code);
		}
		return new Currency(code, properties.currencies().get(code.toLowerCase(Locale.ROOT)));
	}

	public boolean isKnown(final String code) {
		return code != null && FORMAT.matcher(code).matches()
				&& properties.currencies().containsKey(code.toLowerCase(Locale.ROOT));
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
