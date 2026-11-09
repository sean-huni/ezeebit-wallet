package io.ezeebit.architecture.fixture.seam.caller;

import java.util.List;

import io.ezeebit.architecture.fixture.seam.callee.ConcreteCallee;
import org.springframework.stereotype.Component;

@Component
public class ListAcrossCaller {

	public ListAcrossCaller(final List<ConcreteCallee> callees) {
	}

}
