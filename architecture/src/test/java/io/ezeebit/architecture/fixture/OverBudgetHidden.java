package io.ezeebit.architecture.fixture;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Over budget where the scan used to stop: a condition that is off under test, and a @Bean on an abstract base.
public final class OverBudgetHidden {

	private OverBudgetHidden() {
	}

	public abstract static class BaseConfig {

		@Bean
		Object inherited(final String first, final String second, final String third, final String fourth) {
			return first;
		}

	}

	@Configuration
	public static class ChildConfig extends BaseConfig {
	}

	@Configuration
	@ConditionalOnProperty(name = "ezeebit.fixture.never", havingValue = "true")
	public static class ConditionalConfig {

		@Bean
		Object conditional(final String first, final String second, final String third, final String fourth) {
			return first;
		}

	}

}
