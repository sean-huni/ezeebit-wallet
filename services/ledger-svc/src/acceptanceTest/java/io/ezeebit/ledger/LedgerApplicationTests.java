package io.ezeebit.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("acceptance")
class LedgerApplicationTests {

	@Autowired
	private JdbcClient jdbc;

	@Test
	void migrationsSeedTheFiveCurrencies() {
		assertThat(jdbc.sql("SELECT code || ':' || kind || ':' || scale FROM currency ORDER BY code").query(String.class).list())
				.containsExactly("KES:FIAT:2", "NGN:FIAT:2", "USDC:STABLECOIN:6", "USDT:STABLECOIN:6", "ZAR:FIAT:2");
	}

	@Test
	void everyCurrencyHasExactlyOneDepositClearingAccount() {
		assertThat(jdbc.sql("""
				SELECT c.code FROM currency c
				LEFT JOIN account a ON a.currency = c.code AND a.type = 'PLATFORM_DEPOSIT_CLEARING' AND a.merchant_id IS NULL
				GROUP BY c.code HAVING count(a.id) <> 1""").query(String.class).list()).isEmpty();
	}

	@Test
	void everyCurrencyHasExactlyOnePlatformFxAccount() {
		assertThat(jdbc.sql("""
				SELECT c.code FROM currency c
				LEFT JOIN account a ON a.currency = c.code AND a.type = 'PLATFORM_FX' AND a.merchant_id IS NULL
				GROUP BY c.code HAVING count(a.id) <> 1""").query(String.class).list()).isEmpty();
	}

}
