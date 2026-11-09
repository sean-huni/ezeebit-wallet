package io.ezeebit.architecture.fixture.seam.caller;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Supplier;

import io.ezeebit.architecture.fixture.seam.callee.Callee;
import io.ezeebit.architecture.fixture.seam.callee.ConcreteCallee;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Each class injects a concrete class from another package, and is reachable only through a @Bean method.
public final class BuiltHidden {

	private BuiltHidden() {
	}

	public static class BehindInterface implements Callee {

		public BehindInterface(final ConcreteCallee callee) {
		}

	}

	public static class Generic {

		Generic(final ConcreteCallee callee) {
		}

		static Supplier<Generic> supplier(final ConcreteCallee callee) {
			var built = new Generic(callee);
			return () -> built;
		}

	}

	public static class InLambda {

		public InLambda(final ConcreteCallee callee) {
		}

	}

	public static class Inherited {

		public Inherited(final ConcreteCallee callee) {
		}

	}

	public abstract static class BaseConfig {

		@Bean
		Object inherited(final ConcreteCallee callee) {
			return new Inherited(callee);
		}

	}

	public static class ByMethodRef {

		public ByMethodRef(final ConcreteCallee callee) {
		}

	}

	public static class MetaBuilt {

		public MetaBuilt(final ConcreteCallee callee) {
		}

	}

	public static class DefaultBuilt {

		public DefaultBuilt(final ConcreteCallee callee) {
		}

	}

	public static class ConditionalBuilt {

		public ConditionalBuilt(final ConcreteCallee callee) {
		}

	}

	// Built only by a method that is not a @Bean, so the gate must not report it.
	public static class NotABean {

		public NotABean(final ConcreteCallee callee) {
		}

	}

	@Retention(RetentionPolicy.RUNTIME)
	@Bean
	public @interface MetaBean {
	}

	public static class GrandBuilt {

		public GrandBuilt(final ConcreteCallee callee) {
		}

	}

	public interface GrandBeans {

		@Bean
		default Object grandBuilt(final ConcreteCallee callee) {
			return new GrandBuilt(callee);
		}

	}

	public interface DefaultBeans extends GrandBeans {

		@Bean
		default Object defaultBuilt(final ConcreteCallee callee) {
			return new DefaultBuilt(callee);
		}

	}

	@Configuration
	public static class ChildConfig extends BaseConfig implements DefaultBeans {
	}

	@Configuration
	@ConditionalOnProperty(name = "ezeebit.fixture.never", havingValue = "true")
	public static class ConditionalConfig {

		@Bean
		Object conditionalBuilt(final ConcreteCallee callee) {
			return new ConditionalBuilt(callee);
		}

	}

}
