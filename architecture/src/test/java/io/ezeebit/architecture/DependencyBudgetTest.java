package io.ezeebit.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

// The estate's DI limit (rules/be/java/dependency-injection.md): no bean or @Bean method takes more than 3.
class DependencyBudgetTest {

	static final int MAX_INJECTED = 3;

	private static final String MAIN_OUTPUT = "/classes/java/main/";

	@Test
	void everyBeanAndBeanMethodTakesAtMostThreeInjectedDependencies() {
		List<Class<?>> beans = mainBeans();
		assertThat(beans).as("the scan must reach this service's application class, or it checked nothing")
				.anyMatch(bean -> bean.isAnnotationPresent(SpringBootApplication.class));

		Map<String, Integer> offenders = new TreeMap<>();
		for (Class<?> bean : beans) {
			int injected = InjectionCounter.injected(bean);
			if (injected > MAX_INJECTED) {
				offenders.put("bean " + bean.getName(), injected);
			}
			for (var method : bean.getDeclaredMethods()) {
				if (method.isAnnotationPresent(Bean.class) && method.getParameterCount() > MAX_INJECTED) {
					offenders.put("@Bean " + bean.getName() + "#" + method.getName(), method.getParameterCount());
				}
			}
		}

		assertThat(offenders)
				.withFailMessage(() -> report(offenders))
				.isEmpty();
	}

	private static String report(final Map<String, Integer> offenders) {
		StringBuilder text = new StringBuilder(offenders.size() + " exceed " + MAX_INJECTED + " injected dependencies:");
		offenders.forEach((name, count) -> text.append("\n  ").append(name).append(" has ").append(count));
		return text.toString();
	}

	private static List<Class<?>> mainBeans() {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
		ClassLoader loader = DependencyBudgetTest.class.getClassLoader();
		return scanner.findCandidateComponents("io.ezeebit").stream()
				.map(definition -> ClassUtils.resolveClassName(definition.getBeanClassName(), loader))
				.filter(DependencyBudgetTest::isMainCode)
				.<Class<?>>map(bean -> bean)
				.toList();
	}

	private static boolean isMainCode(final Class<?> type) {
		var source = type.getProtectionDomain().getCodeSource();
		return source != null && source.getLocation().getPath().contains(MAIN_OUTPUT);
	}

}
