package io.ezeebit.ledger.mapper;

import static io.ezeebit.ledger.mapper.Fixtures.ZAR;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import io.ezeebit.ledger.data.model.AccountEntity;
import io.ezeebit.ledger.dto.resp.BalanceResp;
import org.junit.jupiter.api.Test;

class BalanceMapperTest {

	@Test
	void balanceIsShownAtTheCurrencyScale() {
		AccountEntity account = new AccountEntity(UUID.randomUUID(), UUID.randomUUID(), "ZAR", "MERCHANT",
				new BigDecimal("350.500000000000000000"));

		assertThat(new BalanceMapper(new AmountFormat()).toResp(account, ZAR)).isEqualTo(new BalanceResp("ZAR", "350.50"));
	}

}
