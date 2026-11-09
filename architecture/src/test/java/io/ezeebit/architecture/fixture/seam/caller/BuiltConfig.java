package io.ezeebit.architecture.fixture.seam.caller;

import java.util.function.Supplier;

import io.ezeebit.architecture.fixture.seam.callee.Callee;
import io.ezeebit.architecture.fixture.seam.callee.ConcreteCallee;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BuiltConfig {

	@Bean
	BuiltAcross builtAcross(final ConcreteCallee callee) {
		return new BuiltAcross(callee);
	}

	@Bean
	Callee builtBehindInterface(final ConcreteCallee callee) {
		return new BuiltHidden.BehindInterface(callee);
	}

	@Bean
	Supplier<BuiltHidden.Generic> declaredInGeneric(final ConcreteCallee callee) {
		return BuiltHidden.Generic.supplier(callee);
	}

	@Bean
	Supplier<Object> builtInLambda(final ConcreteCallee callee) {
		return () -> new BuiltHidden.InLambda(callee);
	}

}
