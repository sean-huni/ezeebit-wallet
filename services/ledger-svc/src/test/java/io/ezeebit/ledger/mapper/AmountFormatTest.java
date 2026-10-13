package io.ezeebit.ledger.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class AmountFormatTest {

	private final AmountFormat format = new AmountFormat();

	@Test
	void storedEighteenDecimalValueIsShownAtTheCurrencyScale() {
		assertThat(format.format(new BigDecimal("1500.500000000000000000"), 2)).isEqualTo("1500.50");
	}

	@Test
	void wholeNumberIsPaddedToTheCurrencyScale() {
		assertThat(format.format(new BigDecimal("100"), 6)).isEqualTo("100.000000");
	}

	@Test
	void largeValueIsNeverWrittenInExponentForm() {
		assertThat(format.format(new BigDecimal("1E+19"), 2)).isEqualTo("10000000000000000000.00");
	}

	@Test
	void valueThatWouldNeedRoundingThrowsInsteadOfRounding() {
		assertThatThrownBy(() -> format.format(new BigDecimal("1.005"), 2)).isInstanceOf(ArithmeticException.class);
	}

}
