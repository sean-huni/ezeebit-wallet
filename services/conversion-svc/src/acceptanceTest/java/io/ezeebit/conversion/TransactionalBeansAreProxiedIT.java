package io.ezeebit.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;

// A package-private bean still needs its transaction proxy; without one @Transactional silently does nothing.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("acceptance")
class TransactionalBeansAreProxiedIT {

	@Autowired
	private ApplicationContext context;

	@Test
	void everyTransactionalBeanIsProxied() {
		List<Object> transactional = Arrays.stream(context.getBeanDefinitionNames())
				.filter(this::isTransactionalProjectBean)
				.map(context::getBean)
				.toList();

		assertThat(transactional).extracting(bean -> ClassUtils.getUserClass(bean).getSimpleName())
				.containsExactlyInAnyOrder("PendingClaimer", "ConversionStarter");
		assertThat(transactional).allMatch(AopUtils::isAopProxy);
	}

	private boolean isTransactionalProjectBean(final String name) {
		Class<?> type = context.getType(name);
		if (type == null || !type.getName().startsWith("io.ezeebit.")) {
			return false;
		}
		Class<?> target = ClassUtils.getUserClass(type);
		return AnnotatedElementUtils.hasAnnotation(target, Transactional.class) || Arrays.stream(target.getDeclaredMethods())
				.anyMatch(method -> AnnotatedElementUtils.hasAnnotation(method, Transactional.class));
	}

}
