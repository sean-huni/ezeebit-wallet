package io.ezeebit.architecture.fixture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Never loaded into a context: DependencyBudgetTest scans this package to prove its report path goes red.
@Configuration
public class OverBudgetConfig {

	@Value("${unused:}")
	private String extra;

	public OverBudgetConfig(final Object a, final Object b, final Object c) {
	}

	@Bean
	String overBudget(final Object a, final Object b, final Object c, final Object d) {
		return "";
	}

}
