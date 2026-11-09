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
	void flagsConcreteAcrossPackagesThroughEveryInjectionKindAndNothingElse() {
		List<Class<?>> fixtures = DependencyBudget.beans(FIXTURES, type -> true);

		assertThat(InjectedSeams.offenders(fixtures)).containsExactly(
				offender("BuiltAcross"),
				offender("BuiltHidden$BehindInterface"), offender("BuiltHidden$ByMethodRef"),
				offender("BuiltHidden$ConditionalBuilt"), offender("BuiltHidden$DefaultBuilt"),
				offender("BuiltHidden$Generic"), offender("BuiltHidden$GrandBuilt"), offender("BuiltHidden$InLambda"),
				offender("BuiltHidden$Inherited"), offender("BuiltHidden$MetaBuilt"),
				offender("ConcreteAcrossCaller"), offender("FieldAcrossCaller"), offender("ListAcrossCaller"));
	}

	private static String offender(final String caller) {
		return "%s.caller.%s -> %s.callee.ConcreteCallee".formatted(FIXTURES, caller, FIXTURES);
	}

}
