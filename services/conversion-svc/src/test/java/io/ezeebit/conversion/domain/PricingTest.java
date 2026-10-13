package io.ezeebit.conversion.domain;

import static io.ezeebit.conversion.domain.Currencies.USDT;
import static io.ezeebit.conversion.domain.Currencies.ZAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class PricingTest {

	@Test
	void theSpreadLowersTheRateTheMerchantGets() {
		Priced priced = Pricing.price(Money.parse("10", USDT), Side.SELL, ZAR, new BigDecimal("18.4321"), 50);

		assertThat(priced.appliedRate()).isEqualByComparingTo("18.3399395");
	}

	@Test
	void aSellAmountQuoteRoundsWhatTheMerchantReceivesDown() {
		Priced priced = Pricing.price(Money.parse("10", USDT), Side.SELL, ZAR, new BigDecimal("18.4321"), 50);

		assertThat(priced.sell()).isEqualTo(new Money(new BigDecimal("10.000000"), USDT));
		assertThat(priced.buy()).isEqualTo(new Money(new BigDecimal("183.39"), ZAR));
	}

	@Test
	void aBuyAmountQuoteRoundsWhatTheMerchantPaysUp() {
		Priced priced = Pricing.price(Money.parse("1000.00", ZAR), Side.BUY, USDT, new BigDecimal("18.4321"), 50);

		assertThat(priced.sell()).isEqualTo(new Money(new BigDecimal("54.525807"), USDT));
		assertThat(priced.buy()).isEqualTo(new Money(new BigDecimal("1000.00"), ZAR));
	}

	@Test
	void roundingNeverCostsThePlatformMoreThanOneMinorUnit() {
		Priced bySell = Pricing.price(Money.parse("1000.00", ZAR), Side.SELL, USDT, new BigDecimal("0.054254"), 50);
		Priced byBuy = Pricing.price(Money.parse("1000.00", ZAR), Side.BUY, USDT, new BigDecimal("18.4321"), 50);

		BigDecimal exactBuy = new BigDecimal("1000.00").multiply(bySell.appliedRate());
		assertThat(bySell.buy().amount()).isLessThanOrEqualTo(exactBuy)
				.isGreaterThan(exactBuy.subtract(new BigDecimal("0.000001")));
		BigDecimal exactSell = new BigDecimal("1000.00").divide(byBuy.appliedRate(), 30, java.math.RoundingMode.HALF_EVEN);
		assertThat(byBuy.sell().amount()).isGreaterThanOrEqualTo(exactSell)
				.isLessThan(exactSell.add(new BigDecimal("0.000001")));
	}

	@Test
	void amountsCarryTheirCurrencysScale() {
		Priced priced = Pricing.price(Money.parse("1000", ZAR), Side.SELL, USDT, new BigDecimal("0.054254"), 50);

		assertThat(priced.sell().amount().scale()).isEqualTo(2);
		assertThat(priced.buy().amount()).isEqualTo(new BigDecimal("53.982730"));
	}

	@Test
	void anAmountThatRoundsToNothingIsRefused() {
		assertThatThrownBy(() -> Pricing.price(Money.parse("0.01", ZAR), Side.SELL, USDT, new BigDecimal("0.00000001"), 50))
				.isInstanceOf(ConversionException.class)
				.extracting("code").isEqualTo(ErrorCode.AMOUNT_TOO_SMALL);
	}

	@Test
	void aZeroSpreadAppliesTheMarketRate() {
		Priced priced = Pricing.price(Money.parse("10", USDT), Side.SELL, ZAR, new BigDecimal("18.4321"), 0);

		assertThat(priced.appliedRate()).isEqualByComparingTo("18.4321");
		assertThat(priced.buy().amount()).isEqualTo(new BigDecimal("184.32"));
	}

}
