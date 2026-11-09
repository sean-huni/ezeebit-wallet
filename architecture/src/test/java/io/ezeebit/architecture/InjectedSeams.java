package io.ezeebit.architecture;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

// Code to interfaces: a bean takes a concrete project class only from its own package. Records and enums are data.
// A @Bean method's parameters are the composition root; what it builds is read from its return type and its body.
final class InjectedSeams {

	static final String PROJECT_ROOT = "io.ezeebit.";

	private InjectedSeams() {
	}

	static List<String> offenders(final List<Class<?>> beans) {
		var offenders = new TreeSet<String>();
		for (Class<?> bean : beans) {
			collect(bean, offenders);
			BeanBodies.beanMethods(bean).stream()
					.flatMap(InjectedSeams::builtBy)
					.filter(built -> built.getName().startsWith(PROJECT_ROOT))
					.forEach(built -> collect(built, offenders));
		}
		return List.copyOf(offenders);
	}

	private static Stream<Class<?>> builtBy(final Method bean) {
		return Stream.concat(classesIn(bean.getGenericReturnType()), BeanBodies.constructedIn(bean).stream());
	}

	private static void collect(final Class<?> bean, final TreeSet<String> offenders) {
		injectedTypes(bean).stream()
				.flatMap(InjectedSeams::classesIn)
				.filter(type -> crossesToConcrete(bean, type))
				.forEach(type -> offenders.add("%s -> %s".formatted(bean.getName(), type.getName())));
	}

	private static List<Type> injectedTypes(final Class<?> bean) {
		List<Type> types = new ArrayList<>();
		for (Class<?> type = bean; type != null && type.getName().startsWith(PROJECT_ROOT); type = type.getSuperclass()) {
			for (Constructor<?> constructor : type.getDeclaredConstructors()) {
				types.addAll(List.of(constructor.getGenericParameterTypes()));
			}
			Arrays.stream(type.getDeclaredFields()).filter(InjectionCounter::isInjectionPoint)
					.forEach(field -> types.add(field.getGenericType()));
			Arrays.stream(type.getDeclaredMethods()).filter(InjectionCounter::isInjectionPoint)
					.map(Method::getGenericParameterTypes).forEach(params -> types.addAll(List.of(params)));
		}
		return types;
	}

	private static Stream<Class<?>> classesIn(final Type type) {
		if (type instanceof Class<?> plain) {
			return Stream.of(plain);
		}
		if (type instanceof ParameterizedType generic) {
			return Stream.concat(classesIn(generic.getRawType()),
					Arrays.stream(generic.getActualTypeArguments()).flatMap(InjectedSeams::classesIn));
		}
		if (type instanceof WildcardType wildcard) {
			return Arrays.stream(wildcard.getUpperBounds()).flatMap(InjectedSeams::classesIn);
		}
		return Stream.empty();
	}

	private static boolean crossesToConcrete(final Class<?> bean, final Class<?> type) {
		boolean concrete = !type.isInterface() && !type.isRecord() && !type.isEnum();
		return concrete && type.getName().startsWith(PROJECT_ROOT) && !type.getPackageName().equals(bean.getPackageName());
	}

}
