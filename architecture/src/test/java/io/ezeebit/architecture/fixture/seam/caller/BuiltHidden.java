package io.ezeebit.architecture.fixture.seam.caller;

import java.util.function.Supplier;

import io.ezeebit.architecture.fixture.seam.callee.Callee;
import io.ezeebit.architecture.fixture.seam.callee.ConcreteCallee;
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

	@Configuration
	public static class ChildConfig extends BaseConfig {
	}

}
