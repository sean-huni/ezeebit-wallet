package io.ezeebit.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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

	@Test
	void countsFieldsInjectedIntoASuperclass() {
		assertThat(InjectionCounter.injected(InheritsAnInjectedField.class)).isEqualTo(4);
	}

	@Test
	void countsSettersInjectedIntoASuperclass() {
		assertThat(InjectionCounter.injected(InheritsAnInjectedSetter.class)).isEqualTo(4);
	}

	@Test
	void countsResourceFields() {
		assertThat(InjectionCounter.injected(ResourceInjection.class)).isEqualTo(4);
	}

	@Test
	void countsAnnotationsComposedFromAnInjectionMarker() {
		assertThat(InjectionCounter.injected(ComposedMarkerInjection.class)).isEqualTo(4);
	}

	@Test
	void takesTheGreediestOfSeveralOptionalAutowiredConstructors() {
		assertThat(InjectionCounter.injected(OptionalConstructors.class)).isEqualTo(4);
	}

	@Test
	void aProviderOrACollectionOfBeansIsOneInjectionPoint() {
		assertThat(InjectionCounter.injected(CollectionInjection.class)).isEqualTo(4);
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

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	@Autowired
	@interface InjectedCollaborator {
	}

	abstract static class InjectedFieldBase {

		@Autowired
		private Object inherited;

	}

	static final class InheritsAnInjectedField extends InjectedFieldBase {

		InheritsAnInjectedField(final String a, final String b, final String c) {
		}

	}

	abstract static class InjectedSetterBase {

		@Autowired
		void setInherited(final Object inherited) {
		}

	}

	static final class InheritsAnInjectedSetter extends InjectedSetterBase {

		InheritsAnInjectedSetter(final String a, final String b, final String c) {
		}

	}

	static final class ResourceInjection {

		@Resource
		private Object resource;

		ResourceInjection(final String a, final String b, final String c) {
		}

	}

	static final class ComposedMarkerInjection {

		@InjectedCollaborator
		private Object composed;

		ComposedMarkerInjection(final String a, final String b, final String c) {
		}

	}

	static final class OptionalConstructors {

		@Autowired(required = false)
		OptionalConstructors(final String a, final String b, final String c, final String d) {
		}

		@Autowired(required = false)
		OptionalConstructors(final String a, final String b) {
		}

	}

	static final class CollectionInjection {

		CollectionInjection(final ObjectProvider<String> provider, final List<String> list, final Map<String, String> map,
				final Set<String> set) {
		}

	}

}
