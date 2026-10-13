package io.ezeebit.ledger.domain;

import static io.ezeebit.ledger.domain.Currencies.USDT;
import static io.ezeebit.ledger.domain.Currencies.ZAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

	@Test
	void addingTwoCurrenciesThrowsCurrencyMismatch() {
		Money rand = Money.parse("10.00", ZAR);
		Money tether = Money.parse("10.000000", USDT);

		assertThatThrownBy(() -> rand.plus(tether))
				.isInstanceOf(LedgerException.class)
				.extracting("code").isEqualTo(ErrorCode.CURRENCY_MISMATCH);
	}

	@Test
	void addingSameCurrencyIsExact() {
		Money sum = Money.parse("0.10", ZAR).plus(Money.parse("0.20", ZAR));

		assertThat(sum.amount()).isEqualByComparingTo(new BigDecimal("0.30"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "10.001", "0.009", "1.000001" })
	void moreDecimalsThanTheCurrencyScaleIsRejectedNotRounded(final String raw) {
		assertThatThrownBy(() -> Money.parse(raw, ZAR))
				.isInstanceOf(LedgerException.class)
				.extracting("code").isEqualTo(ErrorCode.AMOUNT_SCALE_EXCEEDED);
	}

	@Test
	void stablecoinAcceptsSixDecimals() {
		assertThat(Money.parse("25.123456", USDT).amount()).isEqualByComparingTo("25.123456");
	}

	@Test
	void trailingZerosBeyondTheScaleLoseNothingAndAreAccepted() {
		assertThat(Money.parse("10.500", ZAR).amount()).isEqualByComparingTo("10.50");
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.00", "-5.00" })
	void nonPositiveAmountIsRejected(final String raw) {
		assertThatThrownBy(() -> Money.parse(raw, ZAR))
				.isInstanceOf(LedgerException.class)
				.extracting("code").isEqualTo(ErrorCode.AMOUNT_NOT_POSITIVE);
	}

	@Test
	void moreThanTwentyIntegerDigitsIsRejected() {
		assertThatThrownBy(() -> Money.parse("123456789012345678901", ZAR))
				.isInstanceOf(LedgerException.class)
				.extracting("code").isEqualTo(ErrorCode.AMOUNT_TOO_LARGE);
	}

	@Test
	void sumPastTwentyIntegerDigitsIsRefusedAsBalanceLimitExceeded() {
		Money largest = Money.parse("99999999999999999999.99", ZAR);

		assertThatThrownBy(() -> largest.plus(Money.parse("0.01", ZAR)))
				.isInstanceOf(LedgerException.class)
				.extracting("code").isEqualTo(ErrorCode.BALANCE_LIMIT_EXCEEDED);
	}

	@Test
	void sumThatStillFitsTwentyIntegerDigitsIsKept() {
		Money sum = Money.parse("99999999999999999999.98", ZAR).plus(Money.parse("0.01", ZAR));

		assertThat(sum.amount()).isEqualByComparingTo("99999999999999999999.99");
	}

	@Test
	void negativeSumPastTwentyIntegerDigitsIsAlsoRefused() {
		Money mostNegative = Money.parse("99999999999999999999.99", ZAR).negate();

		assertThatThrownBy(() -> mostNegative.plus(Money.parse("0.01", ZAR).negate()))
				.isInstanceOf(LedgerException.class)
				.extracting("code").isEqualTo(ErrorCode.BALANCE_LIMIT_EXCEEDED);
	}

	@Test
	void negateFlipsTheSignAndKeepsTheCurrency() {
		Money negated = Money.parse("7.25", ZAR).negate();

		assertThat(negated).isEqualTo(new Money(new BigDecimal("-7.25"), ZAR.code()));
	}

}
