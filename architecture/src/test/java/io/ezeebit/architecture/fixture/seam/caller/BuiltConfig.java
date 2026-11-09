package io.ezeebit.architecture.fixture.seam.caller;

import io.ezeebit.architecture.fixture.seam.callee.ConcreteCallee;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BuiltConfig {

	@Bean
	BuiltAcross builtAcross(final ConcreteCallee callee) {
		return new BuiltAcross(callee);
	}

}
