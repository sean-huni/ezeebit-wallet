package io.ezeebit.architecture;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

final class InjectionCounter {

	private static final Set<String> INJECTION_MARKERS = Set.of(
			"org.springframework.beans.factory.annotation.Autowired",
			"org.springframework.beans.factory.annotation.Value",
			"jakarta.inject.Inject");

	private InjectionCounter() {
	}

	static int injected(final Class<?> bean) {
		int fields = (int) Arrays.stream(bean.getDeclaredFields()).filter(InjectionCounter::isInjectionPoint).count();
		int methods = Arrays.stream(bean.getDeclaredMethods())
				.filter(InjectionCounter::isInjectionPoint)
				.mapToInt(Method::getParameterCount)
				.sum();
		return constructorParameters(bean) + fields + methods;
	}

	// Mirrors the constructor Spring picks: the marked one, the only one, else the no-arg one.
	private static int constructorParameters(final Class<?> bean) {
		Constructor<?>[] constructors = bean.getDeclaredConstructors();
		return Arrays.stream(constructors)
				.filter(InjectionCounter::isInjectionPoint)
				.findFirst()
				.or(() -> constructors.length == 1 ? Arrays.stream(constructors).findFirst() : noArg(constructors))
				.or(() -> Arrays.stream(constructors).max(Comparator.comparingInt(Constructor::getParameterCount)))
				.map(Constructor::getParameterCount)
				.orElse(0);
	}

	private static Optional<Constructor<?>> noArg(final Constructor<?>[] constructors) {
		return Arrays.stream(constructors).filter(c -> c.getParameterCount() == 0).findFirst();
	}

	private static boolean isInjectionPoint(final AnnotatedElement element) {
		return Arrays.stream(element.getDeclaredAnnotations())
				.map(Annotation::annotationType)
				.map(Class::getName)
				.anyMatch(INJECTION_MARKERS::contains);
	}

}
