package io.ezeebit.ledger.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.ezeebit.ledger.data.model.CurrencyEntity;
import org.junit.jupiter.api.Test;

class CurrencyMapperTest {

	@Test
	void rowBecomesTheDomainCurrency() {
		assertThat(new CurrencyMapper().toDomain(new CurrencyEntity("USDT", "STABLECOIN", 6))).isEqualTo(Fixtures.USDT);
	}

}
