package io.ezeebit.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class CodeToInterfaceTest {

	private static final String FIXTURES = "io.ezeebit.architecture.fixture.seam";

	@Test
	void noBeanInjectsAConcreteProjectClassFromAnotherPackage() {
		List<Class<?>> beans = DependencyBudget.mainBeans("io.ezeebit");
		assertThat(beans).as("the scan must reach this service's application class, or it checked nothing")
				.anyMatch(bean -> bean.isAnnotationPresent(SpringBootApplication.class));

		assertThat(InjectedSeams.offenders(beans)).isEmpty();
	}

	@Test
	void flagsOnlyTheConcreteClassInjectedAcrossPackages() {
		List<Class<?>> fixtures = DependencyBudget.beans(FIXTURES, type -> true);

		assertThat(InjectedSeams.offenders(fixtures)).containsExactly(
				"%s.caller.ConcreteAcrossCaller -> %s.callee.ConcreteCallee".formatted(FIXTURES, FIXTURES));
	}

}
