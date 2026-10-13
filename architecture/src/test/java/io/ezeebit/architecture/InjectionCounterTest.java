package io.ezeebit.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

class InjectionCounterTest {

	@Test
	void countsEveryConstructorParameter() {
		assertThat(InjectionCounter.injected(FourConstructorDependencies.class)).isEqualTo(4);
	}

	@Test
	void countsInjectedFieldsAndSetterParametersBesideTheConstructor() {
		assertThat(InjectionCounter.injected(FieldAndSetterInjection.class)).isEqualTo(4);
	}

	@Test
	void countsOnlyTheAutowiredConstructorWhenThereAreSeveral() {
		assertThat(InjectionCounter.injected(MarkedConstructor.class)).isEqualTo(2);
	}

	@Test
	void countsTheNoArgConstructorWhenSeveralAreUnmarked() {
		assertThat(InjectionCounter.injected(UnmarkedConstructors.class)).isZero();
	}

	@Test
	void plainFieldsAndMethodsAreNotInjectionPoints() {
		assertThat(InjectionCounter.injected(NothingInjected.class)).isZero();
	}

	static final class FourConstructorDependencies {

		FourConstructorDependencies(final String a, final String b, final String c, final String d) {
		}

	}

	static final class FieldAndSetterInjection {

		@Value("${x}")
		private String value;

		@Autowired
		private Object field;

		FieldAndSetterInjection(final String a) {
		}

		@Autowired
		void setOther(final Object other) {
		}

	}

	static final class MarkedConstructor {

		MarkedConstructor() {
		}

		@Autowired
		MarkedConstructor(final String a, final String b) {
		}

	}

	static final class UnmarkedConstructors {

		UnmarkedConstructors() {
		}

		UnmarkedConstructors(final String a, final String b) {
		}

	}

	static final class NothingInjected {

		private String text = "";

		void use(final String a, final String b, final String c, final String d) {
			text = a + b + c + d;
		}

	}

}
