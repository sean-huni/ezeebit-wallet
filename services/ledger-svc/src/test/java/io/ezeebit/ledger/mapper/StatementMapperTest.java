package io.ezeebit.ledger.mapper;

import static io.ezeebit.ledger.mapper.Fixtures.USDT;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.ezeebit.ledger.data.model.StatementLineEntity;
import io.ezeebit.ledger.dto.resp.DepositResp;
import io.ezeebit.ledger.dto.resp.StatementLineResp;
import org.junit.jupiter.api.Test;

class StatementMapperTest {

	private final StatementMapper mapper = new StatementMapper(new AmountFormat());
	private final UUID journal = UUID.randomUUID();
	private final Instant created = Instant.parse("2026-10-13T10:15:30.123456Z");
	private final StatementLineEntity line = new StatementLineEntity(7L, journal, "DEPOSIT", "rail-1", "USDT",
			new BigDecimal("25.123456000000000000"), new BigDecimal("40.000000000000000000"), created);

	@Test
	void statementLineShowsAmountAndBalanceAfterAtTheCurrencyScale() {
		assertThat(mapper.toLine(line, USDT)).isEqualTo(new StatementLineResp(7L, journal, "DEPOSIT", "rail-1", "USDT",
				"25.123456", "40.000000", created));
	}

	@Test
	void depositResponseIsBuiltFromTheStoredMerchantLine() {
		UUID merchant = UUID.randomUUID();

		assertThat(mapper.toDeposit(line, merchant, USDT)).isEqualTo(new DepositResp(journal, merchant, "USDT",
				"25.123456", "40.000000", "rail-1", created));
	}

}
