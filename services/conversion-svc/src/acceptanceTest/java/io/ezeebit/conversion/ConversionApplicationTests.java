package io.ezeebit.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "conversion.sweep-interval=1h")
@ActiveProfiles("acceptance")
class ConversionApplicationTests {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ConversionProperties properties;

	@Test
	void migrationsCreateTheQuoteAndConversionTables() {
		assertThat(jdbc.sql("""
				SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
				AND table_name IN ('quote', 'conversion') ORDER BY table_name""").query(String.class).list())
				.containsExactly("conversion", "quote");
	}

	@Test
	void everyPairIsBetweenAFiatCurrencyAndAStablecoinBothWays() {
		assertThat(properties.pairs()).hasSize(12)
				.allSatisfy((pair, policy) -> {
					String[] sides = pair.split("-");
					assertThat(properties.pairs()).containsKey(sides[1] + "-" + sides[0]);
					assertThat(properties.currencies()).containsKeys(sides);
					assertThat(policy.min()).isPositive().isLessThan(policy.max());
				});
	}

}
