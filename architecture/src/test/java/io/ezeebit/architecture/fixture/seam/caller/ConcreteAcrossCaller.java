package io.ezeebit.architecture.fixture.seam.caller;

import io.ezeebit.architecture.fixture.seam.callee.ConcreteCallee;
import org.springframework.stereotype.Component;

@Component
public class ConcreteAcrossCaller {

	public ConcreteAcrossCaller(final ConcreteCallee callee) {
	}

}
