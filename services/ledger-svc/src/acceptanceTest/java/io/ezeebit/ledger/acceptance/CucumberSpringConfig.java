package io.ezeebit.ledger.acceptance;

import io.cucumber.spring.CucumberContextConfiguration;
import io.cucumber.spring.ScenarioScope;
import io.ezeebit.ledger.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("acceptance")
@Import({ TestcontainersConfiguration.class, CucumberSpringConfig.Glue.class })
public class CucumberSpringConfig {

	@TestConfiguration(proxyBeanMethods = false)
	static class Glue {

		@Bean
		LedgerClient ledgerClient(final Environment environment) {
			return new LedgerClient(environment);
		}

		@Bean
		@ScenarioScope
		ScenarioState scenarioState() {
			return new ScenarioState();
		}

	}

}
