package io.ezeebit.conversion.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import io.ezeebit.conversion.domain.MarketRate;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import io.ezeebit.conversion.outbound.rates.RateDto;
import io.ezeebit.conversion.outbound.rates.RateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateMapperTest {

	private final RateMapper mapper = new RateMapper();

	@Test
	void theRateIsReadAsAnExactDecimalWithItsInstant() {
		MarketRate rate = mapper.toDomain(new RateDto("USDT", "ZAR", "18.4321", "2026-10-13T10:00:00Z"), "USDT", "ZAR");

		assertThat(rate.rate()).hasToString("18.4321");
		assertThat(rate.timestamp()).isEqualTo(Instant.parse("2026-10-13T10:00:00Z"));
	}

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = {
			"USDC, ZAR, 18.4321, 2026-10-13T10:00:00Z",
			"USDT, KES, 18.4321, 2026-10-13T10:00:00Z",
			"USDT, ZAR, null, 2026-10-13T10:00:00Z",
			"USDT, ZAR, eighteen, 2026-10-13T10:00:00Z",
			"USDT, ZAR, 18.4321, null",
			"USDT, ZAR, 18.4321, yesterday",
			"USDT, ZAR, 18.123456789012345, 2026-10-13T10:00:00Z" })
	void anAnswerForAnotherPairOrUnreadableIsRejectedAsMalformed(final String base, final String quote,
			final String value, final String timestamp) {
		assertThatThrownBy(() -> mapper.toDomain(new RateDto(base, quote, value, timestamp), "USDT", "ZAR"))
				.isInstanceOf(ConversionException.class)
				.satisfies(ex -> {
					assertThat(((ConversionException) ex).code()).isEqualTo(ErrorCode.RATE_REJECTED);
					assertThat(((ConversionException) ex).properties()).containsEntry("reason", "RATE_MALFORMED");
				});
	}

	@Test
	void aRateWithFourteenDecimalsIsStillExact() {
		assertThat(mapper.toDomain(new RateDto("USDT", "ZAR", "18.12345678901234", "2026-10-13T10:00:00Z"), "USDT", "ZAR")
				.rate()).hasToString("18.12345678901234");
	}

	@Test
	void noBodyIsRejectedAsMalformed() {
		assertThatThrownBy(() -> mapper.toDomain(null, "USDT", "ZAR")).isInstanceOf(ConversionException.class);
	}

}
