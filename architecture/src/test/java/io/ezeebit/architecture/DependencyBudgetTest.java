package io.ezeebit.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// The estate's DI limit (rules/be/java/dependency-injection.md): no bean or @Bean method takes more than 3.
class DependencyBudgetTest {

	@Test
	void everyBeanAndBeanMethodTakesAtMostThreeInjectedDependencies() {
		List<Class<?>> beans = DependencyBudget.mainBeans("io.ezeebit");
		assertThat(beans).as("the scan must reach this service's application class, or it checked nothing")
				.anyMatch(bean -> bean.isAnnotationPresent(SpringBootApplication.class));

		Map<String, Integer> offenders = DependencyBudget.offenders(beans);

		assertThat(offenders)
				.withFailMessage(() -> DependencyBudget.report(offenders))
				.isEmpty();
	}

	@Test
	void theScanAndReportFlagAnOverBudgetBeanAndBeanMethod() {
		Map<String, Integer> offenders = DependencyBudget.offenders(
				DependencyBudget.beans("io.ezeebit.architecture.fixture", type -> true));

		assertThat(DependencyBudget.report(offenders)).isEqualTo(String.join("\n",
				"2 exceed 3 injected dependencies:",
				"  @Bean io.ezeebit.architecture.fixture.OverBudgetConfig#overBudget has 4",
				"  bean io.ezeebit.architecture.fixture.OverBudgetConfig has 4"));
	}

}
