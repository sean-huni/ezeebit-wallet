package io.ezeebit.architecture;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

// Code to interfaces: a bean takes a concrete project class only from its own package.
// Records and enums are data. A @Bean method is the composition root, so its parameters are not checked.
final class InjectedSeams {

	private static final String PROJECT_ROOT = "io.ezeebit.";

	private InjectedSeams() {
	}

	static List<String> offenders(final List<Class<?>> beans) {
		var offenders = new TreeSet<String>();
		for (Class<?> bean : beans) {
			Arrays.stream(bean.getDeclaredConstructors())
					.map(Constructor::getParameterTypes)
					.flatMap(Arrays::stream)
					.filter(type -> crossesToConcrete(bean, type))
					.forEach(type -> offenders.add("%s -> %s".formatted(bean.getName(), type.getName())));
		}
		return List.copyOf(offenders);
	}

	private static boolean crossesToConcrete(final Class<?> bean, final Class<?> type) {
		boolean concrete = !type.isInterface() && !type.isRecord() && !type.isEnum() && !type.isPrimitive() && !type.isArray();
		return concrete && type.getName().startsWith(PROJECT_ROOT) && !type.getPackageName().equals(bean.getPackageName());
	}

}
