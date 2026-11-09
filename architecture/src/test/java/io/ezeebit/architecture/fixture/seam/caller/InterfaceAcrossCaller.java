package io.ezeebit.architecture.fixture.seam.caller;

import io.ezeebit.architecture.fixture.seam.callee.Callee;
import org.springframework.stereotype.Component;

@Component
public class InterfaceAcrossCaller {

	public InterfaceAcrossCaller(final Callee callee) {
	}

}
