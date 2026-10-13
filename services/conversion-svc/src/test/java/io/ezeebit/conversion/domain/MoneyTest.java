package io.ezeebit.conversion.domain;

import static io.ezeebit.conversion.domain.Currencies.USDT;
import static io.ezeebit.conversion.domain.Currencies.ZAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

	@ParameterizedTest
	@CsvSource({ "10.5, 10.50", "1000, 1000.00", "10.500, 10.50" })
	void anAmountIsHeldAtItsCurrencysScale(final String raw, final String held) {
		assertThat(Money.parse(raw, ZAR).amount()).hasToString(held);
	}

	@ParameterizedTest
	@ValueSource(strings = { "10.001", "0.005" })
	void moreDecimalsThanTheCurrencyAllowsIsRefusedNotRounded(final String raw) {
		assertThatThrownBy(() -> Money.parse(raw, ZAR))
				.isInstanceOf(ConversionException.class)
				.extracting("code").isEqualTo(ErrorCode.AMOUNT_SCALE_EXCEEDED);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.000000", "-1" })
	void anAmountThatIsNotPositiveIsRefused(final String raw) {
		assertThatThrownBy(() -> Money.parse(raw, USDT))
				.isInstanceOf(ConversionException.class)
				.extracting("code").isEqualTo(ErrorCode.AMOUNT_NOT_POSITIVE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "123456789012345678901" })
	void anAmountTheLedgerCannotHoldIsRefused(final String raw) {
		assertThatThrownBy(() -> Money.parse(raw, ZAR))
				.isInstanceOf(ConversionException.class)
				.extracting("code").isEqualTo(ErrorCode.AMOUNT_TOO_LARGE);
	}

}
