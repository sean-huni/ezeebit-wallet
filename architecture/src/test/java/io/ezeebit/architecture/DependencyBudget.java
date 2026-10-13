package io.ezeebit.architecture;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

// A @Bean method's parameters are the budget of the object it builds; that object's own constructor is not counted.
final class DependencyBudget {

	static final int MAX_INJECTED = 3;

	private static final String MAIN_OUTPUT = "/classes/java/main/";

	private DependencyBudget() {
	}

	static List<Class<?>> mainBeans(final String basePackage) {
		return beans(basePackage, DependencyBudget::isMainCode);
	}

	static List<Class<?>> beans(final String basePackage, final Predicate<Class<?>> include) {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
		ClassLoader loader = DependencyBudget.class.getClassLoader();
		return scanner.findCandidateComponents(basePackage).stream()
				.<Class<?>>map(definition -> ClassUtils.resolveClassName(definition.getBeanClassName(), loader))
				.filter(include)
				.toList();
	}

	static Map<String, Integer> offenders(final List<Class<?>> beans) {
		Map<String, Integer> offenders = new TreeMap<>();
		for (Class<?> bean : beans) {
			int injected = InjectionCounter.injected(bean);
			if (injected > MAX_INJECTED) {
				offenders.put("bean %s".formatted(bean.getName()), injected);
			}
			for (Method method : bean.getDeclaredMethods()) {
				if (method.isAnnotationPresent(Bean.class) && method.getParameterCount() > MAX_INJECTED) {
					offenders.put("@Bean %s#%s".formatted(bean.getName(), method.getName()), method.getParameterCount());
				}
			}
		}
		return offenders;
	}

	static String report(final Map<String, Integer> offenders) {
		StringBuilder text = new StringBuilder("%d exceed %d injected dependencies:".formatted(offenders.size(), MAX_INJECTED));
		offenders.forEach((name, count) -> text.append("\n  %s has %d".formatted(name, count)));
		return text.toString();
	}

	private static boolean isMainCode(final Class<?> type) {
		var source = type.getProtectionDomain().getCodeSource();
		return source != null && source.getLocation().getPath().contains(MAIN_OUTPUT);
	}

}
