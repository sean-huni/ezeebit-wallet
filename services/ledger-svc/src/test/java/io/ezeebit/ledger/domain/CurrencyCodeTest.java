package io.ezeebit.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CurrencyCodeTest {

	@ParameterizedTest
	@ValueSource(strings = { "zar", "ZA", "TOOLONG", "Z1R", "" })
	void malformedCodeIsAnUnknownCurrency(final String raw) {
		assertThatThrownBy(() -> new CurrencyCode(raw))
				.isInstanceOf(LedgerException.class)
				.extracting("code").isEqualTo(ErrorCode.UNKNOWN_CURRENCY);
	}

	@Test
	void wellFormedCodeIsKept() {
		assertThat(new CurrencyCode("USDC").value()).isEqualTo("USDC");
	}

}
