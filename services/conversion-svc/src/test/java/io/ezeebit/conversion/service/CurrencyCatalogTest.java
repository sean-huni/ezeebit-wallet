package io.ezeebit.conversion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.exception.ConversionException;
import org.junit.jupiter.api.Test;

class CurrencyCatalogTest {

	private final CurrencyCatalog catalog = new CurrencyCatalog(
			new ConversionProperties(null, null, null, null, null, Map.of("kes", 2, "zar", 2), Map.of()));

	@Test
	void knowsAConfiguredCodeInUpperCase() {
		assertThat(catalog.isKnown("KES")).isTrue();
	}

	@Test
	void refusesTheLowerCaseSpelling() {
		assertThat(catalog.isKnown("kes")).isFalse();
	}

	// U+212A KELVIN SIGN is its own upper case and lower-cases to an ASCII k.
	@Test
	void refusesANonAsciiLetterThatCaseFoldsOntoAConfiguredCode() {
		assertThat(catalog.isKnown("KES")).isFalse();
		assertThatThrownBy(() -> catalog.require("KES", "sell")).isInstanceOf(ConversionException.class);
	}

	@Test
	void refusesNull() {
		assertThat(catalog.isKnown(null)).isFalse();
	}

}
