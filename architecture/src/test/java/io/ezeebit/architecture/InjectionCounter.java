package io.ezeebit.architecture;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.ToIntFunction;

// One injection point counts 1 whatever its type: ObjectProvider<T>, List<T>, Set<T> and Map<String, T> included.
// Markers match by class name, so no injection API has to be on the classpath, and through composed annotations.
final class InjectionCounter {

	private static final Set<String> INJECTION_MARKERS = Set.of(
			"org.springframework.beans.factory.annotation.Autowired",
			"org.springframework.beans.factory.annotation.Value",
			"jakarta.inject.Inject",
			"javax.inject.Inject",
			"jakarta.annotation.Resource",
			"javax.annotation.Resource");

	private InjectionCounter() {
	}

	static int injected(final Class<?> bean) {
		return constructorParameters(bean)
				+ overHierarchy(bean, type -> (int) Arrays.stream(type.getDeclaredFields())
						.filter(InjectionCounter::isInjectionPoint).count())
				+ overHierarchy(bean, type -> Arrays.stream(type.getDeclaredMethods())
						.filter(InjectionCounter::isInjectionPoint).mapToInt(Method::getParameterCount).sum());
	}

	private static int overHierarchy(final Class<?> bean, final ToIntFunction<Class<?>> perType) {
		int total = 0;
		for (Class<?> type = bean; type != null && type != Object.class; type = type.getSuperclass()) {
			total += perType.applyAsInt(type);
		}
		return total;
	}

	// Spring uses the greediest satisfiable marked constructor, else the only one, else the no-arg one.
	private static int constructorParameters(final Class<?> bean) {
		Constructor<?>[] constructors = bean.getDeclaredConstructors();
		int[] marked = Arrays.stream(constructors).filter(InjectionCounter::isInjectionPoint)
				.mapToInt(Constructor::getParameterCount).toArray();
		if (marked.length > 0) {
			return Arrays.stream(marked).max().getAsInt();
		}
		if (constructors.length == 1) {
			return constructors[0].getParameterCount();
		}
		boolean hasNoArg = Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
		return hasNoArg ? 0 : Arrays.stream(constructors).mapToInt(Constructor::getParameterCount).max().orElse(0);
	}

	private static boolean isInjectionPoint(final AnnotatedElement element) {
		Deque<Class<? extends Annotation>> pending = new ArrayDeque<>();
		Arrays.stream(element.getDeclaredAnnotations()).map(Annotation::annotationType).forEach(pending::add);
		Set<Class<? extends Annotation>> seen = new HashSet<>();
		while (!pending.isEmpty()) {
			Class<? extends Annotation> type = pending.pop();
			if (!seen.add(type)) {
				continue;
			}
			if (INJECTION_MARKERS.contains(type.getName())) {
				return true;
			}
			Arrays.stream(type.getDeclaredAnnotations()).map(Annotation::annotationType).forEach(pending::add);
		}
		return false;
	}

}
